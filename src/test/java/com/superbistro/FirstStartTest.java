package com.superbistro;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import com.superbistro.auth.AppUser;
import com.superbistro.auth.AppUserRepository;
import com.superbistro.auth.Role;
import com.superbistro.setup.Launcher;
import com.superbistro.tenant.Restaurant;
import com.superbistro.tenant.RestaurantRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The ready-made "admin" login of a fresh install must choose its own password before anything else. */
@SpringBootTest
class FirstStartTest {
    @Autowired WebApplicationContext ctx;
    @Autowired AppUserRepository users;
    @Autowired RestaurantRepository restaurants;
    @Autowired PasswordEncoder encoder;

    @Test
    void readyMadeLoginMustChangePassword() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        Restaurant r = restaurants.save(new Restaurant("Super Bistro", BigDecimal.ZERO));
        AppUser admin = new AppUser(r.getId(), Launcher.DEFAULT_LOGIN, "Admin", encoder.encode(Launcher.DEFAULT_PASSWORD), Role.OWNER);
        admin.setPlatformAdmin(true);
        admin.setMustChangePassword(true);
        users.save(admin);

        mvc.perform(get("/api/auth/config")).andExpect(jsonPath("$.firstStart", is(true)))
            .andExpect(jsonPath("$.defaultLogin", is("admin")));

        MockHttpSession s = new MockHttpSession();
        var res = mvc.perform(post("/api/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin\",\"password\":\"superbistro\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword", is(true))).andReturn();
        s = (MockHttpSession) res.getRequest().getSession(false);

        mvc.perform(get("/api/menu").session(s)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/password").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"current\":\"wrong\",\"password\":\"my-own-pass-1\"}"))
            .andExpect(status().isBadRequest());
        res = mvc.perform(post("/api/auth/password").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"current\":\"superbistro\",\"password\":\"my-own-pass-1\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword", is(false))).andReturn();
        s = (MockHttpSession) res.getRequest().getSession(false);

        mvc.perform(get("/api/menu").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/config")).andExpect(jsonPath("$.firstStart", is(false)))
            .andExpect(jsonPath("$.defaultPassword", is("")));
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin\",\"password\":\"superbistro\"}"))
            .andExpect(status().isUnauthorized());
    }
}
