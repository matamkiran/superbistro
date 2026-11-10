package com.superbistro.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "superbistro")
public record AppProperties(String timeZone, Boolean allowSignup, Boolean embeddedDb, String dataDir, Boolean openBrowser, String pgDump) {

    public AppProperties {
        if (timeZone == null || timeZone.isBlank()) timeZone = "Asia/Kolkata";
        if (allowSignup == null) allowSignup = true;
        if (embeddedDb == null) embeddedDb = false;
        if (dataDir == null || dataDir.isBlank()) dataDir = com.superbistro.setup.Launcher.dataDir().toString();
        if (openBrowser == null) openBrowser = false;
    }

    public ZoneId zone() {
        return ZoneId.of(timeZone);
    }
}
