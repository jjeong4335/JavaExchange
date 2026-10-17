package com.nyu.cs6103.exchange.common;

import java.util.List;

/**
 * An immutable view of the top N price levels on each side, safe to hand to
 * another thread or serialize to a client.
 */
public record BookSnapshot(String symbol, List<Level> bids, List<Level> asks) {

    public record Level(long priceCents, long quantity, int orderCount) { }

    public Level bestBid() { return bids.isEmpty() ? null : bids.get(0); }
    public Level bestAsk() { return asks.isEmpty() ? null : asks.get(0); }

    public Long spreadCents() {
        Level b = bestBid();
        Level a = bestAsk();
        return (b == null || a == null) ? null : a.priceCents() - b.priceCents();
    }
}
