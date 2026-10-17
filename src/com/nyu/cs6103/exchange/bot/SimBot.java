package com.nyu.cs6103.exchange.bot;

import com.nyu.cs6103.exchange.client.ServerConnection;
import com.nyu.cs6103.exchange.common.BookSnapshot;
import com.nyu.cs6103.exchange.common.Money;
import com.nyu.cs6103.exchange.common.Side;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A headless client that submits orders on its own, so the market has activity
 * without a human clicking Buy in two windows.
 *
 * Each bot is an ordinary client: it speaks the same protocol over the same
 * socket and gets no special treatment from the server. Running several is
 * what makes the concurrency visible -- the order book updates continuously in
 * every open GUI, and the price chart fills in on its own.
 *
 * Behaviour is a crude imitation of a real participant rather than random
 * noise, because purely random orders either never cross (no trades at all) or
 * cross constantly (the book is always empty). The bot therefore:
 *
 *   - tracks the current best bid and offer from the market data it receives
 *   - mostly MAKES, posting a passive order near the midpoint
 *   - sometimes TAKES, crossing the spread to hit a resting order
 *   - cancels its own stale orders so the book does not fill up with them
 *   - watches its own cash and inventory so it does not submit orders the
 *     server's pre-trade check would reject
 */
public class SimBot implements Runnable {

    /** Fallback midpoint per symbol, used before any book has arrived. */
    private static final Map<String, Long> REFERENCE = Map.of(
            "AAPL", 10_200L,
            "MSFT", 20_000L,
            "NVDA", 15_000L);

    private static final long STARTING_CASH_CENTS = 1_000_000_00L;
    private static final long STARTING_SHARES = 1_000L;

    private static final double TAKE_PROBABILITY = 0.35;
    private static final double CANCEL_PROBABILITY = 0.20;
    private static final int MAX_RESTING = 4;

    private final String host;
    private final int port;
    private final String name;
    private final String symbol;
    private final long minDelayMs;
    private final long maxDelayMs;
    private final Random random;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong bestBid = new AtomicLong(-1);
    private final AtomicLong bestAsk = new AtomicLong(-1);

    /** Orders this bot has sent but not yet seen an id for, in send order. */
    private final Deque<Side> pendingSides = new ArrayDeque<>();
    private final Map<Long, Side> myOrderSides = new HashMap<>();
    private final Deque<Long> myRestingOrders = new ArrayDeque<>();

    private long cashCents = STARTING_CASH_CENTS;
    private long shares = STARTING_SHARES;
    private int tradesSeen = 0;

    private ServerConnection connection;

    public SimBot(String host, int port, String name, String symbol,
                  long minDelayMs, long maxDelayMs, long seed) {
        this.host = host;
        this.port = port;
        this.name = name;
        this.symbol = symbol;
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.random = new Random(seed);
    }

    public void stop() {
        running.set(false);
        if (connection != null) {
            connection.close();
        }
    }

    public int tradesSeen() {
        return tradesSeen;
    }

    public String name() {
        return name;
    }

    @Override
    public void run() {
        try {
            connection = new ServerConnection(host, port, new BotHandler());
            // Registration fails harmlessly if the account already exists, so
            // a bot can be restarted against a persistent database.
            connection.register(name, "botpw");
            Thread.sleep(200);
            if (!connection.login(name, "botpw", 4000)) {
                System.err.println(name + ": login failed (" + connection.loginError() + ")");
                return;
            }
            System.out.println(name + " trading " + symbol);

            while (running.get()) {
                Thread.sleep(minDelayMs + (long) (random.nextDouble() * (maxDelayMs - minDelayMs)));
                act();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (running.get()) {
                System.err.println(name + ": " + e.getMessage());
            }
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private void act() throws IOException {
        if (random.nextDouble() < CANCEL_PROBABILITY && !myRestingOrders.isEmpty()) {
            connection.cancel(symbol, myRestingOrders.pollFirst());
            return;
        }

        long bid = bestBid.get();
        long ask = bestAsk.get();
        long mid = midpoint(bid, ask);
        long qty = 5 + random.nextInt(16);

        Side side = chooseSide(qty, mid);
        if (side == null) {
            return;
        }

        boolean take = random.nextDouble() < TAKE_PROBABILITY;
        long price;

        if (take && side == Side.BUY && ask > 0) {
            price = ask;                       // lift the offer
        } else if (take && side == Side.SELL && bid > 0) {
            price = bid;                       // hit the bid
        } else {
            // Post passively: a few cents behind the midpoint on our own side.
            long offset = 5 + random.nextInt(40);
            price = (side == Side.BUY) ? mid - offset : mid + offset;
        }
        if (price <= 0) {
            return;
        }

        // Respect the same limits the server enforces, so the bot does not
        // spend its time collecting rejections.
        if (side == Side.BUY && price * qty > cashCents) {
            return;
        }
        if (side == Side.SELL && qty > shares) {
            return;
        }

        pendingSides.addLast(side);
        connection.order(symbol, side.name(), price, qty);
    }

    /**
     * Picks a side, biased to keep inventory near where it started. A bot that
     * only ever sold would run out of shares and go silent within a minute.
     */
    private Side chooseSide(long qty, long mid) {
        boolean canBuy = cashCents > mid * qty * 2;
        boolean canSell = shares > qty * 2;

        if (!canBuy && !canSell) {
            return null;
        }
        if (!canBuy) {
            return Side.SELL;
        }
        if (!canSell) {
            return Side.BUY;
        }
        double buyBias = 0.5 + (STARTING_SHARES - shares) / (double) (STARTING_SHARES * 4);
        return random.nextDouble() < buyBias ? Side.BUY : Side.SELL;
    }

    private long midpoint(long bid, long ask) {
        if (bid > 0 && ask > 0) {
            return (bid + ask) / 2;
        }
        if (bid > 0) {
            return bid;
        }
        if (ask > 0) {
            return ask;
        }
        return REFERENCE.getOrDefault(symbol, 10_000L);
    }

    private class BotHandler implements ServerConnection.Handler {
        @Override
        public void onBook(BookSnapshot snapshot) {
            if (!snapshot.symbol().equals(symbol)) {
                return;
            }
            bestBid.set(snapshot.bestBid() == null ? -1 : snapshot.bestBid().priceCents());
            bestAsk.set(snapshot.bestAsk() == null ? -1 : snapshot.bestAsk().priceCents());
        }

        @Override
        public void onFill(long orderId, long qty, long priceCents, String status) {
            Side side = myOrderSides.get(orderId);
            if (side == null) {
                return;
            }
            // The server reports cumulative filled quantity for the order, so
            // only the increment since the last report is new.
            tradesSeen++;
            if (side == Side.BUY) {
                cashCents -= priceCents * qty;
                shares += qty;
            } else {
                cashCents += priceCents * qty;
                shares -= qty;
            }
            if ("FILLED".equals(status)) {
                myRestingOrders.remove(orderId);
                myOrderSides.remove(orderId);
            }
        }

        @Override
        public void onOther(String rawLine) {
            if (!rawLine.startsWith("ACCEPTED")) {
                return;
            }
            // Acknowledgements arrive in the order the orders were sent on
            // this connection, so the queue head is this order's side.
            Side side = pendingSides.pollFirst();
            long id = Long.parseLong(rawLine.split("\\|")[1]);
            if (side != null) {
                myOrderSides.put(id, side);
            }
            myRestingOrders.addLast(id);
            while (myRestingOrders.size() > MAX_RESTING) {
                myRestingOrders.pollFirst();
            }
        }

        @Override
        public void onError(String reason) {
            if (!reason.contains("already taken")) {
                System.err.println(name + " <- " + reason);
            }
        }
    }

    /** Starts a group of bots that trade against each other. */
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5555;
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        String symbol = args.length > 3 ? args[3] : "AAPL";

        System.out.printf("starting %d bots on %s:%d trading %s%n", count, host, port, symbol);
        System.out.println("reference midpoint " + Money.format(
                REFERENCE.getOrDefault(symbol, 10_000L)) + "; press Ctrl-C to stop");

        SimBot[] bots = new SimBot[count];
        Thread[] threads = new Thread[count];
        for (int i = 0; i < count; i++) {
            bots[i] = new SimBot(host, port, "bot" + i, symbol, 400, 1_400, 1_000 + i);
            threads[i] = new Thread(bots[i], "bot" + i);
            threads[i].setDaemon(true);
            threads[i].start();
            Thread.sleep(300);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (SimBot b : bots) {
                b.stop();
            }
        }));

        Thread.currentThread().join();
    }
}
