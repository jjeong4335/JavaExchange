package com.nyu.cs6103.exchange.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A single limit order.
 *
 * Prices are stored as an integral number of cents rather than as a double.
 * Binary floating point cannot represent values such as 0.10 exactly, so
 * comparing two doubles for a price match would occasionally fail even when
 * the prices are conceptually identical. Money is always integral here.
 *
 * Instances are mutable (remaining, status) but are only ever mutated by the
 * single matching thread that owns the OrderBook, so no synchronization is
 * required on this class.
 */
public class Order {

    private static final AtomicLong ID_SEQ = new AtomicLong(1);

    private final long id;
    private final long userId;
    private final String symbol;
    private final Side side;
    private final long priceCents;
    private final long quantity;
    private final long sequence;

    private long remaining;
    private OrderStatus status;

    public Order(long userId, String symbol, Side side, long priceCents, long quantity) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol required");
        }
        if (side == null) {
            throw new IllegalArgumentException("side required");
        }
        if (priceCents <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.id = ID_SEQ.getAndIncrement();
        this.sequence = this.id;
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.priceCents = priceCents;
        this.quantity = quantity;
        this.remaining = quantity;
        this.status = OrderStatus.OPEN;
    }

    public long id() { return id; }
    public long userId() { return userId; }
    public String symbol() { return symbol; }
    public Side side() { return side; }
    public long priceCents() { return priceCents; }
    public long quantity() { return quantity; }
    public long sequence() { return sequence; }
    public long remaining() { return remaining; }
    public long filled() { return quantity - remaining; }
    public OrderStatus status() { return status; }

    public boolean isActive() {
        return status == OrderStatus.OPEN || status == OrderStatus.PARTIALLY_FILLED;
    }

    /** Reduce the open quantity by an executed amount and update the status. */
    public void reduceBy(long executedQty) {
        if (executedQty <= 0 || executedQty > remaining) {
            throw new IllegalArgumentException("bad fill quantity " + executedQty);
        }
        remaining -= executedQty;
        status = (remaining == 0) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void markCancelled() {
        status = OrderStatus.CANCELLED;
    }

    @Override
    public String toString() {
        return String.format("Order#%d %s %s %d @ %s (rem %d, %s)",
                id, side, symbol, quantity, Money.format(priceCents), remaining, status);
    }
}
