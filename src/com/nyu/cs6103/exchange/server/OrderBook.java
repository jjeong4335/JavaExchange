package com.nyu.cs6103.exchange.server;

import com.nyu.cs6103.exchange.common.BookSnapshot;
import com.nyu.cs6103.exchange.common.MatchResult;
import com.nyu.cs6103.exchange.common.Order;
import com.nyu.cs6103.exchange.common.Side;
import com.nyu.cs6103.exchange.common.Trade;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A limit order book for one instrument, implementing price-time priority.
 *
 * THREADING CONTRACT: this class is deliberately NOT thread safe. Exactly one
 * thread -- the MatchingEngine's consumer thread -- may touch an instance.
 * Every other thread reaches the book by placing a command on the engine's
 * queue. Confining the mutable state to a single thread removes the entire
 * class of lock-ordering and interleaving bugs, and makes the resulting
 * execution sequence deterministic and therefore reproducible under test.
 *
 * Structure: one TreeMap per side, keyed by price. Bids are ordered highest
 * price first and asks lowest price first, so the best level on either side is
 * always firstEntry(). Each price level holds an ArrayDeque of orders in
 * arrival order, which gives time priority within a level for free: we always
 * consume from the head and always append to the tail.
 *
 * Each level also carries a running total of the open quantity resting on it,
 * updated on rest, fill and cancel. A depth snapshot is taken after every
 * order, and summing each order at a level would make that snapshot cost
 * O(orders in the top levels); the running total makes it O(depth).
 */
public class OrderBook {

    private final String symbol;

    /** One price level: orders in arrival order plus their total open quantity. */
    private static final class PriceLevel {
        final ArrayDeque<Order> orders = new ArrayDeque<>();
        long totalQty;
    }

    private final TreeMap<Long, PriceLevel> bids =
            new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks =
            new TreeMap<>(Comparator.naturalOrder());

    /** Index of every resting order, so cancellation does not scan the book. */
    private final Map<Long, Order> restingById = new HashMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /**
     * Match an incoming order against the book, then rest any unfilled
     * remainder. Returns the executions produced (oldest first) together with
     * every order whose remaining quantity or status changed.
     */
    public MatchResult submit(Order incoming) {
        if (!incoming.symbol().equals(symbol)) {
            throw new IllegalArgumentException(
                    "order for " + incoming.symbol() + " sent to " + symbol + " book");
        }

        List<Trade> trades = new ArrayList<>();
        List<Order> touched = new ArrayList<>();
        touched.add(incoming);
        TreeMap<Long, PriceLevel> opposite =
                (incoming.side() == Side.BUY) ? asks : bids;

        while (incoming.remaining() > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, PriceLevel> best = opposite.firstEntry();
            long bestPrice = best.getKey();

            // A buy crosses when it is willing to pay at least the best ask;
            // a sell crosses when it is willing to accept at most the best bid.
            boolean crosses = (incoming.side() == Side.BUY)
                    ? incoming.priceCents() >= bestPrice
                    : incoming.priceCents() <= bestPrice;
            if (!crosses) {
                break;
            }

            PriceLevel level = best.getValue();
            ArrayDeque<Order> queue = level.orders;
            while (incoming.remaining() > 0 && !queue.isEmpty()) {
                Order resting = queue.peek();
                long qty = Math.min(incoming.remaining(), resting.remaining());

                incoming.reduceBy(qty);
                resting.reduceBy(qty);
                level.totalQty -= qty;

                trades.add(buildTrade(incoming, resting, bestPrice, qty));
                touched.add(resting);

                if (resting.remaining() == 0) {
                    queue.poll();
                    restingById.remove(resting.id());
                }
            }

            // Drop the level once it is exhausted so firstEntry() stays valid.
            if (queue.isEmpty()) {
                opposite.pollFirstEntry();
            }
        }

        if (incoming.remaining() > 0) {
            rest(incoming);
        }
        return new MatchResult(trades, touched);
    }

    private Trade buildTrade(Order incoming, Order resting, long price, long qty) {
        Order buy = (incoming.side() == Side.BUY) ? incoming : resting;
        Order sell = (incoming.side() == Side.BUY) ? resting : incoming;
        return new Trade(symbol, price, qty,
                buy.id(), sell.id(), buy.userId(), sell.userId(), Instant.now());
    }

    private void rest(Order order) {
        TreeMap<Long, PriceLevel> ownSide =
                (order.side() == Side.BUY) ? bids : asks;
        PriceLevel level = ownSide.computeIfAbsent(order.priceCents(), k -> new PriceLevel());
        level.orders.addLast(order);
        level.totalQty += order.remaining();
        restingById.put(order.id(), order);
    }

    /**
     * Remove a resting order. Returns the cancelled order so the caller can
     * persist its new status, or null if the id is unknown -- which covers an
     * order that was already fully filled or already cancelled, a race every
     * real exchange has to answer, and the answer is simply "too late".
     */
    public Order cancel(long orderId, long requestingUserId) {
        Order order = restingById.get(orderId);
        if (order == null) {
            return null;
        }
        if (order.userId() != requestingUserId) {
            throw new SecurityException("user " + requestingUserId
                    + " may not cancel order " + orderId);
        }

        TreeMap<Long, PriceLevel> ownSide =
                (order.side() == Side.BUY) ? bids : asks;
        PriceLevel level = ownSide.get(order.priceCents());
        if (level != null && level.orders.remove(order)) {
            level.totalQty -= order.remaining();
            if (level.orders.isEmpty()) {
                ownSide.remove(order.priceCents());
            }
        }
        restingById.remove(orderId);
        order.markCancelled();
        return order;
    }

    /** Immutable view of the top {@code depth} levels per side. */
    public BookSnapshot snapshot(int depth) {
        return new BookSnapshot(symbol, levels(bids, depth), levels(asks, depth));
    }

    private List<BookSnapshot.Level> levels(TreeMap<Long, PriceLevel> side, int depth) {
        List<BookSnapshot.Level> out = new ArrayList<>();
        for (Map.Entry<Long, PriceLevel> e : side.entrySet()) {
            if (out.size() == depth) {
                break;
            }
            PriceLevel level = e.getValue();
            out.add(new BookSnapshot.Level(e.getKey(), level.totalQty, level.orders.size()));
        }
        return out;
    }

    /**
     * Total open quantity resting on one side; used by the invariant tests.
     * Sums every order directly rather than trusting the per-level totals, so
     * it serves as an independent check on them.
     */
    public long openQuantity(Side side) {
        TreeMap<Long, PriceLevel> book = (side == Side.BUY) ? bids : asks;
        long total = 0;
        for (PriceLevel level : book.values()) {
            for (Order o : level.orders) {
                total += o.remaining();
            }
        }
        return total;
    }

    /**
     * Test hook: true if every level's running total equals the sum of its
     * orders' remaining quantities, on both sides.
     */
    boolean levelTotalsConsistent() {
        for (TreeMap<Long, PriceLevel> side : List.of(bids, asks)) {
            for (PriceLevel level : side.values()) {
                long sum = 0;
                for (Order o : level.orders) {
                    sum += o.remaining();
                }
                if (sum != level.totalQty) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }
}
