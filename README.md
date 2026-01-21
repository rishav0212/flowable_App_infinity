# 🔒 Strict Business Key Enforcement

To maintain **data integrity**, **prevent duplicate workflows**, and ensure **predictable process execution**, the system strictly enforces the presence and uniqueness of the `businessKey` when starting process instances.

This enforcement applies **globally** to **all process-start endpoints**, including the **standard Flowable Runtime API**.

---

## 📌 1. Enforcement Rules

### ✅ Mandatory Business Key
- Every request to start a **standard business process** **must include** a `businessKey`.
- Requests **without** a `businessKey` will be rejected.

---

### 🔁 Uniqueness Guarantee
The `businessKey` must be **globally unique per process definition**.

It **must NOT match** any existing instance that is:

- 🟢 **Active** (currently running)
- 🔵 **Completed** (successfully finished)

#### ♻️ Allowed Reuse (Exception)
A `businessKey` **CAN be reused** **only if** the previous process instance was:

- 🔴 **Terminated / Deleted**
- 📝 And the **delete reason is NOT null**

This allows safe reprocessing of intentionally aborted workflows.

---

## 🚫 2. Bypassing Enforcement (System & Scheduler Processes)

Some automated processes (e.g., schedulers, background jobs, cleanup tasks) **do not require** a human-defined business key.

These processes can **bypass** the enforcement rules.

### 🏷️ Bypass Criteria
If the **process category** is set to:
- `SCHEDULER`
- `SYSTEM`

➡️ Business key validation is **skipped**.

---

### 🧩 BPMN Configuration for Bypass

Set the `targetNamespace` attribute in the `<definitions>` tag.

Example:

    <definitions 
        xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
        targetNamespace="SCHEDULER">

        <process id="emailScheduler"
                 name="15-Minute Email Scheduler"
                 isExecutable="true">
            ...
        </process>
    </definitions>

Processes under this namespace will **not require** a `businessKey`.

---

## 🚀 3. API Usage

### 🔗 Endpoint

    POST /process-api/runtime/process-instances

---

### 📦 Request Body (Standard Process)

    {
      "processDefinitionKey": "dispatchProcess",
      "businessKey": "ORD-2024-001",
      "variables": [
        {
          "name": "initiator",
          "value": "admin"
        }
      ]
    }

Notes:
- Omitting `businessKey` for non-system processes will result in an error.
- Duplicate keys will be rejected unless the previous instance was terminated.

---

## ⚙️ Implementation Details

### 🔍 Validator
- **BusinessKeyEnforcer.java**
- Registered as a **global event listener**
- Listens to **PROCESS_CREATED** events

---

### 🔀 Bypass Logic
1. Retrieve process definition metadata
2. Check the process **category**
3. Skip `validateBusinessKey()` if category is:
   - `SCHEDULER`
   - `SYSTEM`

---

### ❌ Error Handling
- Missing or duplicate `businessKey` for non-bypass processes throws:

  FlowableIllegalArgumentException

- Returned to client as:

  HTTP 400 – Bad Request

---

## ✅ Summary

| Scenario | Result |
|--------|--------|
| Missing businessKey | ❌ Rejected |
| Duplicate Active / Completed | ❌ Rejected |
| Previously Terminated | ✅ Allowed |
| SYSTEM / SCHEDULER Process | 🚫 Validation Skipped |

---

✨ This approach guarantees **strict workflow uniqueness**, **safe retries**, and **clear separation** between business-driven and system-driven processes.
