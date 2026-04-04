# InfinityPlus Custom Form Builder — Complete Documentation

**Platform:** InfinityPlus SaaS  
**Module:** Low-Code Form Builder (`form-builder.html`)  
**Version:** Latest — `developer_features` branch  
**Covers:** End-User Guide + Developer Implementation Guide

---

## Table of Contents

### Part 1 — End-User Guide
1. [What Is the Form Builder?](#1-what-is-the-form-builder)
2. [Opening the Builder](#2-opening-the-builder)
3. [The Toolbar — Understanding Every Control](#3-the-toolbar--understanding-every-control)
4. [The Canvas — Drag and Drop](#4-the-canvas--drag-and-drop)
5. [Database Resources — Connecting to Live Data](#5-database-resources--connecting-to-live-data)
6. [Auto-Fill — Populating Fields Automatically](#6-auto-fill--populating-fields-automatically)
7. [Process Integration — Sending Data to Workflows](#7-process-integration--sending-data-to-workflows)
8. [Google Drive Uploads](#8-google-drive-uploads)
9. [SQL Save Configuration — Where Does the Data Go?](#9-sql-save-configuration--where-does-the-data-go)
10. [Saving and the Lifecycle of a Form](#10-saving-and-the-lifecycle-of-a-form)
11. [End-User Best Practices](#11-end-user-best-practices)

### Part 2 — Developer Guide
12. [Why the Builder Was Built This Way](#12-why-the-builder-was-built-this-way)
13. [What Changed — Before vs After](#13-what-changed--before-vs-after)
14. [File Structure and Architecture](#14-file-structure-and-architecture)
15. [Boot Sequence — How It All Starts](#15-boot-sequence--how-it-all-starts)
16. [The FormBuilderConfig Object](#16-the-formbuilderconfig-object)
17. [The Magic Converter — saveComponent Interceptor](#17-the-magic-converter--savecomponent-interceptor)
18. [JSON Transformations — What the Converter Actually Writes](#18-json-transformations--what-the-converter-actually-writes)
19. [State Management and Global Variables](#19-state-management-and-global-variables)
20. [The SaaS Lockdown Routine](#20-the-saas-lockdown-routine)
21. [The DataGrid Nested Scoping Bug and Fix](#21-the-datagrid-nested-scoping-bug-and-fix)
22. [CSS Fixes — Choices.js Dropdown Overflow](#22-css-fixes--choicesjs-dropdown-overflow)
23. [Multi-Tenant Proxy Integration](#23-multi-tenant-proxy-integration)
24. [Debugging Reference](#24-debugging-reference)
25. [FAQ — Developer Edition](#25-faq--developer-edition)

---

---

# Part 1 — End-User Guide

---

## 1. What Is the Form Builder?

The InfinityPlus Form Builder is a **low-code drag-and-drop interface** for creating and managing data entry forms that connect directly to your SQL database, workflow engine (Flowable), and Google Drive — without writing a single line of code.

Forms you create here are used in two main ways:

- **Task Forms** — attached to a BPMN workflow step, so a user in their Inbox sees this form when they open a task.
- **Action Forms** — opened as a popup when a user clicks an action button (like "Approve" or "Return for Revision").

Every form you build is **isolated to your tenant**. No other client can see or access your forms.

---

## 2. Opening the Builder

Navigate to **Forms** in the left sidebar. You will see a grid of all forms that belong to your tenant.

- Click **New Form** (top right) to open a blank builder.
- Click the **Edit icon** on any existing form card to open that form for editing.

The builder opens in a full-screen overlay on top of the Forms page. The underlying form list stays loaded in the background. When you save or close the builder, the list automatically refreshes.

---

## 3. The Toolbar — Understanding Every Control

The toolbar runs across the top of the builder screen.

### Back to Forms (← Arrow Button)

Closes the builder and returns to the form list. If you have unsaved changes, a confirmation dialog will ask you to confirm before discarding your work.

### Form Title Input

The large text input in the centre of the toolbar. This is the human-readable name of your form (e.g., "Employee Onboarding Checklist").

### API ID Input

Directly below the title, labelled **API ID**. This is the machine-readable identifier used by workflows and the system to reference this form internally.

**Auto-Sync Behaviour:**
- By default, the API ID automatically mirrors the title — it converts your title to a lowercase, hyphen-separated slug (e.g., "Employee Onboarding Checklist" → `employee-onboarding-checklist`).
- The **link icon (🔗)** to the right of the API ID indicates it is currently auto-syncing.
- If you **manually edit** the API ID, the icon changes to a **broken link (🔗̶)** and the field locks to your custom value. Your title can now change freely without overwriting your ID.
- Click the icon again at any time to re-connect the auto-sync.

> **Important:** Once a form is saved and used in a workflow, do not change the API ID. Workflows reference forms by this ID. Changing it after deployment will break those references.

### Enable SQL Sync (Checkbox)

Located in the toolbar to the right of the API ID. Checking this box tells the system that when a user submits this form, the data should be saved into a relational SQL database table (in addition to being stored in Form.io).

This checkbox must be **checked first** before the **SQL Save Configuration** panel becomes available on your Submit button.

When you load an existing form that already has SQL sync enabled, this checkbox is automatically checked.

### Save Form (Button)

The orange button at the far right. It is **disabled** (grey) until you make at least one change to the form after the builder has fully loaded. This prevents accidental saves of a pristine form.

After saving, the button returns to disabled state. It re-enables only when you make a new change.

---

## 4. The Canvas — Drag and Drop

The large white area beneath the toolbar is the **form canvas**. It works like any standard Form.io builder:

- Drag components from the **left sidebar** onto the canvas.
- Click the **gear icon (⚙️)** on any placed component to open its settings popup.
- Click the **trash icon (🗑)** to delete a component.
- Drag components up and down on the canvas to reorder them.

### Settings Popup Tabs

When you open a component's settings, you will see tabs across the top (Display, Data, Validation, API, Conditional, Logic).

> **Note:** The **Logic tab** is hidden on this platform. All automation and custom logic is handled through the purpose-built panels described in this guide (Auto-Fill, Process Integration, etc.). This is intentional — it keeps the form builder safe and predictable.

---

## 5. Database Resources — Connecting to Live Data

The left sidebar contains a special category called **Database Resources**. Components in this category are pre-configured to connect to your tenant's SQL database.

### Database Lookup Component

**What it does:** Creates a dropdown that is populated in real time from a database table. When the user opens the form, the dropdown fetches fresh data from your SQL database.

**How to set it up:**

1. Drag **Database Lookup** from the Database Resources category onto the canvas.
2. Click the gear icon (⚙️) to open settings.
3. You will see the **⚡ Easy Database Setup** panel in the Display tab. This panel only appears on Database Lookup components — it is hidden on standard components.
4. In **Table Name**, select the database table you want to pull data from. The dropdown lists all tables your tenant has access to.
5. In **Display Column Name**, select the column whose value the user will see in the dropdown (e.g., a customer name column or an order number column).
6. In **Filter Mapping**, add at least one row:
    - **Database Column** — the column in the selected table you want to filter on.
    - **Form Field Key** — the key of another field on this form whose value will be used as the filter at runtime.

**How it works at runtime:** When the user's form loads, the system builds a URL like:
```
/api/forms/sql-data?table=tbl_clients&CLIENT_ID={current value of the linked field}
```
The backend queries the database and returns matching rows. The dropdown populates with the Display Column values.

**What the JSON looks like after setup:**

Before you click Save on the settings popup, the raw JSON for this component looks like a blank template. After you click Save, the builder automatically rewrites the component's JSON:
```json
{
  "type": "select",
  "dataSrc": "url",
  "data": {
    "url": "https://.../api/forms/sql-data?table=tbl_clients"
  },
  "template": "<span>{{ item.data.CLIENT_NAME_C }}</span>",
  "filter": "data.CLIENT_ID={{data.account_number}}",
  "valueProperty": "data"
}
```
You do not need to write any of this — the builder generates it from your UI selections. See [Section 17](#17-the-magic-converter--savecomponent-interceptor) for the full technical explanation.

---

## 6. Auto-Fill — Populating Fields Automatically

The Auto-Fill feature lets you automatically populate standard input fields (text fields, numbers, dates) with data pulled from a Database Lookup selection — without any scripting.

**How to set it up:**

1. You must have at least one **Database Lookup** component on the canvas first.
2. Drag any standard input component (e.g., a **Text Field**) onto the canvas.
3. Open its settings (⚙️) and go to the **Data tab**.
4. Find the **⚡ Auto-Fill from Database Lookup** panel (collapsed by default — click to expand).
5. In **Select Lookup Component**, choose the Database Lookup whose selection should drive this field.
6. The **Detected Source Table** field will automatically show you which table that lookup reads from.
7. In **Column to Extract**, select the column from that table whose value should be placed into this field.

**How it works at runtime:** When the user picks a row from the Database Lookup, the Auto-Fill field is instantly populated with the value from the specified column on that same row — without any page reload or user action.

**What the JSON looks like after setup:**

After saving the component settings, the builder automatically writes:
```json
{
  "type": "textfield",
  "key": "customer_code",
  "calculateValue": "value = data.lead_lookup && data.lead_lookup.data ? data.lead_lookup.data.CUST_CODE_C : '';",
  "allowCalculateOverride": true
}
```
Previously, a developer had to write this `calculateValue` expression by hand. Now it is generated automatically from your dropdown selections.

---

## 7. Process Integration — Sending Data to Workflows

When a form is submitted as part of a Flowable BPMN workflow, certain fields need to be promoted to workflow-level process variables so that the next step in the process can use them (e.g., an approval decision, a reference number, a status value).

**How to set it up:**

1. Open any input component's settings (⚙️).
2. Go to the **Display tab**.
3. Find the **⚙️ Process Integration** panel.
4. Check **Inject as Process Variable**.

**What this does:** When the form is submitted, the backend scans the form schema for any component with this checkbox checked. Those field values are extracted and pushed directly into the Flowable process variables for the current workflow instance, making them available to subsequent tasks, gateways, and service calls.

**What the JSON looks like:** The builder appends `"processVariable"` to the component's `tags` array:
```json
{
  "key": "approval_status",
  "tags": ["processVariable"]
}
```

---

## 8. Google Drive Uploads

The **Storage** category in the left sidebar contains the **Google Drive File** component, which lets users upload files directly from the form to a specific folder in your Google Drive — using your organization's service account, not the user's personal Google account.

**How to set it up:**

1. Drag **Google Drive File** from the Storage category onto the canvas.
2. Open its settings (⚙️) and go to the **Display tab**.
3. Find the **⚡ Google Drive Setup** panel.
4. In **Google Drive Folder ID**, paste the ID of the Google Drive folder where uploads should go.

You can find a folder's ID in its browser URL:
```
https://drive.google.com/drive/folders/1A2B3C4D5E6F7G8H
                                         ↑ This is the Folder ID
```

**What the JSON looks like after setup:**
```json
{
  "type": "file",
  "storage": "url",
  "url": "https://.../api/storage/gdrive?folderId=1A2B3C4D5E6F7G8H"
}
```
The folder ID is appended to the upload endpoint URL automatically.

---

## 9. SQL Save Configuration — Where Does the Data Go?

The **SQL Save Configuration** panel defines what happens to the form's data when the user clicks Submit. It lets you map form fields to specific columns in specific database tables.

**Prerequisites:**
- The **Enable SQL Sync** checkbox in the toolbar must be checked.
- Your form must have a Submit button on the canvas.

**How to set it up:**

1. Click on the **Submit button** on your form canvas to select it.
2. Open its settings (⚙️) and go to the **Display tab**.
3. Find the **⚡ SQL Save Configuration** panel. It is only visible when SQL Sync is enabled.
4. Click **Add Another** to add a target table entry.
5. In **Target SQL Table**, select the database table this data should be written to.
6. In the **Map Form Fields to Database Columns** grid below, add rows:
    - **Target DB Column** — the column in the database table.
    - **Source Form Field** — the field on this form whose value should be written to that column.
7. You can add multiple table entries — the system will write to all of them in a single transaction when the form is submitted.

**What the JSON looks like after setup:**

The builder compiles your grid entries into a JSON string stored under `properties.sqlConfig` on the button component:
```json
{
  "type": "button",
  "action": "submit",
  "properties": {
    "sqlConfig": "[{\"table\":\"tbl_orders\",\"keys\":{\"ORDER_NO_C\":\"order_number\",\"CLIENT_ID_C\":\"client_id\"}},{\"table\":\"tbl_items\",\"keys\":{\"ITEM_CODE\":\"item_code\"}}]"
  }
}
```
The backend reads this JSON on form submission, iterates over the table entries, and performs an upsert for each one — inserting new records or updating existing ones based on the mapped key columns.

---

## 10. Saving and the Lifecycle of a Form

### First Save (New Form)
When you click **Save Form** on a new form, the builder sends a `POST` to `/api/forms/form`. The system creates the form in Form.io with your tenant's prefix applied automatically. The form's internal `_id` is captured by the builder — subsequent saves will use `PUT` instead of `POST`.

### Subsequent Saves (Existing Form)
The builder tracks the `_id` returned from the first save. All future saves use `PUT /api/forms/form/{id}`, updating the form in place.

### What You See After Saving
- The Save button becomes disabled (grey) immediately after a successful save.
- The status bar at the bottom reads: **Saved successfully ✓**
- A `postMessage` event is sent to the parent React window, which refreshes the form list automatically.
- The builder stays open so you can continue editing.

### Closing Without Saving
If you click **Back to Forms** with unsaved changes, a browser confirmation dialog will ask: *"You have unsaved changes. Are you sure you want to discard them?"* Click OK to discard, or Cancel to return to the builder.

---

## 11. End-User Best Practices

**Always name your API ID meaningfully.** Workflows and developers reference forms by API ID — a clear name like `purchase-order-approval` is far easier to work with than `form-1738`.

**Set up Filter Mapping on every Database Lookup.** Without a filter, the dropdown will attempt to load every row in the table. For large tables, this is slow and may time out.

**SQL Save Configuration: use identifier columns as your first key mapping.** Map the primary key or a unique business key column first. The backend uses the mapped keys to determine whether to INSERT or UPDATE — if a record with that key already exists, it will be updated; otherwise a new record is created.

**Do not delete and recreate forms.** If a form is already referenced in BPMN processes or past workflow instances, deleting it breaks history. Instead, edit the existing form to add or remove fields.

**Coordinate with your developer before changing the API ID.** The API ID is referenced in BPMN XML. Changing it without updating the BPMN process definition will silently break form rendering on task screens.

---

---

# Part 2 — Developer Guide

---

## 12. Why the Builder Was Built This Way

### The Problem with `@formio/react`

The original plan was to use Form.io's official React wrapper library (`react-formio` / `@formio/react`) to embed the drag-and-drop builder inside the React frontend. This failed for two fundamental reasons:

**1. The CSS War:**
Form.io's builder was built to depend entirely on **Bootstrap 4** for all its visual styling. The InfinityPlus frontend uses **Tailwind CSS**, which also injects global styles. When Bootstrap and Tailwind are loaded in the same `document`, they fight over global element selectors (`button`, `input`, `div`, etc.). The result is a broken UI where both the form builder and the surrounding application look wrong — misaligned buttons, broken margins, invisible text.

**2. The DOM War:**
React manages the page via a Virtual DOM and expects to be the sole controller of what renders. Form.io's drag-and-drop engine relies on heavy direct DOM manipulation — it creates, moves, and destroys real DOM nodes constantly during drag operations. React's reconciler and Form.io's DOM manipulator conflict with each other, causing the builder to crash, freeze, or render blank.

### The Solution — An Isolated Iframe

The solution is a **sandboxed `<iframe>`** containing a pure vanilla JavaScript page (`form-builder.html`) served by Spring Boot.

- Inside the iframe: Bootstrap 4, Form.io JS, all their CSS, and all our custom builder logic.
- Outside the iframe (React app): Tailwind CSS, React component tree — completely unaffected.

An `<iframe>` is a hard CSS and JavaScript boundary. Bootstrap cannot leak out. React cannot interfere. The builder renders exactly as Form.io intended.

Communication between the React parent and the iframe is handled via `window.postMessage()`, which is the browser's native, safe mechanism for cross-frame communication.

---

## 13. What Changed — Before vs After

This section documents what a developer had to do manually before this builder was built, versus what happens automatically now.

### Database Lookup: The `data.url` Field

**Before:** Developer manually configured the select component in Form.io's admin UI or wrote JSON:
```json
"data": { "url": "https://.../api/forms/sql-data?table=tbl_clients" }
```
Any change to the table required manually editing this URL.

**After:** Developer selects the table from a dropdown in the Easy Database Setup panel. The URL is constructed and written automatically by the `saveComponent` interceptor when the settings popup is closed.

---

### Database Lookup: The `filter` Field

**Before:** Developer manually wrote the Form.io filter expression:
```
data.CLIENT_ID={{data.order_client_id}}
```
This required knowing both the exact database column name and the exact form field key — error-prone and undocumented.

**After:** Developer uses the Filter Mapping grid to visually pick the database column from a dropdown and type (or future: pick) the form field key. The filter string is compiled automatically.

---

### Auto-Fill: The `calculateValue` Expression

**Before:** Developer had to write a JavaScript ternary expression directly in the Form.io `calculateValue` field:
```javascript
value = data.lead_info && data.lead_info.data ? data.lead_info.data.ENQ_NUM_C : '';
```
This expression had to account for the fact that Form.io wraps API responses in a nested `.data` object. Getting this wrong (e.g., `data.lead_info.ENQ_NUM_C` instead of `data.lead_info.data.ENQ_NUM_C`) caused silent failures with no error message.

**After:** Developer selects the source lookup component and the target column from dropdowns. The safe ternary expression is generated automatically, including the `.data.` nesting, and injected into `calculateValue`.

---

### Process Variables: The `tags` Array

**Before:** Developer manually added `"processVariable"` to a component's `tags` array via the Form.io API tab. This was invisible to non-developer form builders.

**After:** A checkbox in the component's Display tab handles it. The `saveComponent` interceptor pushes or splices the tag automatically.

---

### SQL Save Mapping: The `sqlConfig` Property

**Before:** Developers manually wrote the JSON configuration and pasted it into a text field on the Submit button's Properties tab:
```json
[{"table":"tbl_orders","keys":{"ORDER_NO_C":"order_number"}}]
```
This was error-prone (JSON syntax errors, wrong column names), undiscoverable, and required developer intervention for every new form.

**After:** Non-developer form builders use the SQL Save Configuration EditGrid to visually configure table and column mappings. The stringified JSON is compiled and stored automatically.

---

### Google Drive File: The `url` Field

**Before:** Developer manually set the file upload component's URL in Form.io to include the specific folder ID.

**After:** Developer/builder pastes the folder ID into a dedicated input in the Google Drive Setup panel. The URL is dynamically rewritten on save.

---

## 14. File Structure and Architecture

The entire builder lives in a **single HTML file** served as a static asset by Spring Boot.

**File:** `src/main/resources/static/form-builder.html`  
**Served at:** `GET http://localhost:8080/form-builder.html`  
**Security:** Listed in `SecurityConfig.java` as `.permitAll()` so Spring Security does not block the browser from loading it.

The file is organized into three logical `<script>` blocks:

### Block 1 — Global State Variables

```html
<script>
    window.isSqlSyncEnabled = false;
    window.FORM_SQL_COMPONENTS = [];
    window.FORM_COMPONENTS_KEYS = [];
</script>
```

These three globals are on the `window` object because Form.io's `dataSrc: 'custom'` feature executes its `data.custom` string in a context where it only has access to `window` — not to closure variables. So these must be globals.

### Block 2 — `FormBuilderConfig` Object

Contains all static builder configuration: custom sidebar components, custom EditForm tab injections, and the SaaS lockdown routine. Returns a single `options` object consumed by `Formio.builder()`.

### Block 3 — Application Logic (`form-builder-app.js` logic)

Contains the boot sequence, API calls, event listeners, state management, and the `saveComponent` interceptor. Self-contained in an IIFE (Immediately Invoked Function Expression) for scope isolation.

---

## 15. Boot Sequence — How It All Starts

```
1. URL Parameters Parsed
   token     → Spring Boot JWT for all API calls
   formPath  → If present, edit mode; if absent, create mode
   api       → Base URL for FORMS_API, BFF_API, SCHEMA_API

2. updatePathLockUI() → Set initial lock icon state

3. fetchConfig()
   GET /api/formio-bff/config
   Authorization: Bearer {jwtToken}
   ← Returns: { formioToken, apiBaseUrl, tenantSlug, tenantId }

4. [If editPath exists] loadExistingForm(editPath)
   GET /api/forms/{editPath}
   Authorization: Bearer {jwtToken}
   ← Populates title input, path input, sql checkbox, sets existingFormId

5. initBuilder(initialSchema)
   a. Override Formio.fetch (inject JWT + error handling)
   b. Formio.setUser()  (prevents 401s on internal SDK calls)
   c. Formio.setBaseUrl(FORMS_API)
   d. Formio.setProjectUrl(FORMS_API)
   e. Formio.builder(container, schema, options)
   f. Register 'saveComponent' event listener
   g. Register 'change' event listener
   h. hideOverlay()
   i. setTimeout(500ms) → set isInitialized = true, isDirty = false, disable Save button

6. Builder is fully ready
```

### Why the 500ms `setTimeout` at the End?

Form.io fires the `change` event multiple times during its own initialization as it sets up the canvas with the initial schema. Without the `isInitialized` guard, the Save button would be enabled the moment the builder loaded — before the user had made any changes. The 500ms delay allows Form.io's internal initialization events to settle before we start tracking "dirty" state.

---

## 16. The FormBuilderConfig Object

`window.FormBuilderConfig.getOptions(SCHEMA_API)` returns the full options object passed to `Formio.builder()`. It accepts `SCHEMA_API` as a parameter so all dynamic URL strings point to the correct tenant backend.

### Custom Sidebar Groups

```javascript
builder: {
    databaseResources: {
        title: 'Database Resources',
        default: true,
        weight: -10,          // Negative weight = appears at the top
        components: {
            databaseLookup: { ... }   // Custom component definition
        }
    },
    storageResources: {
        title: 'Storage',
        default: true,
        weight: -9,
        components: {
            gdriveFile: { ... }
        }
    }
}
```

`weight` controls position in the sidebar — lower numbers appear higher.

### EditForm Injections

`editForm` is Form.io's mechanism for customizing the settings popup that appears when you click the gear icon on a component. The structure is:

```javascript
editForm: {
    [componentType]: [
        {
            key: [tabKey],   // 'display', 'data', 'validation', 'api', etc.
            components: [
                // Custom panels and fields to inject into this tab
            ]
        }
    ]
}
```

The custom panels (Auto-Fill, Process Integration, SQL Submit Config, Google Drive Setup) are injected into the appropriate component types and tabs using the helper function `injectToTab()`.

### Custom Conditional Visibility

Panels use `customConditional` to show/hide based on the component's own data:

```javascript
// Only show SQL Submit Config on submit buttons when SQL Sync is enabled
customConditional: "show = data.action === 'submit' && !!window.isSqlSyncEnabled;"

// Only show Google Drive Setup on file components with the isGDriveFile property
customConditional: "show = !!(data.properties && data.properties.isGDriveFile === 'true');"
```

---

## 17. The Magic Converter — saveComponent Interceptor

This is the core of the custom builder logic. Form.io fires a `saveComponent` event every time the user clicks **Save** inside a component's settings popup. We intercept this event and **mutate the component's JSON** before it is stored in the schema.

```javascript
builderInstance.on('saveComponent', (component) => {
    // component is passed by reference — we mutate it directly
    
    if (component.properties?.isSqlTemplate === 'true') {
        // Rewrite the Database Lookup component
        component.data.url = FORMS_SQL_DATA_URL + '?table=' + component.properties.sqlTable;
        component.template = '<span>{{ item.data.' + component.properties.sqlColumn + ' }}</span>';
        component.filter = compiledFilterString;
    }

    if (component.properties?.autoFillSourceKey && component.properties?.autoFillColumn) {
        // Write the calculateValue expression
        component.calculateValue = `value = data.${sourceKey}?.data?.${colName} ?? '';`;
        component.allowCalculateOverride = true;
    }

    if (component.properties?.isProcessVariable) {
        // Push tag if not already present
        if (!component.tags.includes('processVariable'))
            component.tags.push('processVariable');
    } else {
        // Remove tag
        component.tags = component.tags.filter(t => t !== 'processVariable');
    }

    if (component.type === 'button' && component.action === 'submit') {
        // Compile sqlTablesConfig EditGrid into the sqlConfig JSON string
        component.properties.sqlConfig = JSON.stringify(compiledArray);
    }

    if (component.type === 'file' && component.properties?.isGDriveFile === 'true') {
        // Rewrite the upload URL with the folder ID
        component.url = GDRIVE_ENDPOINT + '?folderId=' + component.properties.gDriveFolder;
    }
});
```

The key insight is that `component` is a live JavaScript object, passed by reference. Any properties we write to it are immediately reflected in the schema that Form.io stores. There is no separate "apply" step needed.

---

## 18. JSON Transformations — What the Converter Actually Writes

### Database Lookup — Before `saveComponent`

This is the raw schema that gets dropped onto the canvas when the component is first dragged from the sidebar:

```json
{
  "label": "Database Lookup",
  "type": "select",
  "dataSrc": "url",
  "properties": {
    "isSqlTemplate": "true",
    "sqlTable": "",
    "sqlColumn": ""
  },
  "customFilterMapping": [{ "dbColumn": "", "formField": "" }],
  "data": { "url": "", "headers": [] },
  "template": "<span></span>",
  "filter": "",
  "valueProperty": "data",
  "authenticate": true
}
```

### Database Lookup — After `saveComponent` (user picked table: `tbl_clients`, column: `CLIENT_NAME_C`, filter: `CLIENT_ID` → `order_client`)

```json
{
  "label": "Database Lookup",
  "type": "select",
  "dataSrc": "url",
  "properties": {
    "isSqlTemplate": "true",
    "sqlTable": "tbl_clients",
    "sqlColumn": "CLIENT_NAME_C"
  },
  "customFilterMapping": [{ "dbColumn": "CLIENT_ID", "formField": "order_client" }],
  "data": {
    "url": "https://.../api/forms/sql-data?table=tbl_clients",
    "headers": []
  },
  "template": "<span>{{ item.data.CLIENT_NAME_C }}</span>",
  "filter": "data.CLIENT_ID={{data.order_client}}",
  "valueProperty": "data",
  "authenticate": true
}
```

### Auto-Fill Text Field — After `saveComponent`

```json
{
  "type": "textfield",
  "key": "customer_code",
  "label": "Customer Code",
  "calculateValue": "value = data.client_lookup && data.client_lookup.data ? data.client_lookup.data.CUST_CODE_C : '';",
  "allowCalculateOverride": true
}
```

### Submit Button — After SQL Save Configuration

```json
{
  "type": "button",
  "action": "submit",
  "label": "Submit",
  "properties": {
    "sqlConfig": "[{\"table\":\"tbl_orders\",\"keys\":{\"ORDER_NO_C\":\"order_number\",\"CLIENT_ID\":\"selected_client\"}},{\"table\":\"tbl_line_items\",\"keys\":{\"ORDER_REF\":\"order_number\",\"ITEM_CODE\":\"item_code\"}}]"
  }
}
```

### Root Form JSON — When SQL Sync is Enabled

When the form is saved and SQL Sync is checked, the `sql` tag is pushed into the root schema's `tags` array:

```json
{
  "title": "Purchase Order Form",
  "path": "purchase-order-form",
  "tags": ["sql"],
  "components": [ ... ]
}
```

The backend reads this root-level `sql` tag to know it should activate SQL mirroring after a successful Form.io submission.

---

## 19. State Management and Global Variables

### `window.isSqlSyncEnabled`

Boolean. Controlled by the SQL Sync checkbox in the toolbar. Used by the `sqlSubmitConfigPanel`'s `customConditional` to show/hide the panel. Also read in `saveForm()` to decide whether to push/remove the `sql` tag from the root schema.

### `window.FORM_SQL_COMPONENTS`

Array. Maintained by `updateSqlComponentsList()`, which runs on every schema `change` event. Contains all components that are Database Lookup type (i.e., have `properties.isSqlTemplate === 'true'`).

Format:
```javascript
[
  { label: "Client Lookup (client_lookup)", value: "client_lookup", table: "tbl_clients" },
  { label: "Product Lookup (product_lookup)", value: "product_lookup", table: "tbl_products" }
]
```

Used as `dataSrc: 'custom'` data for the Auto-Fill **Select Lookup Component** dropdown.

### `window.FORM_COMPONENTS_KEYS`

Array. All non-button components on the current canvas, used as `dataSrc: 'custom'` for the **Source Form Field** dropdown in the SQL Save Configuration grid.

Format:
```javascript
[
  { label: "Order Number (order_number)", value: "order_number" },
  { label: "Client Name (client_name)", value: "client_name" }
]
```

### `isInitialized` (closure variable)

Boolean. Set to `true` by the `setTimeout` in `initBuilder()`. The `markDirty()` function returns immediately if `isInitialized` is false, preventing the Save button from enabling during boot.

### `isPathManuallyEdited` (closure variable)

Boolean. Set to `true` when the user types in the API ID input, or when loading an existing form. Controls whether the title input's `input` event rewrites the path.

### `existingFormId` (closure variable)

String or null. Stores the Form.io `_id` of the form after first save or after loading an existing form. Controls whether `saveForm()` uses `POST` (null) or `PUT` (has value).

---

## 20. The SaaS Lockdown Routine

End users of a SaaS platform must not be able to inject arbitrary JavaScript or access internal metadata fields. Form.io's builder exposes several dangerous fields by default:

- **`calculateValue`** — executes arbitrary JavaScript
- **`customDefaultValue`** — executes arbitrary JavaScript
- **`custom`** (validation) — executes arbitrary JavaScript
- **`customConditional`** — executes arbitrary JavaScript
- **The Logic tab** — full JavaScript event system
- **`tags` field in API tab** — could interfere with backend tag-based filtering
- **`properties` field in API tab** — could overwrite backend-managed metadata

The lockdown is applied by looping through all known component types and injecting `ignore: true` entries:

```javascript
const allFormioComponents = [
    'textfield', 'number', 'currency', 'textarea', 'email', 'select',
    'button', 'panel', 'datagrid', 'editgrid', ...
];

allFormioComponents.forEach(type => {
    injectToTab(type, 'api',        { key: 'tags',                ignore: true });
    injectToTab(type, 'api',        { key: 'properties',           ignore: true });
    injectToTab(type, 'data',       { key: 'calculateValue',       ignore: true });
    injectToTab(type, 'data',       { key: 'customDefaultValue',   ignore: true });
    injectToTab(type, 'validation', { key: 'custom',               ignore: true });
    injectToTab(type, 'conditional',{ key: 'customConditional',    ignore: true });
    editFormConfig[type].push({ key: 'logic', ignore: true }); // Hides entire Logic tab
});
```

`ignore: true` is Form.io's built-in mechanism for removing a field from the builder UI — it still exists in the underlying component schema if previously set, but the user cannot see or edit it.

---

## 21. The DataGrid Nested Scoping Bug and Fix

### The Problem

The SQL Save Configuration uses an **EditGrid** (one row per table) that contains a nested **DataGrid** (one row per column mapping inside each table). The column mapping DataGrid needs to fetch columns from the API based on whichever table was selected in the parent EditGrid row.

In standard Form.io, the way to reference the parent row's data from inside a nested component is `{{ row.tableName }}`. This is used in the DataGrid column's URL:

```
/api/schema/columns?table={{ row.tableName }}
```

However, this `{{ row.tableName }}` interpolation **breaks** in deeply nested contexts. When inside a DataGrid that is itself inside an EditGrid, `row` refers to the DataGrid's own current row — not the parent EditGrid row. The result is an empty `table=` parameter in the API call, which returns no columns.

### The Fix — Isolated State Push via `calculateValue`

The fix uses the DataGrid's own `calculateValue` property to act as a state synchronization mechanism that runs on every render:

```javascript
// On the DataGrid component that wraps the column mappings
calculateValue: `
    if (value && Array.isArray(value) && row && row.tableName) {
        value.forEach(function(r) {
            r._parentTable = row.tableName;
        });
    }
    return value;
`
```

This runs every time the DataGrid re-renders. It reads `row.tableName` from the parent EditGrid context (which is correctly scoped at the DataGrid level) and writes it into a **hidden field** (`_parentTable`) inside every row of the DataGrid.

The column dropdown URL then safely references the hidden field:
```
/api/schema/columns?table={{ row._parentTable }}
```

`row._parentTable` is now on the DataGrid row itself — the scope is correct, and the correct table name flows through even when multiple tables are being configured simultaneously.

A `<hidden>` type component with key `_parentTable` is added to the DataGrid's components to hold this value.

---

## 22. CSS Fixes — Choices.js Dropdown Overflow

Form.io's select components use the **Choices.js** library to render their dropdown menus. Inside DataGrids and EditGrids (which use Bootstrap's `<table>` elements), Choices.js can't correctly calculate how much space is available. Two visible bugs result:

**Bug 1 — Squished Height:** The dropdown opens but shows only 1-2 items even when there are 20, because Bootstrap's `overflow: hidden` on the table cell clips it.

**Bug 2 — Absolute Positioning Failure:** The dropdown appears at the wrong position because it calculates its offset based on the table container, not the viewport.

The fix uses targeted CSS overrides:

```css
/* Allow the table to overflow — mandatory for absolute-positioned children */
.formio-component-datagrid .table-responsive { overflow: visible !important; }
.formio-component-datagrid table,
.formio-component-datagrid td,
.formio-component-datagrid th { overflow: visible !important; }

/* Give each form-group a positioning context for the absolute dropdown */
.formio-component-datagrid .form-group { position: relative !important; }

/* Force Choices.js to ignore its inline height calculations */
.formio-component-datagrid .choices__list--dropdown {
    z-index: 99999 !important;
    position: absolute !important;
    min-width: 250px !important;
    width: max-content !important;
    white-space: nowrap !important;
    height: auto !important;   /* Override Choices.js inline style */
}

/* Constrain the inner scrollable list, not the outer container */
.formio-component-datagrid .choices__list--dropdown .choices__list {
    max-height: 250px !important;
    overflow-y: auto !important;
}
```

The same rules apply to `.formio-component-editgrid` for consistency.

---

## 23. Multi-Tenant Proxy Integration

The form builder does not talk directly to Form.io. All requests go through the Spring Boot proxy (`FormIoProxyController`). From the iframe's perspective:

- `FORMS_API = http://localhost:8080/api/forms` — the proxy endpoint
- `Formio.setBaseUrl(FORMS_API)` — SDK internal calls also go through the proxy
- `Formio.fetch` is overridden to inject `Authorization: Bearer {jwtToken}` on all calls

The proxy reads the JWT to determine which tenant the request belongs to, then injects the `{tenantSlug}--` prefix on form paths before forwarding to Form.io. Responses have the prefix stripped before being returned to the iframe. The iframe is completely unaware of this transformation — it sees clean form paths throughout.

See the **FORMIO_MULTITENANT_DEVELOPER_DOCUMENTATION.md** file for the complete proxy architecture, path prefixing logic, and multi-tenancy isolation details.

---

## 24. Debugging Reference

### Save button stays grey (disabled) even after making changes

**Cause A:** The builder is still in initialization phase. The `isInitialized` flag is set to `true` after a 500ms timeout. If the builder loads extremely slowly, try waiting longer.

**Cause B:** The boot sequence failed silently. Open browser DevTools (F12) → Console. Look for any `boot failed` error. The most common causes are:
- `fetchConfig()` returned 401 → JWT token in `localStorage.jwt_token` is expired. Log out and log back in.
- `fetchConfig()` returned 500 → Form.io credentials in `application.properties` are wrong (`formio.admin-email`, `formio.admin-password`).
- `loadExistingForm()` returned 404 → The form path in the URL doesn't match what's in Form.io. The tenant prefix may be mismatched.

---

### Database Lookup dropdowns show empty (no tables or columns)

**Cause A:** The `SCHEMA_API` (`/api/schema`) backend is not running or the endpoint returns a non-200 response. Check the Network tab for requests to `/api/schema/tables` — look at the response body.

**Cause B:** The endpoint returns data in a wrapped format (e.g., `{ "content": [...] }`) instead of a flat array (`[...]`). Form.io expects a flat array. The Spring Boot `SchemaController` must return `List<Map<String, String>>` directly.

**Cause C:** The toast notification system is showing the error but it disappeared. Check the browser console for network errors on SCHEMA_API URLs.

**Debug Step:** In the browser console, run:
```javascript
fetch('/api/schema/tables', { headers: { 'Authorization': 'Bearer ' + localStorage.getItem('jwt_token') } })
  .then(r => r.json()).then(console.log);
```
This shows exactly what the API is returning.

---

### Columns dropdown is empty after selecting a table

**Cause A:** The `refreshOn` chain failed. The `columns` select component triggers a refresh when `properties.sqlTable` changes. If the form data binding is broken (common in deeply nested contexts), `{{ data.properties.sqlTable }}` in the URL evaluates to an empty string.

**Cause B:** This is exactly the nested scoping problem described in Section 21. Ensure the `_parentTable` hidden field and `calculateValue` on the DataGrid are present.

**Debug Step:** Open DevTools Network tab, look for a request to `/api/schema/columns?table=`. If `table=` is empty or missing, the scoping fix is not working.

---

### "Invalid alias" error when saving a form to Form.io

**Cause:** The form's `name` or `machineName` field contains characters Form.io rejects (spaces, brackets, or special characters). This happened with the earlier prefix format `[tenantSlug] formname`.

**Current fix:** The proxy uses `tenantSlug-formname` (hyphen-separated, no brackets or spaces). If this error reappears, check `injectTenantIntoFormBody()` in `FormIoProxyController.java`.

---

### 401 Unauthorized on `GET /form-builder.html`

**Cause:** Spring Security is blocking the static file. The file must be in `permitAll()`.

**Check:** Open `SecurityConfig.java` and verify:
```java
.requestMatchers(AntPathRequestMatcher.antMatcher("/form-builder.html"))
.permitAll()
```
Also verify the file is in `src/main/resources/static/` — not `src/main/resources/` directly.

---

### 404 on `GET /form-builder.html`

**Cause:** The file is not in the correct static resources location, or Spring Boot's static resources are disabled.

**Check:** Verify the file exists at `src/main/resources/static/form-builder.html`. Spring Boot automatically serves files from `src/main/resources/static/` at the root path.

---

### SQL Sync panel not appearing on the Submit button

**Cause A:** `window.isSqlSyncEnabled` is `false`. Check the toolbar — is the SQL Sync checkbox checked?

**Cause B:** The `customConditional` on the panel is evaluated against the button's own data model. If the button's `action` property is not `'submit'`, the panel is hidden. Verify the button type is Submit, not Reset or Custom.

**Debug Step:** In the builder, open the Submit button settings, go to Display tab. If the SQL Save Configuration panel is truly absent (not just collapsed), open browser console and run:
```javascript
console.log(window.isSqlSyncEnabled);
// Should be: true
```

---

### Auto-Fill not working at runtime (field stays empty)

**Cause A:** The `calculateValue` expression has an incorrect key. The expression references `data.{key}` — if the source Lookup component's key in the form is different from what the expression uses, it will always return empty string.

**Debug Step:** In the Form.io developer console (not the builder — the live form), open browser console and run:
```javascript
// Check what value the lookup component has
console.log(instance.getComponent('your_lookup_key').dataValue);
// Check the nested structure
console.log(instance.getComponent('your_lookup_key').dataValue?.data?.COLUMN_NAME);
```

**Cause B:** `allowCalculateOverride` is not `true`. Without this, if the user has manually typed something into the field, Form.io respects the manual value and ignores `calculateValue`. The converter sets this automatically, but verify it is in the saved schema.

---

### postMessage events not received by React (form list not refreshing)

**Cause A:** The `window.addEventListener('message', handler)` in `FormManager.tsx` was removed during a React component unmount and not re-added (missing return cleanup function or wrong dependency array).

**Cause B:** The postMessage target origin (`'*'`) is being filtered by a browser extension or Content Security Policy.

**Debug Step:** In the React browser tab console, add a temporary global listener:
```javascript
window.addEventListener('message', (e) => console.log('message:', e.data));
```
Then save a form in the builder. You should see `{ type: 'FORMIO_FORM_SAVED', ... }` logged.

---

### Toast notifications appearing but the Schema API is actually working

**Cause:** The global Formio.fetch interceptor catches *all* fetch failures on SCHEMA_API URLs, including temporarily slow requests that later succeed. The toast fires immediately on a network-level throw (e.g., connection timeout), but Form.io may retry and succeed.

This is cosmetic and not harmful. If you want to suppress false positives, add a retry delay before showing the toast.

---

## 25. FAQ — Developer Edition

**Q: Why is the builder in a single HTML file instead of separate JS/CSS files?**  
A: Spring Boot's static resource serving is simple — one file means one request, no build step, no asset pipeline complexity. The file is small enough that this is not a performance concern. Splitting into multiple files would require managing separate static resource paths and complicating deployment.

---

**Q: Why does `Formio.setUser()` need to be called?**  
A: Form.io's SDK uses the "current user" context for some internal permission checks during builder initialization. Without setting a user, certain internal SDK calls return 401 errors even when the Form.io server is working correctly. Setting a mock user object bypasses these checks safely.

---

**Q: Can we add more custom sidebar components?**  
A: Yes. Add a new entry under `builder.{groupName}.components` in `FormBuilderConfig.getOptions()`. The `schema` object is the initial JSON placed on the canvas when the component is dragged in. Add a corresponding `editForm` injection to customize its settings popup.

---

**Q: Why does `properties.sqlConfig` store a JSON *string* instead of an actual JSON object?**  
A: Form.io's `properties` object only supports string values — it is a flat key-value map. Storing a JSON string and parsing it on the backend is the standard pattern for complex configuration in Form.io component properties.

---

**Q: How does the backend know to use sqlConfig when processing submissions?**  
A: `FormIoProxyController` intercepts successful `POST /submission` responses. It fetches the form definition, checks if the form has the `sql` tag at the root level, then reads `components` to find the Submit button via `FormSchemaService.findSubmitButtonProperties()`. That method traverses the component tree and returns the button's `properties` map, which contains `sqlConfig`. The controller then passes this to `FormSchemaService.buildBatchPayload()` for execution.

---

**Q: Why is the `filter` field written as `data.COLUMN={{data.field}}` instead of using Form.io's native filter format?**  
A: The `/api/forms/sql-data` endpoint in `FormIoProxyController` is a custom SQL proxy, not a standard Form.io resource endpoint. The filter string is parsed by `DataMirrorService.applyUniversalFilters()` which reads query parameters. Form.io appends the `filter` string as query parameters to the data URL at runtime, so `data.COLUMN_NAME={{data.formField}}` becomes `?COLUMN_NAME={value}` in the actual HTTP request — which our backend reads as a WHERE clause condition.

---

**Q: What happens if the Schema API (`/api/schema`) is not yet implemented?**  
A: The table and column dropdowns in the builder will fail to load with empty lists. The toast notification system will show error messages. The core form saving and loading functionality is completely unaffected — SCHEMA_API is only used to power the UI dropdowns in the builder settings. All other builder features remain fully functional.

---

*End of Document*