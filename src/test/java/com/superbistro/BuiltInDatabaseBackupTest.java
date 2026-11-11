package com.superbistro;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import com.jayway.jsonpath.JsonPath;
import com.superbistro.setup.SetupServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The built-in PostgreSQL (what a PC without PostgreSQL uses) end to end: start, back up to a
 * zip, change data, restore. Runs where the PostgreSQL program is packed in (Windows and Mac builds).
 */
@SpringBootTest
@EnabledIf("builtin")
class BuiltInDatabaseBackupTest {
    static boolean builtin() {
        return SetupServer.builtinAvailable();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Path dir = Files.createTempDirectory("superbistro-embedded-test");
        r.add("superbistro.embedded-db", () -> "true");
        r.add("superbistro.data-dir", dir::toString);
    }

    @Autowired
    WebApplicationContext ctx;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    private MockHttpSession login(String path, String body) throws Exception {
        var res = mvc.perform(post(path).session(new MockHttpSession()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private String body(org.springframework.test.web.servlet.RequestBuilder rb) throws Exception {
        return mvc.perform(rb).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void backupAndRestoreRoundTrip() throws Exception {
        TestOwners.create(ctx, "Backup Test", "backup-owner@example.com", "secret123");
        MockHttpSession s = login("/api/auth/login", "{\"email\":\"backup-owner@example.com\",\"password\":\"secret123\"}");
        String dosa = JsonPath.read(body(post("/api/menu").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Masala Dosa\",\"price\":70,\"category\":\"Breakfast\",\"veg\":true}")), "$.id");
        mvc.perform(post("/api/orders").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"OFFLINE\",\"type\":\"TAKEAWAY\",\"customer\":\"Asha, \\\"VIP\\\"\",\"items\":[{\"menuItemId\":\"%s\",\"qty\":2,\"note\":\"less oil\\nno onion\"}]}".formatted(dosa)))
            .andExpect(status().isOk());

        // Back up (zip, no pg_dump needed).
        String run = body(post("/api/system/backup/run").session(s).with(csrf()));
        String file = JsonPath.read(run, "$.fileName");
        mvc.perform(get("/api/system").session(s))
            .andExpect(jsonPath("$.history[0].status").value("OK")) // saved on this computer (Drive not connected)
            .andExpect(jsonPath("$.history[0].fileName", endsWith(".zip")))
            .andExpect(jsonPath("$.backup.embeddedDb").value(true));

        // Change things after the backup.
        mvc.perform(delete("/api/menu/" + dosa).session(s).with(csrf())).andExpect(status().is2xxSuccessful());
        mvc.perform(post("/api/menu").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Idli\",\"price\":40,\"category\":\"Breakfast\",\"veg\":true}"))
            .andExpect(status().isOk());

        // A file that isn't a backup is refused and changes nothing.
        mvc.perform(post("/api/system/restore").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content("not a zip".getBytes()))
            .andExpect(status().isBadRequest());

        // Restore: back to exactly the backup, everyone logged out.
        mvc.perform(post("/api/system/restore/local/" + file).session(s).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows", greaterThan(3)));
        mvc.perform(get("/api/menu").session(s)).andExpect(status().isUnauthorized());

        MockHttpSession again = login("/api/auth/login", "{\"email\":\"backup-owner@example.com\",\"password\":\"secret123\"}");
        mvc.perform(get("/api/menu").session(again))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("Masala Dosa"));
        mvc.perform(get("/api/orders").session(again))
            .andExpect(jsonPath("$[0].customer").value("Asha, \"VIP\""))
            .andExpect(jsonPath("$[0].items[0].note").value("less oil\nno onion"));
        // The safety backup made before restoring is listed (backup history is kept, not restored).
        mvc.perform(get("/api/system").session(again))
            .andExpect(jsonPath("$.history[0].trigger").value("BEFORE_RESTORE"));
    }
}
