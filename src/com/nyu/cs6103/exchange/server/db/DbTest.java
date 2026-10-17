package com.nyu.cs6103.exchange.server.db;

import com.nyu.cs6103.exchange.common.MatchResult;
import com.nyu.cs6103.exchange.common.Money;
import com.nyu.cs6103.exchange.common.Order;
import com.nyu.cs6103.exchange.common.Side;
import com.nyu.cs6103.exchange.common.Trade;
import com.nyu.cs6103.exchange.server.OrderBook;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Plain-main harness for the database layer. Requires only the H2 jar. */
public class DbTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testPasswordHashing();
        testSettlementMovesCashAndShares();
        testRollbackLeavesLedgerUntouched();
        testInsufficientSharesAborts();

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testPasswordHashing() throws SQLException {
        Db db = fresh("auth");
        UserDao users = new UserDao(db);

        UserDao.User alice = users.register("alice", "hunter2".toCharArray(), 1_000_00);
        UserDao.User bob = users.register("bob", "hunter2".toCharArray(), 1_000_00);

        check("registration returns an id", alice.id() > 0);

        Optional<UserDao.User> good = users.authenticate("alice", "hunter2".toCharArray());
        Optional<UserDao.User> bad = users.authenticate("alice", "hunter3".toCharArray());
        Optional<UserDao.User> ghost = users.authenticate("nobody", "hunter2".toCharArray());

        check("correct password authenticates", good.isPresent());
        check("wrong password is rejected", bad.isEmpty());
        check("unknown user is rejected", ghost.isEmpty());

        String h1 = hashOf(db, "alice");
        String h2 = hashOf(db, "bob");
        check("identical passwords hash differently (salt works)", !h1.equals(h2));
        check("plaintext is never stored", !h1.contains("hunter2"));
    }

    private static void testSettlementMovesCashAndShares() throws SQLException {
        Db db = fresh("settle");
        UserDao users = new UserDao(db);
        TradeDao trades = new TradeDao(db);

        UserDao.User buyer = users.register("buyer", "pw".toCharArray(), 100_000_00);
        UserDao.User seller = users.register("seller", "pw".toCharArray(), 0);
        trades.seedHolding(seller.id(), "AAPL", 500);

        OrderBook book = new OrderBook("AAPL");
        Order ask = new Order(seller.id(), "AAPL", Side.SELL, 10200, 30);
        Order bid = new Order(buyer.id(), "AAPL", Side.BUY, 10200, 30);
        trades.insertOrder(ask);
        book.submit(ask);
        trades.insertOrder(bid);
        MatchResult result = book.submit(bid);
        List<Trade> executions = result.trades();

        trades.settle(executions, result.touchedOrders());

        long notional = 10200L * 30;
        check("buyer cash debited",
                trades.cashOf(buyer.id()) == 100_000_00L - notional);
        check("seller cash credited", trades.cashOf(seller.id()) == notional);
        check("buyer received shares", trades.holdingOf(buyer.id(), "AAPL") == 30);
        check("seller shares reduced", trades.holdingOf(seller.id(), "AAPL") == 470);
        check("trade recorded", trades.tradeCount() == 1);

        System.out.printf("  [settle] notional %s, buyer cash %s -> %s%n",
                Money.format(notional), Money.format(100_000_00L),
                Money.format(trades.cashOf(buyer.id())));
    }

    /**
     * The atomicity demonstration. A failure is injected after the cash and
     * share writes have already been issued but before the commit.
     */
    private static void testRollbackLeavesLedgerUntouched() throws SQLException {
        Db db = fresh("rollback");
        UserDao users = new UserDao(db);
        TradeDao trades = new TradeDao(db);

        UserDao.User buyer = users.register("buyer", "pw".toCharArray(), 100_000_00);
        UserDao.User seller = users.register("seller", "pw".toCharArray(), 0);
        trades.seedHolding(seller.id(), "AAPL", 500);

        OrderBook book = new OrderBook("AAPL");
        Order ask = new Order(seller.id(), "AAPL", Side.SELL, 10200, 30);
        Order bid = new Order(buyer.id(), "AAPL", Side.BUY, 10200, 30);
        trades.insertOrder(ask);
        book.submit(ask);
        trades.insertOrder(bid);
        MatchResult result = book.submit(bid);
        List<Trade> executions = result.trades();

        trades.setMidTransactionHook(() -> {
            throw new IllegalStateException("simulated crash mid-settlement");
        });

        boolean threw = false;
        try {
            trades.settle(executions, result.touchedOrders());
        } catch (IllegalStateException e) {
            threw = true;
        }
        trades.setMidTransactionHook(null);

        check("the failure propagates", threw);
        check("buyer cash unchanged", trades.cashOf(buyer.id()) == 100_000_00L);
        check("seller cash unchanged", trades.cashOf(seller.id()) == 0L);
        check("no shares moved to buyer", trades.holdingOf(buyer.id(), "AAPL") == 0);
        check("seller holding intact", trades.holdingOf(seller.id(), "AAPL") == 500);
        check("no trade row survives", trades.tradeCount() == 0);

        System.out.println("  [rollback] crash injected after 4 writes; ledger fully restored");
    }

    /** A seller without the shares must abort the whole settlement. */
    private static void testInsufficientSharesAborts() throws SQLException {
        Db db = fresh("shortsell");
        UserDao users = new UserDao(db);
        TradeDao trades = new TradeDao(db);

        UserDao.User buyer = users.register("buyer", "pw".toCharArray(), 100_000_00);
        UserDao.User seller = users.register("seller", "pw".toCharArray(), 0);
        trades.seedHolding(seller.id(), "AAPL", 5);

        OrderBook book = new OrderBook("AAPL");
        Order ask = new Order(seller.id(), "AAPL", Side.SELL, 10200, 30);
        Order bid = new Order(buyer.id(), "AAPL", Side.BUY, 10200, 30);
        trades.insertOrder(ask);
        book.submit(ask);
        trades.insertOrder(bid);
        MatchResult result = book.submit(bid);
        List<Trade> executions = result.trades();

        boolean threw = false;
        try {
            trades.settle(executions, result.touchedOrders());
        } catch (SQLException e) {
            threw = true;
        }

        check("short sale is refused", threw);
        check("buyer cash unchanged after refusal",
                trades.cashOf(buyer.id()) == 100_000_00L);
        check("seller holding unchanged", trades.holdingOf(seller.id(), "AAPL") == 5);
        check("no trade recorded", trades.tradeCount() == 0);
    }

    // ---- helpers ----

    private static Db fresh(String name) throws SQLException {
        Db db = Db.inMemory(name + "-" + System.nanoTime());
        db.initSchema();
        return db;
    }

    private static String hashOf(Db db, String username) throws SQLException {
        try (var c = db.open();
             var ps = c.prepareStatement("SELECT pwd_hash FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + label);
        } else {
            failed++;
            System.out.println("  FAIL  " + label);
        }
    }
}
