package com.nyu.cs6103.exchange.client;

import com.nyu.cs6103.exchange.common.Money;

import javax.swing.table.AbstractTableModel;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A rolling log of executions, newest first.
 *
 * The backing deque is capped: a busy market produces prints indefinitely, and
 * an unbounded list would grow until the client ran out of memory. Nobody
 * scrolls back through thousands of rows anyway, so old prints are discarded
 * once the cap is reached.
 */
public class BlotterTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = { "Time", "Symbol", "Price", "Qty", "" };
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_ROWS = 500;

    public record Print(LocalTime time, String symbol, long priceCents, long qty, boolean mine) { }

    private final Deque<Print> prints = new ArrayDeque<>();

    /** Adds a print at the top. Call on the EDT only. */
    public void add(String symbol, long priceCents, long qty, boolean mine) {
        prints.addFirst(new Print(LocalTime.now(), symbol, priceCents, qty, mine));
        while (prints.size() > MAX_ROWS) {
            prints.removeLast();
        }
        fireTableDataChanged();
    }

    public List<Print> prints() {
        return new ArrayList<>(prints);
    }

    public Print rowAt(int row) {
        int i = 0;
        for (Print p : prints) {
            if (i++ == row) {
                return p;
            }
        }
        return null;
    }

    @Override
    public int getRowCount() {
        return prints.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
        Print p = rowAt(row);
        if (p == null) {
            return "";
        }
        return switch (column) {
            case 0 -> p.time().format(TIME);
            case 1 -> p.symbol();
            case 2 -> Money.format(p.priceCents());
            case 3 -> String.valueOf(p.qty());
            case 4 -> p.mine() ? "you" : "";
            default -> "";
        };
    }
}
