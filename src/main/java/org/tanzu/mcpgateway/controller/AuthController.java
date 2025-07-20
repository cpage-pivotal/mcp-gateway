package org.tanzu.mcpgateway.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.tanzu.mcpgateway.service.SessionService;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private SessionService sessionService;

    @GetMapping("/login")
    public Mono<ResponseEntity<Void>> login() {
        return Mono.just(ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/oauth2/authorization/google"))
                .build());
    }

    @GetMapping(value = "/login", params = "error")
    public Mono<ResponseEntity<Map<String, Object>>> loginError(
            @RequestParam String error,
            @RequestParam(required = false) String error_description) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", error);
        errorResponse.put("error_description", error_description);
        errorResponse.put("message", "OAuth2 authentication failed");
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse));
    }

    @GetMapping("/callback")
    public Mono<ResponseEntity<Map<String, Object>>> callback(
            @AuthenticationPrincipal OAuth2User principal,
            @RegisteredOAuth2AuthorizedClient("google") OAuth2AuthorizedClient authorizedClient,
            ServerWebExchange exchange) {
        
        if (principal == null || authorizedClient == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Authentication failed");
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error));
        }

        return sessionService.createSession(principal, authorizedClient, exchange)
                .map(sessionToken -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("status", "success");
                    response.put("sessionToken", sessionToken);
                    response.put("user", Map.of(
                            "id", principal.getName(),
                            "email", principal.getAttribute("email"),
                            "name", principal.getAttribute("name")
                    ));
                    return ResponseEntity.ok(response);
                })
                .onErrorReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to create session")));
    }

    @RequestMapping(value = "/logout", method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<Map<String, Object>>> logout(ServerWebExchange exchange) {
        return sessionService.invalidateSession(exchange)
                .then(Mono.just(ResponseEntity.ok(Map.of("status", "logged out"))));
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> status(
            @AuthenticationPrincipal OAuth2User principal,
            ServerWebExchange exchange) {
        
        if (principal == null) {
            return Mono.just(ResponseEntity.ok(Map.of("authenticated", false)));
        }

        return sessionService.getSessionInfo(exchange)
                .map(sessionInfo -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("authenticated", true);
                    response.put("user", Map.of(
                            "id", principal.getName(),
                            "email", principal.getAttribute("email"),
                            "name", principal.getAttribute("name")
                    ));
                    response.put("session", sessionInfo);
                    return ResponseEntity.ok(response);
                })
                .defaultIfEmpty(ResponseEntity.ok(Map.of("authenticated", false)));
    }

    @GetMapping("/success")
    public Mono<ResponseEntity<String>> success() {
        return Mono.just(ResponseEntity.ok(
                "<!DOCTYPE html><html><head><title>Authentication Success</title></head>" +
                "<body><h1>Authentication Successful</h1>" +
                "<p>You have been successfully authenticated. You can now close this window.</p>" +
                "<script>window.close();</script></body></html>"
        ));
    }
}