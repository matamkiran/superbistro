package com.superbistro.backup;

import java.io.BufferedReader;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Backup and restore without pg_dump, so it also works with the built-in database.
 *
 * <p>A backup is a zip: {@code manifest.json} (schema version, time, row counts) plus one CSV per
 * table, written with PostgreSQL's COPY inside one read-only snapshot, so it's consistent while
 * orders keep coming in. Restore replaces all data in one transaction: it either fully succeeds
 * or changes nothing.</p>
 */
public final class DatabaseBackup {
    public static final String APP = "superbistro";
    public static final int FORMAT = 1;
    /**
     * Left out: the schema history (Flyway's), logins of the running server, and this computer's
     * own backup history (it describes the files in this computer's backups folder).
     */
    private static final Set<String> SKIP = Set.of("flyway_schema_history", "spring_session", "spring_session_attributes", "backup_run");
    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Restored(String createdAt, Map<String, Long> rows) {
    }

    private DatabaseBackup() {
    }

    /** Writes a full backup of the current database to {@code out}. Returns rows per table. */
    public static Map<String, Long> write(DataSource ds, Path out) throws IOException, SQLException {
        Map<String, Long> rows = new LinkedHashMap<>();
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
                String version = schemaVersion(c);
                CopyManager copy = c.unwrap(PGConnection.class).getCopyAPI();
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
                    OutputStream keepOpen = new FilterOutputStream(zip) {
                        @Override
                        public void write(byte[] b, int off, int len) throws IOException {
                            out.write(b, off, len);
                        }

                        @Override
                        public void close() {
                            // the zip stays open for the next table
                        }
                    };
                    for (String t : tables(c)) {
                        zip.putNextEntry(new ZipEntry("tables/" + t + ".csv"));
                        rows.put(t, copy.copyOut("COPY " + t + " TO STDOUT (FORMAT csv, HEADER true)", keepOpen));
                        zip.closeEntry();
                    }
                    Map<String, Object> manifest = new LinkedHashMap<>();
                    manifest.put("app", APP);
                    manifest.put("format", FORMAT);
                    manifest.put("schemaVersion", version);
                    manifest.put("createdAt", Instant.now().toString());
                    manifest.put("tables", rows);
                    zip.putNextEntry(new ZipEntry("manifest.json"));
                    zip.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                    zip.closeEntry();
                }
            } finally {
                c.rollback();
                c.setAutoCommit(auto);
            }
        }
        return rows;
    }

    /**
     * Replaces all data with the backup in {@code zipFile}. The backup must come from the same
     * Super Bistro version (same database schema); everyone has to log in again afterwards.
     */
    public static Restored restore(DataSource ds, Path zipFile) throws IOException, SQLException {
        try (ZipFile zip = open(zipFile); Connection c = ds.getConnection()) {
            ZipEntry m = zip.getEntry("manifest.json");
            if (m == null) throw new IllegalArgumentException("This is not a Super Bistro backup file (no manifest.json)");
            JsonNode manifest;
            try (InputStream in = zip.getInputStream(m)) {
                manifest = JSON.readTree(in);
            }
            if (!APP.equals(manifest.path("app").asString()) || manifest.path("format").asInt() != FORMAT) {
                throw new IllegalArgumentException("This is not a Super Bistro backup file");
            }
            String backupVersion = manifest.path("schemaVersion").asString();
            String current = schemaVersion(c);
            // Older backups restore into a newer Super Bistro: database updates only add tables and
            // columns (with defaults), and the restore names each backed-up column. A backup from a
            // newer version can't be read by an older one.
            if (compare(backupVersion, current) > 0) {
                throw new IllegalArgumentException("This backup is from a newer Super Bistro (database version " + backupVersion
                    + ", this one is " + current + "). Install the latest Super Bistro, then restore.");
            }
            List<String> tables = tables(c);
            Set<String> inBackup = new LinkedHashSet<>();
            manifest.path("tables").propertyNames().forEach(inBackup::add);
            for (String t : inBackup) {
                if (!tables.contains(t)) throw new IllegalArgumentException("The backup has an unknown table: " + t);
                if (zip.getEntry("tables/" + t + ".csv") == null) throw new IllegalArgumentException("The backup file is incomplete (" + t + " is missing)");
            }

            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            Map<String, Long> rows = new LinkedHashMap<>();
            try (Statement s = c.createStatement()) {
                s.execute("TRUNCATE " + String.join(", ", tables) + " CASCADE");
                CopyManager copy = c.unwrap(PGConnection.class).getCopyAPI();
                for (String t : loadOrder(c, tables)) {
                    if (!inBackup.contains(t)) continue;
                    try (InputStream in = zip.getInputStream(zip.getEntry("tables/" + t + ".csv"))) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                        String header = r.readLine();
                        if (header == null || header.isBlank()) {
                            rows.put(t, 0L);
                            continue;
                        }
                        List<String> cols = new ArrayList<>();
                        for (String col : header.split(",")) {
                            String name = col.trim().replace("\"", "");
                            if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Bad column name in backup: " + name);
                            cols.add(name);
                        }
                        // The header line was read already; the reader continues with the data.
                        rows.put(t, copy.copyIn("COPY " + t + " (" + String.join(", ", cols) + ") FROM STDIN (FORMAT csv)", r));
                    }
                }
                s.execute("DELETE FROM spring_session");
                c.commit();
            } catch (IOException | SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
            return new Restored(manifest.path("createdAt").asString(), rows);
        }
    }

    /** The app's data tables (in this schema), checked to be plain names. */
    static List<String> tables(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() "
                    + "AND table_type = 'BASE TABLE' ORDER BY table_name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String t = rs.getString(1).toLowerCase();
                if (!SKIP.contains(t) && NAME.matcher(t).matches()) out.add(t);
            }
        }
        return out;
    }

    /** Tables ordered so every table comes after the tables it references. */
    static List<String> loadOrder(Connection c, List<String> tables) throws SQLException {
        Map<String, Set<String>> parents = new HashMap<>();
        for (String t : tables) parents.put(t, new HashSet<>());
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT cl.relname, pr.relname FROM pg_constraint k "
                    + "JOIN pg_class cl ON cl.oid = k.conrelid JOIN pg_class pr ON pr.oid = k.confrelid "
                    + "JOIN pg_namespace n ON n.oid = cl.relnamespace "
                    + "WHERE k.contype = 'f' AND n.nspname = current_schema()");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String child = rs.getString(1);
                String parent = rs.getString(2);
                if (parents.containsKey(child) && parents.containsKey(parent) && !child.equals(parent)) parents.get(child).add(parent);
            }
        }
        List<String> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (order.size() < tables.size()) {
            boolean progress = false;
            for (String t : tables) {
                if (!done.contains(t) && done.containsAll(parents.get(t))) {
                    order.add(t);
                    done.add(t);
                    progress = true;
                }
            }
            if (!progress) throw new IllegalStateException("Circular table references: " + parents);
        }
        return order;
    }

    static String schemaVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL "
                 + "ORDER BY installed_rank DESC LIMIT 1")) {
            return rs.next() ? rs.getString(1) : "0";
        }
    }

    private static int compare(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private static ZipFile open(Path f) throws IOException {
        try {
            return new ZipFile(f.toFile());
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("This file is not a Super Bistro backup (.zip)");
        }
    }
}
