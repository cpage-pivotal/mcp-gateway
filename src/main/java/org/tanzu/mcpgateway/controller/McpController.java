package org.tanzu.mcpgateway.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.tanzu.mcpgateway.service.McpProxyService;
import org.tanzu.mcpgateway.service.SessionService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/mcp")
public class McpController {

    @Autowired
    private McpProxyService mcpProxyService;

    @Autowired
    private SessionService sessionService;

    @Value("${mcp.server.url:http://localhost:3000}")
    private String mcpServerUrl;

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sseProxy(ServerWebExchange exchange) {
        return sessionService.getSessionIdFromRequest(exchange)
                .flatMapMany(sessionId -> 
                    sessionService.isSessionValid(sessionId)
                            .flatMapMany(isValid -> {
                                if (!isValid) {
                                    return Flux.just(ServerSentEvent.<String>builder()
                                            .event("error")
                                            .data("{\"error\":\"Invalid or expired session\"}")
                                            .build());
                                }
                                return mcpProxyService.createSseProxy(sessionId, exchange);
                            })
                )
                .defaultIfEmpty(ServerSentEvent.<String>builder()
                        .event("error")
                        .data("{\"error\":\"Authentication required\"}")
                        .build());
    }

    @PostMapping("/message")
    public Mono<ResponseEntity<Object>> sendMessage(
            @RequestBody Map<String, Object> message,
            ServerWebExchange exchange) {
        
        return sessionService.getSessionIdFromRequest(exchange)
                .flatMap(sessionId -> 
                    sessionService.isSessionValid(sessionId)
                            .flatMap(isValid -> {
                                if (!isValid) {
                                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                            .body((Object) Map.of("error", "Invalid or expired session")));
                                }
                                return mcpProxyService.sendMessage(sessionId, message, exchange);
                            })
                )
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body((Object) Map.of("error", "Authentication required")));
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<Object>> getStatus(ServerWebExchange exchange) {
        return sessionService.getSessionIdFromRequest(exchange)
                .flatMap(sessionId -> 
                    sessionService.isSessionValid(sessionId)
                            .flatMap(isValid -> {
                                if (!isValid) {
                                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                            .body((Object) Map.of("error", "Invalid or expired session")));
                                }
                                return mcpProxyService.getServerStatus(sessionId);
                            })
                )
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body((Object) Map.of("error", "Authentication required")));
    }
}