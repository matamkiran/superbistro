package com.superbistro.auth;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.superbistro.common.ApiException;
import com.superbistro.config.AppProperties;
import com.superbistro.tenant.Restaurant;
import com.superbistro.tenant.RestaurantRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record PasswordRequest(@NotBlank String current,
        @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password) {
    }

    public record UserDto(String id, String name, String email, Role role, boolean platformAdmin, boolean mustChangePassword) {
        static UserDto of(CurrentUser u) {
            return new UserDto(u.id().toString(), u.name(), u.email(), u.role(), u.platformAdmin(), u.mustChangePassword());
        }
    }

    public record RestaurantDto(String id, String name, BigDecimal gstPct, String address, String phone, String gstin, String billFooter) {
        public static RestaurantDto of(Restaurant r) {
            return new RestaurantDto(r.getId().toString(), r.getName(), r.getGstPct(), r.getAddress(), r.getPhone(),
                r.getGstin(), r.getBillFooter());
        }
    }

    public record SessionDto(UserDto user, RestaurantDto restaurant) {
    }

    private final AppUserRepository users;
    private final RestaurantRepository restaurants;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository;
    private final AppProperties props;

    public AuthController(AppUserRepository users, RestaurantRepository restaurants, PasswordEncoder encoder,
                          AuthenticationManager authManager, SecurityContextRepository contextRepository,
                          AppProperties props) {
        this.users = users;
        this.restaurants = restaurants;
        this.encoder = encoder;
        this.authManager = authManager;
        this.contextRepository = contextRepository;
        this.props = props;
    }

    /** Public: what the login screen needs to know before anyone signs in. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        // On a fresh install the login screen shows the ready-made login until its password is changed.
        // (also right after "Reset Super Bistro password" on the billing PC)
        String login = users.findAll().stream().filter(u -> u.isPlatformAdmin() && u.isMustChangePassword())
            .map(AppUser::getEmail).findFirst().orElse(null);
        boolean firstStart = login != null;
        return Map.of("firstStart", firstStart,
            "defaultLogin", firstStart ? login : "",
            "defaultPassword", firstStart ? com.superbistro.setup.Launcher.DEFAULT_PASSWORD : "");
    }

    @PostMapping("/login")
    public SessionDto login(@Valid @RequestBody LoginRequest req, HttpServletRequest http, HttpServletResponse res) {
        return startSession(req.email().trim().toLowerCase(Locale.ROOT), req.password(), http, res);
    }

    /** Any signed-in person: change their own password (required once for the ready-made login). */
    @PostMapping("/password")
    @Transactional
    public SessionDto changePassword(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody PasswordRequest req,
                                     HttpServletRequest http, HttpServletResponse res) {
        AppUser u = users.findById(me.id()).orElseThrow(() -> ApiException.notFound("User"));
        if (!encoder.matches(req.current(), u.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The current password is wrong");
        }
        if (req.password().equals(req.current())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a new password, different from the current one");
        }
        u.setPasswordHash(encoder.encode(req.password()));
        u.setMustChangePassword(false);
        users.saveAndFlush(u);
        return startSession(u.getEmail(), req.password(), http, res);
    }

    @GetMapping("/me")
    public SessionDto me(@AuthenticationPrincipal CurrentUser me) {
        Restaurant r = restaurants.findById(me.restaurantId()).orElseThrow(() -> ApiException.notFound("Restaurant"));
        return new SessionDto(UserDto.of(me), RestaurantDto.of(r));
    }

    private SessionDto startSession(String email, String password, HttpServletRequest http, HttpServletResponse res) {
        Authentication auth = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        // New session id on every sign-in (session fixation protection).
        HttpSession old = http.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        http.getSession(true);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, http, res);

        CurrentUser me = (CurrentUser) auth.getPrincipal();
        Restaurant r = restaurants.findById(me.restaurantId()).orElseThrow(() -> ApiException.notFound("Restaurant"));
        return new SessionDto(UserDto.of(me), RestaurantDto.of(r));
    }
}
