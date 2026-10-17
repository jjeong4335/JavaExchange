package com.nyu.cs6103.exchange.client;

import com.nyu.cs6103.exchange.common.BookSnapshot;
import com.nyu.cs6103.exchange.common.Money;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * The main trading window: depth ladder, order entry, execution blotter, and
 * a price chart.
 *
 * Every inbound message arrives on the socket listener thread, and Swing
 * components may only be touched on the event dispatch thread. The handler()
 * below is therefore a thin adapter: each callback does nothing but wrap the
 * real work in SwingUtilities.invokeLater. Keeping that discipline in one
 * place means the rest of the class can assume it is already on the EDT.
 */
public class TradingFrame extends JFrame {

    private static final int DEPTH = 5;
    private static final String[] SYMBOLS = { "AAPL", "MSFT", "NVDA" };

    private final String username;

    private final DepthTableModel depthModel = new DepthTableModel(DEPTH);
    private final BlotterTableModel blotterModel = new BlotterTableModel();
    private final PriceChartPanel chart = new PriceChartPanel();

    private final JComboBox<String> symbolBox = new JComboBox<>(SYMBOLS);
    private final JTextField priceField = new JTextField(8);
    private final JTextField qtyField = new JTextField(6);
    private final JButton buyButton = new JButton("Buy");
    private final JButton sellButton = new JButton("Sell");
    private final JLabel spreadLabel = new JLabel("spread --");
    private final JLabel status = new JLabel("connecting");

    /** Order ids this client owns, so its own prints can be highlighted. */
    private final Set<Long> myOrderIds = new HashSet<>();

    private ServerConnection connection;

    public TradingFrame(String username) {
        super("JavaExchange - " + username);
        this.username = username;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        buyButton.addActionListener(e -> sendOrder("BUY"));
        sellButton.addActionListener(e -> sendOrder("SELL"));
        symbolBox.addActionListener(e -> {
            chart.clear();
            depthModel.update(new BookSnapshot(currentSymbol(), java.util.List.of(), java.util.List.of()));
        });

        setSize(920, 580);
        setMinimumSize(new Dimension(780, 500));
        setLocationRelativeTo(null);
    }

    public void attach(ServerConnection connection) {
        this.connection = connection;
        status.setForeground(new Color(0x0F6E56));
        status.setText("connected as " + username);
    }

    // ---- layout ----

    private JPanel buildTop() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        bar.add(new JLabel("Symbol "));
        symbolBox.setMaximumSize(new Dimension(100, 26));
        bar.add(symbolBox);
        bar.add(Box.createHorizontalStrut(16));
        bar.add(new JLabel("Price "));
        priceField.setMaximumSize(new Dimension(90, 26));
        bar.add(priceField);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(new JLabel("Qty "));
        qtyField.setMaximumSize(new Dimension(70, 26));
        bar.add(qtyField);
        bar.add(Box.createHorizontalStrut(12));
        buyButton.setForeground(new Color(0x185FA5));
        sellButton.setForeground(new Color(0xA32D2D));
        bar.add(buyButton);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(sellButton);
        bar.add(Box.createHorizontalGlue());
        spreadLabel.setFont(spreadLabel.getFont().deriveFont(Font.PLAIN, 12f));
        bar.add(spreadLabel);
        return bar;
    }

    private JSplitPane buildCenter() {
        JTable depthTable = new JTable(depthModel);
        depthTable.setRowHeight(22);
        depthTable.setShowGrid(false);
        depthTable.setDefaultRenderer(Object.class, new LadderRenderer());
        depthTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JTable blotterTable = new JTable(blotterModel);
        blotterTable.setRowHeight(20);
        blotterTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                titled("Order book", new JScrollPane(depthTable)),
                titled("Executions", new JScrollPane(blotterTable)));
        // The ladder is the primary display; the blotter is a running log that
        // rarely needs more than a few visible rows.
        left.setResizeWeight(0.62);
        left.setDividerLocation(250);
        left.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 6));

        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 10));
        right.add(titled("Last trade price", chart), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.62);
        // Without an explicit divider location the split honours the chart's
        // preferred width on first layout and the ladder ends up cramped.
        split.setDividerLocation(560);
        split.setBorder(null);
        return split;
    }

    private JPanel titled(String title, Component body) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xD0D0D0)),
                BorderFactory.createEmptyBorder(6, 12, 8, 12)));
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 12f));
        bar.setPreferredSize(new Dimension(10, 32));
        bar.add(status, BorderLayout.WEST);
        return bar;
    }

    /** Bids blue on the left, asks red on the right. */
    private class LadderRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focused, int row, int column) {
            Component c = super.getTableCellRendererComponent(
                    table, value, selected, focused, row, column);
            setHorizontalAlignment(depthModel.isBidColumn(column) ? RIGHT : LEFT);
            if (!selected) {
                if (depthModel.isPriceColumn(column)) {
                    c.setForeground(depthModel.isBidColumn(column)
                            ? new Color(0x185FA5) : new Color(0xA32D2D));
                } else {
                    c.setForeground(Color.DARK_GRAY);
                }
            }
            return c;
        }
    }

    // ---- actions ----

    private String currentSymbol() {
        return (String) symbolBox.getSelectedItem();
    }

    private void sendOrder(String side) {
        if (connection == null) {
            showStatus("not connected", true);
            return;
        }
        long priceCents;
        long qty;
        try {
            priceCents = Money.toCents(priceField.getText());
        } catch (RuntimeException e) {
            showStatus("price must look like 102.00", true);
            return;
        }
        try {
            qty = Long.parseLong(qtyField.getText().trim());
        } catch (NumberFormatException e) {
            showStatus("quantity must be a whole number", true);
            return;
        }
        if (priceCents <= 0 || qty <= 0) {
            showStatus("price and quantity must be positive", true);
            return;
        }
        try {
            connection.order(currentSymbol(), side, priceCents, qty);
            showStatus("sent " + side + " " + qty + " @ " + Money.format(priceCents), false);
        } catch (IOException e) {
            showStatus("connection lost", true);
        }
    }

    private void showStatus(String message, boolean error) {
        status.setForeground(error ? new Color(0xA32D2D) : Color.DARK_GRAY);
        status.setText(message);
    }

    // ---- inbound, all marshalled onto the EDT ----

    public ServerConnection.Handler handler() {
        return new ServerConnection.Handler() {
            @Override
            public void onBook(BookSnapshot snapshot) {
                SwingUtilities.invokeLater(() -> applyBook(snapshot));
            }

            @Override
            public void onTrade(String symbol, long priceCents, long qty) {
                SwingUtilities.invokeLater(() -> applyTrade(symbol, priceCents, qty));
            }

            @Override
            public void onFill(long orderId, long qty, long priceCents, String orderStatus) {
                SwingUtilities.invokeLater(() -> {
                    myOrderIds.add(orderId);
                    showStatus("filled " + qty + " @ " + Money.format(priceCents)
                            + " (" + orderStatus.toLowerCase() + ")", false);
                });
            }

            @Override
            public void onError(String reason) {
                SwingUtilities.invokeLater(() -> showStatus(reason, true));
            }

            @Override
            public void onOther(String rawLine) {
                if (rawLine.startsWith("ACCEPTED")) {
                    String[] f = rawLine.split("\\|");
                    SwingUtilities.invokeLater(() -> myOrderIds.add(Long.parseLong(f[1])));
                }
            }

            @Override
            public void onDisconnected() {
                SwingUtilities.invokeLater(() -> showStatus("disconnected from server", true));
            }
        };
    }

    private void applyBook(BookSnapshot snapshot) {
        if (!snapshot.symbol().equals(currentSymbol())) {
            return;
        }
        depthModel.update(snapshot);
        Long spread = snapshot.spreadCents();
        spreadLabel.setText(spread == null ? "spread --" : "spread " + Money.format(spread));
    }

    private void applyTrade(String symbol, long priceCents, long qty) {
        if (!symbol.equals(currentSymbol())) {
            return;
        }
        blotterModel.add(symbol, priceCents, qty, false);
        chart.addPrice(symbol, priceCents);
    }

    // exposed for the headless model tests
    DepthTableModel depthModel() { return depthModel; }
    BlotterTableModel blotterModel() { return blotterModel; }
    PriceChartPanel chart() { return chart; }
}
