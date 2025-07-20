package org.tanzu.mcpgateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.tanzu.mcpgateway.service.SessionService;
import org.tanzu.mcpgateway.service.TokenService;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class McpAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(McpAuthenticationFilter.class);

    @Autowired
    private SessionService sessionService;

    @Autowired
    private TokenService tokenService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // Only apply to MCP endpoints
        if (!path.startsWith("/mcp/")) {
            return chain.filter(exchange);
        }

        logger.debug("Applying MCP authentication filter to path: {}", path);

        return sessionService.getSessionIdFromRequest(exchange)
                .flatMap(sessionId -> 
                    sessionService.isSessionValid(sessionId)
                            .flatMap(isValid -> {
                                if (!isValid) {
                                    logger.warn("Invalid session for MCP request: {}", sessionId);
                                    return chain.filter(exchange);
                                }
                                return enrichRequestWithAuthHeaders(exchange, sessionId, chain);
                            })
                )
                .switchIfEmpty(Mono.defer(() -> {
                    logger.warn("No session found for MCP request to path: {}", path);
                    return chain.filter(exchange);
                }));
    }

    private Mono<Void> enrichRequestWithAuthHeaders(ServerWebExchange exchange, 
                                                   String sessionId, 
                                                   GatewayFilterChain chain) {
        return tokenService.getAuthHeaders(sessionId)
                .flatMap(authHeaders -> 
                    sessionService.getUserFromSession(sessionId)
                            .map(userData -> {
                                HttpHeaders enrichedHeaders = new HttpHeaders();
                                enrichedHeaders.putAll(exchange.getRequest().getHeaders());
                                
                                // Add OAuth2 authorization header
                                authHeaders.forEach(enrichedHeaders::add);
                                
                                // Add user context headers
                                enrichedHeaders.add("X-User-ID", (String) userData.get("userId"));
                                enrichedHeaders.add("X-User-Email", (String) userData.get("email"));
                                enrichedHeaders.add("X-User-Name", (String) userData.get("name"));
                                enrichedHeaders.add("X-Session-ID", sessionId);
                                
                                // Add request metadata
                                enrichedHeaders.add("X-Gateway-Request-ID", 
                                                   exchange.getRequest().getId());
                                enrichedHeaders.add("X-Forwarded-For", 
                                                   getClientIp(exchange));
                                
                                logger.debug("Enriched MCP request for session: {} with auth headers", sessionId);
                                
                                ServerHttpRequest enrichedRequest = exchange.getRequest()
                                        .mutate()
                                        .headers(headers -> {
                                            headers.clear();
                                            headers.addAll(enrichedHeaders);
                                        })
                                        .build();
                                
                                return exchange.mutate()
                                        .request(enrichedRequest)
                                        .build();
                            })
                            .defaultIfEmpty(exchange)
                )
                .flatMap(enrichedExchange -> chain.filter(enrichedExchange))
                .onErrorResume(error -> {
                    logger.error("Failed to enrich MCP request for session: {}", sessionId, error);
                    return chain.filter(exchange);
                });
    }

    private String getClientIp(ServerWebExchange exchange) {
        String xForwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return exchange.getRequest().getRemoteAddress() != null 
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    @Override
    public int getOrder() {
        // Execute after authentication but before routing
        return -100;
    }
}