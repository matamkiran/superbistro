package com.superbistro.config;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Desktop installs: open the order desk in the default browser when the server is up. */
@Component
public class OpenBrowserOnStart {
    private static final Logger log = LoggerFactory.getLogger(OpenBrowserOnStart.class);

    private final AppProperties props;

    public OpenBrowserOnStart(AppProperties props) {
        this.props = props;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent e) {
        int port = e.getApplicationContext() instanceof WebServerApplicationContext w ? w.getWebServer().getPort() : 8080;
        String url = "http://localhost:" + port;
        log.info("Super Bistro is ready at {}", url);
        if (!props.openBrowser()) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
            : os.contains("mac") ? new String[] {"open", url}
            : new String[] {"xdg-open", url};
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception ex) {
            log.info("Open {} in a browser", url);
        }
    }
}
