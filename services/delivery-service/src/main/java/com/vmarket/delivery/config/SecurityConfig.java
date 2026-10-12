package com.vmarket.delivery.config;
import com.vmarket.delivery.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;
@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http, JwtAuthenticationFilter jwt,
            @org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
            tools.jackson.databind.ObjectMapper json) throws Exception {
        return http.cors(c -> c.configurationSource(cors)).csrf(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(c -> c.disable()).formLogin(c -> c.disable())
                .authorizeHttpRequests(c -> c
                    .requestMatchers("/api/delivery/health", "/api/delivery/ws", "/error", "/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    .requestMatchers("/api/delivery/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/delivery/shipper/**").hasRole("SHIPPER")
                    .requestMatchers("/api/delivery/seller/**").hasRole("SELLER")
                    .anyRequest().authenticated())
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(c -> c.authenticationEntryPoint((req, res, ex) ->
                    com.vmarket.delivery.web.ErrorResponseWriter.write(res, json, org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bạn cần đăng nhập"))
                    .accessDeniedHandler((req, res, ex) -> com.vmarket.delivery.web.ErrorResponseWriter.write(res, json, org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền"))).build();
    }
}
