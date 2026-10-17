package com.nyu.cs6103.exchange.common;

import java.util.ArrayList;
import java.util.List;

/**
 * The wire format: one message per line, fields separated by '|'.
 *
 * A text protocol was chosen over JSON deliberately. It adds no third-party
 * dependency, it is trivially readable in a packet capture, and the whole
 * codec is small enough to audit at a glance. It also means the server can be
 * driven by hand with `telnet localhost 5555`, which makes demonstrating the
 * networking layer much easier than a binary or JSON format would.
 *
 * Client to server:
 *   REGISTER|username|password
 *   LOGIN|username|password
 *   ORDER|symbol|BUY|10200|50        price in cents
 *   CANCEL|symbol|orderId
 *   QUIT
 *
 * Server to client:
 *   WELCOME|symbols,comma,separated
 *   OK|LOGIN|userId|cashCents
 *   ERR|human readable reason
 *   ACCEPTED|orderId
 *   BOOK|symbol|bidLevels|askLevels     levels are price:qty:count;...
 *   TRADE|symbol|price|qty|buyOrderId|sellOrderId
 *   FILL|orderId|qty|price|status       private, only to the two participants
 */
public final class Protocol {

    public static final char SEP = '|';
    public static final String SEP_S = "|";

    private Protocol() { }

    /** Rejects field values that would corrupt the framing. */
    public static void validateField(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
        if (value.indexOf(SEP) >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(what + " must not contain '|' or a newline");
        }
    }

    public static String[] split(String line) {
        return line.split("\\" + SEP_S, -1);
    }

    public static String join(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    // ---- book snapshot ----

    public static String encodeBook(BookSnapshot s) {
        return join("BOOK", s.symbol(), encodeLevels(s.bids()), encodeLevels(s.asks()));
    }

    private static String encodeLevels(List<BookSnapshot.Level> levels) {
        StringBuilder sb = new StringBuilder();
        for (BookSnapshot.Level l : levels) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(l.priceCents()).append(':')
              .append(l.quantity()).append(':')
              .append(l.orderCount());
        }
        return sb.toString();
    }

    public static BookSnapshot decodeBook(String line) {
        String[] f = split(line);
        if (f.length != 4 || !"BOOK".equals(f[0])) {
            throw new IllegalArgumentException("not a BOOK message: " + line);
        }
        return new BookSnapshot(f[1], decodeLevels(f[2]), decodeLevels(f[3]));
    }

    private static List<BookSnapshot.Level> decodeLevels(String encoded) {
        List<BookSnapshot.Level> out = new ArrayList<>();
        if (encoded.isEmpty()) {
            return out;
        }
        for (String part : encoded.split(";")) {
            String[] p = part.split(":");
            out.add(new BookSnapshot.Level(
                    Long.parseLong(p[0]), Long.parseLong(p[1]), Integer.parseInt(p[2])));
        }
        return out;
    }

    // ---- trade ----

    public static String encodeTrade(Trade t) {
        return join("TRADE", t.symbol(), t.priceCents(), t.quantity(),
                t.buyOrderId(), t.sellOrderId());
    }

    public static String encodeFill(long orderId, long qty, long priceCents, String status) {
        return join("FILL", orderId, qty, priceCents, status);
    }

    public static String error(String reason) {
        return join("ERR", reason.replace(SEP, '/').replace('\n', ' '));
    }
}
