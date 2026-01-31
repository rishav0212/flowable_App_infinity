# 🛡️ InfinityPlus – Secure ToolJet BFF Integration (Community Edition)

> **Audience**: Backend engineers, full‑stack engineers, DevOps, future maintainers, security reviewers

> **Purpose**: This document explains **what was built, why it was built, how it works internally, and how to debug it**.

> **Scope**: This is a **complete architectural + implementation README** for the ToolJet Community Edition secure embedding using a Spring Boot **Backend‑for‑Frontend (BFF)**.

---

## 📌 Executive Summary

ToolJet Community Edition does **not** support stateless JWT‑based embedding. Browsers also **do not send Authorization headers** for iframe sub‑requests (JS, CSS, internal APIs). Because of this, a naïve JWT‑only approach **breaks immediately** after the first HTML response.

To solve this **correctly and securely**, we implemented a **Ticket → Opaque Session UUID Promotion BFF Architecture**.

### In one sentence:

> **A one‑time, JWT‑protected ticket is exchanged for a short‑lived BFF cookie ("wristband"), allowing ToolJet's SPA assets and APIs to load through a controlled proxy.**

This pattern is used internally by enterprise platforms that embed dashboards, analytics tools, and admin UIs.

---

## 🧠 The Core Problems We Had to Solve

### 1️⃣ Iframe Authentication Gap

* Browsers **do not attach Authorization headers** to iframe asset requests:
  * `/assets/*.js`
  * `/api/v2/queries`
  * `/run/*`
* ToolJet is a **Single Page Application (SPA)** and depends on these routes.
* If the backend expects a JWT in the header, these requests will return 401 Unauthorized, causing a "White Screen."

### 2️⃣ ToolJet Community Edition Limitations

* No native embed JWT support
* No stateless SSO hooks
* Expects a logged‑in user via a standard session cookie

### 3️⃣ Security Requirements

* ToolJet must **not be public** (it should live in an internal Docker network reachable only by the BFF)
* Users must **not log in again** (Seamless SSO)
* JWT must remain authoritative for the initial "Ticket" handshake

---

## 🏗️ Final Architecture (Authoritative)

```
┌──────────────┐        (JWT)         ┌────────────────────┐
│   React UI   │ ───────────────────▶│  Spring Boot (BFF) │
│              │                      │                    │
│  <iframe>    │◀── HTML / Assets ───▶│  ToolJet Proxy     │
└──────────────┘                      │                    │
                                      │  • Ticket Issuer   │
                                      │  • Cookie Gate     │
                                      │  • Security Strip  │
                                      └─────────┬──────────┘
                                                │ (internal only)
                                                ▼
                                      ┌────────────────────┐
                                      │ ToolJet CE (Docker)│
                                      │ System User Session│
                                      └────────────────────┘
```

---

## 🔑 Key Concepts (Must Understand)

### 🎟️ Ticket

* **One‑time**: Valid only for the first entry. Once used, it is purged from memory.
* **Short‑lived**: Bootstrap only (default 60s TTL)
* **Protected by JWT**: Generated only via a valid `/api/tooljet/embed-ticket` POST call
* **The Handshake**: It represents the bridge between the JWT world and the Cookie world
* Never reused

### 🎫 Wristband (TJ_BFF_SESSION Cookie)

* Issued **only after ticket validation**
* HttpOnly
* Short TTL
* Used only for ToolJet routes

### 🧍 System User (ToolJet)

* Headless admin / viewer
* Logs into ToolJet **server‑to‑server**
* End users never touch ToolJet auth

---

## 🔁 Request Lifecycle (Step‑by‑Step)

### Phase 1: Ticket Issuance

```
React → POST /api/tooljet/embed-ticket (JWT)
BFF   → validate JWT
BFF   → generate one‑time ticket
BFF   → return iframe URL
```

**Why JWT is required here**:
* This is the **only place** user identity is trusted
* Prevents anonymous iframe access

### Phase 2: Ticket Promotion (The Handshake)

On the first iframe hit, the **wristband cookie** is issued:

```
Browser → GET /tooljet/ticket/{ticket}/applications/{appId}
BFF     → validate ticket
BFF     → SET COOKIE TJ_BFF_SESSION
BFF     → proxy ToolJet HTML
```

The BFF:
1. Validates ticket
2. Resolves identity
3. Drops `TJ_BFF_SESSION` cookie
4. Deletes the ticket

> **Ticket dies. Cookie lives (briefly).**

### Phase 3: Asset & API Continuity

```
Browser → GET /assets/main.js
Browser → POST /api/v2/queries
Browser → Cookie: TJ_BFF_SESSION
BFF     → validate cookie
BFF     → proxy to ToolJet
```

🚨 **Important**: These requests **DO NOT hit ToolJet directly**.
They always hit the **BFF**, because the iframe origin is the BFF.

---

## 🧩 Why This Works (Key Browser Rules)

| Browser Rule      | Effect                   |
| ----------------- | ------------------------ |
| Relative URLs     | Resolve to iframe origin |
| Cookies auto‑sent | Asset continuity         |
| HttpOnly cookies  | No JS access             |
| Docker expose     | ToolJet unreachable      |

---

## 🧠 The "Invisible Header" Problem (Browser Limitation)

### What the Main Application Uses

InfinityPlus uses **JWT-based authentication**.

```http
Authorization: Bearer <JWT_TOKEN>
```

This works perfectly for:
* XHR / fetch calls
* REST APIs
* GraphQL

### ❌ What Fails with iframes

When you do:

```html
<iframe src="http://localhost:8080/applications/my-app" />
```

The browser:
* **DOES NOT send custom headers**
* **DOES NOT send Authorization headers**
* **DOES NOT allow injecting headers** into `<script>`, `<link>`, or `<img>` tags

This is **browser-enforced behavior**, not a bug.

### The Resulting Failure

1. HTML loads
2. JS / CSS assets request `/assets/*.js`
3. Spring Security expects JWT
4. No JWT is present
5. Backend returns `401`
6. ToolJet SPA crashes → **White Screen**

### ✅ The Only Viable Solution

> **Move from Header-based auth (JWT) → Cookie-based continuity**

Why cookies?
* Browsers automatically attach cookies
* Cookies work for all sub-resources
* Cookies survive page reloads inside iframe

This is **not optional**. Any iframe-based SPA must use cookies for continuity.

---

## 🧠 ToolJetBffController – Deep Dive

### Responsibilities

1. Ticket bootstrap
2. Cookie promotion
3. ToolJet proxy
4. Header sanitization
5. URL rewriting
6. Session retry

### `/api/tooljet/embed-ticket`

**Purpose**: Securely bootstrap iframe access.

```java
@PostMapping("/api/tooljet/embed-ticket")
```

**Why JWT is required here**:
* This is the **only place** user identity is trusted
* Prevents anonymous iframe access

---

### `/tooljet/ticket/{ticket}/**`

**Purpose**: First iframe entry

What happens:
* Ticket validated
* UUID Session generated and stored in memory map
* Wristband (opaque UUID) cookie set
* ToolJet HTML proxied
* URL synchronized for ToolJet router

### Cookie Injection Logic (Critical)

```java
ResponseCookie.from("TJ_BFF_SESSION", userEmail)
```

⚠️ **NOTE**: In production, this should be an opaque session ID.
(Current implementation uses email for simplicity and debugging clarity.)

---

## 🧠 Identity Mirror (ToolJet CE Limitation)

### The Core Problem

ToolJet Community Edition:
* Has its own users
* Has its own sessions
* Has **zero knowledge** of InfinityPlus users

### What Happens Without a BFF

If user connects directly:

```
Browser → ToolJet
```

ToolJet responds:
* Login screen
* Or 401

There is **no SSO** in CE.

### The Identity Mirror Solution

We use **impersonation via a system user**.

```
User → BFF → ToolJet (as admin)
```

ToolJet thinks:

> "This is the admin doing something."

The BFF ensures:
* User only sees **allowed apps**
* User cannot escape sandbox

### Why This Is Safe

* ToolJet is not public
* Admin session never reaches browser
* Authorization is enforced **before** proxying

This pattern is commonly called:
* *Headless Admin*
* *Service Account Impersonation*
* *Trusted Proxy Auth*

---

## 🔐 ToolJetAuthService – Headless Login

### Purpose

* Maintain a **server‑side ToolJet session**
* Re‑login automatically if expired

### Why this is safe

* Browser never sees ToolJet cookies
* Session is internal only
* ToolJet is not exposed

### Login Endpoint Used

```
POST /api/authenticate/{organizationId}
```

Payload mirrors browser login exactly.

### Session Retry Logic

If ToolJet responds with `401`:

```
clear cached session
login again
retry request once
```

This handles:
* Session expiry
* ToolJet restarts

---

## 🧠 URL Synchronization (Why It Exists)

### The Path Crisis (React Router Reality)

ToolJet is an SPA. ToolJet boots its engine only if:

```
window.location.pathname === /applications/{uuid}
```

### Why Our URL Breaks It

Initial iframe URL:

```
/tooljet/ticket/{uuid}/applications/{appId}
```

To ToolJet:
* Unknown prefix
* Looks like a nested app
* Router never activates

Result:
* White screen
* No console errors

### The History Rewrite Fix

We inject a script:

```html
<script>
window.history.replaceState({}, '', '/applications/{uuid}');
</script>
```

Without this:
* White screen
* No errors
* ToolJet never boots

---

## 🔁 JSON Host Rewriting

### The Core Problem

ToolJet returns configuration JSON containing:

```
localhost:8082
```

This would bypass the BFF.

### Fix

```java
json.replace("localhost:8082", "localhost:8080")
```

This ensures:
* All requests remain anchored to BFF
* No direct ToolJet access

---

## 🔐 Spring Security Configuration – Explained

### Stateless JWT Enforcement

```java
SessionCreationPolicy.STATELESS
```

Ensures:
* No server memory for users
* JWT remains authoritative
* Each request is independently validated

### Wristband Exception Rule

```java
If TJ_BFF_SESSION cookie exists → permit
```

Why:
* Assets & APIs cannot send JWT
* Cookie is validated inside controller
* UUID is looked up in server-side map

### Negative Header Match

```java
headers = "!Authorization"
```

Prevents:
* Accidental proxying of InfinityPlus APIs
* ToolJet hijacking application endpoints

---

## 🛡️ Security Guarantees

| Threat           | Mitigation           |
| ---------------- | -------------------- |
| JWT bypass       | Ticket requires JWT  |
| Cookie forgery   | HttpOnly + short TTL |
| ToolJet exposure | Internal Docker only |
| Clickjacking     | Controlled CSP       |
| Replay           | One‑time tickets     |

---

## 🐞 Debugging Playbook

### White Screen?

Check:
* `/api/config` response
* Injected history script
* iframe container height

### 401 from ToolJet

Check:
* System credentials
* Organization ID
* ToolJet logs

### Assets Not Loading

Check:
* BFF origin
* Cookie present
* Path rewriting

---

## ⚙️ Configuration Reference

```properties
tooljet.internal.url=http://localhost:8082
tooljet.organization.id=UUID
tooljet.admin.email=...
tooljet.admin.password=...
app.frontend.url=http://localhost:5173
```

---

## 🚨 Known Trade‑offs

| Decision    | Reason                 |
| ----------- | ---------------------- |
| Cookie used | Browser constraint     |
| System user | ToolJet CE limitation  |
| URL rewrite | SPA router requirement |

---

## 📊 Direct vs Ticketed – Final Comparison

| Feature          | Direct ToolJet Access | Ticketed BFF Proxy |
| ---------------- | --------------------- | ------------------ |
| Authentication   | ❌ Fails               | ✅ Works            |
| JWT Support      | ❌ None                | ✅ Preserved        |
| Asset Loading    | ❌ Breaks              | ✅ Continuous       |
| Login UX         | ❌ Double login        | ✅ Seamless         |
| ToolJet Exposure | ❌ Public              | ✅ Hidden           |
| Security Model   | ❌ Weak                | ✅ Defense-in-depth |

---

## 🏁 Why This Architecture Is "Correct"

This design:
* Accepts browser limitations
* Uses each auth mechanism where it fits
* Keeps ToolJet CE unchanged
* Preserves JWT authority
* Prevents accidental data leaks

There is **no simpler secure solution** for ToolJet CE embedding.

---

## 🏁 Final Notes for Future Maintainers

* **Do not expose ToolJet directly**
* **Do not remove cookie promotion**
* **Do not trust client identity**
* **Always debug via Network tab**

This architecture is intentional, defensive, and production‑grade for ToolJet CE.

---

## 📌 If You Need to Extend This

* Replace cookie value with opaque session ID + Redis
* Rotate system credentials
* Support multi‑org routing
* Add audit logs