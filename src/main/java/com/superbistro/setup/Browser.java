package com.superbistro.setup;

import java.util.Locale;

/** Opens a URL in the default browser (desktop installs). Fails quietly. */
public final class Browser {
    private Browser() {
    }

    public static void open(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
            : os.contains("mac") ? new String[] {"open", url}
            : new String[] {"xdg-open", url};
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            System.out.println("Open " + url + " in a browser");
        }
    }
}
