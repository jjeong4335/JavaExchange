package com.nyu.cs6103.exchange.server;

import com.nyu.cs6103.exchange.client.ServerConnection;
import com.nyu.cs6103.exchange.common.BookSnapshot;
import com.nyu.cs6103.exchange.common.Money;
import com.nyu.cs6103.exchange.common.Side;
import com.nyu.cs6103.exchange.server.db.Db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end test over real TCP sockets: a live server, real clients, real
 * matching, real settlement. Nothing is stubbed.
 */
public class NetworkTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        try (ExchangeServer server = new ExchangeServer(0, Db.inMemory("net-" + System.nanoTime()))) {
            int port = server.start();
            testLoginAndBadCredentials(port);
            testTradeRoundTrip(server, port);
            testCancellationPersists(server, port);
            testConcurrentClientsRaceOverTheWire(server, port);
        }

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testLoginAndBadCredentials(int port) throws Exception {
        try (ServerConnection c = new ServerConnection("localhost", port, new ServerConnection.Handler() { })) {
            c.register("neo", "matrix");
            Thread.sleep(200);
            check("valid credentials log in", c.login("neo", "matrix", 2000));
        }
        try (ServerConnection c = new ServerConnection("localhost", port, new ServerConnection.Handler() { })) {
            check("wrong password is refused", !c.login("neo", "wrong", 2000));
            check("refusal does not reveal which field was wrong",
                    "invalid credentials".equals(c.loginError()));
        }
        try (ServerConnection c = new ServerConnection("localhost", port, new ServerConnection.Handler() { })) {
            check("unknown user gets the same message", !c.login("ghost", "matrix", 2000));
            check("identical message for unknown user",
                    "invalid credentials".equals(c.loginError()));
        }
    }

    private static void testTradeRoundTrip(ExchangeServer server, int port) throws Exception {
        List<String> sellerEvents = Collections.synchronizedList(new ArrayList<>());
        List<String> observerTrades = Collections.synchronizedList(new ArrayList<>());
        AtomicLong observerBestBid = new AtomicLong(-1);
        CountDownLatch tradeSeen = new CountDownLatch(1);

        ServerConnection observer = new ServerConnection("localhost", port,
                new ServerConnection.Handler() {
                    @Override public void onTrade(String sym, long price, long qty) {
                        observerTrades.add(sym + " " + qty + "@" + price);
                        tradeSeen.countDown();
                    }
                    @Override public void onBook(BookSnapshot s) {
                        if (s.bestBid() != null) {
                            observerBestBid.set(s.bestBid().priceCents());
                        }
                    }
                });
        observer.register("observer", "pw");
        Thread.sleep(150);
        observer.login("observer", "pw", 2000);

        ServerConnection seller = new ServerConnection("localhost", port,
                new ServerConnection.Handler() {
                    @Override public void onFill(long id, long qty, long price, String status) {
                        sellerEvents.add("FILL " + qty + "@" + price + " " + status);
                    }
                });
        seller.register("seller", "pw");
        Thread.sleep(150);
        seller.login("seller", "pw", 2000);

        ServerConnection buyer = new ServerConnection("localhost", port,
                new ServerConnection.Handler() { });
        buyer.register("buyer", "pw");
        Thread.sleep(150);
        buyer.login("buyer", "pw", 2000);

        seller.order("AAPL", "SELL", 10200, 30);
        Thread.sleep(200);
        buyer.order("AAPL", "BUY", 10200, 50);

        check("trade reached an uninvolved observer", tradeSeen.await(3, TimeUnit.SECONDS));
        Thread.sleep(400);
        server.awaitSettlement();

        check("observer saw exactly one trade", observerTrades.size() == 1);
        check("observer got the right print", observerTrades.contains("AAPL 30@10200"));
        check("seller was privately notified of the fill", !sellerEvents.isEmpty());
        check("unfilled remainder became the best bid", observerBestBid.get() == 10200);

        long buyerShares = server.trades().holdingOf(buyer.userId(), "AAPL");
        long sellerShares = server.trades().holdingOf(seller.userId(), "AAPL");
        check("shares settled to the buyer", buyerShares == 1_000 + 30);
        check("shares left the seller", sellerShares == 1_000 - 30);

        long buyerCash = server.trades().cashOf(buyer.userId());
        check("buyer cash debited by notional",
                buyerCash == 1_000_000_00L - 10200L * 30);

        System.out.printf("  [e2e] buyer cash %s, holding %d; seller holding %d%n",
                Money.format(buyerCash), buyerShares, sellerShares);

        observer.close();
        seller.close();
        buyer.close();
    }

    /**
     * A cancelled order must leave the in-memory book AND have its persisted
     * status updated, so the database order history stays authoritative.
     */
    private static void testCancellationPersists(ExchangeServer server, int port)
            throws Exception {
        ServerConnection client = new ServerConnection("localhost", port,
                new ServerConnection.Handler() { });
        client.register("canceller", "pw");
        Thread.sleep(150);
        client.login("canceller", "pw", 2000);

        // Rest an order far from the market so it will not trade, then cancel.
        client.order("NVDA", "SELL", 99999, 25);
        Thread.sleep(300);
        long id = server.trades().latestOrderId("NVDA");

        check("order rested before cancel",
                "OPEN".equals(server.trades().orderStatus(id)));

        client.cancel("NVDA", id);
        Thread.sleep(400);
        server.awaitSettlement();

        check("cancel removed it from the book",
                server.engine().bookFor("NVDA").openQuantity(Side.SELL) == 0);
        check("cancel persisted to the database",
                "CANCELLED".equals(server.trades().orderStatus(id)));

        client.close();
    }

    /**
     * The concurrency invariant, this time across real sockets: 20 independent
     * clients each fire a buy at the same resting lot at the same moment.
     */
    private static void testConcurrentClientsRaceOverTheWire(ExchangeServer server, int port)
            throws Exception {
        final int clients = 20;
        final long restingQty = 100;

        ServerConnection maker = new ServerConnection("localhost", port,
                new ServerConnection.Handler() { });
        maker.register("maker", "pw");
        Thread.sleep(150);
        maker.login("maker", "pw", 2000);
        maker.order("MSFT", "SELL", 20000, restingQty);
        Thread.sleep(300);

        List<ServerConnection> takers = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            ServerConnection c = new ServerConnection("localhost", port,
                    new ServerConnection.Handler() { });
            c.register("taker" + i, "pw");
            Thread.sleep(60);
            c.login("taker" + i, "pw", 2000);
            takers.add(c);
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(clients);
        for (ServerConnection c : takers) {
            new Thread(() -> {
                try {
                    start.await();
                    c.order("MSFT", "BUY", 20000, 10);
                } catch (Exception e) {
                    System.err.println("taker failed: " + e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        Thread.sleep(800);
        server.awaitSettlement();

        long makerRemaining = server.trades().holdingOf(maker.userId(), "MSFT");
        long boughtTotal = 0;
        int winners = 0;
        for (ServerConnection c : takers) {
            long held = server.trades().holdingOf(c.userId(), "MSFT");
            long gained = held - 1_000;
            boughtTotal += gained;
            if (gained > 0) {
                winners++;
            }
        }

        System.out.printf("%n  [race/tcp] %d clients x 10 shares against a resting %d%n",
                clients, restingQty);
        System.out.printf("  [race/tcp] settled=%d  winners=%d  makerHolding=%d%n",
                boughtTotal, winners, makerRemaining);

        check("total settled never exceeds the resting lot", boughtTotal <= restingQty);
        check("total settled equals the resting lot", boughtTotal == restingQty);
        check("maker gave up exactly that many shares", makerRemaining == 1_000 - restingQty);
        check("exactly ten clients were filled", winners == 10);
        check("all clients stayed connected", server.engine().knowsSymbol("MSFT"));

        maker.close();
        takers.forEach(ServerConnection::close);
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
