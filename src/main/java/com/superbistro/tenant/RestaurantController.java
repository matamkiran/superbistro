package com.superbistro.tenant;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.superbistro.auth.AuthController.RestaurantDto;
import com.superbistro.auth.CurrentUser;
import com.superbistro.common.ApiException;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/restaurant")
public class RestaurantController {

    public record UpdateRestaurant(
        @NotBlank @Size(max = 80) String name,
        @NotNull @DecimalMin("0") @DecimalMax(value = "28", message = "GST cannot be above 28%") BigDecimal gstPct,
        @Size(max = 200) String address,
        @Size(max = 40) String phone,
        @jakarta.validation.constraints.Pattern(regexp = "|[0-9]{2}[A-Za-z0-9]{13}", message = "must be a 15-character GSTIN like 36ABCDE1234F1Z5") String gstin,
        @Size(max = 200) String billFooter) {
    }

    private final RestaurantRepository restaurants;

    public RestaurantController(RestaurantRepository restaurants) {
        this.restaurants = restaurants;
    }

    @GetMapping
    public RestaurantDto get(@AuthenticationPrincipal CurrentUser me) {
        return RestaurantDto.of(find(me));
    }

    @PutMapping
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public RestaurantDto update(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody UpdateRestaurant req) {
        Restaurant r = find(me);
        r.setName(req.name().trim());
        r.setGstPct(req.gstPct());
        r.setBillDetails(t(req.address()), t(req.phone()), t(req.gstin()).toUpperCase(), t(req.billFooter()));
        return RestaurantDto.of(r);
    }

    private static String t(String s) {
        return s == null ? "" : s.trim();
    }

    private Restaurant find(CurrentUser me) {
        return restaurants.findById(me.restaurantId()).orElseThrow(() -> ApiException.notFound("Restaurant"));
    }
}
