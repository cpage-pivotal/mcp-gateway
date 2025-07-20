package org.tanzu.mcpgateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/**", "/health").permitAll()
                        .pathMatchers("/auth/login", "/auth/callback", "/auth/success").permitAll()
                        .pathMatchers("/auth/status", "/auth/logout").authenticated()
                        .pathMatchers("/mcp/admin/**").permitAll()
                        .pathMatchers("/mcp/**").authenticated()
                        .anyExchange().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .authenticationSuccessHandler((exchange, authentication) -> {
                            URI redirectUri = URI.create("/auth/callback");
                            exchange.getExchange().getResponse().getHeaders().setLocation(redirectUri);
                            exchange.getExchange().getResponse().setStatusCode(org.springframework.http.HttpStatus.FOUND);
                            return exchange.getExchange().getResponse().setComplete();
                        })
                        .authenticationFailureHandler((exchange, ex) -> {
                            URI redirectUri = URI.create("/auth/login?error=oauth2_failed");
                            exchange.getExchange().getResponse().getHeaders().setLocation(redirectUri);
                            exchange.getExchange().getResponse().setStatusCode(org.springframework.http.HttpStatus.FOUND);
                            return exchange.getExchange().getResponse().setComplete();
                        })
                )
                .logout(logout -> logout
                        .logoutUrl("/auth/logout")
                        .logoutSuccessHandler(new RedirectServerLogoutSuccessHandler())
                )
                .build();
    }


    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        return new CorsWebFilter(corsConfigurationSource());
    }
}