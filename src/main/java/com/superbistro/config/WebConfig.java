package com.superbistro.config;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.superbistro.auth.CurrentUser;

/**
 * Caching for the React build: index.html must always be revalidated so browsers pick up a new
 * version right after an update; files under /assets have content hashes in their names, so they
 * can be cached for a year.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
            .addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }

    /** The ready-made "admin" login can do nothing but choose its own password. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws IOException {
                Authentication a = SecurityContextHolder.getContext().getAuthentication();
                if (a != null && a.getPrincipal() instanceof CurrentUser u && u.mustChangePassword()) {
                    res.setStatus(403);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"Choose your own password first\"}");
                    return false;
                }
                return true;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/**");
    }

    @Bean
    OncePerRequestFilter noCacheForAppShell() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                String p = req.getRequestURI();
                if (p.equals("/") || p.equals("/index.html") || p.equals("/sw.js") || p.equals("/manifest.webmanifest") || p.startsWith("/api/")) {
                    res.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                }
                chain.doFilter(req, res);
            }
        };
    }
}
