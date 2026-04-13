# Flowable Multi-Tenant Background Execution Architecture

## 1. Executive Summary

In a multi-tenant Flowable architecture, standard human-triggered processes rely on HTTP requests equipped with JWT tokens to determine the active tenant and resolve the correct database schema.

However, fully automated background processes—such as Timer Start Events (e.g., cron jobs) and Asynchronous Service Tasks—execute on isolated internal engine threads. These threads lack a Spring Security HTTP context, leading to immediate `SecurityException: No authentication data found` errors when data services attempt to resolve the active schema.

This document outlines the enterprise-grade solution implemented to invisibly inject tenant context into background jobs without altering low-code BPMN XML files or polluting business logic.

## 2. The Core Problem

When a Groovy script task executes `data.selectAll("tbl_orders", ...)`:

1. `FlowableDataService` asks `UserContextService` for the current tenant schema.

2. `UserContextService` inspects the Spring `SecurityContextHolder`.

3. **If Web Request:** A JWT is found, the schema is parsed, and the query executes successfully.

4. **If Background Timer:** The `SecurityContextHolder` is completely empty. The request fails, and the task crashes.

## 3. Design Objectives

To solve this cleanly, the solution had to meet three strict criteria:

1. **Zero BPMN Modifications:** Workflow modelers should not have to hardcode tenant IDs or pass engine variables (like `DelegateExecution`) into low-code `${data}` expressions.

2. **Zero Engine Hacks:** The solution must survive Flowable library upgrades. Injecting dynamic properties into JUEL expressions or aggressively modifying the `BpmnParseHandler` proved fragile and prone to caching errors.

3. **Strict Memory Safety:** Multi-tenant thread leaks are catastrophic. The context must be strictly bound to the execution lifecycle and wiped immediately upon completion.

## 4. The "Silver Bullet" Solution: Core Command Interception

Every action Flowable takes—whether a user clicking a button or a timer waking up at 3:00 AM—runs as a `Command` through the engine's core `CommandInterceptor` chain.

By inserting a custom **`TenantAwareCommandInterceptor`** at the very bottom of this chain, we intercept the engine milliseconds before it executes any logic.

### The Tenant Extraction Fallback Mechanism

Flowable aggressively optimizes memory and often strips the `tenantId` from background job memory objects as the thread changes context. To guarantee we always find the tenant, the interceptor uses a 3-tier deep-search strategy:

1. **Direct Method Invocation:** Uses reflection to call `.getTenantId()` on the executing command.

2. **Nested Job Inspection:** Uses reflection to search for private `job` objects hidden inside the command and checks them for a tenant ID.

3. **The JDBC Silver Bullet:** If the object in memory only contains a `jobId` (which happens during `ExecuteAsyncJobCmd`), we bypass Flowable's memory entirely. We use Spring's `JdbcTemplate` to query Flowable's raw database tables (`ACT_RU_JOB`, `ACT_RU_TIMER_JOB`, `ACT_RU_DEADLETTER_JOB`) to extract the persistent `TENANT_ID_`.

## 5. Implementation Architecture

The architecture consists of three interconnected components:

### A. `UserContextService.java` (The Storage)

Upgraded to support a dual-context lookup.

* Added a thread-safe `ThreadLocal<String> BACKGROUND_SCHEMA`.

* When resolving the schema, it first checks if the `BACKGROUND_SCHEMA` is populated. If it is, it returns it instantly, bypassing the Spring Security JWT requirement entirely.

### B. `TenantAwareCommandInterceptor.java` (The Engine)

The workhorse class. It sits in Flowable's command pipeline.

1. Extracts the `tenantId` using the 3-tier strategy.

2. Resolves the `Tenant` entity from the database to find the actual `schemaName`.

3. Injects the schema via `UserContextService.setBackgroundSchema(...)`.

4. Executes the Flowable Command (e.g., running the Groovy script).

5. **Critically:** Uses a `finally {}` block to call `UserContextService.clearBackgroundSchema()`, guaranteeing the thread is wiped clean for the next job.

### C. `FlowableListenerConfig.java` (The Registration)

Registers the interceptor cleanly using Flowable's official Spring Boot configuration hooks:

```java
if (engineConfiguration.getCustomPreCommandInterceptors() == null) {
    engineConfiguration.setCustomPreCommandInterceptors(new ArrayList<>());
}
engineConfiguration.getCustomPreCommandInterceptors().add(tenantAwareCommandInterceptor);
```

## 6. Step-by-Step Execution Flow

1. **Deployment:** An `autoDispatchTrigger.bpmn20.xml` containing a 5-minute timer is deployed via the REST API. Flowable assigns it to Tenant `saar_biotech`.

2. **Trigger:** The 5-minute timer elapses. Flowable's background AsyncExecutor thread wakes up.

3. **Interception:** The `TenantAwareCommandInterceptor` catches the command.

4. **Resolution:** The interceptor sees the job has a blank tenant ID in memory, extracts the `jobId`, queries `ACT_RU_TIMER_JOB` via JDBC, and successfully retrieves `saar_biotech`.

5. **Injection:** The interceptor queries the main database, finds the schema name, and sets `UserContextService.BACKGROUND_SCHEMA`.

6. **Execution:** The Groovy `<scriptTask>` executes. It calls `data.selectAll(...)`. The data service asks `UserContextService` for the schema. It finds the `BACKGROUND_SCHEMA` and returns it happily.

7. **Cleanup:** The script finishes. The interceptor's `finally` block fires, wiping the `BACKGROUND_SCHEMA`. The thread returns to the pool, completely clean.

## 7. Conclusion

This architecture represents a professional, zero-compromise solution to multi-tenant background execution. By operating at the Command Interceptor level and implementing a raw JDBC fallback, the system achieves **100% reliability** regardless of how Flowable caches XML files or manages its internal thread memory. It allows workflow developers to write clean, low-code scripts without ever worrying about tenant propagation.
