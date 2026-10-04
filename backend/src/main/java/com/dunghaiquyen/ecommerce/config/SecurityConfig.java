package com.dunghaiquyen.ecommerce.config;

import com.dunghaiquyen.ecommerce.common.security.CustomUserDetailsService;
import com.dunghaiquyen.ecommerce.common.security.JwtAuthenticationFilter;
import com.dunghaiquyen.ecommerce.common.security.RestAccessDeniedHandler;
import com.dunghaiquyen.ecommerce.common.security.RestAuthenticationEntryPoint;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // GET-only for guests, per API_SPEC_PHASE1.md section 13 (access matrix): guest only reads catalog data.
    private static final String[] PUBLIC_GET_ENDPOINTS = {
            "/api/v1/products/**",
            "/api/v1/categories/**",
            "/api/v1/brands/**",
            "/api/v1/shipping/methods",
            "/api/v1/banners/active",
            "/api/v1/pages/*",
            "/api/v1/settings/public",
            "/api/v1/inventory/stream",
            "/api/v1/realtime/stream",
            "/api/v1/collections",
            "/api/v1/collections/*",
            "/api/v1/promotions/active"
    };

    // All methods public: guest cart (session_id-based, no JWT) needs full
    // read/write, and the payment gateway calls the callback directly with no
    // JWT (auth is the checksum check inside the handler) - API_SPEC_PHASE1.md 8.2.
    private static final String[] PUBLIC_ANY_METHOD_ENDPOINTS = {
            "/api/v1/cart/**",
            "/api/v1/products/search-by-image",
            "/api/v1/visual-search/status",
            "/api/v1/recommendations/logs",
            "/api/v1/payments/callback",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health",
            "/actuator/health/**"
    };

    // Only these specific auth endpoints are anonymous (API_SPEC_PHASE1.md 3.1-3.6).
    // /api/v1/auth/logout is deliberately NOT here: it requires an access token.
    private static final String[] PUBLIC_AUTH_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/google"
    };

    private final AppCorsProperties corsProperties;

    public SecurityConfig(AppCorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint restAuthenticationEntryPoint,
            RestAccessDeniedHandler restAccessDeniedHandler)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        // No valid authentication at all (missing/invalid/expired token,
                        // or a token JwtAuthenticationFilter declined to honor) -> 401.
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        // Valid authentication, rule just denies this URL/method -> 403.
                        .accessDeniedHandler(restAccessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_ENDPOINTS).permitAll()
                        .requestMatchers(PUBLIC_ANY_METHOD_ENDPOINTS).permitAll()
                        .requestMatchers(PUBLIC_AUTH_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            CustomUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(corsProperties.allowedOrigins().split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
