package com.nyu.cs6103.exchange.common;

import java.util.List;

/**
 * The outcome of submitting one order.
 *
 * Settlement needs more than the executions: it also has to write back the new
 * remaining quantity and status of every order the match touched, which is the
 * aggressor plus each resting order it consumed. Returning both together keeps
 * them consistent -- there is no window in which a caller could persist the
 * trades and forget the order updates.
 */
public record MatchResult(List<Trade> trades, List<Order> touchedOrders) {

    public boolean isEmpty() {
        return trades.isEmpty();
    }

    public long executedQuantity() {
        return trades.stream().mapToLong(Trade::quantity).sum();
    }
}
