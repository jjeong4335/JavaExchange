package com.nyu.cs6103.exchange.common;

import java.time.Instant;

/**
 * One execution between an aggressing order and a resting order.
 *
 * The trade always prints at the resting order's price. The resting order was
 * on the book first and set the terms; an aggressor willing to pay more simply
 * receives price improvement.
 */
public record Trade(
        String symbol,
        long priceCents,
        long quantity,
        long buyOrderId,
        long sellOrderId,
        long buyUserId,
        long sellUserId,
        Instant executedAt) {

    public long notionalCents() {
        return priceCents * quantity;
    }

    @Override
    public String toString() {
        return String.format("TRADE %s %d @ %s (buy#%d / sell#%d)",
                symbol, quantity, Money.format(priceCents), buyOrderId, sellOrderId);
    }
}
