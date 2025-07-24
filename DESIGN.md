# OAuth2-Authenticated MCP on Cloud Foundry: Design and Implementation Plan

## Executive Summary

This document outlines the design and implementation plan for creating a secure, OAuth2-authenticated Model Context Protocol (MCP) environment on Cloud Foundry. The solution enables users to authenticate via SSO and execute MCP tools with their specific identity and permissions, using GitHub as the demonstration service.

**⚠️ CRITICAL AUTHENTICATION GAP IDENTIFIED**: The original design assumed Google OAuth2 tokens could be used directly with GitHub APIs, which is incorrect. This document now reflects the current reality and proposed solutions.

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
   - **⚠️ GAP**: Maps to user identities but cannot directly access GitHub APIs

4. **MCP Client** (User's Chat Application)
   - Connects to Spring Cloud Gateway endpoints
   - Sends MCP protocol requests
   - Displays results to users

### High-Level Flow

```
User → MCP Client → Spring Cloud Gateway → GitHub MCP Server → GitHub API
         ↑                    ↓                     ↑
         └─── Google OAuth2 ←                      │
                                                   │
                                            ❌ BROKEN LINK
                                        (Google token ≠ GitHub token)
```

## Authentication Gap Analysis

### The Problem

**Google OAuth2 tokens cannot be used with GitHub APIs**. These are separate authentication systems:

- **Google OAuth2 token**: Valid only for Google APIs (Gmail, Drive, etc.)
- **GitHub API**: Requires GitHub-issued tokens (Personal Access Token, GitHub App token, etc.)

### Current Broken Flow

1. ✅ User authenticates with Google OAuth2 → gets Google token
2. ✅ mcp-gateway stores Google OAuth2 token in Redis  
3. ❌ mcp-gateway forwards Google OAuth2 token to github-mcp-server
4. ❌ github-mcp-server tries to use Google OAuth2 token with GitHub API → **FAILS**

### Evidence from Code

```go
// github-mcp-server expects GitHub token, not Google token
restClient := gogithub.NewClient(nil).WithAuthToken(cfg.Token)
```

```java
// mcp-gateway currently forwards Google OAuth2 token
enrichedHeaders.add("Authorization", "Bearer " + googleOAuth2Token);
```

## Current Working Solution (Temporary)

### Using GITHUB_ALLOW_UNAUTHENTICATED=true

To bypass the authentication gap temporarily:

1. **Set Environment Variable**:
   ```yaml
   env:
     GITHUB_ALLOW_UNAUTHENTICATED: "true"
     GITHUB_PERSONAL_ACCESS_TOKEN: "ghp_your_github_token_here"
   ```

2. **Behavior**:
   - github-mcp-server uses `OptionalAuthenticationMiddleware`
   - All requests use the same configured GitHub Personal Access Token
   - No user differentiation - all operations appear as same GitHub user

3. **Limitations**:
   - ❌ No user isolation (everyone uses same GitHub identity)
   - ❌ No personalized results (all users see same notifications, repos, etc.)
   - ❌ Security risk (shared GitHub permissions)
   - ❌ Poor audit trail (all actions from same GitHub user)

## Proposed Long-term Solutions

### Option 1: Switch to GitHub OAuth2 (Recommended)
Replace Google OAuth2 with GitHub OAuth2:

```yaml
# Instead of Google
GITHUB_OAUTH_CLIENT_ID: [from GitHub App]
GITHUB_OAUTH_CLIENT_SECRET: [from GitHub App]
OAUTH_REDIRECT_URI: https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/callback
```

**Pros**: Direct token compatibility, user-specific GitHub access
**Cons**: Requires GitHub accounts for all users

### Option 2: Dual Authentication Flow
Keep Google OAuth2 for identity, add GitHub OAuth2 for GitHub access:

1. User authenticates with Google (identity)
2. User separately authorizes GitHub access
3. System maps Google identity → GitHub tokens
4. Each user gets personalized GitHub operations

### Option 3: GitHub App Integration
Use a GitHub App that users install:

1. User authenticates with Google (identity)
2. User installs/authorizes GitHub App for their repositories
3. System uses GitHub App installation tokens per user
4. Map Google identity → GitHub App installation

### Option 4: Manual Token Configuration
Users manually provide GitHub PATs:

1. User authenticates with Google OAuth2 (identity)
2. User separately configures GitHub Personal Access Token in profile
3. System maps Google identity → user-provided GitHub PAT

## Detailed Design (Current State)

### Authentication Flow (As Implemented)

1. **Initial Authentication**
   - User accesses `/auth/login` endpoint on Spring Cloud Gateway
   - Gateway redirects to Google OAuth2 provider
   - User authenticates and authorizes the application
   - Google OAuth2 redirects back to `https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/callback`
   - Gateway exchanges code for **Google OAuth2 access token**
   - Gateway creates a session and returns session token to client

2. **Token Management**
   - Spring Cloud Gateway maintains token store (Redis via mcp-redis service)
   - Maps session tokens to **Google OAuth2 access tokens**
   - Handles token refresh automatically
   - ❌ **Gap**: Cannot use Google tokens with GitHub APIs

### MCP Protocol Integration

1. **SSE Connection Establishment**
   - Client connects to `/mcp/sse` endpoint with session token
   - Gateway validates session and retrieves associated **Google OAuth2 token**
   - Gateway establishes SSE connection to GitHub MCP Server
   - ❌ **Gap**: Injects Google token (incompatible with GitHub API)

2. **Request Flow (Current)**
   - Client sends MCP requests via SSE connection
   - Gateway intercepts and enriches requests with:
     - ❌ Google OAuth2 access token (wrong token type)
     - ✅ User identity information  
     - ✅ Additional security headers
   - Gateway forwards enriched requests to MCP Server

3. **Response Flow (Broken)**
   - ❌ MCP Server fails to authenticate with GitHub API using Google token
   - ❌ Operations fail or fall back to unauthenticated mode

### Security Considerations

1. **Token Security**
   - Never expose OAuth2 tokens to client ✅
   - Use secure session tokens for client-gateway communication ✅
   - Implement token rotation and expiry ✅
   - Store tokens encrypted at rest in mcp-redis ✅

2. **Connection Security**
   - All connections use HTTPS/WSS ✅
   - Implement CORS policies ✅
   - Rate limiting per user/session ✅
   - ❌ **Gap**: Request signing between Gateway and MCP Server needs GitHub tokens

3. **Authorization**
   - Gateway performs initial authorization checks ✅
   - ❌ **Gap**: MCP Server cannot validate permissions without proper GitHub tokens
   - ❌ **Gap**: Cannot implement principle of least privilege per user
   - ❌ **Gap**: Audit logging compromised without proper user context

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

3. **MCP Proxy Module** ✅ **COMPLETED**
   - ✅ Implement SSE proxy capability for `/mcp/sse` endpoint
   - ✅ Create request enrichment filters to inject authentication headers
   - ✅ Build connection management for long-lived SSE connections
   - ✅ Handle connection resilience and reconnection logic

### Phase 3: GitHub MCP Server Integration 🔄 **CURRENT - WITH GAPS**

1. **MCP Server Deployment** ✅ **COMPLETED**
   - ✅ Package github-mcp-server for CF deployment
   - ✅ Configure SSE transport settings
   - ✅ Set up environment for dynamic authentication

2. **Authentication Integration** ⚠️ **PARTIALLY WORKING**
   - ✅ Modify MCP server to accept auth headers from gateway
   - ⚠️ GitHub API client requires GitHub tokens (currently using Google tokens - BROKEN)
   - ⚠️ User context management works but uses wrong token type
   - ⚠️ Temporary workaround: `GITHUB_ALLOW_UNAUTHENTICATED=true`

### Phase 4: Authentication Gap Resolution 🔄 **NEXT PRIORITY**

1. **Token Architecture Decision**
   - Choose between GitHub OAuth2, dual authentication, or GitHub App approach
   - Design token mapping system
   - Plan migration from Google-only authentication

2. **Implementation of Chosen Solution**
   - Implement GitHub token acquisition flow
   - Update TokenService to handle GitHub tokens  
   - Modify request enrichment to send correct token types
   - Test user-specific GitHub operations

3. **End-to-End Integration Testing**
   - Test complete authentication flow with proper GitHub tokens
   - Verify MCP protocol compliance with authenticated users
   - Test various GitHub operations with user-specific context
   - Validate permission enforcement per user

### Phase 5: Production Readiness (Future)

1. **Performance Optimization**
   - Load testing for concurrent users
   - SSE connection pooling optimization
   - Token caching strategies (leveraging mcp-redis)
   - Response time optimization

2. **Monitoring and Observability**
   - Implement comprehensive logging
   - Set up metrics collection
   - Create dashboards for key metrics
   - Configure alerting rules

## Technical Specifications

### Spring Cloud Gateway Configuration

```yaml
Key Routes:
# Authentication Endpoints
- /auth/login: Google OAuth2 login initiation
- /auth/callback: Google OAuth2 callback handler (https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/callback)
- /auth/logout: Session termination
- /auth/status: Authentication status check

# MCP Protocol Endpoints
- /mcp/sse: SSE proxy to MCP Server (real-time communication)
- /mcp/message: HTTP message proxy to MCP Server
- /mcp/status: MCP server health status

# Administrative Endpoints
- /mcp/admin/status: Overall system status and statistics
- /mcp/admin/connections: Active connection management
- /mcp/admin/resilience: Circuit breaker and retry statistics
- /mcp/admin/circuit-breaker/{serviceKey}/reset: Manual circuit breaker reset

# Health and Monitoring
- /actuator/health: Application health check endpoint
- /actuator/metrics: Application metrics
- /actuator/info: Application information

Required Service Bindings:
- mcp-redis: Session and token storage
- mcp-gateway: Gateway service features

Environment Variables:
- GOOGLE_OAUTH_CLIENT_ID: Google OAuth2 client ID
- GOOGLE_OAUTH_CLIENT_SECRET: Google OAuth2 client secret  
- OAUTH_REDIRECT_URI: https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/callback
- MCP_SERVER_URL: Backend MCP server URL
```

### GitHub MCP Server Configuration

**Current Temporary Configuration:**
```yaml
Environment Variables:
- GITHUB_ALLOW_UNAUTHENTICATED: "true"
- GITHUB_PERSONAL_ACCESS_TOKEN: [Single GitHub PAT for all users]
- GITHUB_HOST: "https://github.com"
- GITHUB_TOOLSETS: "repos,issues,pull_requests,users,notifications"
```

**Target Configuration (After Gap Resolution):**
```yaml
Environment Variables:
- GITHUB_ALLOW_UNAUTHENTICATED: "false"
- GITHUB_HOST: "https://github.com"
- GITHUB_TOOLSETS: "repos,issues,pull_requests,users,notifications"
# GitHub tokens provided per-request via Authorization header
```

### Authentication Header Processing

**Current (Broken) Flow:**
1. Accept `Authorization` header with Google OAuth2 Bearer token
2. Extract user identity from custom headers
3. ❌ Try to initialize GitHub client with Google token → FAILS

**Target Flow:**
1. Accept `Authorization` header with GitHub Bearer token
2. Extract user identity from custom headers  
3. ✅ Initialize GitHub client with proper GitHub token → WORKS

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

### ✅ Completed Spring Cloud Gateway
- ✅ Full OAuth2 authentication flow working with Google SSO
- ✅ Session management operational using mcp-redis
- ✅ SSE proxy implementation with real-time MCP protocol support
- ✅ Request enrichment filter with automatic authentication header injection
- ✅ Connection management with lifecycle tracking and idle cleanup
- ✅ Resilience implementation with circuit breakers and retry logic
- ✅ Administrative monitoring endpoints for operational visibility

### ⚠️ Partially Working GitHub MCP Server
- ✅ SSE transport and MCP protocol handling
- ✅ Authentication middleware (accepts headers from gateway)
- ✅ All GitHub API tools and capabilities
- ❌ **CRITICAL GAP**: Cannot use Google OAuth2 tokens with GitHub API
- ⚠️ **TEMPORARY WORKAROUND**: Running with `GITHUB_ALLOW_UNAUTHENTICATED=true`

## Risk Mitigation

### Technical Risks

1. **Authentication Token Mismatch** ❌ **ACTIVE RISK**
   - Risk: Google OAuth2 tokens incompatible with GitHub API
   - Current Mitigation: Temporary `GITHUB_ALLOW_UNAUTHENTICATED=true` mode
   - Long-term Mitigation: Implement proper GitHub token acquisition

2. **SSE Connection Stability**
   - Risk: Long-lived connections may drop
   - Mitigation: Implement reconnection logic, connection pooling ✅

3. **Token Expiration**
   - Risk: Operations fail due to expired tokens
   - Mitigation: Proactive token refresh using mcp-redis cache ✅

4. **Performance at Scale**
   - Risk: Gateway becomes bottleneck
   - Mitigation: Horizontal scaling, caching via mcp-redis, connection pooling ✅

### Security Risks

1. **Shared GitHub Identity** ❌ **ACTIVE RISK**
   - Risk: All users share same GitHub token in current temporary mode
   - Impact: No user isolation, shared permissions, poor audit trail
   - Mitigation: Priority resolution of authentication gap

2. **Token Security**
   - Never expose OAuth2 tokens to client ✅
   - Use secure session tokens for client-gateway communication ✅
   - Implement token rotation and expiry ✅
   - Store tokens encrypted at rest in mcp-redis ✅

3. **Connection Security**
   - All connections use HTTPS/WSS ✅
   - Implement CORS policies ✅
   - Rate limiting per user/session ✅

## Next Steps (Priority Order)

1. **CRITICAL**: Resolve authentication gap
   - Decide on GitHub token acquisition strategy
   - Implement chosen solution (GitHub OAuth2, dual auth, or GitHub App)
   - Test user-specific GitHub operations

2. **HIGH**: End-to-end integration testing
   - Verify complete flow with proper authentication
   - Test all MCP operations with user context
   - Performance testing under load

3. **MEDIUM**: Production hardening
   - Enhanced monitoring and alerting
   - Security auditing
   - Documentation and runbooks

The system is **functionally working** but operates in a **shared-identity mode** that is not suitable for production use. The authentication gap must be resolved to achieve the original design goals of user-specific GitHub operations.
