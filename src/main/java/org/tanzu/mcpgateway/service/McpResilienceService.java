package org.tanzu.mcpgateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

@Service
public class McpResilienceService {

    private static final Logger logger = LoggerFactory.getLogger(McpResilienceService.class);

    @Value("${mcp.resilience.circuit.failure.threshold:5}")
    private int circuitBreakerFailureThreshold;

    @Value("${mcp.resilience.circuit.timeout:30000}")
    private long circuitBreakerTimeoutMs;

    @Value("${mcp.resilience.circuit.success.threshold:3}")
    private int circuitBreakerSuccessThreshold;

    @Value("${mcp.resilience.retry.max.attempts:3}")
    private int maxRetryAttempts;

    @Value("${mcp.resilience.retry.base.delay:1000}")
    private long baseRetryDelayMs;

    @Value("${mcp.resilience.retry.max.delay:10000}")
    private long maxRetryDelayMs;

    private final ConcurrentHashMap<String, CircuitBreakerState> circuitBreakerStates;
    private final AtomicLong totalRetryAttempts;
    private final AtomicLong totalCircuitBreakerTrips;

    public McpResilienceService() {
        this.circuitBreakerStates = new ConcurrentHashMap<>();
        this.totalRetryAttempts = new AtomicLong(0);
        this.totalCircuitBreakerTrips = new AtomicLong(0);
    }

    public <T> Mono<T> executeWithResilience(String serviceKey, Mono<T> operation) {
        return checkCircuitBreaker(serviceKey)
                .flatMap(allowed -> {
                    if (!allowed) {
                        return Mono.error(new CircuitBreakerOpenException("Circuit breaker is open for service: " + serviceKey));
                    }
                    
                    return operation
                            .doOnSuccess(result -> recordSuccess(serviceKey))
                            .doOnError(error -> recordFailure(serviceKey, error))
                            .retryWhen(createRetrySpec(serviceKey));
                });
    }

    public <T> Flux<T> executeWithResilienceFlux(String serviceKey, Flux<T> operation) {
        return checkCircuitBreaker(serviceKey)
                .flatMapMany(allowed -> {
                    if (!allowed) {
                        return Flux.error(new CircuitBreakerOpenException("Circuit breaker is open for service: " + serviceKey));
                    }
                    
                    return operation
                            .doOnNext(item -> recordSuccess(serviceKey))
                            .doOnError(error -> recordFailure(serviceKey, error))
                            .retryWhen(createRetrySpec(serviceKey));
                });
    }

    private Mono<Boolean> checkCircuitBreaker(String serviceKey) {
        return Mono.fromCallable(() -> {
            CircuitBreakerState state = circuitBreakerStates.computeIfAbsent(serviceKey, 
                k -> new CircuitBreakerState());
            
            synchronized (state) {
                switch (state.getState()) {
                    case CLOSED:
                        return true;
                    case OPEN:
                        if (Instant.now().isAfter(state.getNextAttemptTime())) {
                            state.setState(CircuitState.HALF_OPEN);
                            logger.info("Circuit breaker for {} transitioning to HALF_OPEN", serviceKey);
                            return true;
                        }
                        return false;
                    case HALF_OPEN:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void recordSuccess(String serviceKey) {
        CircuitBreakerState state = circuitBreakerStates.get(serviceKey);
        if (state != null) {
            synchronized (state) {
                switch (state.getState()) {
                    case HALF_OPEN:
                        state.incrementSuccessCount();
                        if (state.getSuccessCount() >= circuitBreakerSuccessThreshold) {
                            state.setState(CircuitState.CLOSED);
                            state.reset();
                            logger.info("Circuit breaker for {} closed after successful recovery", serviceKey);
                        }
                        break;
                    case CLOSED:
                        state.reset();
                        break;
                }
            }
        }
    }

    private void recordFailure(String serviceKey, Throwable error) {
        CircuitBreakerState state = circuitBreakerStates.computeIfAbsent(serviceKey, 
            k -> new CircuitBreakerState());
        
        synchronized (state) {
            state.incrementFailureCount();
            
            if (state.getFailureCount() >= circuitBreakerFailureThreshold) {
                state.setState(CircuitState.OPEN);
                state.setNextAttemptTime(Instant.now().plusMillis(circuitBreakerTimeoutMs));
                totalCircuitBreakerTrips.incrementAndGet();
                logger.warn("Circuit breaker for {} opened due to {} failures. Error: {}", 
                           serviceKey, state.getFailureCount(), error.getMessage());
            }
        }
    }

    private Retry createRetrySpec(String serviceKey) {
        return Retry.backoff(maxRetryAttempts, Duration.ofMillis(baseRetryDelayMs))
                .maxBackoff(Duration.ofMillis(maxRetryDelayMs))
                .jitter(0.5)
                .filter(this::isRetryableError)
                .doBeforeRetry(retrySignal -> {
                    totalRetryAttempts.incrementAndGet();
                    logger.info("Retrying operation for service: {}, attempt: {}, delay: {}ms, error: {}", 
                               serviceKey, 
                               retrySignal.totalRetries() + 1,
                               retrySignal.totalRetriesInARow() * baseRetryDelayMs,
                               retrySignal.failure().getMessage());
                })
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                    logger.error("Retry exhausted for service: {} after {} attempts", 
                                serviceKey, retrySignal.totalRetries());
                    return retrySignal.failure();
                });
    }

    private boolean isRetryableError(Throwable throwable) {
        // Don't retry authentication errors
        if (throwable instanceof WebClientResponseException) {
            WebClientResponseException wcre = (WebClientResponseException) throwable;
            HttpStatus status = (HttpStatus) wcre.getStatusCode();
            
            // Don't retry 4xx client errors except for specific cases
            if (status.is4xxClientError()) {
                return status == HttpStatus.REQUEST_TIMEOUT || 
                       status == HttpStatus.TOO_MANY_REQUESTS;
            }
            
            // Retry 5xx server errors
            return status.is5xxServerError();
        }
        
        // Don't retry circuit breaker errors
        if (throwable instanceof CircuitBreakerOpenException) {
            return false;
        }
        
        // Retry connection and timeout errors
        return throwable instanceof java.net.ConnectException ||
               throwable instanceof java.util.concurrent.TimeoutException ||
               throwable.getCause() instanceof java.net.ConnectException;
    }

    public Mono<ResilienceStats> getResilienceStats() {
        return Mono.fromCallable(() -> {
            ResilienceStats stats = new ResilienceStats();
            stats.setTotalRetryAttempts(totalRetryAttempts.get());
            stats.setTotalCircuitBreakerTrips(totalCircuitBreakerTrips.get());
            stats.setActiveCircuitBreakers(circuitBreakerStates.size());
            
            circuitBreakerStates.forEach((key, state) -> {
                CircuitBreakerInfo info = new CircuitBreakerInfo();
                info.setServiceKey(key);
                info.setState(state.getState());
                info.setFailureCount(state.getFailureCount());
                info.setSuccessCount(state.getSuccessCount());
                info.setNextAttemptTime(state.getNextAttemptTime());
                stats.getCircuitBreakers().put(key, info);
            });
            
            return stats;
        });
    }

    public Mono<Void> resetCircuitBreaker(String serviceKey) {
        return Mono.fromRunnable(() -> {
            CircuitBreakerState state = circuitBreakerStates.get(serviceKey);
            if (state != null) {
                synchronized (state) {
                    state.setState(CircuitState.CLOSED);
                    state.reset();
                    logger.info("Circuit breaker for {} manually reset", serviceKey);
                }
            }
        });
    }

    // Inner classes for state management
    private static class CircuitBreakerState {
        private CircuitState state = CircuitState.CLOSED;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private volatile Instant nextAttemptTime = Instant.now();

        public CircuitState getState() { return state; }
        public void setState(CircuitState state) { this.state = state; }
        public int getFailureCount() { return failureCount.get(); }
        public int getSuccessCount() { return successCount.get(); }
        public void incrementFailureCount() { failureCount.incrementAndGet(); }
        public void incrementSuccessCount() { successCount.incrementAndGet(); }
        public Instant getNextAttemptTime() { return nextAttemptTime; }
        public void setNextAttemptTime(Instant nextAttemptTime) { this.nextAttemptTime = nextAttemptTime; }
        
        public void reset() {
            failureCount.set(0);
            successCount.set(0);
        }
    }

    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }

    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }

    // DTOs for statistics
    public static class ResilienceStats {
        private long totalRetryAttempts;
        private long totalCircuitBreakerTrips;
        private int activeCircuitBreakers;
        private ConcurrentHashMap<String, CircuitBreakerInfo> circuitBreakers = new ConcurrentHashMap<>();

        // Getters and setters
        public long getTotalRetryAttempts() { return totalRetryAttempts; }
        public void setTotalRetryAttempts(long totalRetryAttempts) { this.totalRetryAttempts = totalRetryAttempts; }
        public long getTotalCircuitBreakerTrips() { return totalCircuitBreakerTrips; }
        public void setTotalCircuitBreakerTrips(long totalCircuitBreakerTrips) { this.totalCircuitBreakerTrips = totalCircuitBreakerTrips; }
        public int getActiveCircuitBreakers() { return activeCircuitBreakers; }
        public void setActiveCircuitBreakers(int activeCircuitBreakers) { this.activeCircuitBreakers = activeCircuitBreakers; }
        public ConcurrentHashMap<String, CircuitBreakerInfo> getCircuitBreakers() { return circuitBreakers; }
    }

    public static class CircuitBreakerInfo {
        private String serviceKey;
        private CircuitState state;
        private int failureCount;
        private int successCount;
        private Instant nextAttemptTime;

        // Getters and setters
        public String getServiceKey() { return serviceKey; }
        public void setServiceKey(String serviceKey) { this.serviceKey = serviceKey; }
        public CircuitState getState() { return state; }
        public void setState(CircuitState state) { this.state = state; }
        public int getFailureCount() { return failureCount; }
        public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
        public int getSuccessCount() { return successCount; }
        public void setSuccessCount(int successCount) { this.successCount = successCount; }
        public Instant getNextAttemptTime() { return nextAttemptTime; }
        public void setNextAttemptTime(Instant nextAttemptTime) { this.nextAttemptTime = nextAttemptTime; }
    }
}