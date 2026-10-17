package com.nyu.cs6103.exchange.server.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Account creation and authentication.
 *
 * Passwords are never stored, and never compared, in plaintext. Each account
 * gets its own random salt, stored beside the hash: two users who happen to
 * choose the same password still produce different hashes, so an attacker
 * cannot spot reused passwords or precompute a single rainbow table against
 * the whole table.
 *
 * The pepper is a second secret mixed into every hash but held OUTSIDE the
 * database -- in configuration, here an environment variable. If the database
 * file alone is stolen, the attacker still lacks the pepper and cannot mount
 * an offline dictionary attack against the stolen hashes.
 *
 * A production system would use bcrypt, scrypt, or Argon2, which are
 * deliberately slow. SHA-256 is used here to stay within the standard library;
 * the salt-and-pepper structure is the part being demonstrated.
 */
public class UserDao {

    private static final String PEPPER =
            System.getenv().getOrDefault("EXCHANGE_PEPPER", "cs6103-default-pepper");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final Db db;

    public UserDao(Db db) {
        this.db = db;
    }

    public record User(long id, String username, long cashCents) { }

    /** Creates an account. Throws if the username is taken. */
    public User register(String username, char[] password, long startingCashCents)
            throws SQLException {
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = HEX.formatHex(saltBytes);
        String hash = hash(password, salt);

        String sql = "INSERT INTO users (username, pwd_hash, salt, cash_cents) VALUES (?,?,?,?)";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setLong(4, startingCashCents);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new User(keys.getLong(1), username, startingCashCents);
            }
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    /** Returns the user when the credentials verify, otherwise empty. */
    public Optional<User> authenticate(String username, char[] password) throws SQLException {
        String sql = "SELECT id, pwd_hash, salt, cash_cents FROM users WHERE username = ?";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String stored = rs.getString("pwd_hash");
                String candidate = hash(password, rs.getString("salt"));
                if (!constantTimeEquals(stored, candidate)) {
                    return Optional.empty();
                }
                return Optional.of(new User(
                        rs.getLong("id"), username, rs.getLong("cash_cents")));
            }
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static String hash(char[] password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            md.update(PEPPER.getBytes(StandardCharsets.UTF_8));
            md.update(new String(password).getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Compares without short-circuiting on the first differing character, so
     * the time taken does not leak how much of the hash matched.
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
