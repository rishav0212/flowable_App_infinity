# Form.io Multi-Tenant Architecture — Developer Documentation

**Project:** InfinityPlus SaaS Platform  
**Module:** Form Builder & Form.io Integration  
**Last Updated:** Based on `developer_features` branch  
**Status:** Production Implementation

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Architecture Overview](#2-architecture-overview)
3. [Core Isolation Strategy](#3-core-isolation-strategy)
4. [File & Component Reference](#4-file--component-reference)
5. [Data Flow — End to End](#5-data-flow--end-to-end)
6. [API Reference](#6-api-reference)
7. [Configuration Reference](#7-configuration-reference)
8. [Frontend Integration](#8-frontend-integration)
9. [Backend Deep Dive](#9-backend-deep-dive)
10. [The form-builder.html Iframe](#10-the-form-builderhtml-iframe)
11. [Known Issues & Debug Guide](#11-known-issues--debug-guide)
12. [Security Model](#12-security-model)
13. [FAQ](#13-faq)

---

## 1. Problem Statement

### Why Form.io Multi-Tenancy Was Needed

The InfinityPlus platform hosts multiple client tenants on a single Form.io open-source server. Form.io's open-source edition is a **single-project, single-namespace server** — it has no native concept of tenants. This meant:

- Tenant A could potentially see Tenant B's forms.
- All forms shared the same flat namespace with no isolation.
- No access control between different clients' form definitions.

### Why Not the Enterprise Plan?

Form.io Enterprise adds native multi-tenancy but costs significantly more. The open-source server is self-hosted and free — so the goal was to **bolt multi-tenancy on top of the open-source server purely through the Spring Boot proxy layer**, without touching Form.io's code at all.

### Why Not Use the React Form.io Builder Component?

The `@formio/react` library's builder component has two critical problems in this stack:

1. **CSS War**: Form.io's builder requires Bootstrap 4. The React frontend uses Tailwind CSS. Importing Bootstrap globally into the React app breaks Tailwind's utility classes and vice versa — buttons, margins, and inputs all end up looking wrong.

2. **DOM Conflict**: React uses a Virtual DOM. Form.io's builder uses aggressive, direct DOM manipulation for drag-and-drop. The two systems fight over who controls the DOM, resulting in broken rendering and crashes.

**Solution**: An isolated `<iframe>` containing a vanilla JS `form-builder.html` page served by Spring Boot. The iframe acts as a CSS sandbox — Bootstrap lives inside the iframe only, and Tailwind stays outside.

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      REACT FRONTEND                             │
│  FormManager.tsx                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Lists forms via GET /api/forms/form                      │  │
│  │ Opens <iframe src="/form-builder.html?token=JWT"> for    │  │
│  │ create/edit. Listens for postMessage events.             │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
              │                              │
              │ JWT Bearer Token             │ postMessage
              ▼                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   SPRING BOOT BACKEND                           │
│                                                                 │
│  FormBuilderBffController  →  GET /api/formio-bff/config        │
│  (Returns formioToken, tenantSlug, tenantId to the iframe)      │
│                                                                 │
│  FormIoProxyController     →  ALL /api/forms/** requests        │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  INBOUND:  Inject tenant prefix into path/name/tags     │  │
│  │  OUTBOUND: Strip tenant prefix from JSON response        │  │
│  │  FILTER:   Add ?tags=tenant:{id} to list queries         │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
              │
              │ HTTP (x-jwt-token: FormIo admin token)
              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  FORM.IO SERVER (Open Source)                   │
│                                                                 │
│  Forms stored as:  {tenantSlug}--{formPath}                    │
│  Tags on all forms: tenant:{tenantId}                          │
│  No direct access by any client browser.                       │
└─────────────────────────────────────────────────────────────────┘
```

Form.io is treated as a **dumb JSON storage engine**. The Spring Boot backend is the only entity with credentials to talk to Form.io. No client ever sends requests directly to the Form.io server URL.

---

## 3. Core Isolation Strategy

Three layers of isolation are applied simultaneously:

### Layer 1: Path Prefixing (Primary Isolation)

Every form's `path` field is prefixed with the tenant's URL slug before being saved to Form.io.

```
Developer creates:   employee-onboarding
Stored in Form.io:  saar-biotech--employee-onboarding
Developer sees:      employee-onboarding  (prefix stripped in response)
```

Two tenants can both create a form called `employee-onboarding` — they are completely separate objects in Form.io because their prefixes differ.

### Layer 2: Tenant Tag (Secondary Filter / Backup)

Every form gets a tag injected: `tenant:{tenantId}`.

When listing forms, the proxy adds `?tags=tenant:{tenantId}` to the Form.io query, so the response is pre-filtered server-side. This is faster and more robust than client-side filtering.

> **Note:** The proxy switched from `path__regex` (regex-based filtering) to `tags=tenant:{tenantId}` (tag-based filtering) because it avoids URL encoding issues with the `^` character in regex queries.

### Layer 3: machineName Prefixing

Form.io also stores a `machineName` field (an internal identifier). This is also prefixed with the same tenant slug to prevent any collisions inside Form.io's internal namespace.

### What the Tenant Sees

The proxy strips all prefixes from responses before they reach the frontend. So a form stored as `saar-biotech--employee-form` is returned to the React app as `employee-form`. BPMN processes, forms, and the iframe all work with the clean, un-prefixed names.

---

## 4. File & Component Reference

### Backend Files

| File | Location | Purpose |
|------|----------|---------|
| `FormIoProxyController.java` | `controller/` | Main proxy — all `/api/forms/**` requests pass through here. Handles tenant injection and stripping. |
| `FormBuilderBffController.java` | `controller/` | BFF config endpoint — gives the iframe its Form.io token and tenant info. |
| `FormIoAuthService.java` | `service/` | Manages the Form.io admin JWT. Logs in once, caches token, re-logs on 401/440. |
| `UserContextService.java` | `service/` | Reads `tenantId`, `tenantSlug`, `schemaName`, `userId` from the Spring Security context (JWT claims). |
| `FormIoClient.java` | `client/` | Feign client for direct Form.io calls (used for read intercepts like SQL tag checking). |
| `SecurityConfig.java` | `config/` | Adds `/form-builder.html` to the `permitAll()` list so the iframe can load without a JWT. |
| `form-builder.html` | `src/main/resources/static/` | The isolated iframe page. Served by Spring Boot. Contains the entire Form.io vanilla JS builder. |

### Frontend Files

| File | Location | Purpose |
|------|----------|---------|
| `FormManager.tsx` | `src/` | Lists forms for the current tenant. Opens the builder iframe in a full-screen overlay. |
| `api.ts` | `src/` | `fetchAllForms()` — calls `GET /api/forms/form?type=form` to list forms. |
| `App.tsx` | `src/` | Add `<Route path="forms" element={<FormManager />} />` here to enable the route. |

---

## 5. Data Flow — End to End

### 5a. Developer Opens Form Builder (New Form)

```
1. Dev clicks "New Form" in FormManager.tsx
2. React opens full-screen overlay with:
   <iframe src="http://localhost:8080/form-builder.html?token={JWT}&api=http://localhost:8080/api">
3. form-builder.html loads (Spring Boot serves it as a static file)
4. iframe JS calls:
   GET /api/formio-bff/config
   Headers: { Authorization: Bearer {JWT} }
5. FormBuilderBffController validates JWT via Spring Security,
   calls FormIoAuthService.getAccessToken() to get the Form.io admin token
   returns:
   {
     formioToken: "...",   // Form.io admin JWT (not exposed to browser directly)
     apiBaseUrl: "http://localhost:8080/api/forms",
     tenantSlug: "saar-biotech",
     tenantId: "uuid-..."
   }
6. iframe sets Formio.setBaseUrl(apiBaseUrl) and Formio.setToken(formioToken)
7. Form.io builder renders the drag-and-drop canvas
8. Developer builds the form
```

### 5b. Developer Saves the Form

```
1. Dev clicks "Save Form" button inside the iframe
2. iframe JS POSTs:
   POST /api/forms/form
   Headers: { Authorization: Bearer {JWT} }
   Body: { title: "Employee Onboarding", path: "employee-onboarding", name: "employee-onboarding", ... }

3. FormIoProxyController receives request
4. classifyPath("/form", POST) → PathType.FORM_CREATE
5. injectTenantIntoFormBody() transforms the body:
   - path:        "employee-onboarding"       → "saar-biotech--employee-onboarding"
   - name:        "employee-onboarding"       → "saar-biotech-employee-onboarding"
   - machineName: "employee-onboarding"       → "saar-biotech-employee-onboarding"
   - tags: []                                 → ["tenant:uuid-..."]
6. Proxy forwards to Form.io with admin x-jwt-token header
7. Form.io saves it and returns the saved JSON
8. stripTenantPrefixFromResponse() strips all prefixes from the response JSON
9. Proxy returns clean JSON to iframe
10. iframe sends postMessage to React: { type: "FORMIO_FORM_SAVED", formPath: "employee-onboarding" }
11. React closes the overlay, refreshes the form list
```

### 5c. Listing Forms for a Tenant

```
1. FormManager.tsx mounts, calls:
   GET /api/forms/form?type=form&limit=1000&select=_id,title,path,name,tags,modified
   Headers: { Authorization: Bearer {JWT} }

2. FormIoProxyController receives request
3. classifyPath("/form", GET) → PathType.FORM_LIST
4. injectTenantFilter() adds to query string:
   &tags=tenant:{tenantId}
5. Final URL to Form.io:
   GET {formio.url}/form?type=form&limit=1000&select=...&tags=tenant:{tenantId}
6. Form.io returns only forms tagged for this tenant
7. stripTenantPrefixFromResponse() removes path/name/machineName prefixes
8. React receives clean form list with un-prefixed paths
```

### 5d. Editing an Existing Form

```
1. Dev clicks "Edit" on a form card in FormManager.tsx (e.g., form with path "invoice-form")
2. React opens iframe with:
   /form-builder.html?token={JWT}&api=.../api&formPath=invoice-form
3. iframe JS sees editPath = "invoice-form"
4. iframe calls:
   GET /api/forms/invoice-form
5. FormIoProxyController:
   classifyPath("/invoice-form", GET) → PathType.FORM_SPECIFIC
   addPrefixToSpecificPath("/invoice-form", "saar-biotech") → "/saar-biotech--invoice-form"
   Calls Form.io GET /saar-biotech--invoice-form
   Gets form back, strips prefix in response
6. iframe loads the form JSON into the builder
7. existingFormId is set to the form's _id
8. Dev edits the form, clicks Save
9. iframe sends PUT to /api/forms/form/{existingFormId}
10. classifyPath → FORM_UPDATE_BY_ID
    injectTenantIntoFormBody() ensures path/name/machineName still carry the prefix
    Proxy forwards PUT to Form.io
```

### 5e. Form Submission by End User

```
1. End user fills out the rendered form in the main React UI
2. React/Formio SDK POSTs:
   POST /api/forms/{formPath}/submission
3. FormIoProxyController:
   classifyPath("/{formPath}/submission", POST) → PathType.FORM_SPECIFIC_CHILD
   addPrefixToChildPath() → /{tenantSlug}--{formPath}/submission
4. Proxy forwards to Form.io
5. If form has "sql" tag: async thread mirrors data to PostgreSQL via DataMirrorService
6. Response returned to browser
```

---

## 6. API Reference

### GET `/api/formio-bff/config`

**Purpose:** Called by `form-builder.html` on load. Returns configuration needed to operate the builder.

**Auth:** Bearer JWT (user's Spring Boot JWT, not Form.io token)

**Response:**
```json
{
  "formioToken": "eyJhbGciOiJIUzI1NiJ9...",
  "apiBaseUrl": "http://localhost:8080/api/forms",
  "tenantSlug": "saar-biotech",
  "tenantId": "6f1a2b3c-..."
}
```

**Errors:**
- `500` — Form.io auth service failed to get a token (check formio admin credentials)
- `401` — JWT missing or expired

---

### GET `/api/forms/form`

**Purpose:** List all forms for the current tenant.

**Auth:** Bearer JWT

**Query Params:**
- `type=form` — Excludes resource/wizard type definitions
- `limit=1000` — Override Form.io's default 10-item page limit
- `select=_id,title,path,name,tags,modified` — Optimization

**What happens internally:**  
Proxy appends `&tags=tenant:{tenantId}` to the query.

**Response:** Array of Form.io form objects with tenant prefixes stripped.

---

### POST `/api/forms/form`

**Purpose:** Create a new form.

**Auth:** Bearer JWT

**Body:** Form.io form JSON  
**What happens internally:**
- `path` → prefixed with `{tenantSlug}--`
- `name` → prefixed with `{tenantSlug}-`
- `machineName` → prefixed with `{tenantSlug}-`
- `tags` → `["tenant:{tenantId}"]` injected

---

### PUT `/api/forms/form/{formId}`

**Purpose:** Update an existing form by its Form.io internal `_id`.

**Auth:** Bearer JWT

**What happens internally:**  
`classifyPath` detects `form/{id}` with PUT → `FORM_UPDATE_BY_ID`. Body is re-injected with tenant prefix to prevent "invalid alias" errors that occurred when Form.io's server rejected names without proper prefixes during updates.

---

### GET `/api/forms/{formPath}`

**Purpose:** Fetch a single form by its path (un-prefixed).

**Auth:** Bearer JWT

**What happens internally:**  
`classifyPath` → `FORM_SPECIFIC`. Path is prefixed before calling Form.io. Response is un-prefixed before returning.

---

### DELETE `/api/forms/{formPath}`

**Purpose:** Delete a form.

**Auth:** Bearer JWT

**What happens internally:**  
`classifyPath` → `FORM_SPECIFIC`. Path is prefixed.

---

### POST `/api/forms/{formPath}/submission`

**Purpose:** Submit data to a form.

**Auth:** Bearer JWT

**What happens internally:**
- `classifyPath` → `FORM_SPECIFIC_CHILD`
- Path becomes `{tenantSlug}--{formPath}/submission`
- If form has `sql` tag: async thread runs SQL mirroring via `DataMirrorService`

---

### GET `/api/forms/sql-data`

**Purpose:** Proxy for Form.io Select components that need to fetch data from PostgreSQL.

**Auth:** Bearer JWT

**Query Params:**
- `table=tbl_clients` — Target table name
- Any other param is treated as a WHERE filter column

---

## 7. Configuration Reference

### `application.properties` (relevant keys)

```properties
# Form.io server URL (self-hosted)
formio.url=http://your-formio-server:3001

# Form.io admin credentials (used by FormIoAuthService)
formio.admin-email=admin@yourserver.com
formio.admin-password=yourpassword

# Backend URL (used by BFF controller to build apiBaseUrl)
app.backend.url=http://localhost:8080

# Frontend URL (used for CORS)
app.frontend.url=http://localhost:5173
```

### SecurityConfig.java

The following line is **required** for the iframe to load:

```java
.requestMatchers(AntPathRequestMatcher.antMatcher("/form-builder.html"))
.permitAll()
```

Without this, Spring Security blocks the browser from loading the static HTML file because it doesn't have a Bearer token at load time. The iframe gets its token from the URL query parameter, not from a cookie.

---

## 8. Frontend Integration

### FormManager.tsx Key Behaviors

**Loading forms:**
```typescript
const res = await fetch(
  `${API_BASE_URL}/api/forms/form?type=form&limit=1000&select=_id,title,path,name,tags,modified`,
  { headers: { Authorization: `Bearer ${token}` } }
);
```

**Opening the builder iframe:**
```typescript
const src =
  `${API_BASE_URL}/form-builder.html` +
  `?token=${encodeURIComponent(token)}` +
  `&api=${encodeURIComponent(API_BASE_URL + "/api")}` +
  (formPath ? `&formPath=${encodeURIComponent(formPath)}` : "");
```

**Listening for iframe messages:**
```typescript
const handler = (e: MessageEvent) => {
  if (e.data?.type === "FORMIO_FORM_SAVED") {
    onSaved();   // Refresh form list
  }
  if (e.data?.type === "FORMIO_BUILDER_CLOSED") {
    onClose();   // Hide the overlay
  }
};
window.addEventListener("message", handler);
```

**Filtering out tenant tags from display:**
```typescript
const displayTags = (form.tags || []).filter((t) => !t.startsWith("tenant:"));
```

This prevents the internal `tenant:uuid` tag from showing up on the form card UI.

---

### Adding Forms to Navigation (App.tsx)

Add this route inside the tenant layout `<Route path="/:tenantId">`:

```tsx
<Route path="forms" element={<FormManager />} />
```

Add a NavIcon:
```tsx
<NavIcon to={`/${currentTenant}/forms`} icon="fas fa-wpforms" label="Form Builder" />
```

---

### Fetching Forms for Dropdowns (api.ts)

The `fetchAllForms()` function is used in `ActionEditorModal.tsx` to populate the "Target Form" dropdown when configuring BPMN task action buttons:

```typescript
export const fetchAllForms = async () => {
  const res = await api.get(
    "/api/forms/form?type=form&limit=100000&select=_id,title,path,name,key"
  );
  return Array.isArray(res.data) ? res.data : res.data.forms || [];
};
```

Because all requests go through the proxy, this automatically only returns forms for the current tenant.

---

## 9. Backend Deep Dive

### PathType Classification (FormIoProxyController)

The proxy must decide what to do with each request based on its URL pattern and HTTP method.

```java
private PathType classifyPath(String path, HttpMethod method) {
    String p = path.startsWith("/") ? path.substring(1) : path;

    // /form (POST = create, GET = list)
    if (p.equals("form") || p.startsWith("form?")) {
        if (method == HttpMethod.POST) return PathType.FORM_CREATE;
        return PathType.FORM_LIST;
    }

    // /form/{id} (PUT = update by internal ID)
    if (p.startsWith("form/")) {
        if (method == HttpMethod.PUT && !p.contains("/submission"))
            return PathType.FORM_UPDATE_BY_ID;
        return PathType.OTHER;
    }

    // /some-path/something (has slash, not form/)
    if (p.contains("/")) return PathType.FORM_SPECIFIC_CHILD;
    // /some-path (no slash)
    return PathType.FORM_SPECIFIC;
}
```

**Key distinction that was added:** `FORM_UPDATE_BY_ID` was introduced to handle `PUT /api/forms/form/{id}` correctly. Without this, update calls were incorrectly classified as `FORM_SPECIFIC` and the proxy would try to prefix `form/` as a form path, breaking updates entirely.

### injectTenantIntoFormBody

```java
private String injectTenantIntoFormBody(String body, String tenantSlug, String tenantId) {
    ObjectNode formJson = (ObjectNode) objectMapper.readTree(body);

    // 1. Prefix the path
    String originalPath = formJson.has("path") ? formJson.get("path").asText() : "";
    if (!originalPath.startsWith(tenantSlug + PREFIX_SEPARATOR)) {
        formJson.put("path", tenantSlug + PREFIX_SEPARATOR + originalPath);
    }

    // 2. Prefix name (safe format: no spaces or brackets, uses hyphen)
    // IMPORTANT: Form.io rejects names with spaces or brackets in some versions.
    // Earlier attempts used "[tenantSlug] name" which caused "Invalid alias" errors.
    if (formJson.has("name")) {
        String name = formJson.get("name").asText();
        String namePrefix = tenantSlug + "-";
        if (!name.startsWith(namePrefix)) {
            formJson.put("name", namePrefix + name);
        }
    }

    // 3. Prefix machineName (same safe format)
    if (formJson.has("machineName")) {
        String machineName = formJson.get("machineName").asText();
        String namePrefix = tenantSlug + "-";
        if (!machineName.startsWith(namePrefix)) {
            formJson.put("machineName", namePrefix + machineName);
        }
    }

    // 4. Inject tenant tag
    ArrayNode tags = ...; // existing or new empty array
    String tenantTag = "tenant:" + tenantId;
    // Only add if not already present
    if (!hasTag) tags.add(tenantTag);
    formJson.set("tags", tags);

    return objectMapper.writeValueAsString(formJson);
}
```

**Why the hyphen format (`tenantSlug-name`) vs bracket format (`[tenantSlug] name`):**  
Form.io has internal validation on `name` and `machineName` fields. Square brackets and spaces were rejected in some Form.io server versions with an "Invalid alias" error. The hyphen format (`saar-biotech-employee-form`) is safe because it uses only URL-safe characters.

### stripPrefixFromNode (Response Transformation)

```java
private void stripPrefixFromNode(JsonNode node, String tenantSlug) {
    if (!(node instanceof ObjectNode)) return;
    ObjectNode obj = (ObjectNode) node;

    // Strip from path
    if (obj.has("path")) {
        String path = obj.get("path").asText();
        obj.put("path", stripTenantPrefix(path, tenantSlug));
    }

    // Strip from name (hyphen format)
    if (obj.has("name")) {
        String name = obj.get("name").asText();
        String namePrefix = tenantSlug + "-";
        if (name.startsWith(namePrefix)) {
            obj.put("name", name.substring(namePrefix.length()));
        }
    }

    // Strip from machineName (hyphen format)
    if (obj.has("machineName")) {
        String machineName = obj.get("machineName").asText();
        String namePrefix = tenantSlug + "-";
        if (machineName.startsWith(namePrefix)) {
            obj.put("machineName", machineName.substring(namePrefix.length()));
        }
    }
}
```

### Critical Fix: Content-Length Header

After stripping prefixes, the response JSON body is smaller than the original. Without removing the `Content-Length` header, the browser would receive a truncated response because the declared size no longer matched the actual body size.

```java
private ResponseEntity<Object> cleanResponse(ResponseEntity<String> upstreamResponse, Object body) {
    HttpHeaders cleanHeaders = new HttpHeaders();
    cleanHeaders.putAll(upstreamResponse.getHeaders());
    // Remove CORS headers (Spring Boot adds its own)
    cleanHeaders.remove("Access-Control-Allow-Origin");
    // ...
    // CRITICAL: Remove stale Content-Length — Spring Boot recalculates it
    cleanHeaders.remove("Content-Length");
    // ...
}
```

### Why Response Body is Parsed as JsonNode (Not Passed as String)

```java
// ❌ Old approach: string passed directly → Content-Type mismatch
return new ResponseEntity<>(response.getBody(), cleanHeaders, status);

// ✅ New approach: parse to JsonNode → Spring serializes as proper JSON
Object finalResponseBody = response.getBody();
if (response.getBody() != null) {
    try {
        JsonNode root = objectMapper.readTree(response.getBody());
        // ... strip prefixes ...
        finalResponseBody = root; // JsonNode, not String
    } catch (Exception e) {
        finalResponseBody = response.getBody(); // fallback to raw string
    }
}
return cleanResponse(response, finalResponseBody);
```

When `finalResponseBody` is a `String` like `"{\"path\":\"...\"}"`, Spring Boot wraps it in another layer of JSON encoding, producing `"{\"path\":\"...\"}"` (a JSON string literal) instead of `{"path":"..."}` (a JSON object). Parsing to `JsonNode` first prevents this double-encoding.

### FormIoAuthService — Token Caching

```java
public String getAccessToken() {
    if (cachedToken == null) {
        login();
    }
    return cachedToken;
}
```

The service caches the Form.io admin JWT in memory. When any proxied request gets a `401` back from Form.io:

```java
} catch (HttpClientErrorException.Unauthorized e) {
    authService.invalidateToken();
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
}
```

The token is cleared and the next request will trigger a re-login. The Feign client (`FormIoClient`) has its own retry logic configured in `FormIoConfiguration.java` for the same purpose.

---

## 10. The form-builder.html Iframe

Located at: `src/main/resources/static/form-builder.html`  
Served by Spring Boot at: `http://localhost:8080/form-builder.html`

### URL Parameters Consumed

| Parameter | Source | Purpose |
|-----------|--------|---------|
| `token` | React (localStorage jwt_token) | Spring Boot JWT for authenticating `/api/formio-bff/config` and all proxy calls |
| `api` | React (API_BASE_URL + "/api") | Base URL for constructing FORMS_API and BFF_API |
| `formPath` | React (form.path) | If present, the iframe is in edit mode and loads this form |

### Boot Sequence (JavaScript)

```javascript
(async function boot() {
    // 1. Validate token presence
    if (!jwtToken) throw new Error('No authentication token provided.');

    // 2. Fetch config from BFF
    const config = await fetchConfig();
    formioToken = config.formioToken;
    tenantSlug  = config.tenantSlug;

    // 3. Load existing form if in edit mode
    let initialSchema = {};
    if (editPath) initialSchema = await loadExistingForm(editPath);

    // 4. Initialize the Form.io builder
    await initBuilder(initialSchema);
})();
```

### Formio SDK Configuration

```javascript
// Intercept all SDK fetch calls to add the Authorization header
const originalFetch = Formio.fetch;
Formio.fetch = function(url, options) {
    options = options || {};
    options.headers = options.headers || {};
    options.headers['Authorization'] = 'Bearer ' + jwtToken;
    return originalFetch.call(this, url, options);
};

// Point the SDK at our proxy, not directly at Form.io
Formio.setBaseUrl(FORMS_API);   // http://localhost:8080/api/forms
Formio.setProjectUrl(FORMS_API);
```

This is why the iframe can make form CRUD calls through the proxy — every internal SDK fetch call automatically includes the user's JWT, and the proxy validates it to determine the tenant context.

### Save Logic (New Form vs Edit)

```javascript
if (existingFormId) {
    // Update by internal Form.io ID
    res = await fetch(FORMS_API + '/form/' + existingFormId, {
        method: 'PUT', ...
    });
} else {
    // Create new form
    res = await fetch(FORMS_API + '/form', {
        method: 'POST', ...
    });
}
```

### postMessage Communication

**From iframe to React:**

```javascript
// After successful save
window.parent.postMessage({
    type: 'FORMIO_FORM_SAVED',
    formId: saved._id,
    formPath: saved.path,   // Already stripped of prefix by proxy
    formTitle: saved.title
}, '*');

// When user clicks Cancel/Back
window.parent.postMessage({ type: 'FORMIO_BUILDER_CLOSED' }, '*');
```

**React listens:**
```typescript
const handler = (e: MessageEvent) => {
    if (e.data?.type === "FORMIO_FORM_SAVED") onSaved();
    if (e.data?.type === "FORMIO_BUILDER_CLOSED") onClose();
};
window.addEventListener("message", handler);
return () => window.removeEventListener("message", handler);
```

---

## 11. Known Issues & Debug Guide

### Issue: "Invalid alias" error when saving a form

**Symptom:** Form.io returns a 400 with "Invalid alias" when the iframe tries to save.

**Root Cause:** The `name` or `machineName` field contained characters Form.io rejects (spaces, brackets). Earlier implementation used `[tenantSlug] formname` format.

**Fix Applied:** Changed to hyphen format: `tenantSlug-formname`. Check `injectTenantIntoFormBody()` and `stripPrefixFromNode()` — both must use the same format consistently.

**Debug Step:**  
Log the transformed body in `injectTenantIntoFormBody()` before forwarding to see exactly what's being sent to Form.io.

---

### Issue: Forms from other tenants appearing in the list

**Symptom:** A user sees forms that belong to a different tenant.

**Root Cause:** The tag filter is not being applied, or the tenant tag was never injected when the form was created.

**Debug Steps:**
1. Open Form.io admin UI directly (http://yourformio:3001) and check if the form has a `tenant:{id}` tag.
2. Check the `injectTenantFilter()` method — ensure it's adding `&tags=tenant:{tenantId}` and not an empty tenantId.
3. Check `UserContextService.getCurrentTenantId()` — if this returns null, the filter won't be applied. This means the JWT is missing the `tenantId` claim.

---

### Issue: Response is truncated / JSON parse error on frontend

**Symptom:** The frontend receives a partial JSON response or a JSON string that is itself a string (double-encoded).

**Root Cause:** Either the `Content-Length` header wasn't removed after stripping prefixes, or the response body was returned as a `String` instead of a `JsonNode`.

**Fix Applied:** `cleanResponse()` removes `Content-Length`, and the proxy parses the response into `JsonNode` before returning.

**Debug Step:**  
Check if `cleanHeaders.remove("Content-Length")` is present in `cleanResponse()`.

---

### Issue: Builder loads but shows "blank" or white screen

**Symptom:** The iframe loads but the Form.io builder canvas is empty/white.

**Root Cause 1:** The `formio.full.min.js` CDN is blocked or unreachable.  
**Fix:** Check network tab in browser devtools inside the iframe context.

**Root Cause 2:** `Formio.setBaseUrl()` points to an incorrect URL.  
**Fix:** Verify the `api` URL param passed to the iframe is correct.

**Root Cause 3:** The BFF config call failed (401 or 500), so `formioToken` is null.  
**Fix:** Check the browser network tab for the `/api/formio-bff/config` call. If it's 401, the JWT in `localStorage.getItem("jwt_token")` is expired. If 500, Form.io credentials in `application.properties` are wrong.

---

### Issue: Edit mode doesn't load the existing form

**Symptom:** Builder opens blank even when `formPath` is passed in the URL.

**Root Cause:** `GET /api/forms/{formPath}` returns 404. This can happen if the form was saved before the proxy was added (no prefix in Form.io), or the prefix logic has a mismatch.

**Debug Steps:**
1. Check what path is stored in Form.io directly (admin UI).
2. Call `GET /api/forms/{formPath}` directly and check what URL the proxy constructs — add a log in `addPrefixToSpecificPath()`.

---

### Issue: Tenant filter returns no forms even when forms exist

**Symptom:** `GET /api/forms/form` returns an empty array even though forms were saved.

**Root Cause:** The forms were saved with a different `tenantId` tag than the one being used to filter now. This can happen if the tenant UUID changed.

**Debug Step:**  
Call `GET /api/forms/form` without the tag filter by temporarily logging the Form.io URL in the proxy. Check what tags the forms actually have in Form.io.

---

### Issue: CORS error when iframe loads

**Symptom:** Browser console shows CORS error on `/api/formio-bff/config` from the iframe.

**Root Cause:** The iframe is served from `http://localhost:8080` but `app.frontend.url` in `application.properties` only allows `http://localhost:5173`.

**Note:** Since the iframe is served by Spring Boot itself, it's on the same origin as the API. This should not cause CORS issues. If it does, check if the `SecurityConfig` CORS configuration explicitly lists `http://localhost:8080` or uses `*`.

---

## 12. Security Model

### No Direct Form.io Browser Access

No client browser ever makes a request directly to the Form.io server. All Form.io traffic flows through the Spring Boot proxy, which:
- Validates the user's JWT
- Extracts the tenant from the JWT
- Injects tenant prefixes/tags before forwarding
- Strips prefixes from responses

Even if a user somehow discovered the Form.io server URL, they would need the admin credentials (stored only in `application.properties` on the server) to do anything.

### The "Illusion" — Prefix Transparency

Tenants never see the `{tenantSlug}--` prefix. From their perspective, their form is named `employee-form`. In Form.io's database it's `saar-biotech--employee-form`. The proxy maintains this illusion bidirectionally.

### Token Flow

```
Browser localStorage:  Spring Boot JWT (scoped to user + tenant)
                         ↓ (passed as URL param to iframe)
iframe:                  Spring Boot JWT  →  GET /api/formio-bff/config
                                                ↓ (never returned to browser)
Spring Boot (memory):    Form.io Admin JWT  →  All Form.io API calls
```

The Form.io admin JWT is **never** sent to the browser. The iframe receives it from the BFF config endpoint only in memory (JavaScript variable) and uses it only for the `x-jwt-token` header on requests to the proxy — which then forwards it to Form.io. Since all Form.io traffic goes through the proxy, the admin token is effectively invisible to end users.

### Tenant Isolation Verification

Every proxy request extracts the tenant from the authenticated Spring Security context:

```java
String tenantSlug = userContextService.getCurrentTenantSlug();
String tenantId   = userContextService.getCurrentTenantId();
```

`UserContextService` reads these from the JWT claims set during OAuth2 login. There is no way for a user to pass a different tenant in a request — the proxy ignores any tenant information in the request body and uses only what's in the validated JWT.

---

## 13. FAQ

**Q: Can a developer from Tenant A see Tenant B's forms?**  
A: No. The tenant tag filter is applied server-side by Form.io itself. Even if Tenant A's developer made a raw HTTP request to `/api/forms/form`, the Spring Boot proxy would inject `&tags=tenant:{TenantA_ID}` and Form.io would return only Tenant A's forms.

---

**Q: What happens if two tenants create a form with the same path?**  
A: No conflict. Tenant A creates `employee-form` → stored as `acme--employee-form`. Tenant B creates `employee-form` → stored as `globocorp--employee-form`. They are completely separate in Form.io's database.

---

**Q: Can I add custom properties to the Form.io builder (like sqlConfig)?**  
A: Yes. This is done inside `form-builder.html` by customizing the `Formio.builder()` options object. The builder's job is only to generate a JSON schema. Custom properties like `sqlConfig` are saved as part of the submit button's `properties` object in the JSON. They are then read and acted upon by `FormIoProxyController`'s write intercept when a form submission comes in.

---

**Q: Why is the form-builder.html served by Spring Boot instead of the React public folder?**  
A: Spring Boot serving it keeps all the authentication infrastructure in one place. The iframe calls `/api/formio-bff/config` which is a Spring Boot endpoint — having the HTML file on the same origin as the API avoids any potential same-origin complications and keeps deployment simpler.

---

**Q: What is the `sql` tag and how does it work?**  
A: When a form is tagged with `sql` in Form.io, the proxy intercepts both reads and writes for that form's submissions and routes them to PostgreSQL via `DataMirrorService` instead of (or in addition to) storing in Form.io's MongoDB. This is the mechanism that enables the `sqlConfig` feature on submit buttons.

---

**Q: The form list shows the tenant tag (tenant:uuid). How do I hide it?**  
A: In `FormManager.tsx`, filter it out before rendering:
```typescript
const displayTags = (form.tags || []).filter((t) => !t.startsWith("tenant:"));
```
This is already implemented. If you want to also strip them from API responses, uncomment the tag-cleaning block in `stripPrefixFromNode()` in `FormIoProxyController.java`.

---

**Q: What Form.io version is being used?**  
A: `formiojs@4.15.0` via CDN in `form-builder.html`. The Feign client connects to whatever version of the Form.io server is running at `formio.url`.

---

**Q: Does deleting a form from the UI actually delete it from Form.io?**  
A: Yes. `FormManager.tsx` calls `DELETE /api/forms/{path}`, the proxy prefixes the path and calls `DELETE {formIoUrl}/{tenantSlug}--{path}`. Form.io handles the deletion.

---

**Q: What happens to submissions if a form is deleted?**  
A: Form.io deletes all submissions associated with the form. This is Form.io's built-in behavior when a form is deleted.

---

*End of Document*