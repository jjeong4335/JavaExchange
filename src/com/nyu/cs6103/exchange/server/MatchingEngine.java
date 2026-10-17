package com.nyu.cs6103.exchange.server;

import com.nyu.cs6103.exchange.common.BookSnapshot;
import com.nyu.cs6103.exchange.common.MatchResult;
import com.nyu.cs6103.exchange.common.Order;
import com.nyu.cs6103.exchange.common.Trade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Owns every OrderBook and is the only thread permitted to touch them.
 *
 * Client handler threads never call into an OrderBook directly. They enqueue a
 * Command and return immediately, so a slow book cannot stall the networking
 * layer. This single consumer is the point at which concurrent requests are
 * given a total order: when two users race for the same resting quantity,
 * whichever command reaches the queue first is the one that fills.
 *
 * The engine deliberately holds no locks. All shared mutable state lives
 * behind the queue, which is the only synchronized handoff in the design.
 */
public class MatchingEngine implements Runnable {

    /** Something the engine can be asked to do. */
    public sealed interface Command permits SubmitOrder, CancelOrder, Shutdown { }

    public record SubmitOrder(Order order) implements Command { }

    public record CancelOrder(String symbol, long orderId, long userId) implements Command { }

    public record Shutdown() implements Command { }

    /**
     * Callback into the rest of the server. Invoked on the engine thread, so
     * implementations must not block -- persist and broadcast asynchronously.
     */
    public interface Listener {
        void onMatch(MatchResult result);
        void onBookChanged(BookSnapshot snapshot);
        default void onCancelled(Order order) { }
        default void onRejected(Order order, String reason) { }
    }

    private static final int SNAPSHOT_DEPTH = 5;

    private final BlockingQueue<Command> queue = new LinkedBlockingQueue<>();
    private final Map<String, OrderBook> books = new HashMap<>();
    private final Listener listener;
    private volatile boolean running = true;

    public MatchingEngine(List<String> symbols, Listener listener) {
        this.listener = listener;
        for (String s : symbols) {
            books.put(s, new OrderBook(s));
        }
    }

    /** Called from any client handler thread. Does not block on matching. */
    public void submit(Order order) {
        queue.add(new SubmitOrder(order));
    }

    public void cancel(String symbol, long orderId, long userId) {
        queue.add(new CancelOrder(symbol, orderId, userId));
    }

    public void shutdown() {
        queue.add(new Shutdown());
    }

    public boolean knowsSymbol(String symbol) {
        return books.containsKey(symbol);
    }

    @Override
    public void run() {
        while (running) {
            Command command;
            try {
                command = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                handle(command);
            } catch (RuntimeException e) {
                // One malformed request must never kill the engine thread and
                // take the whole market down with it.
                System.err.println("engine error on " + command + ": " + e);
            }
        }
    }

    private void handle(Command command) {
        switch (command) {
            case SubmitOrder(Order order) -> {
                OrderBook book = books.get(order.symbol());
                if (book == null) {
                    listener.onRejected(order, "unknown symbol " + order.symbol());
                    return;
                }
                MatchResult result = book.submit(order);
                if (!result.isEmpty()) {
                    listener.onMatch(result);
                }
                listener.onBookChanged(book.snapshot(SNAPSHOT_DEPTH));
            }
            case CancelOrder(String symbol, long orderId, long userId) -> {
                OrderBook book = books.get(symbol);
                if (book != null) {
                    Order cancelled = book.cancel(orderId, userId);
                    if (cancelled != null) {
                        listener.onCancelled(cancelled);
                        listener.onBookChanged(book.snapshot(SNAPSHOT_DEPTH));
                    }
                }
            }
            case Shutdown ignored -> running = false;
        }
    }

    /**
     * Test-only accessor. Safe to call once the engine thread has stopped;
     * calling it while the engine is running would read state owned by
     * another thread.
     */
    public OrderBook bookFor(String symbol) {
        return books.get(symbol);
    }
}
