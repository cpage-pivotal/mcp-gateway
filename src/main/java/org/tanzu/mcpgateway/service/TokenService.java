package org.tanzu.mcpgateway.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class TokenService {

    private static final String TOKEN_PREFIX = "tokens:";
    private static final Duration DEFAULT_TOKEN_EXPIRY = Duration.ofHours(1);

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    public Mono<Void> storeTokens(String sessionId, OAuth2AuthorizedClient authorizedClient) {
        String tokenKey = TOKEN_PREFIX + sessionId;
        
        OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
        OAuth2RefreshToken refreshToken = authorizedClient.getRefreshToken();
        
        Map<String, Object> tokenData = new HashMap<>();
        tokenData.put("accessToken", accessToken.getTokenValue());
        tokenData.put("tokenType", accessToken.getTokenType().getValue());
        tokenData.put("scope", String.join(",", accessToken.getScopes()));
        tokenData.put("issuedAt", accessToken.getIssuedAt().toString());
        tokenData.put("expiresAt", accessToken.getExpiresAt().toString());
        
        if (refreshToken != null) {
            tokenData.put("refreshToken", refreshToken.getTokenValue());
            if (refreshToken.getIssuedAt() != null) {
                tokenData.put("refreshTokenIssuedAt", refreshToken.getIssuedAt().toString());
            }
            if (refreshToken.getExpiresAt() != null) {
                tokenData.put("refreshTokenExpiresAt", refreshToken.getExpiresAt().toString());
            }
        }
        
        tokenData.put("clientRegistrationId", authorizedClient.getClientRegistration().getRegistrationId());
        tokenData.put("storedAt", Instant.now().toString());

        Duration expiry = calculateExpiry(accessToken);
        
        return redisTemplate.opsForHash().putAll(tokenKey, tokenData)
                .then(redisTemplate.expire(tokenKey, expiry))
                .then();
    }

    public Mono<Map<String, Object>> getTokens(String sessionId) {
        String tokenKey = TOKEN_PREFIX + sessionId;
        
        return redisTemplate.opsForHash().entries(tokenKey)
                .collectMap(entry -> (String) entry.getKey(), Map.Entry::getValue)
                .filter(tokenData -> !tokenData.isEmpty());
    }

    public Mono<String> getAccessToken(String sessionId) {
        return getTokens(sessionId)
                .map(tokenData -> (String) tokenData.get("accessToken"))
                .filter(token -> token != null && !token.isEmpty());
    }

    public Mono<String> getRefreshToken(String sessionId) {
        return getTokens(sessionId)
                .map(tokenData -> (String) tokenData.get("refreshToken"))
                .filter(token -> token != null && !token.isEmpty());
    }

    public Mono<Boolean> isTokenExpired(String sessionId) {
        return getTokens(sessionId)
                .map(tokenData -> {
                    String expiresAtStr = (String) tokenData.get("expiresAt");
                    if (expiresAtStr == null) {
                        return false;
                    }
                    Instant expiresAt = Instant.parse(expiresAtStr);
                    return Instant.now().isAfter(expiresAt.minusSeconds(60));
                })
                .defaultIfEmpty(true);
    }

    public Mono<Void> updateAccessToken(String sessionId, OAuth2AccessToken newAccessToken) {
        String tokenKey = TOKEN_PREFIX + sessionId;
        
        Map<String, Object> updates = new HashMap<>();
        updates.put("accessToken", newAccessToken.getTokenValue());
        updates.put("tokenType", newAccessToken.getTokenType().getValue());
        updates.put("scope", String.join(",", newAccessToken.getScopes()));
        updates.put("issuedAt", newAccessToken.getIssuedAt().toString());
        updates.put("expiresAt", newAccessToken.getExpiresAt().toString());
        updates.put("updatedAt", Instant.now().toString());

        Duration expiry = calculateExpiry(newAccessToken);
        
        return redisTemplate.opsForHash().putAll(tokenKey, updates)
                .then(redisTemplate.expire(tokenKey, expiry))
                .then();
    }

    public Mono<Void> removeTokens(String sessionId) {
        String tokenKey = TOKEN_PREFIX + sessionId;
        return redisTemplate.delete(tokenKey).then();
    }

    public Mono<Map<String, String>> getAuthHeaders(String sessionId) {
        return getAccessToken(sessionId)
                .map(accessToken -> {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Authorization", "Bearer " + accessToken);
                    return headers;
                })
                .defaultIfEmpty(Map.of());
    }

    private Duration calculateExpiry(OAuth2AccessToken accessToken) {
        if (accessToken.getExpiresAt() != null) {
            Duration tokenLifetime = Duration.between(Instant.now(), accessToken.getExpiresAt());
            return tokenLifetime.isNegative() ? DEFAULT_TOKEN_EXPIRY : tokenLifetime.plusMinutes(5);
        }
        return DEFAULT_TOKEN_EXPIRY;
    }
}