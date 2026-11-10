package com.superbistro.setup;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.superbistro.backup.PgTools;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * First-run setup, served with the JDK's built-in HTTP server before Spring starts.
 * It connects to PostgreSQL as an admin (e.g. the "postgres" user created by the PostgreSQL 15
 * installer), creates a dedicated "superbistro" login and database, saves the connection to
 * DATA_DIR/superbistro.properties, and then starts the real app on the same port.
 */
public final class SetupServer {
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,40}");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final int port;
    private final Path dataDir;
    private final boolean desktop;
    private final Runnable startApp;
    private HttpServer server;

    SetupServer(int port, Path dataDir, boolean desktop, Runnable startApp) {
        this.port = port;
        this.dataDir = dataDir;
        this.desktop = desktop;
        this.startApp = startApp;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handle);
        server.start();
        String url = "http://localhost:" + port;
        System.out.println();
        System.out.println("  Super Bistro first-time setup: open " + url);
        System.out.println();
        if (desktop) Browser.open(url);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if ("GET".equals(method) && (path.equals("/") || path.equals("/index.html") || path.equals("/setup"))) {
                try (InputStream in = getClass().getResourceAsStream("/setup/index.html")) {
                    send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
                }
            } else if ("GET".equals(method) && path.equals("/setup/status")) {
                Path pgDump = PgTools.findPgDump(null);
                json(ex, 200, Map.of("setup", true, "dataDir", dataDir.toString(),
                    "pgDump", pgDump == null ? "" : pgDump.toString(), "windows", isWindows(),
                    "builtin", builtinAvailable(), "postgresRunning", portOpen("localhost", 5432)));
            } else if ("POST".equals(method) && path.equals("/setup/install")) {
                install(ex, form(ex));
            } else if (path.startsWith("/api/") || path.startsWith("/actuator")) {
                // the main app is not up yet; the setup page polls this until it is
                json(ex, 503, Map.of("error", "Super Bistro is starting"));
            } else {
                ex.getResponseHeaders().add("Location", "/");
                send(ex, 302, "text/plain", new byte[0]);
            }
        } catch (Exception e) {
            json(ex, 500, Map.of("error", "Setup error: " + e.getMessage()));
        }
    }

    private void install(HttpExchange ex, Map<String, String> f) throws IOException {
        if ("builtin".equals(f.get("mode"))) {
            installBuiltin(ex, f);
            return;
        }
        String host = f.getOrDefault("host", "localhost").trim();
        String portStr = f.getOrDefault("port", "5432").trim();
        String adminUser = f.getOrDefault("adminUser", "postgres").trim();
        String adminPassword = f.getOrDefault("adminPassword", "");
        String dbName = f.getOrDefault("dbName", "superbistro").trim().toLowerCase(Locale.ROOT);

        if (host.isEmpty() || !portStr.matches("\\d{2,5}")) { json(ex, 400, err("Enter the PostgreSQL server and port (usually localhost and 5432).")); return; }
        if (!SAFE_NAME.matcher(dbName).matches()) { json(ex, 400, err("The database name can use lowercase letters, digits and _ only.")); return; }
        String problem = ownerProblem(f);
        if (problem != null) { json(ex, 400, err(problem)); return; }

        String appPassword = randomPassword();
        String base = "jdbc:postgresql://" + host + ":" + portStr + "/";
        try (Connection c = DriverManager.getConnection(base + "postgres", adminUser, adminPassword)) {
            int version;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SHOW server_version_num")) {
                rs.next();
                version = Integer.parseInt(rs.getString(1));
            }
            if (version < 130000) {
                json(ex, 400, err("This PostgreSQL is too old. Install PostgreSQL 15 (13 or newer works)."));
                return;
            }
            try (Statement s = c.createStatement()) {
                boolean roleExists;
                try (ResultSet rs = s.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + dbName + "'")) {
                    roleExists = rs.next();
                }
                // appPassword is [A-Za-z0-9] only, dbName matches SAFE_NAME: safe to inline.
                s.execute((roleExists ? "ALTER" : "CREATE") + " ROLE " + dbName + " LOGIN PASSWORD '" + appPassword + "'");
                boolean dbExists;
                try (ResultSet rs = s.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'")) {
                    dbExists = rs.next();
                }
                if (dbExists) {
                    s.execute("ALTER DATABASE " + dbName + " OWNER TO " + dbName);
                } else {
                    s.execute("CREATE DATABASE " + dbName + " OWNER " + dbName + " ENCODING 'UTF8' TEMPLATE template0");
                }
            }
        } catch (SQLException e) {
            json(ex, 400, err(friendly(e, host, portStr, adminUser)));
            return;
        }
        // Make sure the new login really works before saving anything.
        try (Connection c = DriverManager.getConnection(base + dbName, dbName, appPassword)) {
            c.isValid(5);
        } catch (SQLException e) {
            json(ex, 400, err("The database was created but Super Bistro couldn't sign in to it: " + e.getMessage()));
            return;
        }

        Properties p = new Properties();
        p.setProperty("DB_URL", base + dbName);
        p.setProperty("DB_USER", dbName);
        p.setProperty("DB_PASSWORD", appPassword);
        p.setProperty("ALLOW_SIGNUP", "on".equals(f.get("allowSignup")) ? "true" : "false");
        Path pgDump = PgTools.findPgDump(f.get("pgDump"));
        if (pgDump != null) p.setProperty("PG_DUMP", pgDump.toString());
        Launcher.write(dataDir.resolve(Launcher.CONFIG_FILE), p, "Super Bistro settings, written by first-time setup. Keep this file private.");

        finish(ex, f, pgDump == null ? "" : pgDump.toString());
    }

    /**
     * Built-in database: Super Bistro runs its own private PostgreSQL (data in DATA_DIR/pgdata),
     * so nothing else has to be installed on the PC.
     */
    private void installBuiltin(HttpExchange ex, Map<String, String> f) throws IOException {
        if (!builtinAvailable()) {
            json(ex, 400, err("This copy of Super Bistro has no built-in database for this computer. Choose \"My own PostgreSQL server\"."));
            return;
        }
        String problem = ownerProblem(f);
        if (problem != null) { json(ex, 400, err(problem)); return; }

        Properties p = new Properties();
        p.setProperty("EMBEDDED_DB", "true");
        p.setProperty("ALLOW_SIGNUP", "on".equals(f.get("allowSignup")) ? "true" : "false");
        Launcher.write(dataDir.resolve(Launcher.CONFIG_FILE), p, "Super Bistro settings, written by first-time setup. Keep this file private.");
        finish(ex, f, "");
    }

    /** Checks the restaurant and owner fields; returns a message, or null when they're fine. */
    private static String ownerProblem(Map<String, String> f) {
        if (f.getOrDefault("restaurantName", "").isBlank() || f.getOrDefault("ownerName", "").isBlank()) return "Enter the restaurant name and your name.";
        if (!EMAIL.matcher(f.getOrDefault("email", "").trim()).matches()) return "Enter a valid email for the owner login.";
        if (f.getOrDefault("password", "").length() < 8) return "The owner password must be at least 8 characters.";
        return null;
    }

    /** Saves the owner for the first start, turns on autostart, answers the page and starts the app. */
    private void finish(HttpExchange ex, Map<String, String> f, String pgDump) throws IOException {
        if (isWindows() && "on".equals(f.get("autostart"))) {
            registerWindowsAutostart();
        }
        BootstrapOwner.set(new BootstrapOwner(f.get("restaurantName").trim(), f.get("ownerName").trim(),
            f.get("email").trim().toLowerCase(Locale.ROOT), new BCryptPasswordEncoder().encode(f.get("password"))));
        json(ex, 200, Map.of("ok", true, "pgDump", pgDump));

        // Hand the port over to the real app.
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            server.stop(0);
            ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdown();
            startApp.run();
        }, "superbistro-start");
        t.setDaemon(false);
        t.start();
    }

    /** True when the PostgreSQL program for this OS is packed inside the app. */
    public static boolean builtinAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String name = os.contains("win") ? "windows" : os.contains("mac") ? "darwin" : "linux";
        String a = arch.equals("aarch64") || arch.equals("arm64") ? "arm_64" : "x86_64";
        return SetupServer.class.getResource("/postgres-" + name + "-" + a + ".txz") != null;
    }

    private static boolean portOpen(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Starts Super Bistro when this Windows user signs in (no admin rights needed). */
    static void registerWindowsAutostart() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe == null) return; // not running from the installed app
        try {
            new ProcessBuilder("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "SuperBistro", "/t", "REG_SZ", "/d", "\"" + exe + "\"", "/f").inheritIO().start().waitFor();
        } catch (Exception e) {
            System.out.println("Could not turn on start-with-Windows: " + e.getMessage());
        }
    }

    private static String friendly(SQLException e, String host, String port, String user) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.startsWith("08")) {
            return "Can't reach PostgreSQL at " + host + ":" + port + ". Is PostgreSQL 15 installed and running? (" + e.getMessage() + ")";
        }
        if (state.equals("28P01") || state.equals("28000")) {
            return "PostgreSQL refused the password for user \"" + user + "\". Use the password you chose while installing PostgreSQL.";
        }
        if (state.equals("42501")) {
            return "User \"" + user + "\" isn't allowed to create databases. Use the \"postgres\" admin user.";
        }
        return "PostgreSQL error: " + e.getMessage();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 28; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private static Map<String, Object> err(String msg) {
        return Map.of("error", msg);
    }

    private static Map<String, String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = java.net.URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static void json(HttpExchange ex, int status, Map<String, ?> data) throws IOException {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, ?> e : data.entrySet()) {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Boolean || v instanceof Number) sb.append(v);
            else sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")).append('"');
        }
        sb.append('}');
        send(ex, status, "application/json; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            ex.getResponseBody().write(body);
        }
        ex.close();
    }
}
