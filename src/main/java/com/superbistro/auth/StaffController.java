package com.superbistro.auth;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.superbistro.common.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Owner adds cashier/kitchen logins for their own restaurant. */
@RestController
@RequestMapping("/api/staff")
@PreAuthorize("hasRole('OWNER')")
public class StaffController {

    public record StaffDto(String id, String name, String email, Role role) {
        static StaffDto of(AppUser u) {
            return new StaffDto(u.getId().toString(), u.getName(), u.getEmail(), u.getRole());
        }
    }

    public record NewStaff(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Email @Size(max = 160) String email,
        @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password,
        @NotNull Role role) {
    }

    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    public StaffController(AppUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @GetMapping
    public List<StaffDto> list(@AuthenticationPrincipal CurrentUser me) {
        return users.findByRestaurantIdOrderByCreatedAt(me.restaurantId()).stream().map(StaffDto::of).toList();
    }

    @PostMapping
    @Transactional
    public StaffDto add(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody NewStaff req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "That email already has an account");
        }
        AppUser u = new AppUser(me.restaurantId(), email, req.name().trim(), encoder.encode(req.password()), req.role());
        return StaffDto.of(users.save(u));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void remove(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id) {
        if (id.equals(me.id())) {
            throw ApiException.badRequest("You can't remove your own login");
        }
        AppUser u = users.findByIdAndRestaurantId(id, me.restaurantId()).orElseThrow(() -> ApiException.notFound("Staff member"));
        users.delete(u);
    }
}
