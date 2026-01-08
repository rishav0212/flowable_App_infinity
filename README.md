# Flowable App Backend

## 🚀 Key Features & Updates

### 🔒 Strict Business Key Enforcement
To ensure data integrity and prevent duplicate workflows, the system now strictly enforces the presence and uniqueness of the `businessKey` when starting **any** process instance. This applies globally to all endpoints, including the standard Flowable runtime API.

#### 1. Requirements
* **Mandatory:** Every request to start a process **must** include a `businessKey`.
* **Unique:** The `businessKey` must be unique across the entire system for that specific process definition.
    * It cannot match any **Active** (currently running) instance.
    * It cannot match any **Completed** (successfully finished) instance.
    * *Exception:* It **CAN** be reused if the previous instance was **Terminated/Deleted** (e.g., cancelled drafts).

#### 2. API Usage
**Endpoint:** `POST /process-api/runtime/process-instances`

**Request Body:**
```json
{
  "processDefinitionKey": "dispatchProcess",
  "businessKey": "ORD-2024-001",  // <-- NOW REQUIRED
  "variables": [
    {
      "name": "initiator",
      "value": "admin"
    }
  ]
}