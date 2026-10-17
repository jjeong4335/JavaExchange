package com.nyu.cs6103.exchange.client;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;

/**
 * Connection and credentials screen.
 *
 * Network calls run on a worker thread, never on the event dispatch thread. A
 * socket connect to an unreachable host can block for the full TCP timeout;
 * doing that on the EDT would freeze the window with no way to cancel, which
 * is the single most common Swing mistake.
 */
public class LoginFrame extends JFrame {

    private final JTextField hostField = new JTextField("localhost", 12);
    private final JTextField portField = new JTextField("5555", 6);
    private final JTextField userField = new JTextField(12);
    private final JPasswordField passField = new JPasswordField(12);
    private final JButton loginButton = new JButton("Log in");
    private final JButton registerButton = new JButton("Register");
    private final JLabel status = new JLabel(" ");

    public LoginFrame() {
        super("JavaExchange - Connect");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(buildForm(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        loginButton.addActionListener(e -> submit(false));
        registerButton.addActionListener(e -> submit(true));
        getRootPane().setDefaultButton(loginButton);

        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(null);
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        JLabel title = new JLabel("JavaExchange");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        form.add(title, c);
        c.gridwidth = 1;

        addRow(form, c, 1, "Host", hostField);
        addRow(form, c, 2, "Port", portField);
        addRow(form, c, 3, "Username", userField);
        addRow(form, c, 4, "Password", passField);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(registerButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(loginButton);
        c.gridx = 1; c.gridy = 5;
        form.add(buttons, c);
        return form;
    }

    private void addRow(JPanel form, GridBagConstraints c, int row, String label, JTextField field) {
        c.gridx = 0; c.gridy = row;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        form.add(field, c);
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(4, 20, 10, 20));
        status.setPreferredSize(new Dimension(240, 18));
        bar.add(status, BorderLayout.CENTER);
        return bar;
    }

    private void submit(boolean registerFirst) {
        String host = hostField.getText().trim();
        String username = userField.getText().trim();
        String password = new String(passField.getPassword());

        if (username.isEmpty() || password.isEmpty()) {
            showError("Username and password are required");
            return;
        }
        if (username.contains("|")) {
            showError("Username may not contain '|'");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            showError("Port must be a number");
            return;
        }

        setBusy(true);
        status.setForeground(Color.DARK_GRAY);
        status.setText(registerFirst ? "Registering..." : "Connecting...");

        // Off the EDT: connect() and the login round trip both block.
        new Thread(() -> connect(host, port, username, password, registerFirst), "login").start();
    }

    private void connect(String host, int port, String username, String password,
                         boolean registerFirst) {
        ServerConnection connection = null;
        try {
            TradingFrame frame = new TradingFrame(username);
            connection = new ServerConnection(host, port, frame.handler());

            if (registerFirst) {
                connection.register(username, password);
                Thread.sleep(250);
            }
            boolean ok = connection.login(username, password, 4000);
            if (!ok) {
                String reason = connection.loginError() != null
                        ? connection.loginError() : "no response from server";
                connection.close();
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    showError(reason);
                });
                return;
            }

            final ServerConnection ready = connection;
            SwingUtilities.invokeLater(() -> {
                frame.attach(ready);
                frame.setVisible(true);
                dispose();
            });

        } catch (IOException e) {
            closeQuietly(connection);
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                showError("Cannot reach " + host + ":" + port);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(connection);
        }
    }

    private static void closeQuietly(ServerConnection c) {
        if (c != null) {
            c.close();
        }
    }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        registerButton.setEnabled(!busy);
    }

    private void showError(String message) {
        status.setForeground(new Color(0xA32D2D));
        status.setText(message);
        setBusy(false);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }
}
