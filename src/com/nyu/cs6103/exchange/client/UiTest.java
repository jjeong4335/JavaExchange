package com.nyu.cs6103.exchange.client;

import com.nyu.cs6103.exchange.common.BookSnapshot;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Exercises the view models and the chart painting without a display.
 *
 * The frames themselves need a windowing system, so they are not constructed
 * here; what is tested is everything the frames delegate to. The chart is
 * painted into an offscreen image, which catches the arithmetic faults that
 * matter -- division by zero on a flat series, index errors on a short one --
 * without needing a screen.
 */
public class UiTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        testDepthLadder();
        testBlotterCap();
        testChartEdgeCases();
        testChartRendersSeries();

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testDepthLadder() {
        DepthTableModel model = new DepthTableModel(5);
        check("empty ladder still has 5 rows", model.getRowCount() == 5);
        check("empty cells render blank", "".equals(model.getValueAt(0, 0)));

        model.update(new BookSnapshot("AAPL",
                List.of(new BookSnapshot.Level(10200, 20, 1),
                        new BookSnapshot.Level(10100, 40, 2)),
                List.of(new BookSnapshot.Level(10300, 50, 1))));

        check("bid qty in column 0", "20".equals(model.getValueAt(0, 0)));
        check("bid price formatted as dollars", "102.00".equals(model.getValueAt(0, 1)));
        check("ask price in column 2", "103.00".equals(model.getValueAt(0, 2)));
        check("ask qty in column 3", "50".equals(model.getValueAt(0, 3)));
        check("second bid level", "101.00".equals(model.getValueAt(1, 1)));
        check("missing ask level is blank", "".equals(model.getValueAt(1, 2)));
        check("rows beyond depth are blank", "".equals(model.getValueAt(4, 0)));
        check("spread computed", model.snapshot().spreadCents() == 100);
    }

    private static void testBlotterCap() {
        BlotterTableModel model = new BlotterTableModel();
        for (int i = 0; i < 600; i++) {
            model.add("AAPL", 10000 + i, 10, i % 2 == 0);
        }
        check("blotter caps at 500 rows", model.getRowCount() == 500);
        // The last print added was i=599, i.e. 10599 cents.
        check("newest print is first", "105.99".equals(model.getValueAt(0, 2)));
        check("odd-indexed print is not mine", "".equals(model.getValueAt(0, 4)));
        check("even-indexed print is marked mine", "you".equals(model.getValueAt(1, 4)));
        check("oldest surviving print is i=100", "101.00".equals(model.getValueAt(499, 2)));
    }

    private static void testChartEdgeCases() {
        PriceChartPanel chart = new PriceChartPanel();
        chart.setSize(360, 220);

        paint(chart);
        check("paints with no data", true);

        chart.addPrice("AAPL", 10200);
        BufferedImage one = paint(chart);
        check("paints with a single point", chart.pointCount() == 1);
        check("a single print is actually plotted, not a placeholder",
                !looksLikePlaceholder(one));

        // A flat series would divide by zero if the range were not padded.
        for (int i = 0; i < 5; i++) {
            chart.addPrice("AAPL", 10200);
        }
        paint(chart);
        check("paints a completely flat series", chart.pointCount() == 6);

        for (int i = 0; i < 400; i++) {
            chart.addPrice("AAPL", 10000 + i);
        }
        check("chart caps its history at 240 points", chart.pointCount() == 240);
        paint(chart);
        check("paints a full history", true);
    }

    private static void testChartRendersSeries() throws Exception {
        PriceChartPanel chart = new PriceChartPanel();
        chart.setSize(400, 240);
        long[] series = { 10200, 10250, 10180, 10310, 10290, 10400, 10350, 10480 };
        for (long p : series) {
            chart.addPrice("AAPL", p);
        }

        BufferedImage img = paint(chart);
        long nonWhite = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                    nonWhite++;
                }
            }
        }
        check("chart actually draws something", nonWhite > 500);

        File out = new File("chart-preview.png");
        ImageIO.write(img, "png", out);
        System.out.println("  [chart] wrote " + out.getName()
                + " (" + nonWhite + " painted pixels)");
    }

    /**
     * The placeholder is grey text on white; a plotted series adds the green
     * line and marker. Looking for green pixels distinguishes the two.
     */
    private static boolean looksLikePlaceholder(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (g > r + 20 && g > b + 20) {
                    return false;
                }
            }
        }
        return true;
    }

    private static BufferedImage paint(PriceChartPanel chart) {
        BufferedImage img = new BufferedImage(
                chart.getWidth(), chart.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        chart.paint(g);
        g.dispose();
        return img;
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + label);
        } else {
            failed++;
            System.out.println("  FAIL  " + label);
        }
    }
}
