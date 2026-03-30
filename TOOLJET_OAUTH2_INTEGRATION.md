# ToolJet ↔ Spring Boot OAuth2 Integration

> **Infinity Plus — Internal Permissions API via Machine-to-Machine OAuth 2.0**

---

## Table of Contents

- [Overview](#overview)
- [Part 1 — End User Guide: Adding the Data Source in ToolJet](#part-1--end-user-guide-adding-the-data-source-in-tooljet)
- [Part 2 — Developer Guide: Architecture, Debugging & Flow](#part-2--developer-guide-architecture-debugging--flow)

---

## Overview

This document explains how ToolJet fetches Casbin-based user permissions from the Spring Boot backend using OAuth 2.0 Client Credentials (Machine-to-Machine). The backend exposes a secured internal endpoint at:

```
GET /api/permissions/internal/tooljet-permissions
```

ToolJet authenticates using a short-lived JWT (5 minutes) obtained via the OAuth2 token endpoint before calling this API. No user login is involved — this is pure server-to-server communication.

---

## Part 1 — End User Guide: Adding the Data Source in ToolJet

This section is for anyone setting up the **Infinity Plus Data Source** in a ToolJet workspace for the first time.

### Step 1 — Create a New REST API Data Source

1. Open your ToolJet workspace.
2. Go to **Data Sources** in the left sidebar.
3. Click **Add Data Source** → select **REST API**.
4. Name it: `infinity-plus-data-source`

### Step 2 — Fill in the Credentials Section

| Field | Value |
|---|---|
| **Base URL** | `https://infinity-plus-services-dev-493376716946.asia-south2.run.app` |
| **Headers** | *(leave empty)* |
| **URL Parameters** | See below |
| **Body** | *(leave empty)* |
| **Cookies** | *(leave empty)* |

**URL Parameters** — Click **Add** twice and enter:

| Key | Value |
|---|---|
| `organisationId` | `ad2d75f8-471b-4b6a-9c63-32546b04049a` *(your ToolJet workspace UUID)* |
| `email` | `{{globals.currentUser?.email \|\| 'your-dev-email@example.com'}}` |

> **Why organisationId?** This is your ToolJet workspace UUID. The backend uses it to look up which internal tenant you belong to via the `tbl_tooljet_workspaces` mapping table. It never changes for your workspace.

> **Why email?** ToolJet exposes the logged-in user's email via `globals.currentUser.email`. The backend uses this to find the user's internal ID and evaluate their Casbin permissions.

### Step 3 — Fill in the Authentication Section

| Field | Value |
|---|---|
| **Authentication Type** | `OAuth 2.0` |
| **Grant Type** | `Client credentials` ← **This must be Client Credentials, not Authorization Code** |
| **Access Token URL** | `https://infinity-plus-services-dev-493376716946.asia-south2.run.app/oauth2/token` |
| **Client ID** | `tooljet-internal-client` |
| **Client Secret** | *(provided separately — do not share)* |
| **Scope(s)** | `internal:read` |
| **Client Authentication** | `Send as basic auth header` |
| **Audience** | *(leave empty)* |

> ⚠️ **Important:** Do **not** include `{noop}` in the Client Secret field. That prefix is only used internally in the Spring Boot code. In ToolJet, enter the plain secret string only.

### Step 4 — Save and Authorize

Click **Save and Authorize**. ToolJet will:
1. POST to `/oauth2/token` with your client credentials
2. Receive a Bearer JWT valid for 5 minutes
3. Automatically attach it as `Authorization: Bearer <token>` on every query

You should see a green success indicator. If it fails, see [Troubleshooting](#troubleshooting) below.

### Step 5 — Create a Query Using This Data Source

1. In your ToolJet app, create a new **Query**.
2. Select `infinity-plus-data-source` as the data source.
3. Set:
   - **Method:** `GET`
   - **URL Path:** `/api/permissions/internal/tooljet-permissions`
   - **Query Parameters:** *(already handled at the data source level — no need to repeat)*
4. Name the query `fetchPermissions`.
5. Enable **Run this query on page load**.

The query response will look like:

```json
{
  "userId": "USR-001",
  "organisationId": "ad2d75f8-471b-4b6a-9c63-32546b04049a",
  "tenantId": "252ad06d-daf0-4796-b59f-825fd8eaf20f",
  "permissions": {
    "purchase_order:view": true,
    "purchase_order:create": true,
    "invoice:view": true
  }
}
```

### Step 6 — Use Permissions in Your App

Reference permissions anywhere in ToolJet using:

```js
{{queries.fetchPermissions.data.permissions['purchase_order:view']}}
```

Use this to show/hide buttons, tables, or entire pages.

---

### Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `Failed to fetch access token` | Wrong credentials or scope | Re-enter Client ID and Secret manually (no copy-paste spaces). Ensure Scope is `internal:read`. |
| `status: 401` on token endpoint | `{noop}` included in secret, or wrong auth method | Remove `{noop}` from ToolJet secret field. Try toggling Client Authentication between `basic auth header` and `Send in body`. |
| `status: 500 — Tenant/Organisation not found` | Wrong `organisationId` value | Confirm the UUID matches the `workspace_uuid` column in `tbl_tooljet_workspaces`. |
| `User not found` | Email not in tenant's `tbl_user_email_mapping` | Ask an admin to add your email to the correct tenant schema. |

---

## Part 2 — Developer Guide: Architecture, Debugging & Flow

This section covers the full technical design, the files involved, common errors encountered during setup, and how the security chain works.

---

### Architecture Overview

```
ToolJet Frontend
      │
      │  (1) GET /api/permissions/internal/tooljet-permissions
      │       ?organisationId=<workspace_uuid>&email=<user_email>
      │       Authorization: Bearer <5-min JWT>
      ▼
Spring Boot Backend
      │
      ├─ InternalApiSecurityConfig  ← validates OAuth2 Bearer token
      │       └─ checks SCOPE_internal:read
      │
      ├─ PermissionController
      │       ├─ (2) lookup tbl_tooljet_workspaces by workspace_uuid
      │       │        → resolves internal tenant_id
      │       ├─ (3) lookup tbl_tenants by tenant_id
      │       │        → resolves schema_name (e.g., saar_biotech)
      │       ├─ (4) lookup tbl_user_email_mapping by email
      │       │        → resolves internal user_id
      │       ├─ (5) query tbl_resource_actions for all resources
      │       └─ (6) call Casbin enforcer for each resource:action
      │
      └─ Response: { permissions: { "po:view": true, ... } }
```

The token flow is separate:

```
ToolJet Backend (Node.js)
      │
      │  POST /oauth2/token
      │  Authorization: Basic base64(client_id:client_secret)
      │  Body: grant_type=client_credentials&scope=internal:read
      ▼
Spring Authorization Server (ToolJetOAuth2ServerConfig)
      │
      └─ Returns: { access_token: "eyJ...", expires_in: 299 }
```

---

### Files Involved

#### `ToolJetOAuth2ServerConfig.java`

Sets up the Authorization Server that issues tokens to ToolJet.

Key beans:

- **`authorizationServerSecurityFilterChain`** — intercepts only `/oauth2/**` paths via `securityMatcher`. Uses `@Order(HIGHEST_PRECEDENCE)` so it runs before all other security chains.
- **`registeredClientRepository`** — registers ToolJet as an in-memory M2M client with:
  - `clientId`: `tooljet-internal-client`
  - `clientSecret`: `{noop}tooljet-super-secret-key-123`
  - Both `CLIENT_SECRET_BASIC` and `CLIENT_SECRET_POST` auth methods (tolerates ToolJet's quirks)
  - `CLIENT_CREDENTIALS` grant only
  - `internal:read` scope
  - 5-minute token TTL
- **`jwkSource`** — generates a fresh 2048-bit RSA key pair on startup for signing JWTs
- **`jwtDecoder`** — allows in-process validation without an external HTTP call
- **`passwordEncoder`** — `DelegatingPasswordEncoder` to handle the `{noop}` prefix correctly

```java
// The critical bean that was missing — without this, Spring can't decode {noop} and returns 401
@Bean
public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
}
```

> ⚠️ **Key lesson:** Without the `PasswordEncoder` bean, Spring Authorization Server does not know how to interpret `{noop}` prefixes, causing every token request to return `invalid_client`.

---

#### `InternalApiSecurityConfig.java`

A dedicated security filter chain that protects `/api/permissions/internal/**`.

```java
@Bean
@Order(1) // After Auth Server (HIGHEST_PRECEDENCE), before main SecurityConfig
public SecurityFilterChain internalApiSecurityFilterChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher(new AntPathRequestMatcher("/api/permissions/internal/**"))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(new AntPathRequestMatcher("/api/permissions/internal/**"))
            .hasAuthority("SCOPE_internal:read")
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable);

    return http.build();
}
```

**Why `AntPathRequestMatcher` instead of plain strings?**

Flowable registers additional servlets (`/dmn-api/*`, `/process-api/*`) alongside the main Spring MVC `DispatcherServlet`. When more than one servlet is registered, Spring Security 6 cannot auto-detect which routing rules to use for plain string patterns, and throws:

```
This method cannot decide whether these patterns are Spring MVC patterns or not.
```

Using `new AntPathRequestMatcher(...)` explicitly bypasses this ambiguity.

**Why three separate filter chains?**

| Chain | Order | Handles |
|---|---|---|
| `authorizationServerSecurityFilterChain` | `HIGHEST_PRECEDENCE` | `/oauth2/**` — token issuance |
| `internalApiSecurityFilterChain` | `1` | `/api/permissions/internal/**` — M2M API |
| `filterChain` (main SecurityConfig) | Default | Everything else — human user JWTs |

This separation means ToolJet's OAuth2 Bearer token never collides with the custom `JwtAuthenticationFilter` used for human users.

---

#### `PermissionController.java` — Internal Endpoint

The full resolution chain inside `getToolJetPermissions`:

```java
// Step 1: workspace_uuid → internal tenant_id (via tbl_tooljet_workspaces)
ToolJetWorkspace mapping = toolJetWorkspaceRepository.findByWorkspaceUuid(organisationId)
        .orElseThrow(...);

// Step 2: tenant_id → schema_name (via tbl_tenants, using JPA @OneToOne)
Tenant tenant = mapping.getTenant(); // No extra DB call needed — JPA relationship
String schema = tenant.getSchemaName();
String internalTenantId = tenant.getId();

// Step 3: email → user_id (via tbl_user_email_mapping in tenant schema)
userId = allowedUserService.getUserIdByEmail(email, schema);

// Step 4: evaluate every resource:action via Casbin
for (Record2<String, String> record : resourceActions) {
    if (casbinService.canDo(userId, internalTenantId, schema, key, action)) {
        permissions.put(key + ":" + action, true);
    }
}
```

**Smart Lookup:** If `userId` is absent (developer mode in ToolJet builder — no URL params), the endpoint falls back to email-based resolution. In production embeds, the `userId` is injected via URL params and used directly.

---

### Errors Encountered and Resolutions

#### Error 1: `applyDefaultSecurity` deprecation

```
'applyDefaultSecurity(...)' is deprecated since version 1.4 and marked for removal
```

**Fix:** Replace the static method with the fluent configurer API:

```java
// Before (deprecated)
OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

// After (Spring Security 6.4+)
OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
        OAuth2AuthorizationServerConfigurer.authorizationServer();
http.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
    .with(authorizationServerConfigurer, server -> server.oidc(Customizer.withDefaults()))
    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
```

---

#### Error 2: `AntPathRequestMatcher` required (multiple servlets)

```
This method cannot decide whether these patterns are Spring MVC patterns or not.
This is because there is more than one mappable servlet in your servlet context:
{org.springframework.web.servlet.DispatcherServlet=[/dmn-api/*]}
```

**Fix:** Wrap all path strings in `new AntPathRequestMatcher(...)` in the `InternalApiSecurityConfig`.

---

#### Error 3: Token returns `invalid_client` (401)

The `/oauth2/token` endpoint was reachable but returning 401 with body `invalid_client`.

**Root Cause:** The `PasswordEncoder` bean was not defined. Spring Authorization Server could not interpret `{noop}` and therefore could not verify the secret.

**Fix:** Add to `ToolJetOAuth2ServerConfig`:

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
}
```

**Verified** using curl before fixing ToolJet:

```bash
curl -X POST https://<your-backend>/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "tooljet-internal-client:tooljet-super-secret-key-123" \
  -d "grant_type=client_credentials" \
  -d "scope=internal:read"
```

Once the `PasswordEncoder` was added, this returned:

```json
{
  "access_token": "eyJraWQi...",
  "scope": "internal:read",
  "token_type": "Bearer",
  "expires_in": 299
}
```

---

#### Error 4: `Tenant record missing for ID: 1`

After OAuth2 was working, the permissions endpoint returned 500 with:

```
java.lang.RuntimeException: Tenant record missing for ID: 1
```

**Root Cause:** The `ToolJetWorkspace` entity had a `@OneToOne` JPA relationship to `Tenant`. But the code was calling `tenantRepository.findById(mapping.getId())`, which passed the auto-increment primary key `1` instead of the UUID `tenant_id`.

**Fix:** Use the JPA relationship directly — no second repository call needed:

```java
// Wrong: passes auto-increment PK (1)
Tenant tenant = tenantRepository.findById(mapping.getId().toString())...

// Correct: use the JPA relationship
Tenant tenant = mapping.getTenant();
String internalTenantId = tenant.getId(); // the UUID
```

---

#### Error 5: `Tenant record missing for ID: com.example...Tenant@3f387e5`

After the above fix, a Java object reference appeared in the error instead of a UUID.

**Root Cause:** The code was calling `.toString()` on a `Tenant` entity object that had no `@Override toString()`, producing a memory address instead of the actual ID string.

**Fix:** Call `.getId()` (or the appropriate getter) on the entity:

```java
// Wrong
String internalTenantId = mapping.getTenant().toString();

// Correct
String internalTenantId = mapping.getTenant().getId();
```

---

### Security Model

The endpoint is secured by three layers:

1. **OAuth2 Client Credentials** — ToolJet must exchange a client_id + client_secret for a 5-minute JWT before any API call. Stolen tokens expire quickly.

2. **Scope Enforcement** — The JWT must contain the `internal:read` scope. Even with a valid token, requests without this scope are rejected with 403.

3. **Tenant Isolation** — The `organisationId` (workspace UUID) is mapped to an internal `tenant_id` through the `tbl_tooljet_workspaces` bridge table. ToolJet can never access data for a tenant it isn't mapped to.

**Security boundaries:**

| What is exposed | What is protected |
|---|---|
| Permission booleans per resource:action | User passwords, PII, business data |
| The user's internal ID | The mapping table structure |
| Which permissions are granted | Casbin policy details |

**Hardening for production:**

- Move `tooljet-super-secret-key-123` to an environment variable:
  ```java
  .clientSecret("{noop}" + System.getenv("TOOLJET_CLIENT_SECRET"))
  ```
- Consider migrating from `InMemoryRegisteredClientRepository` to a JDBC-backed repository for per-tenant client credentials as you onboard more customers.
- Add rate limiting on `/api/permissions/internal/**` (e.g., using Bucket4j) to prevent abuse if a secret is ever leaked.

---

### How to Verify Everything is Working (End-to-End Check)

**Step 1 — Test the token endpoint directly:**

```bash
curl -X POST https://<your-backend>/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "tooljet-internal-client:<your-secret>" \
  -d "grant_type=client_credentials&scope=internal:read"
```

Expected: `200 OK` with `access_token` in the response body.

**Step 2 — Test the permissions endpoint with the token:**

```bash
curl -X GET "https://<your-backend>/api/permissions/internal/tooljet-permissions\
?organisationId=<workspace_uuid>&email=yourname@example.com" \
  -H "Authorization: Bearer <token-from-step-1>"
```

Expected: `200 OK` with a `permissions` map.

**Step 3 — In ToolJet:**

Run the `fetchPermissions` query and confirm `status: ok` with `data.permissions` populated.

---

### Database Tables Referenced

| Table | Schema | Purpose |
|---|---|---|
| `tbl_tooljet_workspaces` | `infinity_plus_management` | Maps ToolJet workspace UUID → internal tenant_id |
| `tbl_tenants` | `infinity_plus_management` | Maps tenant_id → schema_name |
| `tbl_user_email_mapping` | `<tenant_schema>` | Maps email → internal user_id |
| `tbl_resource_actions` | `<tenant_schema>` | All registered resource:action pairs |
| `casbin_rule` | `<tenant_schema>` | Casbin policy table (managed by jCasbin) |

---

*Last updated: March 2026 — Infinity Plus Platform*
