package com.nyu.cs6103.exchange.server.db;

import com.nyu.cs6103.exchange.common.Order;
import com.nyu.cs6103.exchange.common.Trade;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * Persistence of orders and settlement of executions.
 *
 * Settlement is the reason this project is not a CRUD application. A single
 * execution is four separate writes: debit the buyer's cash, credit the
 * seller's, move the shares, and record the trade. Any one of them landing
 * without the others corrupts the ledger -- cash that was spent but bought
 * nothing, or shares that exist in two accounts at once. There is no way to
 * repair that after the fact, because the system cannot tell which half was
 * the truth.
 *
 * So settle() disables autocommit, performs every write on one connection, and
 * commits only at the very end. Any exception rolls the whole batch back and
 * the database is left exactly as it was before the call.
 */
public class TradeDao {

    private final Db db;

    /**
     * Test hook: when set, the callback is invoked partway through the
     * transaction so a failure can be injected and the rollback observed.
     */
    private Runnable midTransactionHook = null;

    public TradeDao(Db db) {
        this.db = db;
    }

    void setMidTransactionHook(Runnable hook) {
        this.midTransactionHook = hook;
    }

    /** Records a newly accepted order before it reaches the book. */
    public void insertOrder(Order order) throws SQLException {
        String sql = """
                INSERT INTO orders (id, user_id, symbol, side, price_cents,
                                    quantity, remaining, status)
                VALUES (?,?,?,?,?,?,?,?)""";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, order.id());
            ps.setLong(2, order.userId());
            ps.setString(3, order.symbol());
            ps.setString(4, order.side().name());
            ps.setLong(5, order.priceCents());
            ps.setLong(6, order.quantity());
            ps.setLong(7, order.remaining());
            ps.setString(8, order.status().name());
            ps.executeUpdate();
        }
    }

    /**
     * Settles a batch of executions atomically. Either every trade in the list
     * is fully applied, or none of it is.
     */
    public void settle(List<Trade> trades, List<Order> touchedOrders) throws SQLException {
        if (trades.isEmpty()) {
            return;
        }

        Connection c = null;
        try {
            c = db.open();
            c.setAutoCommit(false);

            for (Trade t : trades) {
                long notional = t.notionalCents();

                debitCash(c, t.buyUserId(), notional);
                creditCash(c, t.sellUserId(), notional);
                moveShares(c, t.sellUserId(), t.buyUserId(), t.symbol(), t.quantity());
                insertTrade(c, t);
            }

            if (midTransactionHook != null) {
                midTransactionHook.run();
            }

            for (Order o : touchedOrders) {
                updateOrderState(c, o);
            }

            c.commit();

        } catch (SQLException | RuntimeException e) {
            if (c != null) {
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            throw e;
        } finally {
            if (c != null) {
                try {
                    c.setAutoCommit(true);
                    c.close();
                } catch (SQLException ignored) {
                    // closing failure must not mask the original exception
                }
            }
        }
    }

    private void debitCash(Connection c, long userId, long cents) throws SQLException {
        // The WHERE clause enforces sufficient funds inside the statement, so
        // the check and the update cannot be separated by another transaction.
        String sql = "UPDATE users SET cash_cents = cash_cents - ? "
                + "WHERE id = ? AND cash_cents >= ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cents);
            ps.setLong(2, userId);
            ps.setLong(3, cents);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("insufficient funds for user " + userId);
            }
        }
    }

    private void creditCash(Connection c, long userId, long cents) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET cash_cents = cash_cents + ? WHERE id = ?")) {
            ps.setLong(1, cents);
            ps.setLong(2, userId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("no such user " + userId);
            }
        }
    }

    private void moveShares(Connection c, long from, long to, String symbol, long qty)
            throws SQLException {
        String take = "UPDATE holdings SET quantity = quantity - ? "
                + "WHERE user_id = ? AND symbol = ? AND quantity >= ?";
        try (PreparedStatement ps = c.prepareStatement(take)) {
            ps.setLong(1, qty);
            ps.setLong(2, from);
            ps.setString(3, symbol);
            ps.setLong(4, qty);
            if (ps.executeUpdate() != 1) {
                throw new SQLException(
                        "seller " + from + " lacks " + qty + " " + symbol);
            }
        }

        String give = """
                MERGE INTO holdings (user_id, symbol, quantity)
                KEY (user_id, symbol)
                VALUES (?, ?, COALESCE(
                    (SELECT quantity FROM holdings WHERE user_id = ? AND symbol = ?), 0) + ?)""";
        try (PreparedStatement ps = c.prepareStatement(give)) {
            ps.setLong(1, to);
            ps.setString(2, symbol);
            ps.setLong(3, to);
            ps.setString(4, symbol);
            ps.setLong(5, qty);
            ps.executeUpdate();
        }
    }

    private void insertTrade(Connection c, Trade t) throws SQLException {
        String sql = """
                INSERT INTO trades (symbol, price_cents, quantity, buy_order_id,
                                    sell_order_id, buy_user_id, sell_user_id, executed_at)
                VALUES (?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, t.symbol());
            ps.setLong(2, t.priceCents());
            ps.setLong(3, t.quantity());
            ps.setLong(4, t.buyOrderId());
            ps.setLong(5, t.sellOrderId());
            ps.setLong(6, t.buyUserId());
            ps.setLong(7, t.sellUserId());
            ps.setTimestamp(8, Timestamp.from(t.executedAt()));
            ps.executeUpdate();
        }
    }

    /** Persists a cancelled order's status outside any settlement batch. */
    public void markCancelled(Order o) throws SQLException {
        try (Connection c = db.open()) {
            updateOrderState(c, o);
        }
    }

    private void updateOrderState(Connection c, Order o) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE orders SET remaining = ?, status = ? WHERE id = ?")) {
            ps.setLong(1, o.remaining());
            ps.setString(2, o.status().name());
            ps.setLong(3, o.id());
            ps.executeUpdate();
        }
    }

    // ---- read helpers used by the client and the tests ----

    public long cashOf(long userId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT cash_cents FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public long holdingOf(long userId, String symbol) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT quantity FROM holdings WHERE user_id = ? AND symbol = ?")) {
            ps.setLong(1, userId);
            ps.setString(2, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public long latestOrderId(String symbol) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MAX(id) FROM orders WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    public String orderStatus(long orderId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM orders WHERE id = ?")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public int tradeCount() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM trades");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Total shares of a symbol held across every account. */
    public long totalHoldings(String symbol) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(quantity), 0) FROM holdings WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Total cash across every account. */
    public long totalCash() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(cash_cents), 0) FROM users");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public void seedHolding(long userId, String symbol, long qty) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "MERGE INTO holdings (user_id, symbol, quantity) KEY (user_id, symbol) VALUES (?,?,?)")) {
            ps.setLong(1, userId);
            ps.setString(2, symbol);
            ps.setLong(3, qty);
            ps.executeUpdate();
        }
    }
}
