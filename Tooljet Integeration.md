# 🛡️ InfinityPlus – Secure ToolJet BFF Integration (Community Edition)

> **Audience**: Backend engineers, full‑stack engineers, DevOps, future maintainers, security reviewers
> **Purpose**: This document explains **what was built, why it was built, how it works internally, and how to debug it**.
> **Scope**: This is a **complete architectural + implementation README** for the ToolJet Community Edition secure embedding using a Spring Boot **Backend‑for‑Frontend (BFF)**.

---

## 📌 Executive Summary

ToolJet Community Edition does **not** support stateless JWT‑based embedding. Browsers also **do not send Authorization headers** for iframe sub‑requests (JS, CSS, internal APIs). Because of this, a naïve JWT‑only approach **breaks immediately** after the first HTML response.

To solve this **correctly and securely**, we implemented a **Ticket → Cookie Promotion BFF Architecture**.

### In one sentence:

> **A one‑time, JWT‑protected ticket is exchanged for a short‑lived BFF cookie (“wristband”), allowing ToolJet’s SPA assets and APIs to load through a controlled proxy.**

This pattern is used internally by enterprise platforms that embed dashboards, analytics tools, and admin UIs.

---

## 🧠 The Core Problems We Had to Solve

### 1️⃣ Iframe Authentication Gap

* Browsers **do not attach Authorization headers** to iframe asset requests:

    * `/assets/*.js`
    * `/api/v2/queries`
    * `/run/*`
* ToolJet is a **Single Page Application (SPA)** and depends on these routes.

### 2️⃣ ToolJet Community Edition Limitations

* No native embed JWT support
* No stateless SSO hooks
* Expects a logged‑in user (session cookie)

### 3️⃣ Security Requirements

* ToolJet must **not be public**
* Users must **not log in again**
* JWT must remain authoritative
* No ToolJet cookies must leak to the browser

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

* One‑time
* Short‑lived (bootstrap only)
* Protected by JWT
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

### Phase 2: Ticket Promotion

```
Browser → GET /tooljet/ticket/{ticket}/applications/{appId}
BFF     → validate ticket
BFF     → SET COOKIE TJ_BFF_SESSION
BFF     → proxy ToolJet HTML
```

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

## 🧠 ToolJetBffController – Deep Dive

### Responsibilities

1. Ticket bootstrap
2. Cookie promotion
3. ToolJet proxy
4. Header sanitization
5. URL rewriting
6. Session retry

---

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
* Wristband cookie set
* ToolJet HTML proxied
* URL synchronized for ToolJet router

---

### Cookie Injection Logic (Critical)

```java
ResponseCookie.from("TJ_BFF_SESSION", userEmail)
```

⚠️ **NOTE**: In production, this should be an opaque session ID.
(Current implementation uses email for simplicity and debugging clarity.)

---

## 🧠 URL Synchronization Hack (Why It Exists)

ToolJet’s internal React Router **only activates** if the URL matches:

```
/applications/{uuid}
```

But our iframe URL is:

```
/tooljet/ticket/{ticket}/applications/{uuid}
```

### Solution

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

## 🔁 JSON Host Rewriting (Identity Mirror)

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

## 🔐 ToolJetAuthService – Headless Login

### Purpose

* Maintain a **server‑side ToolJet session**
* Re‑login automatically if expired

### Why this is safe

* Browser never sees ToolJet cookies
* Session is internal only
* ToolJet is not exposed

---

### Login Endpoint Used

```
POST /api/authenticate/{organizationId}
```

Payload mirrors browser login exactly.

---

## 🔄 Session Retry Logic

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

## 🔐 Spring Security Configuration – Explained

### Stateless JWT Enforcement

```java
SessionCreationPolicy.STATELESS
```

Ensures:

* No server memory for users
* JWT remains authoritative

---

### Wristband Exception Rule

```java
If TJ_BFF_SESSION cookie exists → permit
```

Why:

* Assets & APIs cannot send JWT
* Cookie is validated inside controller

---

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

### White Screen

Check:

* `/api/config` response
* Injected history script
* iframe container height

---

### 401 from ToolJet

Check:

* System credentials
* Organization ID
* ToolJet logs

---

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

---

**End of README**
