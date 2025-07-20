package org.tanzu.mcpgateway.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tanzu.mcpgateway.service.McpConnectionManager;
import org.tanzu.mcpgateway.service.McpProxyService;
import org.tanzu.mcpgateway.service.McpResilienceService;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/mcp/admin")
public class McpAdminController {

    @Autowired
    private McpProxyService mcpProxyService;

    @Autowired
    private McpConnectionManager connectionManager;

    @Autowired
    private McpResilienceService resilienceService;

    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> getOverallStatus() {
        return Mono.zip(
                mcpProxyService.getConnectionStats(),
                resilienceService.getResilienceStats()
        ).map(tuple -> {
            Map<String, Object> status = new HashMap<>();
            status.put("connections", tuple.getT1());
            status.put("resilience", tuple.getT2());
            status.put("timestamp", System.currentTimeMillis());
            status.put("status", "operational");
            return ResponseEntity.ok(status);
        });
    }

    @GetMapping("/connections")
    public Mono<ResponseEntity<Map<String, Object>>> getConnectionStats() {
        return connectionManager.getConnectionStats()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/resilience")
    public Mono<ResponseEntity<McpResilienceService.ResilienceStats>> getResilienceStats() {
        return resilienceService.getResilienceStats()
                .map(ResponseEntity::ok);
    }

    @PostMapping("/circuit-breaker/{serviceKey}/reset")
    public Mono<ResponseEntity<Map<String, Object>>> resetCircuitBreaker(@PathVariable String serviceKey) {
        return resilienceService.resetCircuitBreaker(serviceKey)
                .then(Mono.just(ResponseEntity.ok(Map.of(
                        "message", "Circuit breaker reset successfully",
                        "serviceKey", serviceKey
                ))));
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> getHealth() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "mcp-proxy",
                "timestamp", System.currentTimeMillis()
        )));
    }
}