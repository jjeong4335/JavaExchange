package com.nyu.cs6103.exchange.server;

import com.nyu.cs6103.exchange.common.BookSnapshot;
import com.nyu.cs6103.exchange.common.MatchResult;
import com.nyu.cs6103.exchange.common.Order;
import com.nyu.cs6103.exchange.common.OrderStatus;
import com.nyu.cs6103.exchange.common.Side;
import com.nyu.cs6103.exchange.common.Trade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plain-main test harness for the matching core. No JUnit dependency so it
 * runs with nothing but a JDK.
 */
public class EngineTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testNoCrossRests();
        testSimpleCross();
        testPartialFillLeavesRemainder();
        testPriceThenTimePriority();
        testPriceImprovementForAggressor();
        testSweepsMultipleLevels();
        testCancelRemovesLiquidity();
        testCancelWrongUserRejected();
        testLevelTotalsMatchOrders();
        testConcurrentRaceForSameQuantity();

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------- scenarios ----------

    /** An order that does not cross simply rests; no trade is produced. */
    private static void testNoCrossRests() {
        OrderBook book = new OrderBook("AAPL");
        List<Trade> t = book.submit(buy(1, 10100, 40)).trades();
        check("no-cross produces no trade", t.isEmpty());
        check("no-cross rests full size", book.openQuantity(Side.BUY) == 40);
    }

    /** Buy meets an equal-priced ask and both are fully consumed. */
    private static void testSimpleCross() {
        OrderBook book = new OrderBook("AAPL");
        book.submit(sell(1, 10200, 30));
        List<Trade> t = book.submit(buy(2, 10200, 30)).trades();

        check("simple cross yields one trade", t.size() == 1);
        check("trade quantity is 30", t.get(0).quantity() == 30);
        check("trade price is 102.00", t.get(0).priceCents() == 10200);
        check("book is empty afterwards", book.isEmpty());
    }

    /** The diagram scenario: buy 50 into a resting 30, remainder rests. */
    private static void testPartialFillLeavesRemainder() {
        OrderBook book = new OrderBook("AAPL");
        book.submit(sell(1, 10300, 50));
        book.submit(sell(2, 10200, 30));
        book.submit(buy(3, 10100, 40));
        book.submit(buy(4, 10000, 100));

        List<Trade> t = book.submit(buy(5, 10200, 50)).trades();

        check("partial fill yields one trade", t.size() == 1);
        check("only the resting 30 executes", t.get(0).quantity() == 30);
        check("remaining 20 joins the bid side", book.openQuantity(Side.BUY) == 40 + 100 + 20);
        check("10200 ask level is gone", book.openQuantity(Side.SELL) == 50);

        BookSnapshot snap = book.snapshot(5);
        check("best bid is now 102.00", snap.bestBid().priceCents() == 10200);
        check("best bid size is 20", snap.bestBid().quantity() == 20);
        check("best ask is 103.00", snap.bestAsk().priceCents() == 10300);
    }

    /** Better price wins; equal price falls back to arrival order. */
    private static void testPriceThenTimePriority() {
        OrderBook book = new OrderBook("AAPL");
        Order early = sell(1, 10200, 10);
        Order late = sell(2, 10200, 10);
        Order worse = sell(3, 10300, 10);
        book.submit(worse);
        book.submit(early);
        book.submit(late);

        List<Trade> t = book.submit(buy(4, 10300, 25)).trades();

        check("sweep produces three fills", t.size() == 3);
        check("cheapest level first", t.get(0).priceCents() == 10200);
        check("earliest order at that level first", t.get(0).sellOrderId() == early.id());
        check("then the later order at same price", t.get(1).sellOrderId() == late.id());
        check("then the worse price", t.get(2).priceCents() == 10300);
        check("last fill is partial (5 of 10)", t.get(2).quantity() == 5);
    }

    /** A buyer willing to overpay still trades at the resting ask. */
    private static void testPriceImprovementForAggressor() {
        OrderBook book = new OrderBook("AAPL");
        book.submit(sell(1, 10000, 10));
        List<Trade> t = book.submit(buy(2, 10500, 10)).trades();

        check("prints at the resting price, not the aggressor's",
                t.get(0).priceCents() == 10000);
    }

    /** One large order consumes several price levels in ascending order. */
    private static void testSweepsMultipleLevels() {
        OrderBook book = new OrderBook("AAPL");
        book.submit(sell(1, 10100, 10));
        book.submit(sell(2, 10200, 10));
        book.submit(sell(3, 10300, 10));

        List<Trade> t = book.submit(buy(4, 10300, 30)).trades();

        check("all three levels consumed", t.size() == 3);
        check("ask side fully drained", book.openQuantity(Side.SELL) == 0);
        long total = t.stream().mapToLong(Trade::quantity).sum();
        check("total executed equals 30", total == 30);
    }

    private static void testCancelRemovesLiquidity() {
        OrderBook book = new OrderBook("AAPL");
        Order resting = sell(7, 10200, 30);
        book.submit(resting);

        check("cancel succeeds", book.cancel(resting.id(), 7) != null);
        check("status is CANCELLED", resting.status() == OrderStatus.CANCELLED);
        check("liquidity is gone", book.openQuantity(Side.SELL) == 0);

        List<Trade> t = book.submit(buy(8, 10200, 30)).trades();
        check("nothing left to trade against", t.isEmpty());
        check("second cancel of same id fails", book.cancel(resting.id(), 7) == null);
    }

    private static void testCancelWrongUserRejected() {
        OrderBook book = new OrderBook("AAPL");
        Order resting = sell(7, 10200, 30);
        book.submit(resting);
        boolean threw = false;
        try {
            book.cancel(resting.id(), 99);
        } catch (SecurityException e) {
            threw = true;
        }
        check("another user cannot cancel my order", threw);
        check("order survives the attempt", book.openQuantity(Side.SELL) == 30);
    }

    /**
     * The invariant that justifies the whole design. Many threads fire buy
     * orders simultaneously at a single resting lot of 100. Exactly 100 shares
     * must trade -- never more, however the threads interleave.
     */
    private static void testConcurrentRaceForSameQuantity() throws Exception {
        final int threads = 40;
        final long restingQty = 100;

        List<Trade> executions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger snapshots = new AtomicInteger();

        MatchingEngine engine = new MatchingEngine(List.of("AAPL"), new MatchingEngine.Listener() {
            @Override public void onMatch(MatchResult r) { executions.addAll(r.trades()); }
            @Override public void onBookChanged(BookSnapshot s) { snapshots.incrementAndGet(); }
        });
        Thread engineThread = new Thread(engine, "matching-engine");
        engineThread.start();

        engine.submit(sell(1, 10200, restingQty));

        // Release every thread at the same instant to maximise contention.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final long userId = 100 + i;
            pool.execute(() -> {
                try {
                    start.await();
                    engine.submit(buy(userId, 10200, 10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        // Drain: a no-op cancel behind the real work flushes the queue.
        Thread.sleep(300);
        engine.shutdown();
        engineThread.join(2000);

        long totalExecuted = executions.stream().mapToLong(Trade::quantity).sum();
        long stillResting = engine.bookFor("AAPL").openQuantity(Side.SELL);
        long unfilledBids = engine.bookFor("AAPL").openQuantity(Side.BUY);

        System.out.printf("%n  [race] %d threads x 10 shares against a resting %d%n",
                threads, restingQty);
        System.out.printf("  [race] executed=%d  askRemaining=%d  bidsQueued=%d%n",
                totalExecuted, stillResting, unfilledBids);

        check("never oversells the resting quantity", totalExecuted <= restingQty);
        check("sells exactly the resting quantity", totalExecuted == restingQty);
        check("ask side fully consumed", stillResting == 0);
        check("losers rest as bids, none vanish",
                unfilledBids == threads * 10 - restingQty);
    }

    /**
     * Snapshot quantities come from per-level running totals. Drive a random
     * mix of crossing orders, partial fills and cancels, and check after every
     * step that each total still equals the sum of its orders.
     */
    private static void testLevelTotalsMatchOrders() {
        OrderBook book = new OrderBook("AAPL");
        java.util.Random rnd = new java.util.Random(7);
        List<Order> live = new ArrayList<>();
        boolean consistent = true;
        for (int i = 0; i < 20_000 && consistent; i++) {
            if (!live.isEmpty() && rnd.nextInt(4) == 0) {
                Order victim = live.remove(rnd.nextInt(live.size()));
                book.cancel(victim.id(), victim.userId());
            } else {
                long price = 10000 + rnd.nextInt(21) - 10;
                long qty = 1 + rnd.nextInt(100);
                long user = 1 + rnd.nextInt(5);
                Order o = rnd.nextBoolean() ? buy(user, price, qty) : sell(user, price, qty);
                book.submit(o);
                if (o.remaining() > 0) {
                    live.add(o);
                }
            }
            consistent = book.levelTotalsConsistent();
        }
        check("level totals match order sums after fills and cancels", consistent);

        BookSnapshot snap = book.snapshot(Integer.MAX_VALUE);
        long bidSnap = snap.bids().stream().mapToLong(BookSnapshot.Level::quantity).sum();
        long askSnap = snap.asks().stream().mapToLong(BookSnapshot.Level::quantity).sum();
        check("full-depth snapshot equals open quantity on both sides",
                bidSnap == book.openQuantity(Side.BUY)
                        && askSnap == book.openQuantity(Side.SELL));
    }

    // ---------- helpers ----------

    private static Order buy(long user, long price, long qty) {
        return new Order(user, "AAPL", Side.BUY, price, qty);
    }

    private static Order sell(long user, long price, long qty) {
        return new Order(user, "AAPL", Side.SELL, price, qty);
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
