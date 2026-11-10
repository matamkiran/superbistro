package com.superbistro.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import com.superbistro.SuperBistroApplication;

import org.springframework.boot.SpringApplication;

/**
 * Starts Super Bistro.
 *
 * <ol>
 *   <li>If a database is configured (environment DB_URL, EMBEDDED_DB=true, or the saved
 *       DATA_DIR/superbistro.properties from an earlier setup), the app starts normally.</li>
 *   <li>Otherwise this is a fresh install: a small setup page is served on the same port. Once
 *       the owner has entered the PostgreSQL details there, the settings are saved and the app
 *       starts in the same process.</li>
 * </ol>
 */
public final class Launcher {
    public static final String CONFIG_FILE = "superbistro.properties";

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = dataDir();
        int port = port();
        boolean desktop = flag("OPEN_BROWSER", "superbistro.open-browser");

        // Shortcut clicked while Super Bistro is already running (e.g. started at Windows login):
        // just open it in the browser instead of failing on a busy port.
        if (desktop && alreadyRunning(port)) {
            Browser.open("http://localhost:" + port);
            return;
        }

        Path config = dataDir.resolve(CONFIG_FILE);
        boolean configured = env("DB_URL", "spring.datasource.url") != null
            || flag("EMBEDDED_DB", "superbistro.embedded-db")
            || Files.exists(config);

        if (configured) {
            startApp(args, dataDir);
        } else {
            Files.createDirectories(dataDir);
            new SetupServer(port, dataDir, desktop, () -> startApp(args, dataDir)).start();
        }
    }

    /** Starts the Spring application with any settings saved by setup as defaults. */
    static void startApp(String[] args, Path dataDir) {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("superbistro.data-dir", dataDir.toString());
        Path config = dataDir.resolve(CONFIG_FILE);
        if (Files.exists(config)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(config)) {
                p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Can't read " + config + ": " + e.getMessage(), e);
            }
            p.forEach((k, v) -> defaults.put(String.valueOf(k), String.valueOf(v)));
        }
        SpringApplication app = new SpringApplication(SuperBistroApplication.class);
        app.setDefaultProperties(defaults); // environment variables still win
        app.run(args);
    }

    /** Where settings, backups and logs live. Windows: C:\ProgramData\SuperBistro. */
    public static Path dataDir() {
        String v = env("DATA_DIR", "superbistro.data-dir");
        if (v != null && !v.isBlank()) {
            return Path.of(v).toAbsolutePath();
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String programData = System.getenv("ProgramData");
        if (windows && programData != null) {
            return Path.of(programData, "SuperBistro");
        }
        return Path.of(System.getProperty("user.home"), "SuperBistro");
    }

    static int port() {
        String v = env("PORT", "server.port");
        try {
            return v == null ? 8080 : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 8080;
        }
    }

    static String env(String envName, String propName) {
        String v = System.getProperty(propName);
        if (v == null) v = System.getenv(envName);
        return v;
    }

    static boolean flag(String envName, String propName) {
        return "true".equalsIgnoreCase(env(envName, propName));
    }

    private static boolean alreadyRunning(int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://localhost:" + port + "/actuator/health").toURL().openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(800);
            int code = c.getResponseCode();
            try (InputStream in = c.getInputStream()) {
                return code == 200 && new String(in.readAllBytes()).contains("UP");
            }
        } catch (IOException e) {
            return false;
        }
    }

    static void write(Path file, Properties p, String comment) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, comment);
        }
    }
}
