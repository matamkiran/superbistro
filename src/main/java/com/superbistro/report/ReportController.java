package com.superbistro.report;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.superbistro.backup.SystemSettings;
import com.superbistro.common.ApiException;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Report email settings and "send now" (main admin only). The password is never sent back. */
@RestController
@RequestMapping("/api/system/report")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class ReportController {

    /** password: leave empty to keep the saved one. to: one or more addresses, comma separated. */
    public record ReportSettings(
        @Size(max = 300) @Pattern(regexp = "|[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+(\\s*[,;]\\s*[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+)*",
            message = "should be an email address (several: separate with commas)") String to,
        Boolean enabled,
        @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]", message = "should be a time like 23:15") String time,
        @Size(max = 120) String host,
        @Pattern(regexp = "|[0-9]{2,5}") String port,
        @Size(max = 160) String user,
        @Size(max = 200) String password) {
    }

    private final SystemSettings settings;
    private final ReportService reports;

    public ReportController(SystemSettings settings, ReportService reports) {
        this.settings = settings;
        this.reports = reports;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, String> s = settings.all();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("to", s.getOrDefault(ReportService.TO, ""));
        out.put("enabled", !"false".equals(s.get(ReportService.ENABLED)));
        out.put("time", s.getOrDefault(ReportService.TIME, "23:15"));
        out.put("host", s.getOrDefault(ReportService.SMTP_HOST, "smtp.gmail.com"));
        out.put("port", s.getOrDefault(ReportService.SMTP_PORT, "587"));
        out.put("user", s.getOrDefault(ReportService.SMTP_USER, ""));
        out.put("hasPassword", s.containsKey(ReportService.SMTP_PASSWORD));
        out.put("configured", reports.configured(s));
        out.put("lastResult", s.getOrDefault(ReportService.LAST_RESULT, ""));
        return out;
    }

    @PutMapping
    public Map<String, Object> save(@Valid @RequestBody ReportSettings req) {
        if (req.to() != null) settings.put(ReportService.TO, req.to().trim());
        if (req.enabled() != null) settings.put(ReportService.ENABLED, req.enabled().toString());
        if (req.time() != null) settings.put(ReportService.TIME, req.time());
        if (req.host() != null) settings.put(ReportService.SMTP_HOST, req.host().trim());
        if (req.port() != null && !req.port().isBlank()) settings.put(ReportService.SMTP_PORT, req.port().trim());
        if (req.user() != null) settings.put(ReportService.SMTP_USER, req.user().trim());
        if (req.password() != null && !req.password().isBlank()) settings.put(ReportService.SMTP_PASSWORD, req.password().trim());
        return get();
    }

    /** Preview in the browser: ?day=2026-11-11 or ?month=2026-10 (default: today). */
    @GetMapping(value = "/preview", produces = "text/html;charset=UTF-8")
    public String preview(@RequestParam(required = false) java.time.LocalDate day, @RequestParam(required = false) String month) {
        if (month != null) return reports.previewMonth(java.time.YearMonth.parse(month));
        return reports.previewDay(day != null ? day : java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
    }

    /** kind: "test" or "today". */
    @PostMapping("/send")
    public Map<String, Object> send(@RequestParam(defaultValue = "test") String kind) {
        try {
            return Map.of("ok", true, "result", reports.sendNow(!"today".equals(kind)));
        } catch (IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }
}
