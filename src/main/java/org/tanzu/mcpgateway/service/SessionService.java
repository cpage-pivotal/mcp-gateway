package org.tanzu.mcpgateway.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class SessionService {

    private static final String SESSION_COOKIE_NAME = "MCP_SESSION";
    private static final String SESSION_PREFIX = "session:";
    private static final Duration SESSION_TIMEOUT = Duration.ofHours(24);

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Autowired
    private TokenService tokenService;

    public Mono<String> createSession(OAuth2User user, OAuth2AuthorizedClient authorizedClient, 
                                     ServerWebExchange exchange) {
        String sessionId = UUID.randomUUID().toString();
        String sessionKey = SESSION_PREFIX + sessionId;

        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("userId", user.getName());
        sessionData.put("email", user.getAttribute("email"));
        sessionData.put("name", user.getAttribute("name"));
        sessionData.put("createdAt", Instant.now().toString());
        sessionData.put("lastAccessedAt", Instant.now().toString());

        return tokenService.storeTokens(sessionId, authorizedClient)
                .then(redisTemplate.opsForHash().putAll(sessionKey, sessionData))
                .then(redisTemplate.expire(sessionKey, SESSION_TIMEOUT))
                .then(Mono.fromRunnable(() -> {
                    ResponseCookie sessionCookie = ResponseCookie.from(SESSION_COOKIE_NAME, sessionId)
                            .httpOnly(true)
                            .secure(true)
                            .sameSite("Lax")
                            .maxAge(SESSION_TIMEOUT)
                            .path("/")
                            .build();
                    exchange.getResponse().addCookie(sessionCookie);
                }))
                .thenReturn(sessionId);
    }

    public Mono<Void> invalidateSession(ServerWebExchange exchange) {
        return getSessionIdFromRequest(exchange)
                .flatMap(sessionId -> {
                    String sessionKey = SESSION_PREFIX + sessionId;
                    return tokenService.removeTokens(sessionId)
                            .then(redisTemplate.delete(sessionKey))
                            .then(Mono.fromRunnable(() -> {
                                ResponseCookie expiredCookie = ResponseCookie.from(SESSION_COOKIE_NAME, "")
                                        .httpOnly(true)
                                        .secure(true)
                                        .maxAge(0)
                                        .path("/")
                                        .build();
                                exchange.getResponse().addCookie(expiredCookie);
                            }));
                })
                .then();
    }

    public Mono<Map<String, Object>> getSessionInfo(ServerWebExchange exchange) {
        return getSessionIdFromRequest(exchange)
                .flatMap(sessionId -> {
                    String sessionKey = SESSION_PREFIX + sessionId;
                    return redisTemplate.opsForHash().entries(sessionKey)
                            .collectMap(entry -> (String) entry.getKey(), Map.Entry::getValue)
                            .flatMap(sessionData -> {
                                if (sessionData.isEmpty()) {
                                    return Mono.empty();
                                }
                                sessionData.put("lastAccessedAt", Instant.now().toString());
                                return redisTemplate.opsForHash().put(sessionKey, "lastAccessedAt", 
                                        sessionData.get("lastAccessedAt"))
                                        .then(redisTemplate.expire(sessionKey, SESSION_TIMEOUT))
                                        .thenReturn(sessionData);
                            });
                });
    }

    public Mono<String> getSessionIdFromRequest(ServerWebExchange exchange) {
        HttpCookie sessionCookie = exchange.getRequest().getCookies().getFirst(SESSION_COOKIE_NAME);
        if (sessionCookie != null && !sessionCookie.getValue().isEmpty()) {
            return Mono.just(sessionCookie.getValue());
        }
        
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String sessionId = authHeader.substring(7);
            return Mono.just(sessionId);
        }
        
        return Mono.empty();
    }

    public Mono<Boolean> isSessionValid(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Mono.just(false);
        }
        
        String sessionKey = SESSION_PREFIX + sessionId;
        return redisTemplate.hasKey(sessionKey);
    }

    public Mono<Map<String, Object>> getUserFromSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Mono.empty();
        }
        
        String sessionKey = SESSION_PREFIX + sessionId;
        return redisTemplate.opsForHash().entries(sessionKey)
                .collectMap(entry -> (String) entry.getKey(), Map.Entry::getValue)
                .filter(sessionData -> !sessionData.isEmpty());
    }
}