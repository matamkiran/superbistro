package com.superbistro.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.superbistro.common.ApiException;
import com.superbistro.config.AppProperties;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** System page for the installation's main admin: backups and the Google Drive connection. */
@RestController
@RequestMapping("/api/system")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class SystemController {
    private static final String STATE_ATTR = "googleOauthState";

    public record BackupSettings(
        @NotNull Boolean enabled,
        @NotBlank @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "must be a time like 23:30") String time,
        @NotNull @Min(1) @Max(365) Integer keepLocal,
        @NotNull @Min(1) @Max(365) Integer keepDrive) {
    }

    public record GoogleClient(@NotBlank String clientId, @NotBlank String clientSecret) {
    }

    private final BackupService backups;
    private final SystemSettings settings;
    private final AppProperties props;

    public SystemController(BackupService backups, SystemSettings settings, AppProperties props) {
        this.backups = backups;
        this.settings = settings;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> overview(HttpServletRequest req) {
        Map<String, String> s = settings.all();
        Path pgDump = backups.pgDump();
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("enabled", "true".equals(s.getOrDefault(SystemSettings.BACKUP_ENABLED, "true")));
        backup.put("time", s.getOrDefault(SystemSettings.BACKUP_TIME, "23:30"));
        backup.put("keepLocal", BackupService.intSetting(s, SystemSettings.KEEP_LOCAL, 14));
        backup.put("keepDrive", BackupService.intSetting(s, SystemSettings.KEEP_DRIVE, 30));
        backup.put("nextRun", backups.nextRun(s));
        backup.put("running", backups.isRunning());
        backup.put("pgDump", pgDump == null ? null : pgDump.toString());
        backup.put("folder", backups.backupDir().toString());
        backup.put("embeddedDb", props.embeddedDb());

        String clientId = s.getOrDefault(SystemSettings.G_CLIENT_ID, System.getenv().getOrDefault("GOOGLE_CLIENT_ID", ""));
        Map<String, Object> google = new LinkedHashMap<>();
        google.put("configured", backups.drive(s) != null);
        google.put("clientId", clientId);
        google.put("connected", s.containsKey(SystemSettings.G_REFRESH));
        google.put("account", s.getOrDefault(SystemSettings.G_ACCOUNT, ""));
        google.put("folderName", GoogleDrive.FOLDER_NAME);
        google.put("redirectUri", redirectUri());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backup", backup);
        out.put("google", google);
        out.put("history", backups.history(30));
        out.put("dataDir", props.dataDir());
        out.put("timeZone", props.timeZone());
        return out;
    }

    @PutMapping("/backup")
    public Map<String, Object> saveBackup(@Valid @RequestBody BackupSettings b, HttpServletRequest req) {
        settings.put(SystemSettings.BACKUP_ENABLED, String.valueOf(b.enabled()));
        settings.put(SystemSettings.BACKUP_TIME, b.time());
        settings.put(SystemSettings.KEEP_LOCAL, String.valueOf(b.keepLocal()));
        settings.put(SystemSettings.KEEP_DRIVE, String.valueOf(b.keepDrive()));
        return overview(req);
    }

    @PostMapping("/backup/run")
    public BackupService.Run runNow() {
        try {
            return backups.runBackup("MANUAL");
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Download a backup file kept on this computer. */
    @GetMapping("/backup/file/{name}")
    public ResponseEntity<FileSystemResource> download(@PathVariable String name) {
        if (!name.matches("superbistro_[0-9_-]+\\.dump")) throw ApiException.notFound("Backup");
        Path f = backups.backupDir().resolve(name);
        if (!Files.isRegularFile(f)) throw ApiException.notFound("Backup");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(new FileSystemResource(f));
    }

    @PutMapping("/google/client")
    public Map<String, Object> saveClient(@Valid @RequestBody GoogleClient c, HttpServletRequest req) {
        settings.put(SystemSettings.G_CLIENT_ID, c.clientId().trim());
        settings.put(SystemSettings.G_CLIENT_SECRET, c.clientSecret().trim());
        return overview(req);
    }

    /** Step 1 of connecting: the Google sign-in page to send the admin to. */
    @GetMapping("/google/connect")
    public Map<String, String> connect(HttpSession session) {
        GoogleDrive drive = backups.drive(settings.all());
        if (drive == null) throw ApiException.badRequest("Enter the Google client ID and secret first");
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        String state = HexFormat.of().formatHex(b);
        session.setAttribute(STATE_ATTR, state);
        return Map.of("url", drive.authUrl(redirectUri(), state));
    }

    /** Step 2: Google sends the browser back here with a one-time code. */
    @GetMapping("/google/callback")
    public void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                         @RequestParam(required = false) String error, HttpSession session, HttpServletResponse res) throws IOException {
        Object expected = session.getAttribute(STATE_ATTR);
        session.removeAttribute(STATE_ATTR);
        if (error != null) {
            res.sendRedirect("/?google=" + enc("Google sign-in was cancelled") + "#system");
            return;
        }
        if (code == null || expected == null || !expected.equals(state)) {
            res.sendRedirect("/?google=" + enc("The Google sign-in link expired. Try Connect again.") + "#system");
            return;
        }
        Map<String, String> s = settings.all();
        GoogleDrive drive = backups.drive(s);
        try {
            String refresh = drive.exchangeCode(code, redirectUri());
            String token = drive.accessToken(refresh);
            String account = drive.accountEmail(token);
            String folder = drive.ensureFolder(token, null);
            settings.put(SystemSettings.G_REFRESH, refresh);
            settings.put(SystemSettings.G_ACCOUNT, account);
            settings.put(SystemSettings.G_FOLDER, folder);
            res.sendRedirect("/?google=connected#system");
        } catch (IOException e) {
            res.sendRedirect("/?google=" + enc(e.getMessage()) + "#system");
        }
    }

    @PostMapping("/google/disconnect")
    public Map<String, Object> disconnect(HttpServletRequest req) {
        Map<String, String> s = settings.all();
        GoogleDrive drive = backups.drive(s);
        if (drive != null && s.containsKey(SystemSettings.G_REFRESH)) drive.revoke(s.get(SystemSettings.G_REFRESH));
        for (String k : List.of(SystemSettings.G_REFRESH, SystemSettings.G_ACCOUNT, SystemSettings.G_FOLDER)) {
            settings.put(k, null);
        }
        return overview(req);
    }

    private static String redirectUri() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/system/google/callback").toUriString();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
