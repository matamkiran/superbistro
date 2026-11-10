package com.superbistro.menu;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.superbistro.auth.CurrentUser;
import com.superbistro.common.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    public record MenuItemDto(String id, String name, BigDecimal price, String category, boolean veg, boolean active) {
        static MenuItemDto of(MenuItem m) {
            return new MenuItemDto(m.getId().toString(), m.getName(), m.getPrice(), m.getCategory(), m.isVeg(), m.isActive());
        }
    }

    public record MenuItemRequest(
        @NotBlank @Size(max = 80) String name,
        @NotNull @DecimalMin(value = "0", message = "cannot be negative") BigDecimal price,
        @Size(max = 40) String category,
        Boolean veg) {
    }

    public record AvailabilityRequest(@NotNull Boolean active) {
    }

    private final MenuItemRepository menu;

    public MenuController(MenuItemRepository menu) {
        this.menu = menu;
    }

    @GetMapping
    public List<MenuItemDto> list(@AuthenticationPrincipal CurrentUser me) {
        return menu.findByRestaurantIdOrderByCategoryAscCreatedAtAsc(me.restaurantId()).stream().map(MenuItemDto::of).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public MenuItemDto create(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody MenuItemRequest req) {
        MenuItem m = new MenuItem(me.restaurantId(), req.name().trim(), req.price(), category(req.category()), !Boolean.FALSE.equals(req.veg()));
        return MenuItemDto.of(menu.save(m));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public MenuItemDto update(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id, @Valid @RequestBody MenuItemRequest req) {
        MenuItem m = find(me, id);
        m.setName(req.name().trim());
        m.setPrice(req.price());
        m.setCategory(category(req.category()));
        m.setVeg(!Boolean.FALSE.equals(req.veg()));
        return MenuItemDto.of(m);
    }

    /** Staff can mark a dish sold out / back on without editing the menu. */
    @PatchMapping("/{id}/availability")
    @Transactional
    public MenuItemDto availability(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id, @Valid @RequestBody AvailabilityRequest req) {
        MenuItem m = find(me, id);
        m.setActive(req.active());
        return MenuItemDto.of(m);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal CurrentUser me, @PathVariable UUID id) {
        menu.delete(find(me, id));
    }

    public record ImportRequest(@NotNull @Size(max = 1000, message = "can have at most 1000 dishes") List<@Valid MenuItemRequest> items) {
    }

    public record ImportResult(int added, int updated, List<MenuItemDto> menu) {
    }

    /**
     * Bulk load from a spreadsheet. Matches existing dishes by name (ignoring case and spaces):
     * a match gets the new price/category/veg, anything else is added. Nothing is deleted.
     */
    @PostMapping("/import")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public ImportResult importMenu(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody ImportRequest req) {
        java.util.Map<String, MenuItem> byName = new java.util.HashMap<>();
        for (MenuItem m : menu.findByRestaurantIdOrderByCategoryAscCreatedAtAsc(me.restaurantId())) {
            byName.put(key(m.getName()), m);
        }
        int added = 0, updated = 0;
        for (MenuItemRequest r : req.items()) {
            String name = r.name().trim();
            boolean veg = !Boolean.FALSE.equals(r.veg());
            MenuItem m = byName.get(key(name));
            if (m != null) {
                m.setPrice(r.price());
                m.setCategory(category(r.category()));
                m.setVeg(veg);
                updated++;
            } else {
                m = menu.save(new MenuItem(me.restaurantId(), name, r.price(), category(r.category()), veg));
                byName.put(key(name), m);
                added++;
            }
        }
        return new ImportResult(added, updated, list(me));
    }

    public record RenameCategory(@NotBlank @Size(max = 40) String from, @NotBlank @Size(max = 40) String to) {
    }

    public record CategoryAvailability(@NotBlank @Size(max = 40) String category, @NotNull Boolean active) {
    }

    /** Renames a menu section (e.g. "Bites" to "Snacks") for every dish in it. */
    @PostMapping("/category/rename")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public List<MenuItemDto> renameCategory(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody RenameCategory req) {
        String from = req.from().trim(), to = req.to().trim();
        int n = 0;
        for (MenuItem m : menu.findByRestaurantIdOrderByCategoryAscCreatedAtAsc(me.restaurantId())) {
            if (m.getCategory().equalsIgnoreCase(from)) {
                m.setCategory(to);
                n++;
            }
        }
        if (n == 0) {
            throw ApiException.notFound("Section \"" + from + "\"");
        }
        return list(me);
    }

    /** Marks every dish in a section sold out or available (staff can do this too). */
    @PostMapping("/category/availability")
    @Transactional
    public List<MenuItemDto> categoryAvailability(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody CategoryAvailability req) {
        for (MenuItem m : menu.findByRestaurantIdOrderByCategoryAscCreatedAtAsc(me.restaurantId())) {
            if (m.getCategory().equalsIgnoreCase(req.category().trim())) {
                m.setActive(req.active());
            }
        }
        return list(me);
    }

    private static String key(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private MenuItem find(CurrentUser me, UUID id) {
        return menu.findByIdAndRestaurantId(id, me.restaurantId()).orElseThrow(() -> ApiException.notFound("Dish"));
    }

    private static String category(String c) {
        return c == null || c.isBlank() ? "Other" : c.trim();
    }
}
