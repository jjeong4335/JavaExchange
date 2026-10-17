package com.nyu.cs6103.exchange.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Conversion helpers between display prices and the internal cent representation. */
public final class Money {

    private Money() { }

    public static long toCents(String dollars) {
        return new BigDecimal(dollars.trim())
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact();
    }

    public static String format(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
