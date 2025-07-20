package org.tanzu.mcpgateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class McpConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger(McpConnectionManager.class);

    @Value("${mcp.connection.idle.timeout:300000}")
    private long idleTimeoutMs;

    @Value("${mcp.connection.max.per.session:5}")
    private int maxConnectionsPerSession;

    @Value("${mcp.connection.cleanup.interval:60000}")
    private long cleanupIntervalMs;

    private final Map<String, SseConnection> activeConnections;
    private final Map<String, AtomicLong> sessionConnectionCounts;
    private final AtomicLong totalConnectionsCreated;
    private final AtomicLong currentActiveConnections;

    public McpConnectionManager() {
        this.activeConnections = new ConcurrentHashMap<>();
        this.sessionConnectionCounts = new ConcurrentHashMap<>();
        this.totalConnectionsCreated = new AtomicLong(0);
        this.currentActiveConnections = new AtomicLong(0);
    }

    public Mono<SseConnection> createConnection(String sessionId, String connectionId) {
        return Mono.fromCallable(() -> {
            // Check session connection limit
            AtomicLong sessionCount = sessionConnectionCounts.computeIfAbsent(sessionId, k -> new AtomicLong(0));
            if (sessionCount.get() >= maxConnectionsPerSession) {
                throw new IllegalStateException("Maximum connections per session exceeded: " + maxConnectionsPerSession);
            }

            SseConnection connection = new SseConnection(connectionId, sessionId, Instant.now());
            activeConnections.put(connectionId, connection);
            sessionCount.incrementAndGet();
            totalConnectionsCreated.incrementAndGet();
            currentActiveConnections.incrementAndGet();

            logger.info("Created SSE connection: {} for session: {}, total active: {}", 
                       connectionId, sessionId, currentActiveConnections.get());

            return connection;
        });
    }

    public Mono<Void> removeConnection(String connectionId) {
        return Mono.fromRunnable(() -> {
            SseConnection connection = activeConnections.remove(connectionId);
            if (connection != null) {
                String sessionId = connection.getSessionId();
                AtomicLong sessionCount = sessionConnectionCounts.get(sessionId);
                if (sessionCount != null) {
                    sessionCount.decrementAndGet();
                    if (sessionCount.get() <= 0) {
                        sessionConnectionCounts.remove(sessionId);
                    }
                }
                currentActiveConnections.decrementAndGet();

                logger.info("Removed SSE connection: {} for session: {}, total active: {}", 
                           connectionId, sessionId, currentActiveConnections.get());
            }
        });
    }

    public Mono<Void> updateConnectionActivity(String connectionId) {
        return Mono.fromRunnable(() -> {
            SseConnection connection = activeConnections.get(connectionId);
            if (connection != null) {
                connection.updateLastActivity();
                logger.trace("Updated activity for connection: {}", connectionId);
            }
        });
    }

    public Flux<SseConnection> getConnectionsForSession(String sessionId) {
        return Flux.fromIterable(activeConnections.values())
                .filter(connection -> sessionId.equals(connection.getSessionId()));
    }

    public Mono<Boolean> isConnectionActive(String connectionId) {
        return Mono.fromCallable(() -> activeConnections.containsKey(connectionId));
    }

    public Mono<Map<String, Object>> getConnectionStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = Map.of(
                    "totalConnectionsCreated", totalConnectionsCreated.get(),
                    "currentActiveConnections", currentActiveConnections.get(),
                    "sessionsWithConnections", sessionConnectionCounts.size(),
                    "connectionDetails", activeConnections.values().stream()
                            .map(conn -> Map.of(
                                    "connectionId", conn.getConnectionId(),
                                    "sessionId", conn.getSessionId(),
                                    "createdAt", conn.getCreatedAt().toString(),
                                    "lastActivity", conn.getLastActivity().toString(),
                                    "idleTime", Duration.between(conn.getLastActivity(), Instant.now()).toMillis()
                            )).toList()
            );
            return stats;
        });
    }

    @Scheduled(fixedDelayString = "${mcp.connection.cleanup.interval:60000}")
    public void cleanupIdleConnections() {
        Instant cutoff = Instant.now().minusMillis(idleTimeoutMs);
        
        activeConnections.values().stream()
                .filter(connection -> connection.getLastActivity().isBefore(cutoff))
                .forEach(connection -> {
                    logger.info("Cleaning up idle connection: {} for session: {}, idle for: {}ms", 
                               connection.getConnectionId(), 
                               connection.getSessionId(),
                               Duration.between(connection.getLastActivity(), Instant.now()).toMillis());
                    
                    removeConnection(connection.getConnectionId()).subscribe();
                    
                    // Dispose the connection if it has a disposable
                    if (connection.getDisposable() != null && !connection.getDisposable().isDisposed()) {
                        connection.getDisposable().dispose();
                    }
                });
        
        logger.debug("Connection cleanup completed. Active connections: {}", currentActiveConnections.get());
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down MCP Connection Manager. Active connections: {}", currentActiveConnections.get());
        
        activeConnections.values().forEach(connection -> {
            if (connection.getDisposable() != null && !connection.getDisposable().isDisposed()) {
                connection.getDisposable().dispose();
            }
        });
        
        activeConnections.clear();
        sessionConnectionCounts.clear();
        
        logger.info("MCP Connection Manager shutdown completed");
    }

    public static class SseConnection {
        private final String connectionId;
        private final String sessionId;
        private final Instant createdAt;
        private volatile Instant lastActivity;
        private volatile Disposable disposable;

        public SseConnection(String connectionId, String sessionId, Instant createdAt) {
            this.connectionId = connectionId;
            this.sessionId = sessionId;
            this.createdAt = createdAt;
            this.lastActivity = createdAt;
        }

        public void updateLastActivity() {
            this.lastActivity = Instant.now();
        }

        public void setDisposable(Disposable disposable) {
            this.disposable = disposable;
        }

        public String getConnectionId() { return connectionId; }
        public String getSessionId() { return sessionId; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getLastActivity() { return lastActivity; }
        public Disposable getDisposable() { return disposable; }
    }
}