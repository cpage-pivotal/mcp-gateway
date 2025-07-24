# Dual Authentication Flow - Design and Implementation Plan

## Executive Summary

This document details the design and implementation plan for Option 2: Dual Authentication Flow, which addresses the authentication gap in the MCP Gateway system. This approach maintains Google OAuth2 for user identity while adding GitHub OAuth2 for API access, providing a secure and user-friendly solution for personalized GitHub operations.

## Overview

### Current Problem
- Google OAuth2 tokens cannot be used with GitHub APIs
- All users currently share a single GitHub identity (security risk)
- No user isolation or personalized GitHub operations

### Solution Approach
- Keep Google OAuth2 as the primary identity provider
- Add GitHub OAuth2 as a secondary authentication for GitHub API access
- Map Google identities to GitHub tokens in Redis
- Provide seamless user experience with proper token management

## Architecture Design

### Component Updates

#### 1. Enhanced Spring Cloud Gateway
```
New Components:
├── Dual OAuth2 Configuration
│   ├── Google OAuth2 (Primary - Identity)
│   └── GitHub OAuth2 (Secondary - API Access)
├── Token Mapping Service
│   ├── Google ID → GitHub Token mapping
│   └── Token lifecycle management
├── Enhanced Session Service
│   ├── Multi-token session management
│   └── Token refresh orchestration
└── GitHub Auth Controller
    ├── GitHub OAuth2 flow endpoints
    └── Token linking endpoints
```

#### 2. Enhanced Redis Schema
```
Session Storage:
- session:{sessionId} → {
    googleUserId: string,
    googleToken: string,
    githubToken: string (optional),
    githubUsername: string (optional),
    createdAt: timestamp,
    expiresAt: timestamp
  }

Token Mapping:
- user:google:{googleId}:github → {
    githubToken: string,
    githubUsername: string,
    scope: string[],
    expiresAt: timestamp
  }

Token Metadata:
- token:github:{token}:metadata → {
    googleUserId: string,
    createdAt: timestamp,
    lastUsed: timestamp,
    refreshToken: string (encrypted)
  }
```

#### 3. Updated Request Flow
```
Client Request → Gateway:
1. Validate Google session
2. Check for GitHub token mapping
3. If GitHub token exists:
   - Enrich request with GitHub token
   - Forward to MCP server
4. If no GitHub token:
   - Return auth required response
   - Include GitHub auth URL
```

## User Experience Flow

### Initial Setup (First Time)
```
1. User accesses application
2. Redirected to Google OAuth2 login
3. After Google auth success:
   - Dashboard shows "GitHub not connected"
   - Prominent "Connect GitHub" button
4. User clicks "Connect GitHub"
5. Redirected to GitHub OAuth2
6. After GitHub auth success:
   - Tokens linked in backend
   - Dashboard shows "GitHub connected"
   - User can now use MCP tools
```

### Subsequent Sessions
```
1. User logs in with Google
2. System checks for existing GitHub token
3. If valid GitHub token exists:
   - Seamless access to MCP tools
4. If GitHub token expired/revoked:
   - Automatic refresh attempt
   - If refresh fails, prompt reconnection
```

### Token Lifecycle Management
```
Token Refresh Strategy:
├── Google Token
│   ├── Refresh automatically via Spring Security
│   └── 1-hour access token, long-lived refresh token
└── GitHub Token
    ├── Check expiry before each use
    ├── Refresh using stored refresh token
    └── Re-authenticate if refresh fails
```

## Implementation Plan

### Phase 1: Infrastructure Preparation (3-5 days)

#### 1.1 GitHub OAuth2 App Setup
- [ ] Create GitHub OAuth2 App in GitHub settings
- [ ] Configure redirect URI: `https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/github/callback`
- [ ] Obtain Client ID and Client Secret
- [ ] Document required scopes: `repo`, `user`, `notifications`

#### 1.2 Redis Schema Updates
- [ ] Design token mapping schema
- [ ] Create migration scripts for existing sessions
- [ ] Implement backward compatibility layer
- [ ] Add indexes for efficient lookups

#### 1.3 Configuration Updates
```yaml
# New environment variables for mcp-gateway
GITHUB_OAUTH_CLIENT_ID: ${github_client_id}
GITHUB_OAUTH_CLIENT_SECRET: ${github_client_secret}
GITHUB_OAUTH_REDIRECT_URI: https://mcp-gateway.apps.tas-ndc.kuhn-labs.com/auth/github/callback
GITHUB_OAUTH_SCOPES: repo,user,notifications,read:org

# Feature flags
DUAL_AUTH_ENABLED: true
GITHUB_TOKEN_REQUIRED: false  # Start permissive
```

### Phase 2: Backend Implementation (5-7 days)

#### 2.1 Token Mapping Service
```java
@Service
public class TokenMappingService {
    
    public Mono<Void> linkGitHubToken(String googleUserId, 
                                     String githubToken, 
                                     GitHubUserInfo userInfo) {
        // Store mapping in Redis
        // Encrypt sensitive data
        // Set appropriate TTLs
    }
    
    public Mono<String> getGitHubToken(String googleUserId) {
        // Retrieve GitHub token for Google user
        // Check expiry and refresh if needed
        // Return null if no mapping exists
    }
    
    public Mono<Void> unlinkGitHubToken(String googleUserId) {
        // Remove GitHub token mapping
        // Clean up related data
    }
}
```

#### 2.2 Dual Auth Controller
```java
@RestController
@RequestMapping("/auth/github")
public class GitHubAuthController {
    
    @GetMapping("/connect")
    public Mono<Void> initiateGitHubAuth(ServerWebExchange exchange) {
        // Verify Google authentication
        // Generate state parameter
        // Redirect to GitHub OAuth2
    }
    
    @GetMapping("/callback")
    public Mono<Void> handleGitHubCallback(
            @RequestParam String code,
            @RequestParam String state,
            ServerWebExchange exchange) {
        // Validate state parameter
        // Exchange code for token
        // Link with Google identity
        // Redirect to success page
    }
    
    @GetMapping("/status")
    public Mono<ResponseEntity<GitHubConnectionStatus>> getStatus(
            @AuthenticationPrincipal OAuth2User principal) {
        // Check GitHub connection status
        // Return connection details
    }
    
    @DeleteMapping("/disconnect")
    public Mono<ResponseEntity<Void>> disconnect(
            @AuthenticationPrincipal OAuth2User principal) {
        // Remove GitHub token mapping
        // Revoke GitHub token via API
    }
}
```

#### 2.3 Enhanced Request Filter
```java
@Component
public class DualAuthRequestFilter implements GlobalFilter {
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, 
                           GatewayFilterChain chain) {
        return sessionService.getGoogleUserId(exchange)
            .flatMap(googleUserId -> 
                tokenMappingService.getGitHubToken(googleUserId))
            .map(githubToken -> {
                // Add GitHub token to Authorization header
                // Add user context headers
                // Continue chain
            })
            .switchIfEmpty(handleMissingGitHubToken(exchange))
            .then(chain.filter(exchange));
    }
}
```

### Phase 3: Frontend Integration (3-4 days)

#### 3.1 User Dashboard Updates
```javascript
// Connection status component
const GitHubConnectionStatus = () => {
    const [status, setStatus] = useState(null);
    
    useEffect(() => {
        fetchGitHubStatus().then(setStatus);
    }, []);
    
    if (!status?.connected) {
        return (
            <Alert>
                <p>GitHub not connected</p>
                <Button onClick={connectGitHub}>
                    Connect GitHub Account
                </Button>
            </Alert>
        );
    }
    
    return (
        <Card>
            <p>GitHub connected as: {status.username}</p>
            <p>Scopes: {status.scopes.join(', ')}</p>
            <Button onClick={disconnectGitHub}>
                Disconnect
            </Button>
        </Card>
    );
};
```

#### 3.2 MCP Client Updates
```javascript
// Handle auth-required responses
const handleMCPRequest = async (request) => {
    try {
        const response = await sendMCPRequest(request);
        return response;
    } catch (error) {
        if (error.code === 'GITHUB_AUTH_REQUIRED') {
            showGitHubAuthPrompt();
            return null;
        }
        throw error;
    }
};
```

### Phase 4: Migration Strategy (2-3 days)

#### 4.1 Gradual Rollout Plan
```
Week 1: Soft Launch
- Enable dual auth for staff users
- Monitor token mapping performance
- Gather feedback on UX

Week 2: Beta Users
- Enable for 10% of users
- A/B test connection prompts
- Refine error handling

Week 3: General Availability
- Enable for all users
- Maintain backward compatibility
- Provide migration tools

Week 4: Enforcement
- Make GitHub connection mandatory
- Disable shared token mode
- Remove GITHUB_ALLOW_UNAUTHENTICATED
```

#### 4.2 Data Migration
```sql
-- Migration checklist
1. Backup existing session data
2. Create new Redis keys with updated schema
3. Migrate active sessions to new format
4. Update session service to handle both formats
5. Gradually phase out old format
6. Clean up deprecated keys
```

### Phase 5: Security Hardening (2-3 days)

#### 5.1 Token Security
- [ ] Implement token encryption at rest
- [ ] Add token rotation policies
- [ ] Implement anomaly detection
- [ ] Add audit logging for token usage

#### 5.2 Authorization Policies
```java
@Configuration
public class SecurityPolicies {
    
    // Require GitHub token for MCP endpoints
    public SecurityWebFilterChain mcpSecurity(
            ServerHttpSecurity http) {
        return http
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/mcp/**")
                .access(requireGitHubToken())
                .anyExchange().authenticated()
            )
            .build();
    }
    
    private ReactiveAuthorizationManager<AuthorizationContext> 
            requireGitHubToken() {
        return (mono, context) -> mono
            .flatMap(auth -> checkGitHubToken(auth))
            .map(AuthorizationDecision::new);
    }
}
```

### Phase 6: Testing & Validation (3-4 days)

#### 6.1 Unit Tests
```java
@Test
void testTokenMapping() {
    // Test Google ID to GitHub token mapping
    // Test token refresh logic
    // Test error scenarios
}

@Test
void testDualAuthFlow() {
    // Test complete authentication flow
    // Test token linking
    // Test disconnection
}
```

#### 6.2 Integration Tests
- [ ] End-to-end authentication flow
- [ ] Token refresh scenarios
- [ ] Error handling and recovery
- [ ] Performance under load

#### 6.3 Security Tests
- [ ] Token leakage prevention
- [ ] Session hijacking protection
- [ ] Authorization bypass attempts
- [ ] Rate limiting validation

## Monitoring & Operations

### Key Metrics
```yaml
Authentication Metrics:
- google_auth_success_rate
- github_auth_success_rate
- token_mapping_operations
- token_refresh_rate
- auth_error_rate

Performance Metrics:
- token_lookup_latency
- auth_flow_duration
- redis_operation_time

Security Metrics:
- failed_auth_attempts
- token_revocation_count
- suspicious_activity_alerts
```

### Operational Dashboards
1. **Authentication Overview**
   - Active sessions by auth type
   - Connection success rates
   - Error distribution

2. **Token Management**
   - Token expiry timeline
   - Refresh success rates
   - Mapping statistics

3. **User Experience**
   - Time to complete dual auth
   - Drop-off rates
   - Support ticket correlation

## Risk Mitigation

### Technical Risks
1. **Token Sync Issues**
   - Mitigation: Implement robust retry logic
   - Fallback: Grace period for reconnection

2. **Performance Impact**
   - Mitigation: Aggressive caching
   - Monitoring: Latency alerts

3. **Migration Failures**
   - Mitigation: Comprehensive rollback plan
   - Testing: Staged rollout

### Security Risks
1. **Token Exposure**
   - Mitigation: Encryption at rest
   - Monitoring: Access audit logs

2. **Account Takeover**
   - Mitigation: Strong state validation
   - Detection: Anomaly monitoring

## Success Criteria

### Technical Success
- [ ] 99.9% authentication success rate
- [ ] <100ms token lookup latency
- [ ] Zero security incidents
- [ ] 100% backward compatibility

### User Success
- [ ] 90% GitHub connection rate
- [ ] <2 minutes to complete dual auth
- [ ] <5% support tickets related to auth
- [ ] Positive user feedback

## Timeline Summary

```
Total Duration: 4-5 weeks

Week 1: Infrastructure & Backend Core
- GitHub OAuth2 setup
- Redis schema updates
- Token mapping service

Week 2: Integration & Frontend
- Request filter updates
- Frontend components
- User experience flow

Week 3: Testing & Migration Prep
- Comprehensive testing
- Migration tools
- Documentation

Week 4: Rollout & Monitoring
- Staged deployment
- Performance tuning
- Issue resolution

Week 5: Enforcement & Cleanup
- Mandatory GitHub connection
- Remove legacy code
- Final optimization
```

## Conclusion

The Dual Authentication Flow provides a secure and user-friendly solution to the authentication gap while maintaining the benefits of Google SSO for identity management. This approach offers:

1. **Security**: User-specific GitHub tokens with proper isolation
2. **Flexibility**: Users can connect/disconnect GitHub as needed
3. **Compatibility**: Maintains existing Google authentication
4. **Scalability**: Efficient token management via Redis

The phased implementation approach ensures minimal disruption while providing a clear path to full user-specific GitHub operations.