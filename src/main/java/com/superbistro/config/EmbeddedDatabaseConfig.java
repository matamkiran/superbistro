package com.superbistro.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One-click desktop mode: runs a private PostgreSQL server inside the app, storing data in
 * DATA_DIR/pgdata. Data survives restarts and upgrades. Back up by copying that folder while
 * the app is stopped.
 */
@Configuration
@ConditionalOnProperty(name = "superbistro.embedded-db", havingValue = "true")
public class EmbeddedDatabaseConfig {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedDatabaseConfig.class);
    private static final int PG_PORT = 54329;

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres(AppProperties props) throws IOException {
        Path dir = Path.of(props.dataDir()).toAbsolutePath();
        Files.createDirectories(dir);
        log.info("Starting built-in PostgreSQL with data in {}", dir.resolve("pgdata"));
        return EmbeddedPostgres.builder()
            .setDataDirectory(dir.resolve("pgdata"))
            .setCleanDataDirectory(false)
            .setOverrideWorkingDirectory(dir.resolve("pgbin").toFile())
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
}
