package com.nyu.cs6103.exchange.client;

import com.nyu.cs6103.exchange.common.BookSnapshot;
import com.nyu.cs6103.exchange.common.Money;

import javax.swing.table.AbstractTableModel;
import java.util.List;

/**
 * Presents a BookSnapshot as the classic four-column ladder:
 *
 *     bid qty | bid price | ask price | ask qty
 *
 * Row 0 is the top of book on both sides, so the spread sits on the first row
 * and a trader reads the most important numbers without scanning.
 *
 * THREADING: like every Swing model, this must only be touched on the event
 * dispatch thread. Market data arrives on the socket listener thread, so
 * TradingFrame hops threads with invokeLater before calling update(). Mutating
 * a table model off the EDT produces corruption that appears only under load,
 * which is precisely the bug class that survives manual testing.
 */
public class DepthTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = { "Bid Qty", "Bid", "Ask", "Ask Qty" };

    private final int depth;
    private BookSnapshot snapshot;

    public DepthTableModel(int depth) {
        this.depth = depth;
        this.snapshot = new BookSnapshot("", List.of(), List.of());
    }

    /** Replaces the displayed book. Call on the EDT only. */
    public void update(BookSnapshot next) {
        this.snapshot = next;
        fireTableDataChanged();
    }

    public BookSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public int getRowCount() {
        return depth;
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
        List<BookSnapshot.Level> side =
                (column <= 1) ? snapshot.bids() : snapshot.asks();
        if (row >= side.size()) {
            return "";
        }
        BookSnapshot.Level level = side.get(row);
        return switch (column) {
            case 0, 3 -> String.valueOf(level.quantity());
            case 1, 2 -> Money.format(level.priceCents());
            default -> "";
        };
    }

    /** True for the two price columns, which the renderer colours. */
    public boolean isPriceColumn(int column) {
        return column == 1 || column == 2;
    }

    public boolean isBidColumn(int column) {
        return column <= 1;
    }
}
