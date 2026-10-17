package com.nyu.cs6103.exchange.bot;

import com.nyu.cs6103.exchange.common.Money;
import com.nyu.cs6103.exchange.server.ExchangeServer;
import com.nyu.cs6103.exchange.server.db.Db;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a live market of bots against a real server and checks the two things
 * that must hold no matter how the threads interleave.
 *
 * Conservation is the strongest statement available about the system: an
 * exchange moves cash and shares between accounts but never creates or
 * destroys either. If any interleaving double-filled an order, dropped a
 * settlement, or committed half a transaction, these totals would drift.
 */
public class SimBotTest {

    private static final String SYMBOL = "AAPL";
    private static final int BOTS = 4;
    private static final long RUN_MILLIS = 12_000;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        try (ExchangeServer server =
                     new ExchangeServer(0, Db.inMemory("bots-" + System.nanoTime()))) {
            int port = server.start();

            List<SimBot> bots = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < BOTS; i++) {
                SimBot bot = new SimBot("localhost", port, "bot" + i, SYMBOL, 120, 420, 7_000 + i);
                Thread t = new Thread(bot, "bot" + i);
                t.setDaemon(true);
                bots.add(bot);
                threads.add(t);
                t.start();
                Thread.sleep(250);
            }

            // Every account is seeded identically at registration.
            long expectedShares = (long) BOTS * 1_000;
            long expectedCash = (long) BOTS * 1_000_000_00L;

            System.out.printf("  [bots] running %d bots for %d seconds...%n",
                    BOTS, RUN_MILLIS / 1000);
            Thread.sleep(RUN_MILLIS);

            for (SimBot b : bots) {
                b.stop();
            }
            Thread.sleep(600);
            server.awaitSettlement();

            int trades = server.trades().tradeCount();
            long actualShares = server.trades().totalHoldings(SYMBOL);
            long actualCash = server.trades().totalCash();

            System.out.printf("  [bots] %d trades settled%n", trades);
            System.out.printf("  [bots] shares %d (expected %d)%n", actualShares, expectedShares);
            System.out.printf("  [bots] cash %s (expected %s)%n",
                    Money.format(actualCash), Money.format(expectedCash));

            check("bots actually traded with each other", trades > 5);
            check("no shares were created or destroyed", actualShares == expectedShares);
            check("no cash was created or destroyed", actualCash == expectedCash);

            long moved = 0;
            for (SimBot b : bots) {
                moved += b.tradesSeen();
            }
            check("every bot participated", moved > 0);
            check("bots stayed within their means",
                    server.trades().cashOf(1) >= 0);
        }

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
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
