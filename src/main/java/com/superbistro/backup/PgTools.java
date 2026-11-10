package com.superbistro.backup;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Finds PostgreSQL's pg_dump and reads connection details out of a JDBC URL. */
public final class PgTools {
    private PgTools() {
    }

    public record Target(String host, int port, String database, String sslmode) {
    }

    /** jdbc:postgresql://host[:port]/db[?sslmode=require&...] */
    public static Target parse(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("Not a PostgreSQL JDBC URL: " + jdbcUrl);
        }
        String rest = jdbcUrl.substring("jdbc:postgresql://".length());
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        int slash = rest.indexOf('/');
        String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
        String db = slash >= 0 ? rest.substring(slash + 1) : "postgres";
        String host = hostPort;
        int port = 5432;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && !hostPort.endsWith("]")) {
            host = hostPort.substring(0, colon);
            port = Integer.parseInt(hostPort.substring(colon + 1));
        }
        String sslmode = null;
        for (String kv : query.split("&")) {
            if (kv.startsWith("sslmode=")) {
                sslmode = kv.substring("sslmode=".length());
            }
        }
        return new Target(host.isEmpty() ? "localhost" : host, port, db.isEmpty() ? "postgres" : db, sslmode);
    }

    /**
     * Looks for pg_dump: an explicit setting first, then the usual install folders
     * (newest version first), then the PATH.
     */
    public static Path findPgDump(String configured) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? "pg_dump.exe" : "pg_dump";
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            candidates.add(Files.isDirectory(p) ? p.resolve(exe) : p);
        }
        List<Path> roots = windows
            ? List.of(Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "PostgreSQL"))
            : List.of(Path.of("/Library/PostgreSQL"), Path.of("/usr/lib/postgresql"), Path.of("/opt/homebrew/opt"),
                      Path.of("/usr/local/opt"), Path.of("/Applications/Postgres.app/Contents/Versions"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.list(root)) {
                s.sorted(Comparator.comparing(PgTools::versionOf).reversed())
                    .forEach(dir -> candidates.add(dir.resolve("bin").resolve(exe)));
            } catch (Exception ignored) {
                // unreadable folder: skip
            }
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isBlank()) candidates.add(Path.of(dir, exe));
        }
        for (Path c : candidates) {
            if (Files.isRegularFile(c) && Files.isExecutable(c)) {
                return c;
            }
        }
        return null;
    }

    private static int versionOf(Path dir) {
        String digits = dir.getFileName().toString().replaceAll("[^0-9.]", "");
        try {
            return (int) (Double.parseDouble(digits.isEmpty() ? "0" : digits.split("\\.")[0]) );
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
