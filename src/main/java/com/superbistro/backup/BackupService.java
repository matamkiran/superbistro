package com.superbistro.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.superbistro.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Full database backups (a .zip, see {@link DatabaseBackup}; restore on the System page), kept in
 * DATA_DIR/backups and uploaded to Google Drive when connected. Works with the built-in database
 * and with a separate PostgreSQL; no pg_dump needed. Runs daily at the chosen time; if the
 * computer was off at that time, it runs as soon as Super Bistro is running again.
 */
@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    public record Run(String id, long startedAt, Long finishedAt, String trigger, String status, String fileName,
                      Long sizeBytes, boolean onDrive, String message) {
    }

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final SystemSettings settings;
    private final AppProperties props;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BackupService(JdbcTemplate jdbc, DataSource dataSource, SystemSettings settings, AppProperties props) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.settings = settings;
        this.props = props;
    }

    public Path backupDir() {
        return Path.of(props.dataDir()).resolve("backups");
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Checked every minute: runs the daily backup once the chosen time has passed today. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void scheduledCheck() {
        Map<String, String> s = settings.all();
        if (!"true".equals(s.getOrDefault(SystemSettings.BACKUP_ENABLED, "true"))) return;
        LocalTime at = LocalTime.parse(s.getOrDefault(SystemSettings.BACKUP_TIME, "23:30"));
        LocalDateTime now = LocalDateTime.now(props.zone());
        String today = now.toLocalDate().toString();
        if (now.toLocalTime().isBefore(at) || today.equals(s.get(SystemSettings.LAST_SCHEDULED_DAY))) return;
        settings.put(SystemSettings.LAST_SCHEDULED_DAY, today);
        runBackup("SCHEDULED");
    }

    /** Runs one backup now. Returns the recorded run (also when it failed). */
    public Run runBackup(String trigger) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A backup is already running");
        }
        UUID id = UUID.randomUUID();
        Instant started = Instant.now();
        jdbc.update("INSERT INTO backup_run (id, started_at, trigger_type, status) VALUES (?, ?, ?, 'RUNNING')",
            id, Timestamp.from(started), trigger);
        Path file = null;
        String driveId = null;
        StringBuilder note = new StringBuilder();
        String status = "FAILED";
        try {
            file = dump(started);
            status = "OK";
            note.append("Saved on this computer");
            pruneLocal();
            Map<String, String> s = settings.all();
            if (s.containsKey(SystemSettings.G_REFRESH)) {
                try {
                    driveId = uploadToDrive(file, s);
                    note.append(" and on Google Drive (").append(s.getOrDefault(SystemSettings.G_ACCOUNT, "connected account")).append(")");
                } catch (IOException e) {
                    status = "PARTIAL";
                    note.append(". Google Drive upload failed: ").append(e.getMessage());
                }
            } else {
                note.append(". Google Drive is not connected");
            }
        } catch (Exception e) {
            note.setLength(0);
            note.append(e.getMessage());
            log.warn("Backup failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
        Long size = null;
        try {
            if (file != null) size = Files.size(file);
        } catch (IOException ignored) {
            // size unknown
        }
        String msg = note.length() > 990 ? note.substring(0, 990) : note.toString();
        jdbc.update("UPDATE backup_run SET finished_at = ?, status = ?, file_name = ?, size_bytes = ?, drive_file_id = ?, message = ? WHERE id = ?",
            Timestamp.from(Instant.now()), status, file == null ? null : file.getFileName().toString(), size, driveId, msg, id);
        log.info("Backup {}: {}", status, msg);
        return history(1).get(0);
    }

    private Path dump(Instant started) throws IOException, SQLException {
        Files.createDirectories(backupDir());
        String stamp = "superbistro_" + LocalDateTime.ofInstant(started, props.zone()).format(FILE_TS);
        String name = stamp + ".zip";
        for (int i = 2; Files.exists(backupDir().resolve(name)); i++) {
            name = stamp + "-" + i + ".zip"; // never overwrite an earlier backup
        }
        Path out = backupDir().resolve(name);
        Path part = backupDir().resolve(name + ".part");
        try {
            DatabaseBackup.write(dataSource, part);
            Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
        return out;
    }

    /**
     * Replaces all data with a backup file (from this computer, Google Drive or another PC).
     * A safety backup of the current data is made first.
     */
    public DatabaseBackup.Restored restore(Path file) throws IOException, SQLException {
        Run safety = runBackup("BEFORE_RESTORE");
        if ("FAILED".equals(safety.status())) {
            throw new IOException("Could not make a safety backup of the current data first: " + safety.message());
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A backup is running; try again in a minute");
        }
        try {
            DatabaseBackup.Restored r = DatabaseBackup.restore(dataSource, file);
            log.info("Restored backup from {} ({} tables)", r.createdAt(), r.rows().size());
            return r;
        } finally {
            running.set(false);
        }
    }

    private String uploadToDrive(Path file, Map<String, String> s) throws IOException {
        GoogleDrive drive = drive(s);
        if (drive == null) throw new IOException("Google client ID/secret are missing");
        String token = drive.accessToken(s.get(SystemSettings.G_REFRESH));
        String folder = drive.ensureFolder(token, s.get(SystemSettings.G_FOLDER));
        if (!folder.equals(s.get(SystemSettings.G_FOLDER))) settings.put(SystemSettings.G_FOLDER, folder);
        String id = drive.upload(token, folder, file);
        int keep = intSetting(s, SystemSettings.KEEP_DRIVE, 30);
        List<GoogleDrive.DriveFile> files = drive.list(token, folder);
        for (int i = keep; i < files.size(); i++) {
            drive.delete(token, files.get(i).id());
        }
        return id;
    }

    private void pruneLocal() throws IOException {
        int keep = intSetting(settings.all(), SystemSettings.KEEP_LOCAL, 14);
        try (Stream<Path> s = Files.list(backupDir())) {
            List<Path> dumps = new ArrayList<>(s.filter(p -> isBackupName(p.getFileName().toString())).toList());
            dumps.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
            for (int i = keep; i < dumps.size(); i++) {
                Files.deleteIfExists(dumps.get(i));
            }
        }
    }

    public GoogleDrive drive(Map<String, String> s) {
        String id = firstNonBlank(s.get(SystemSettings.G_CLIENT_ID), System.getenv("GOOGLE_CLIENT_ID"));
        String secret = firstNonBlank(s.get(SystemSettings.G_CLIENT_SECRET), System.getenv("GOOGLE_CLIENT_SECRET"));
        return id == null || secret == null ? null : new GoogleDrive(id, secret);
    }

    public List<Run> history(int limit) {
        return jdbc.query("SELECT * FROM backup_run ORDER BY started_at DESC LIMIT ?", (rs, i) -> {
            Timestamp fin = rs.getTimestamp("finished_at");
            long size = rs.getLong("size_bytes");
            return new Run(rs.getString("id"), rs.getTimestamp("started_at").getTime(), fin == null ? null : fin.getTime(),
                rs.getString("trigger_type"), rs.getString("status"), rs.getString("file_name"),
                rs.wasNull() ? null : size, rs.getString("drive_file_id") != null, rs.getString("message"));
        }, limit);
    }

    /** When the next scheduled backup will run, for display. */
    public String nextRun(Map<String, String> s) {
        if (!"true".equals(s.getOrDefault(SystemSettings.BACKUP_ENABLED, "true"))) return null;
        LocalTime at = LocalTime.parse(s.getOrDefault(SystemSettings.BACKUP_TIME, "23:30"));
        LocalDate day = LocalDate.now(props.zone());
        boolean doneToday = day.toString().equals(s.get(SystemSettings.LAST_SCHEDULED_DAY));
        return LocalDateTime.of(doneToday ? day.plusDays(1) : day, at).toString();
    }

    /** Backup files in the backups folder: .zip (current) and .dump (made with pg_dump by older versions). */
    public static boolean isBackupName(String name) {
        return name.matches("superbistro_[0-9_-]+\\.(zip|dump)");
    }

    static int intSetting(Map<String, String> s, String key, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(s.getOrDefault(key, String.valueOf(fallback))));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }
}
