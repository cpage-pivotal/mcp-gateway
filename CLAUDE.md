# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an OAuth2-authenticated Spring Cloud Gateway for the Model Context Protocol (MCP) that runs on Cloud Foundry. The gateway enables secure SSO authentication and proxies MCP requests to backend servers while maintaining user context.

## Architecture

- **Spring Boot 3.4.7** with Java 21
- **Spring Cloud Gateway** for request routing and proxying
- **OAuth2 authentication** via Google SSO
- **Redis session storage** for scalable session management
- **WebFlux reactive stack** for async/non-blocking operations
- **Cloud Foundry deployment** with health checks and scaling

Key components:
- `SecurityConfig.java` - OAuth2 and CORS security configuration
- `AuthController.java` - Authentication REST API endpoints (/auth/*)
- `SessionService.java` - Redis-based session management
- `TokenService.java` - OAuth2 token storage and retrieval
- `GatewayConfig.java` - Custom gateway routes

## Common Commands

### Build and Test
```bash
# Clean and compile
./mvnw clean compile

# Run tests
./mvnw test

# Package application
./mvnw clean package

# Run locally
./mvnw spring-boot:run
```

### Cloud Foundry Deployment
```bash
# Deploy to Cloud Foundry
cf push

# View application logs
cf logs mcp-gateway --recent

# Check application status
cf app mcp-gateway

# View service bindings
cf services
```

### Development
```bash
# Run with specific profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Debug mode
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
```

## Configuration

### Required Environment Variables (Production)
- `GOOGLE_OAUTH_CLIENT_ID` - Google OAuth2 client ID
- `GOOGLE_OAUTH_CLIENT_SECRET` - Google OAuth2 client secret
- `OAUTH_REDIRECT_URI` - OAuth2 callback URL (https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/callback)

### Service Dependencies
- **mcp-redis** (p-redis/shared-vm) - Session and token storage
- **mcp-gateway** (p.gateway/standard) - Gateway service features

### Key Endpoints
- `/auth/login` - OAuth2 login initiation
- `/auth/callback` - OAuth2 callback handler
- `/auth/logout` - Session termination
- `/auth/status` - Authentication status
- `/mcp/sse` - MCP SSE proxy endpoint (planned)
- `/actuator/health` - Health check

## Development Notes

- Uses WebFlux reactive programming model
- Session data stored in Redis for horizontal scaling
- OAuth2 tokens never exposed to client applications
- All communication secured via HTTPS in production
- CORS configured for cross-origin client access
- Health checks integrated with Cloud Foundry

## Current Status

Phase 2 COMPLETE: Core authentication functionality operational
- ✅ Google OAuth2 integration working
- ✅ Session management with Redis
- ✅ Token storage and retrieval
- 🔄 NEXT: Phase 3 - MCP SSE proxy implementation

Refer to DESIGN.md for complete architecture and implementation details.