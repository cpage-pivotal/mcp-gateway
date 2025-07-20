package org.tanzu.mcpgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class McpProxyService {

    private static final Logger logger = LoggerFactory.getLogger(McpProxyService.class);

    @Autowired
    private TokenService tokenService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private McpConnectionManager connectionManager;

    @Autowired
    private McpResilienceService resilienceService;

    @Value("${mcp.server.url:http://localhost:3000}")
    private String mcpServerUrl;

    @Value("${mcp.server.sse.path:/sse}")
    private String mcpSsePath;

    @Value("${mcp.server.message.path:/message}")
    private String mcpMessagePath;

    @Value("${mcp.connection.timeout:30000}")
    private long connectionTimeoutMs;

    @Value("${mcp.reconnect.attempts:3}")
    private int maxReconnectAttempts;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public McpProxyService() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public Flux<ServerSentEvent<String>> createSseProxy(String sessionId, ServerWebExchange exchange) {
        logger.info("Creating SSE proxy for session: {}", sessionId);

        return enrichRequestWithAuth(sessionId)
                .flatMapMany(authHeaders -> {
                    String connectionId = sessionId + "_" + Instant.now().toEpochMilli();
                    
                    return connectionManager.createConnection(sessionId, connectionId)
                            .flatMapMany(connection -> 
                                establishSseConnection(connection, authHeaders)
                                        .doOnSubscribe(subscription -> {
                                            logger.info("SSE connection established for session: {} with connection ID: {}", 
                                                       sessionId, connectionId);
                                        })
                                        .doOnCancel(() -> {
                                            logger.info("SSE connection cancelled for session: {}", sessionId);
                                            connectionManager.removeConnection(connectionId).subscribe();
                                        })
                                        .doOnComplete(() -> {
                                            logger.info("SSE connection completed for session: {}", sessionId);
                                            connectionManager.removeConnection(connectionId).subscribe();
                                        })
                                        .doOnError(error -> {
                                            logger.error("SSE connection error for session: {}", sessionId, error);
                                            connectionManager.removeConnection(connectionId).subscribe();
                                        })
                            );
                })
                .onErrorResume(error -> {
                    logger.error("Failed to create SSE proxy for session: {}", sessionId, error);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("{\"error\":\"Failed to establish connection to MCP server\"}")
                            .build());
                });
    }

    public Mono<ResponseEntity<Object>> sendMessage(String sessionId, 
                                                                Map<String, Object> message, 
                                                                ServerWebExchange exchange) {
        logger.info("Sending message for session: {}", sessionId);

        return enrichRequestWithAuth(sessionId)
                .flatMap(authHeaders -> 
                    enrichMessageWithUserContext(sessionId, message)
                            .flatMap(enrichedMessage -> {
                                Mono<Map> baseRequest = webClient.post()
                                        .uri(mcpServerUrl + mcpMessagePath)
                                        .headers(headers -> headers.setAll(authHeaders))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(BodyInserters.fromValue(enrichedMessage))
                                        .retrieve()
                                        .bodyToMono(Map.class)
                                        .timeout(Duration.ofMillis(connectionTimeoutMs));

                                return resilienceService.executeWithResilience("mcp-message-" + sessionId, baseRequest)
                                        .map(response -> ResponseEntity.ok((Object) response));
                            })
                )
                .onErrorResume(WebClientResponseException.Unauthorized.class, error -> {
                    logger.warn("Unauthorized request for session: {}", sessionId);
                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body((Object) Map.of("error", "Invalid authentication credentials")));
                })
                .onErrorResume(error -> {
                    logger.error("Failed to send message for session: {}", sessionId, error);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body((Object) Map.of("error", "Failed to send message to MCP server")));
                });
    }

    public Mono<ResponseEntity<Object>> getServerStatus(String sessionId) {
        logger.info("Getting server status for session: {}", sessionId);

        return enrichRequestWithAuth(sessionId)
                .flatMap(authHeaders -> {
                    Mono<Map> baseRequest = webClient.get()
                            .uri(mcpServerUrl + "/status")
                            .headers(headers -> headers.setAll(authHeaders))
                            .retrieve()
                            .bodyToMono(Map.class)
                            .timeout(Duration.ofMillis(connectionTimeoutMs));

                    return resilienceService.executeWithResilience("mcp-status-" + sessionId, baseRequest)
                            .map(response -> ResponseEntity.ok((Object) response));
                })
                .onErrorResume(error -> {
                    logger.error("Failed to get server status for session: {}", sessionId, error);
                    return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body((Object) Map.of("error", "MCP server unavailable")));
                });
    }

    private Flux<ServerSentEvent<String>> establishSseConnection(McpConnectionManager.SseConnection connection, 
                                                               Map<String, String> authHeaders) {
        String connectionId = connection.getConnectionId();
        String sessionId = connection.getSessionId();

        Flux<ServerSentEvent<String>> baseFlux = webClient.get()
                .uri(mcpServerUrl + mcpSsePath)
                .headers(headers -> headers.setAll(authHeaders))
                .retrieve()
                .bodyToFlux(String.class)
                .map(data -> {
                    connectionManager.updateConnectionActivity(connectionId).subscribe();
                    return ServerSentEvent.<String>builder()
                            .data(data)
                            .build();
                })
                .timeout(Duration.ofMillis(connectionTimeoutMs));

        Flux<ServerSentEvent<String>> sseFlux = resilienceService
                .executeWithResilienceFlux("mcp-sse-" + sessionId, baseFlux);

        // Set the disposable on the connection for cleanup
        connection.setDisposable(sseFlux.subscribe());

        return sseFlux;
    }

    private Mono<Map<String, String>> enrichRequestWithAuth(String sessionId) {
        return tokenService.getAuthHeaders(sessionId)
                .flatMap(authHeaders -> 
                    sessionService.getUserFromSession(sessionId)
                            .map(userData -> {
                                Map<String, String> enrichedHeaders = new HashMap<>(authHeaders);
                                enrichedHeaders.put("X-User-ID", (String) userData.get("userId"));
                                enrichedHeaders.put("X-User-Email", (String) userData.get("email"));
                                enrichedHeaders.put("X-User-Name", (String) userData.get("name"));
                                enrichedHeaders.put("X-Session-ID", sessionId);
                                enrichedHeaders.put("Content-Type", "application/json");
                                return enrichedHeaders;
                            })
                            .defaultIfEmpty(authHeaders)
                );
    }

    private Mono<Map<String, Object>> enrichMessageWithUserContext(String sessionId, 
                                                                   Map<String, Object> message) {
        return sessionService.getUserFromSession(sessionId)
                .map(userData -> {
                    Map<String, Object> enrichedMessage = new HashMap<>(message);
                    enrichedMessage.put("user", Map.of(
                            "id", userData.get("userId"),
                            "email", userData.get("email"),
                            "name", userData.get("name")
                    ));
                    enrichedMessage.put("sessionId", sessionId);
                    enrichedMessage.put("timestamp", Instant.now().toString());
                    return enrichedMessage;
                })
                .defaultIfEmpty(message);
    }

    public Mono<Map<String, Object>> getConnectionStats() {
        return connectionManager.getConnectionStats();
    }
}