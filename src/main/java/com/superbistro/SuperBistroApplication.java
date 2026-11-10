package com.superbistro;

import com.superbistro.setup.Launcher;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SuperBistroApplication {
    /** Entry point: runs first-time setup when no database is configured yet, then the app. */
    public static void main(String[] args) throws Exception {
        Launcher.main(args);
    }
}
