package com.superbistro.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Built-in database ("Built-in database" in setup, or EMBEDDED_DB=true): runs a private
 * PostgreSQL server packed inside the app, so the PC needs nothing else installed. Data is kept
 * in DATA_DIR/pgdata and survives restarts and updates; the program files are unpacked to
 * DATA_DIR/pgbin. Backups (System page) work the same as with a separate PostgreSQL.
 */
@Configuration
@ConditionalOnProperty(name = "superbistro.embedded-db", havingValue = "true")
public class EmbeddedDatabaseConfig {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedDatabaseConfig.class);
    private static final int PG_PORT = 54329;
    /** The Windows PostgreSQL build needs the Microsoft C++ runtime; the bundled Java has it. */
    private static final List<String> MSVC_DLLS = List.of("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll");

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres(AppProperties props) throws IOException {
        Path dir = Path.of(props.dataDir()).toAbsolutePath();
        Path data = dir.resolve("pgdata");
        Path bin = dir.resolve("pgbin");
        Files.createDirectories(dir);
        log.info("Starting built-in PostgreSQL with data in {}", data);
        addWindowsRuntime(bin);
        stopLeftover(bin, data);
        try {
            return start(data, bin);
        } catch (IOException | RuntimeException first) {
            // First start on a fresh Windows PC: the programs were only just unpacked, so the
            // runtime DLLs could not be added before. Or a server from an unclean shutdown
            // (window closed, power cut) is still holding the data folder.
            log.warn("Built-in PostgreSQL did not start ({}); retrying", first.getMessage());
            addWindowsRuntime(bin);
            stopLeftover(bin, data);
            return start(data, bin);
        }
    }

    private static EmbeddedPostgres start(Path data, Path bin) throws IOException {
        return EmbeddedPostgres.builder()
            .setDataDirectory(data)
            .setCleanDataDirectory(false)
            .setOverrideWorkingDirectory(bin.toFile())
            .setPort(PG_PORT)
            .start();
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres pg) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pg.getJdbcUrl("postgres", "postgres"));
        ds.setUsername("postgres");
        ds.setMaximumPoolSize(5);
        ds.setPoolName("superbistro-embedded");
        return ds;
    }

    /** Copies the C++ runtime DLLs next to postgres.exe where they are missing (Windows only). */
    private static void addWindowsRuntime(Path bin) {
        if (!isWindows()) return;
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        for (Path pgBin : pgBinDirs(bin)) {
            for (String dll : MSVC_DLLS) {
                Path from = javaBin.resolve(dll);
                Path to = pgBin.resolve(dll);
                if (Files.exists(from) && !Files.exists(to)) {
                    try {
                        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        log.warn("Could not copy {} for PostgreSQL: {}", dll, e.getMessage());
                    }
                }
            }
        }
    }

    /** Stops a built-in server left running by an unclean shutdown, so this start can use the data. */
    private static void stopLeftover(Path bin, Path data) {
        if (!Files.exists(data.resolve("postmaster.pid"))) return;
        for (Path pgBin : pgBinDirs(bin)) {
            Path pgCtl = pgBin.resolve(isWindows() ? "pg_ctl.exe" : "pg_ctl");
            if (!Files.isExecutable(pgCtl)) continue;
            try {
                Process p = new ProcessBuilder(pgCtl.toString(), "stop", "-D", data.toString(), "-m", "fast", "-w", "-t", "30")
                    .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes()).trim();
                if (p.waitFor(40, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    log.info("Stopped a built-in PostgreSQL left running from last time");
                } else {
                    log.debug("pg_ctl stop: {}", out); // usually "no server running": nothing to do
                }
            } catch (IOException e) {
                log.debug("pg_ctl stop failed: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
    }

    /** The unpacked PostgreSQL bin folders (DATA_DIR/pgbin/PG-xxxx/bin). */
    private static List<Path> pgBinDirs(Path bin) {
        if (!Files.isDirectory(bin)) return List.of();
        try (Stream<Path> s = Files.list(bin)) {
            return s.map(d -> d.resolve("bin")).filter(Files::isDirectory).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
