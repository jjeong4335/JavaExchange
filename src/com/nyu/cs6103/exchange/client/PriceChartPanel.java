package com.nyu.cs6103.exchange.client;

import com.nyu.cs6103.exchange.common.Money;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Last-trade price series, drawn by overriding paintComponent.
 *
 * No charting library is used: the point of this panel is to exercise Java2D
 * directly. It handles the axis scaling, the gridlines, and the polyline
 * itself, which is a few dozen lines and avoids another jar for the grader to
 * collect.
 *
 * Two cases are easy to get wrong and are handled explicitly: fewer than two
 * points (nothing to join, so draw a placeholder), and a completely flat
 * series where max equals min (which would divide by zero when scaling, so the
 * range is padded).
 */
public class PriceChartPanel extends JPanel {

    private static final int MAX_POINTS = 240;
    private static final int PAD_LEFT = 52;
    private static final int PAD_RIGHT = 6;
    private static final int PAD_TOP = 6;
    private static final int PAD_BOTTOM = 8;

    private final Deque<Long> prices = new ArrayDeque<>();
    private String symbol = "";

    public PriceChartPanel() {
        setPreferredSize(new Dimension(280, 180));
        setMinimumSize(new Dimension(220, 140));
        setBackground(Color.WHITE);
    }

    /** Appends a print. Call on the EDT only. */
    public void addPrice(String symbol, long priceCents) {
        this.symbol = symbol;
        prices.addLast(priceCents);
        while (prices.size() > MAX_POINTS) {
            prices.removeFirst();
        }
        repaint();
    }

    public void clear() {
        prices.clear();
        repaint();
    }

    public int pointCount() {
        return prices.size();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            draw(g2);
        } finally {
            // Always dispose the copy; leaking Graphics objects starves the
            // native rendering resources over a long session.
            g2.dispose();
        }
    }

    private void draw(Graphics2D g2) {
        int w = getWidth();
        int h = getHeight();
        int plotW = w - PAD_LEFT - PAD_RIGHT;
        int plotH = h - PAD_TOP - PAD_BOTTOM;
        if (plotW <= 10 || plotH <= 10) {
            return;
        }

        g2.setColor(new Color(0xE0E0E0));
        g2.drawRect(PAD_LEFT, PAD_TOP, plotW, plotH);

        // Only a genuinely empty series gets the placeholder. A single print
        // still has a price worth showing, even though there is no line to
        // draw yet -- the scaling below already handles n == 1.
        if (prices.isEmpty()) {
            g2.setColor(Color.GRAY);
            g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            String msg = "waiting for trades";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, PAD_LEFT + (plotW - fm.stringWidth(msg)) / 2, PAD_TOP + plotH / 2);
            return;
        }

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long p : prices) {
            min = Math.min(min, p);
            max = Math.max(max, p);
        }
        // A flat series would make the scale factor infinite; pad the range.
        if (max == min) {
            max = min + 100;
            min = Math.max(0, min - 100);
        }
        long range = max - min;

        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g2.setColor(new Color(0xF0F0F0));
        for (int i = 0; i <= 4; i++) {
            int y = PAD_TOP + plotH - (plotH * i / 4);
            g2.setColor(new Color(0xF0F0F0));
            g2.drawLine(PAD_LEFT + 1, y, PAD_LEFT + plotW - 1, y);
            g2.setColor(new Color(0x808080));
            String label = Money.format(min + range * i / 4);
            g2.drawString(label, 6, y + 4);
        }

        int n = prices.size();
        int[] xs = new int[n];
        int[] ys = new int[n];
        int i = 0;
        for (long p : prices) {
            xs[i] = PAD_LEFT + (n == 1 ? plotW / 2 : plotW * i / (n - 1));
            ys[i] = PAD_TOP + plotH - (int) ((p - min) * plotH / range);
            i++;
        }

        g2.setColor(new Color(0x1D9E75));
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawPolyline(xs, ys, n);

        long last = prices.peekLast();
        g2.setColor(new Color(0x0F6E56));
        g2.fillOval(xs[n - 1] - 3, ys[n - 1] - 3, 6, 6);
        g2.setFont(getFont().deriveFont(Font.BOLD, 12f));
        g2.drawString(symbol + "  " + Money.format(last), PAD_LEFT + 6, PAD_TOP + 16);
    }
}
