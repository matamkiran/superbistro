package com.superbistro.backup;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Installation-wide key/value settings in the system_setting table. */
@Component
public class SystemSettings {
    public static final String BACKUP_ENABLED = "backup.enabled";
    public static final String BACKUP_TIME = "backup.time";
    public static final String KEEP_LOCAL = "backup.keepLocal";
    public static final String KEEP_DRIVE = "backup.keepDrive";
    public static final String LAST_SCHEDULED_DAY = "backup.lastScheduledDay";
    public static final String G_CLIENT_ID = "google.clientId";
    public static final String G_CLIENT_SECRET = "google.clientSecret";
    public static final String G_REFRESH = "google.refreshToken";
    public static final String G_FOLDER = "google.folderId";
    public static final String G_ACCOUNT = "google.account";

    private final JdbcTemplate jdbc;

    public SystemSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> all() {
        Map<String, String> m = new HashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM system_setting", rs -> {
            m.put(rs.getString(1), rs.getString(2));
        });
        return m;
    }

    public String get(String key, String fallback) {
        return jdbc.query("SELECT setting_value FROM system_setting WHERE setting_key = ?",
            rs -> rs.next() ? rs.getString(1) : fallback, key);
    }

    @Transactional
    public void put(String key, String value) {
        if (value == null) {
            jdbc.update("DELETE FROM system_setting WHERE setting_key = ?", key);
            return;
        }
        int n = jdbc.update("UPDATE system_setting SET setting_value = ? WHERE setting_key = ?", value, key);
        if (n == 0) {
            jdbc.update("INSERT INTO system_setting (setting_key, setting_value) VALUES (?, ?)", key, value);
        }
    }
}
