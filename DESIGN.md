# OAuth2-Authenticated MCP on Cloud Foundry: Design and Implementation Plan

## Executive Summary

This document outlines the design and implementation plan for creating a secure, OAuth2-authenticated Model Context Protocol (MCP) environment on Cloud Foundry. The solution enables users to authenticate via SSO and execute MCP tools with their specific identity and permissions, using GitHub as the demonstration service.

## Architecture Overview

### Components

1. **Spring Cloud Gateway** (Cloud Foundry App)
   - Serves as the entry point for all client requests
   - Handles OAuth2/SSO authentication flow
   - Proxies authenticated requests to the MCP Server
   - Manages SSE connections for MCP protocol

2. **GitHub MCP Server** (Cloud Foundry App)
   - Runs the github-mcp-server with SSE transport
   - Executes GitHub operations using authenticated user context
   - Handles MCP protocol requests (initialize, tools/list, tools/call)

3. **OAuth2 Provider** (Google OAuth2)
   - Provides SSO authentication via Google
   - Issues OAuth2 tokens for authenticated users
   - Maps to user identities for GitHub integration

4. **MCP Client** (User's Chat Application)
   - Connects to Spring Cloud Gateway endpoints
   - Sends MCP protocol requests
   - Displays results to users

### High-Level Flow

```
User → MCP Client → Spring Cloud Gateway → GitHub MCP Server → GitHub API
         ↑                    ↓
         └─── Google OAuth2 ←
```

## Detailed Design

### Authentication Flow

1. **Initial Authentication**
   - User accesses `/auth/login` endpoint on Spring Cloud Gateway
   - Gateway redirects to Google OAuth2 provider
   - User authenticates and authorizes the application
   - Google OAuth2 redirects back to `https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/callback`
   - Gateway exchanges code for access token and refresh token
   - Gateway creates a session and returns session token to client

2. **Token Management**
   - Spring Cloud Gateway maintains token store (Redis via mcp-redis service)
   - Maps session tokens to OAuth2 access tokens
   - Handles token refresh automatically
   - Validates token expiry and refreshes as needed

### MCP Protocol Integration

1. **SSE Connection Establishment**
   - Client connects to `/mcp/sse` endpoint with session token
   - Gateway validates session and retrieves associated OAuth2 token
   - Gateway establishes SSE connection to GitHub MCP Server
   - Injects authentication headers into upstream requests

2. **Request Flow**
   - Client sends MCP requests via SSE connection
   - Gateway intercepts and enriches requests with:
     - User identity information
     - OAuth2 access token
     - Additional security headers
   - Gateway forwards enriched requests to MCP Server

3. **Response Flow**
   - MCP Server processes requests using provided identity
   - Server sends responses back via SSE
   - Gateway forwards responses to client unchanged

### Security Considerations

1. **Token Security**
   - Never expose OAuth2 tokens to client
   - Use secure session tokens for client-gateway communication
   - Implement token rotation and expiry
   - Store tokens encrypted at rest in mcp-redis

2. **Connection Security**
   - All connections use HTTPS/WSS
   - Implement CORS policies
   - Rate limiting per user/session
   - Request signing between Gateway and MCP Server

3. **Authorization**
   - Gateway performs initial authorization checks
   - MCP Server validates permissions for each operation
   - Implement principle of least privilege
   - Audit logging for all operations

## Implementation Plan

### Phase 1: Infrastructure Setup ✅ **COMPLETED**

1. **Cloud Foundry Preparation** ✅ **COMPLETED**
   - ✅ Create CF space and configure quotas
   - ✅ Set up required services:
     - ✅ **mcp-redis** (p-redis with shared-vm plan) for session/token storage
     - ✅ **mcp-gateway** (p.gateway with standard plan) for Spring Cloud Gateway service

2. **OAuth2 Provider Configuration** ✅ **COMPLETED**
   - ✅ Register application with Google OAuth2 provider
   - ✅ Configure OAuth consent screen with Google Cloud Console
   - ✅ Create OAuth2 credentials (Client ID and Client Secret obtained)
   - ✅ Configure redirect URLs: `https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/callback`
   - ✅ Set up required scopes: `openid`, `email`, `profile`

**Phase 1 Summary:**
- ✅ Cloud Foundry environment fully configured with spaces, quotas, and service instances
- ✅ Google OAuth2 application registered and configured
- ✅ Service instances ready for application binding:
  - `mcp-redis`: Ready for session storage, OAuth2 token caching, and user context management
  - `mcp-gateway`: Ready for authentication flow, request routing, and SSE connection proxying
- ✅ Production domain configured: `mcp-gateway.apps.tas-ndc.kuhn-labs.com`

### Phase 2: Spring Cloud Gateway Development ✅ **COMPLETED**

1. **Core Gateway Setup** ✅ **COMPLETED**
   - ✅ Initialize Spring Boot project with Cloud Gateway starter
   - ✅ Configure CF deployment manifest for `mcp-gateway.apps.tas-ndc.kuhn-labs.com`
   - ✅ Bind to mcp-redis and mcp-gateway services
   - ✅ Implement health checks and monitoring endpoints

2. **Authentication Module** ✅ **COMPLETED**
   - ✅ Implement Google OAuth2 client configuration
   - ✅ Create `/auth/login`, `/auth/callback`, `/auth/logout`, and `/auth/status` endpoints  
   - ✅ Develop session management system using mcp-redis
   - ✅ Build token storage and retrieval logic
   - ✅ Configure CORS for client applications

3. **MCP Proxy Module** 🔄 **NEXT**
   - Implement SSE proxy capability for `/mcp/sse` endpoint
   - Create request enrichment filters to inject authentication headers
   - Build connection management for long-lived SSE connections
   - Handle connection resilience and reconnection logic

**Phase 2.1 Summary (Core Gateway Setup):**
- ✅ Spring Boot project configured with Spring Cloud Gateway, OAuth2 Client, Redis Reactive, Actuator, WebFlux, and Spring Session Redis dependencies
- ✅ Cloud Foundry manifest.yml created with service bindings to mcp-redis and mcp-gateway services
- ✅ Application configuration established with gateway routes, OAuth2 client settings, Redis session storage, and actuator endpoints
- ✅ Health monitoring implemented with custom health indicator, gateway configuration, and info contributor
- ✅ Project structure ready for authentication module development

**Phase 2.2 Summary (Authentication Module):**
- ✅ Complete Google OAuth2 authentication flow implemented and tested
- ✅ Session management with Redis-backed secure session storage
- ✅ Token service for OAuth2 access token and refresh token management
- ✅ Authentication endpoints providing JSON API responses
- ✅ CORS configuration for cross-origin client access
- ✅ Comprehensive security configuration with WebFlux Security
- ✅ Integration tested with real Google OAuth2 credentials

**Key Implementation Components:**
- `SecurityConfig.java` - OAuth2 and CORS security configuration
- `AuthController.java` - Authentication REST API endpoints
- `SessionService.java` - Redis-based session management
- `TokenService.java` - OAuth2 token storage and retrieval
- `RedisConfig.java` - Reactive Redis template configuration

**Key Configuration Values:**
```yaml
# Google OAuth2 Configuration
GOOGLE_OAUTH_CLIENT_ID: [obtained from Google Cloud Console]
GOOGLE_OAUTH_CLIENT_SECRET: [obtained from Google Cloud Console]
OAUTH_REDIRECT_URI: http://localhost:8080/login/oauth2/code/google (local) | https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/login/oauth2/code/google (production)

# Cloud Foundry Application URL
CF_APP_URL: https://mcp-gateway.apps.tas-ndc.kuhn-labs.com
```

### Phase 3: GitHub MCP Server Integration (Week 4)

1. **MCP Server Deployment**
   - Package github-mcp-server for CF deployment
   - Configure SSE transport settings
   - Set up environment for dynamic authentication

2. **Authentication Integration**
   - Modify MCP server to accept auth headers from gateway
   - Implement GitHub API client with dynamic tokens
   - Create user context management
   - Test with various permission levels

### Phase 4: End-to-End Integration (Week 5)

1. **Integration Testing**
   - Test complete authentication flow using Google OAuth2
   - Verify MCP protocol compliance
   - Test various GitHub operations with user context
   - Validate permission enforcement

2. **Performance Optimization**
   - Load testing for concurrent users
   - SSE connection pooling optimization
   - Token caching strategies (leveraging mcp-redis)
   - Response time optimization

### Phase 5: Production Readiness (Week 6)

1. **Monitoring and Observability**
   - Implement comprehensive logging
   - Set up metrics collection
   - Create dashboards for key metrics
   - Configure alerting rules

2. **Documentation and Training**
   - API documentation
   - Deployment runbooks
   - Troubleshooting guides
   - User documentation

## Technical Specifications

### Spring Cloud Gateway Configuration

```yaml
Key Routes:
- /auth/login: Google OAuth2 login initiation
- /auth/callback: Google OAuth2 callback handler (https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/callback)
- /auth/logout: Session termination
- /mcp/sse: SSE proxy to MCP Server
- /health: Health check endpoint

Required Service Bindings:
- mcp-redis: Session and token storage
- mcp-gateway: Gateway service features

Environment Variables:
- GOOGLE_OAUTH_CLIENT_ID: Google OAuth2 client ID
- GOOGLE_OAUTH_CLIENT_SECRET: Google OAuth2 client secret  
- OAUTH_REDIRECT_URI: https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/callback
```

### MCP Server Modifications

1. **Authentication Header Processing**
   - Accept `Authorization` header with Bearer token
   - Extract user identity from custom headers
   - Initialize GitHub client with provided token

2. **Context Propagation**
   - Maintain user context throughout request lifecycle
   - Pass context to all tool executions
   - Include user identity in responses

### Cloud Foundry Deployment

1. **Gateway Manifest**
   - Memory: 1GB minimum
   - Instances: 2+ for high availability
   - Services: mcp-redis, mcp-gateway
   - Environment variables for Google OAuth2 config
   - Domain: mcp-gateway.apps.tas-ndc.kuhn-labs.com

2. **MCP Server Manifest**
   - Memory: 512MB minimum
   - Instances: 2+ for high availability
   - Health check configuration
   - SSE-specific timeout settings

## Current Infrastructure Status

### ✅ Completed Service Instances
- **mcp-redis**
  - Service: p-redis
  - Plan: shared-vm
  - Purpose: Session storage, OAuth2 token caching, user context management
  - Status: Ready for application binding

- **mcp-gateway**
  - Service: p.gateway
  - Plan: standard
  - Purpose: Spring Cloud Gateway service features
  - Status: Ready for application binding

### ✅ Completed OAuth2 Setup
- **Google Cloud Console Configuration**
  - ✅ OAuth consent screen configured
  - ✅ OAuth2 credentials created (Client ID and Client Secret obtained)
  - ✅ Redirect URI configured: `https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/callback`
  - ✅ Scopes configured: `openid`, `email`, `profile`

### ✅ Ready for Phase 3
- Phase 2 Spring Cloud Gateway development with authentication COMPLETED
- Full OAuth2 authentication flow working with Google SSO
- Session management operational using mcp-redis
- Ready to begin GitHub MCP Server integration and SSE proxy development

## Risk Mitigation

### Technical Risks

1. **SSE Connection Stability**
   - Risk: Long-lived connections may drop
   - Mitigation: Implement reconnection logic, connection pooling

2. **Token Expiration**
   - Risk: Operations fail due to expired tokens
   - Mitigation: Proactive token refresh using mcp-redis cache, graceful error handling

3. **Performance at Scale**
   - Risk: Gateway becomes bottleneck
   - Mitigation: Horizontal scaling, caching via mcp-redis, connection pooling

### Security Risks

1. **Token Leakage**
   - Risk: OAuth2 tokens exposed to clients
   - Mitigation: Strict token isolation using mcp-redis, session-based architecture

2. **Unauthorized Access**
   - Risk: Users access resources beyond permissions
   - Mitigation: Multi-layer authorization, audit logging

## Success Criteria

1. **Functional Requirements**
   - Users can authenticate via Google SSO
   - MCP operations execute with correct user context
   - All GitHub operations respect user permissions
   - SSE connections remain stable

2. **Non-Functional Requirements**
   - Response time < 2 seconds for MCP operations
   - Support 100+ concurrent users
   - 99.9% uptime for gateway
   - Zero OAuth2 token exposure to clients

3. **Security Requirements**
   - All communications encrypted
   - Audit trail for all operations
   - Token rotation implemented
   - No privilege escalation possible

## Future Enhancements

1. **Multi-Provider Support**
   - Add support for multiple OAuth2 providers
   - Implement provider-specific MCP servers
   - Create abstraction layer for provider differences

2. **Advanced Features**
   - Implement request queuing for rate limits
   - Add caching layer for frequently accessed data
   - Create admin dashboard for monitoring
   - Implement fine-grained permission controls

3. **Ecosystem Integration**
   - Support additional MCP servers
   - Create plugin architecture
   - Implement federation with other gateways
   - Add support for webhook-based tools

## Conclusion

**Current Status:** Phase 2 is now COMPLETE with full authentication functionality operational. The project is ready to proceed to Phase 3 GitHub MCP Server integration with:

- ✅ Phase 1: Cloud Foundry infrastructure and OAuth2 setup COMPLETE
- ✅ Phase 2: Spring Cloud Gateway with authentication COMPLETE
- ✅ Google OAuth2 authentication flow fully tested and working
- ✅ Redis-based session management operational
- ✅ Secure token storage and retrieval system implemented
- ✅ CORS and security configuration complete
- ✅ Ready for MCP proxy module development in Phase 3

This plan provides a comprehensive approach to implementing OAuth2-authenticated MCP on Cloud Foundry. The phased implementation allows for iterative development and testing, while the architecture ensures security, scalability, and maintainability. The solution can serve as a foundation for broader MCP ecosystem integration while maintaining strong security boundaries.