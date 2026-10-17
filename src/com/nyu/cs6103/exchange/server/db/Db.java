package com.nyu.cs6103.exchange.server.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the JDBC URL and creates the schema on first use.
 *
 * H2 runs in embedded mode so the grader needs no database installation --
 * the jar is the server. Everything below is plain JDBC, so pointing this at
 * PostgreSQL would mean changing only the URL and the driver.
 *
 * The schema is a text block rather than a file loaded from the classpath.
 * Reading it as a resource works when the build copies non-Java files into the
 * output directory, but IDEs differ on whether they do that, which turns a
 * one-line DDL problem into an IDE configuration problem. Keeping the DDL in
 * the source guarantees it is always present wherever the class is.
 */
public class Db {

    /**
     * Schema definition. Every statement is IF NOT EXISTS so that opening an
     * existing database is a no-op rather than an error.
     */
    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS users (
                id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                username      VARCHAR(32)   NOT NULL UNIQUE,
                pwd_hash      VARCHAR(128)  NOT NULL,
                salt          VARCHAR(64)   NOT NULL,
                cash_cents    BIGINT        NOT NULL,
                created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT chk_cash_nonneg CHECK (cash_cents >= 0)
            );

            CREATE TABLE IF NOT EXISTS holdings (
                user_id   BIGINT       NOT NULL,
                symbol    VARCHAR(8)   NOT NULL,
                quantity  BIGINT       NOT NULL,
                PRIMARY KEY (user_id, symbol),
                FOREIGN KEY (user_id) REFERENCES users(id),
                CONSTRAINT chk_qty_nonneg CHECK (quantity >= 0)
            );

            CREATE TABLE IF NOT EXISTS orders (
                id          BIGINT       PRIMARY KEY,
                user_id     BIGINT       NOT NULL,
                symbol      VARCHAR(8)   NOT NULL,
                side        VARCHAR(4)   NOT NULL,
                price_cents BIGINT       NOT NULL,
                quantity    BIGINT       NOT NULL,
                remaining   BIGINT       NOT NULL,
                status      VARCHAR(16)  NOT NULL,
                created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id),
                CONSTRAINT chk_remaining CHECK (remaining >= 0 AND remaining <= quantity)
            );

            CREATE TABLE IF NOT EXISTS trades (
                id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                symbol        VARCHAR(8)  NOT NULL,
                price_cents   BIGINT      NOT NULL,
                quantity      BIGINT      NOT NULL,
                buy_order_id  BIGINT      NOT NULL,
                sell_order_id BIGINT      NOT NULL,
                buy_user_id   BIGINT      NOT NULL,
                sell_user_id  BIGINT      NOT NULL,
                executed_at   TIMESTAMP   NOT NULL,
                FOREIGN KEY (buy_order_id)  REFERENCES orders(id),
                FOREIGN KEY (sell_order_id) REFERENCES orders(id)
            );

            CREATE INDEX IF NOT EXISTS idx_orders_user   ON orders(user_id);
            CREATE INDEX IF NOT EXISTS idx_trades_symbol ON trades(symbol, executed_at);
            """;

    private final String url;

    public Db(String url) {
        this.url = url;
    }

    /** File-backed database that survives restarts. */
    public static Db file(String path) {
        return new Db("jdbc:h2:" + path + ";DB_CLOSE_DELAY=-1");
    }

    /** In-memory database, used by the tests. */
    public static Db inMemory(String name) {
        return new Db("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    public void initSchema() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute(SCHEMA);
        }
    }
}
