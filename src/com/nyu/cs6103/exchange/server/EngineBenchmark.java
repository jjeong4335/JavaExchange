package com.nyu.cs6103.exchange.server;

import com.nyu.cs6103.exchange.common.BookSnapshot;
import com.nyu.cs6103.exchange.common.MatchResult;
import com.nyu.cs6103.exchange.common.Order;
import com.nyu.cs6103.exchange.common.Side;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plain-main throughput benchmark for the matching core. No JMH dependency, so
 * it runs with nothing but a JDK, like the other harnesses.
 *
 * Three measurements:
 *
 *   1. OrderBook   -- OrderBook.submit() called directly on one thread. This is
 *                     the cost of price-time matching alone.
 *   2. + snapshot  -- the same, plus the 5-level depth snapshot the engine
 *                     builds after every order. Isolates the snapshot cost.
 *   3. Engine      -- orders handed to MatchingEngine.submit() from a producer
 *                     thread and processed by the engine thread, including the
 *                     queue handoff, listener callbacks and the depth snapshot
 *                     built after every order. Networking and the database are
 *                     NOT included.
 *
 * Orders are generated before timing starts (fixed seed, so every run sees the
 * same order flow): 50/50 buy/sell, limit prices uniform within +/- 0.50 of
 * 100.00, quantity 1-100. Because both sides quote around the same mid, a large
 * share of orders cross and the rest keep the book populated.
 *
 * Each measurement runs several warm-up rounds (so the JIT compiles the hot
 * path) followed by measured rounds; the median round is reported.
 *
 * Usage: java -cp out com.nyu.cs6103.exchange.server.EngineBenchmark [orders] [rounds]
 */
public class EngineBenchmark {

    private static final String SYMBOL = "AAPL";
    private static final int WARMUP_ROUNDS = 5;

    public static void main(String[] args) throws Exception {
        int orders = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        System.out.printf("JDK %s, %s %s, %d cores%n",
                System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        System.out.printf("%,d orders per round, %d warm-up + %d measured rounds%n%n",
                orders, WARMUP_ROUNDS, rounds);

        report("OrderBook.submit (matching only)", measure(orders, rounds, Mode.BOOK));
        report("OrderBook.submit + snapshot(5)", measure(orders, rounds, Mode.BOOK_SNAPSHOT));
        report("MatchingEngine (queue + thread + snapshot)", measure(orders, rounds, Mode.ENGINE));
    }

    private enum Mode { BOOK, BOOK_SNAPSHOT, ENGINE }

    private static double[] measure(int orders, int rounds, Mode mode)
            throws InterruptedException {
        double[] perSec = new double[rounds];
        long[] trades = new long[1];
        for (int r = 0; r < WARMUP_ROUNDS + rounds; r++) {
            Order[] flow = generate(orders, 42L + r);
            trades[0] = 0;
            long nanos = switch (mode) {
                case BOOK -> runBook(flow, trades, false);
                case BOOK_SNAPSHOT -> runBook(flow, trades, true);
                case ENGINE -> runEngine(flow, trades);
            };
            if (r >= WARMUP_ROUNDS) {
                perSec[r - WARMUP_ROUNDS] = orders / (nanos / 1e9);
            }
            if (r == WARMUP_ROUNDS + rounds - 1) {
                System.out.printf("  (last round: %,d trades from %,d orders)%n",
                        trades[0], orders);
            }
        }
        return perSec;
    }

    private static long runBook(Order[] flow, long[] trades, boolean snapshot) {
        OrderBook book = new OrderBook(SYMBOL);
        long sink = 0;
        long start = System.nanoTime();
        for (Order o : flow) {
            trades[0] += book.submit(o).trades().size();
            if (snapshot) {
                sink += book.snapshot(5).bids().size();
            }
        }
        long nanos = System.nanoTime() - start;
        if (sink < 0) {
            System.out.println(sink);   // keeps the JIT from discarding the snapshots
        }
        return nanos;
    }

    private static long runEngine(Order[] flow, long[] trades) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicLong processed = new AtomicLong();
        long target = flow.length;

        MatchingEngine engine = new MatchingEngine(List.of(SYMBOL), new MatchingEngine.Listener() {
            @Override
            public void onMatch(MatchResult result) {
                trades[0] += result.trades().size();
            }

            @Override
            public void onBookChanged(BookSnapshot snapshot) {
                // Called exactly once per submitted order, so it marks progress.
                if (processed.incrementAndGet() == target) {
                    done.countDown();
                }
            }
        });
        Thread t = new Thread(engine, "engine-bench");
        t.start();

        long start = System.nanoTime();
        for (Order o : flow) {
            engine.submit(o);
        }
        if (!done.await(5, TimeUnit.MINUTES)) {
            throw new IllegalStateException("engine did not finish");
        }
        long nanos = System.nanoTime() - start;

        engine.shutdown();
        t.join();
        return nanos;
    }

    private static Order[] generate(int n, long seed) {
        Random rnd = new Random(seed);
        Order[] out = new Order[n];
        for (int i = 0; i < n; i++) {
            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            long price = 10_000 + rnd.nextInt(101) - 50;   // 99.50 .. 100.50
            long qty = 1 + rnd.nextInt(100);
            out[i] = new Order(1 + rnd.nextInt(100), SYMBOL, side, price, qty);
        }
        return out;
    }

    private static void report(String name, double[] perSec) {
        double[] sorted = perSec.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        System.out.printf("%-44s median %,12.0f orders/sec  (min %,.0f, max %,.0f)%n%n",
                name, median, sorted[0], sorted[sorted.length - 1]);
    }
}
