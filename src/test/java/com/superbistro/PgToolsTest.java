package com.superbistro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.superbistro.backup.PgTools;

import org.junit.jupiter.api.Test;

class PgToolsTest {
    @Test
    void parsesJdbcUrls() {
        PgTools.Target a = PgTools.parse("jdbc:postgresql://localhost:5433/superbistro");
        assertEquals("localhost", a.host());
        assertEquals(5433, a.port());
        assertEquals("superbistro", a.database());
        assertNull(a.sslmode());

        PgTools.Target b = PgTools.parse("jdbc:postgresql://ep-x.aws.neon.tech/neondb?sslmode=require&channelBinding=x");
        assertEquals("ep-x.aws.neon.tech", b.host());
        assertEquals(5432, b.port());
        assertEquals("neondb", b.database());
        assertEquals("require", b.sslmode());
    }
}
