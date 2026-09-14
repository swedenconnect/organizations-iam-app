![Sweden Connect](images/sweden-connect.png)

# Export and Import

---

## Table of Contents

1. [**Overview**](#overview)
2. [**The Bundle Format**](#the-bundle-format)

   2.1. [Full Example](#full-example)

   2.2. [Functions](#functions)

   2.3. [Organizations](#organizations)

   2.4. [Users](#users)

3. [**Duplicate Detection**](#duplicate-detection)
4. [**Validation**](#validation)
5. [**Endpoints**](#endpoints)

   5.1. [Export](#export)

   5.2. [Import — Dry Run](#import-dry-run)

   5.3. [Import — Confirm](#import-confirm)

   5.4. [Import — Cancel](#import-cancel)

---

<a name="overview"></a>
## 1. Overview

The IAM admin application can export its data as a single JSON file, and bulk-import
organizations, functions and users from a file in the same format. Typical uses are
bulk-registering a batch of new organizations, functions and people at once, and taking a
realm snapshot for backup or migration between environments.

**This feature is superuser-only**, both for export and for import. Every endpoint described
below is under the session-based `/api/` namespace used by the admin application's own
frontend (see [IAM Admin Application — Service API](iam-admin-app-apis.md) for the separate,
bearer-token-authenticated `/iam-api/v1/` endpoints).

Import is a **two-step** process:

1. **Dry run** — the uploaded file is validated against the current state of the realm.
   Organizations, functions and users that already exist are detected as duplicates (see
   [Duplicate Detection](#duplicate-detection)) and reported, but nothing is created. The
   validated, duplicate-filtered batch is held server-side under a `batchId`.
2. **Confirm** — the batch identified by `batchId` is created. The file itself is uploaded
   only once, in the dry-run step.

A duplicate is always **skipped entirely** — if a person in the file already exists, neither
the person nor any rights listed for them are created. Managed OAuth clients are out of scope
for this feature; they have a separate lifecycle and are managed under `/api/clients` (see
[Registering a Client](registering-a-client.md)).

Superuser accounts (`superuser: true`) are never included in an export, and there is no way to
grant superuser status through import — user creation has no such field. See
[Rights Model — Users](rights-model.md#users) for what a superuser account is.

---

<a name="the-bundle-format"></a>
## 2. The Bundle Format

The same JSON shape is used for both directions: `GET /api/export` returns it, and
`POST /api/import/dry-run` accepts a file in this shape.

```json
{
  "schemaVersion": "1.0",
  "exportedAt": "2026-09-14T10:00:00Z",
  "functions": [ /* BundleFunctionEntry[] */ ],
  "organizations": [ /* BundleOrganizationEntry[] */ ],
  "users": [ /* BundleUserEntry[] */ ]
}
```

| Field          | Type              | Description                                                                 |
|----------------|-------------------|-------------------------------------------------------------------------------|
| `schemaVersion`| string            | Must be exactly `"1.0"`. An import with any other value is rejected outright. |
| `exportedAt`   | string \| null    | ISO-8601 timestamp of when an export was taken. Informational only — ignored on import. |
| `functions`    | array             | See [Functions](#functions). May be empty or omitted.                        |
| `organizations`| array             | See [Organizations](#organizations). May be empty or omitted.                |
| `users`        | array             | See [Users](#users). May be empty or omitted.                                |

A file need not cover every entity type — an import that only adds new functions, with empty
`organizations` and `users` arrays, is valid.

**Processing order:** `functions` → `organizations` → `users`. Later entries may reference
earlier ones: `organizations[].attachedFunctions` refers to a function `id`, and
`users[].rights[].functionId` refers to a function attached to `users[].rights[].orgIdentifier`.
A reference may resolve either to something already in Keycloak, or to an entry earlier in the
same file.

<a name="full-example"></a>
### 2.1. Full Example

```json
{
  "schemaVersion": "1.0",
  "exportedAt": "2026-09-14T10:00:00Z",
  "functions": [
    {
      "id": "walletreg",
      "nameSv": "Walletregistrering",
      "nameEn": "Wallet registration",
      "descriptionSv": "Registrering av digitala plånböcker",
      "descriptionEn": "Registration of digital wallets"
    },
    {
      "id": "demo",
      "nameSv": "Demo",
      "nameEn": "Demo",
      "descriptionSv": null,
      "descriptionEn": null
    }
  ],
  "organizations": [
    {
      "orgIdentifier": "2021006883",
      "legalName": "Myndigheten för Digital förvaltning",
      "nameSv": "Digg - Myndigheten för Digital förvaltning",
      "nameEn": "Digg - Authority for Digital Government",
      "contactEmail": "info@digg.se",
      "contactPhone": null,
      "attachedFunctions": ["walletreg", "demo"]
    },
    {
      "orgIdentifier": "5561234567",
      "legalName": "Exempel Aktiebolag",
      "nameSv": null,
      "nameEn": null,
      "contactEmail": null,
      "contactPhone": "+46701234567",
      "attachedFunctions": ["demo"]
    }
  ],
  "users": [
    {
      "name": "Anna Andersson",
      "email": "anna.andersson@digg.se",
      "personalIdentityNumber": "197001011234",
      "orgAffiliation": null,
      "phoneNumber": "+46701112233",
      "rights": [
        { "orgIdentifier": "2021006883", "functionId": "walletreg", "right": "admin" },
        { "orgIdentifier": "2021006883", "functionId": null, "right": "read" }
      ]
    },
    {
      "name": "Bertil Bengtsson",
      "email": "bertil@exempel.se",
      "personalIdentityNumber": null,
      "orgAffiliation": "bertil@5561234567",
      "phoneNumber": null,
      "rights": [
        { "orgIdentifier": "5561234567", "functionId": "demo", "right": "write" }
      ]
    }
  ]
}
```

Anna is granted `admin` on `walletreg` specifically, plus organization-wide `read` on
`2021006883` (which also covers `demo`, the org's other attached function). Bertil, identified
by organizational affiliation rather than a personal identity number, is granted `write` on
`demo` only.

<a name="functions"></a>
### 2.2. Functions — `BundleFunctionEntry`

| Field          | Type            | Description                                                             |
|----------------|-----------------|---------------------------------------------------------------------------|
| `id`           | string          | The function identifier. Must match `^[a-z0-9_-]+$` and be unique in the realm — this is what the task description calls "function_group_id"; there is no separate function-group concept. |
| `nameSv`       | string \| null  | Swedish display name. Must not be blank for a new function.               |
| `nameEn`       | string \| null  | English display name. Must not be blank for a new function.               |
| `descriptionSv`| string \| null  | Optional longer description in Swedish.                                   |
| `descriptionEn`| string \| null  | Optional longer description in English.                                   |

<a name="organizations"></a>
### 2.3. Organizations — `BundleOrganizationEntry`

| Field              | Type            | Description                                                            |
|--------------------|-----------------|----------------------------------------------------------------------------|
| `orgIdentifier`    | string          | Ten-digit Swedish organizational number, no dash. The organization's identity. |
| `legalName`        | string          | The name registered at Bolagsverket. Mandatory for a new organization.     |
| `nameSv`           | string \| null  | Optional Swedish display name.                                             |
| `nameEn`           | string \| null  | Optional English display name.                                             |
| `contactEmail`     | string \| null  | Optional contact email.                                                    |
| `contactPhone`     | string \| null  | Optional contact phone.                                                    |
| `attachedFunctions`| string[]        | Function `id`s attached to this organization. Each must resolve per [Processing order](#the-bundle-format). |

<a name="users"></a>
### 2.4. Users — `BundleUserEntry`

| Field                     | Type            | Description                                                             |
|---------------------------|-----------------|------------------------------------------------------------------------|
| `name`                    | string          | Full name, split on the first space into first/last name — the same convention `POST /api/users` uses. Must not be blank. |
| `email`                   | string \| null  | Optional email address. Must contain `@` if given.                     |
| `personalIdentityNumber`  | string \| null  | 12-digit Swedish personal identity number.                             |
| `orgAffiliation`          | string \| null  | Organizational affiliation, format `userID@organization-number`.       |
| `phoneNumber`             | string \| null  | Optional phone number.                                                 |
| `rights`                  | array           | `{ orgIdentifier, functionId, right }`. `functionId: null` means the right is organization-wide. `right` is `read`, `write`, or `admin`. |

**At least one of `personalIdentityNumber` or `orgAffiliation` is required** — see
[Duplicate Detection](#duplicate-detection). A user entry with neither is rejected as an error,
regardless of the deployment's `eidAttributeRequired` setting for the manual registration form.

There is no `superuser` field: superuser status cannot be granted through import.

---

<a name="duplicate-detection"></a>
## 3. Duplicate Detection

| Entity type   | Duplicate key                                             |
|---------------|-------------------------------------------------------------|
| Function      | `id`                                                         |
| Organization  | `orgIdentifier`                                              |
| User          | `personalIdentityNumber` if given, otherwise `orgAffiliation`|

Functions and organizations are matched on their natural identifier, which is the only sane
choice since it is also what every reference to them uses.

Users are matched on `personalIdentityNumber` / `orgAffiliation` — the same two eID attributes
`POST /api/users` already enforces uniqueness on when a user is created through the regular
form (see [Rights Model — Users](rights-model.md#users)). **Email is deliberately not used as a
duplicate key**: it is a plain profile field with no uniqueness constraint elsewhere in the
system, so using it here would be a new and inconsistent rule.

A duplicate is **skipped in full**. If a person in the file already exists, none of the rights
listed for them are applied either — even ones the existing account does not yet hold. This
keeps the outcome simple to reason about and to report: every entry in the preview and the
final report is exactly `new`/`created`, `skipped_duplicate`, or `error`, never partially
applied.

---

<a name="validation"></a>
## 4. Validation

Besides the field constraints in the tables above, a `rights` entry is rejected as an error on
the whole user entry if:

- `right` is not one of `read`, `write`, `admin`; or
- `orgIdentifier` does not resolve to an organization already in Keycloak or earlier in the
  same file; or
- `functionId` is set but is not attached to that organization, either already in Keycloak or
  by an `attachedFunctions` entry earlier in the same file.

An uploaded file is rejected outright (nothing is validated per-entry) if it is not valid JSON,
`schemaVersion` is anything other than `"1.0"`, or any one of `functions`, `organizations`,
`users` exceeds 5000 entries.

State can change between the dry run and the confirm step (for example, another administrator
creates the same organization in the meantime). Confirm re-checks every entry immediately
before creating it and re-reports it as `skipped_duplicate` rather than failing if so.

---

<a name="endpoints"></a>
## 5. Endpoints

All four endpoints require a superuser session and return `403 Forbidden` otherwise.

<a name="export"></a>
### 5.1. Export

```
GET /api/export
```

**Response:** `200 OK`, `Content-Disposition: attachment; filename="iam-export.json"`,
`Cache-Control: no-store` — the body is an [`ImportExportBundle`](#the-bundle-format) covering
every function, every organization, and every non-superuser user with their rights.

<a name="import-dry-run"></a>
### 5.2. Import — Dry Run

```
POST /api/import/dry-run
Content-Type: multipart/form-data; boundary=...

--...
Content-Disposition: form-data; name="file"; filename="import.json"
Content-Type: application/json

{ "schemaVersion": "1.0", ... }
--...--
```

**Response:** `200 OK`

```json
{
  "batchId": "3f9c1a2e-...-8b7d6e5f4a3c",
  "functions": [
    { "key": "walletreg", "status": "new", "reason": null }
  ],
  "organizations": [
    { "key": "2021006883", "status": "skipped_duplicate", "reason": "organization already exists" }
  ],
  "users": [
    { "key": "197001011234", "status": "new", "reason": null },
    { "key": "row-2", "status": "error", "reason": "at least one of personalIdentityNumber or orgAffiliation must be given" }
  ]
}
```

Every entry in the uploaded file gets exactly one outcome, `status` one of `new`,
`skipped_duplicate`, or `error`. `key` is the entity's duplicate key (see
[Duplicate Detection](#duplicate-detection)); for a user with neither eID attribute it falls
back to `row-N` (1-based position in the file) so the entry can still be located. `reason` is
set for `skipped_duplicate` and `error`.

`batchId` identifies the validated, duplicate-filtered batch held server-side (in the caller's
session, bound to the confirming superuser, for 15 minutes) for the confirm step.

**Error responses:**

| Status | Condition                                                          |
|--------|---------------------------------------------------------------------|
| 400    | File missing/empty, not valid JSON, unsupported `schemaVersion`, or an entry-count limit exceeded |
| 403    | Caller is not a superuser                                           |

<a name="import-confirm"></a>
### 5.3. Import — Confirm

```
POST /api/import/{batchId}/confirm
```

**Response:** `200 OK`

```json
{
  "functions": [
    { "key": "walletreg", "status": "created", "reason": null }
  ],
  "organizations": [],
  "users": [
    { "key": "197001011234", "status": "created", "reason": null }
  ]
}
```

Same shape as the dry-run report, but only for the `new` entries the dry run identified, and
`status` is now `created`, `skipped_duplicate` (state changed since the dry run — see
[Validation](#validation)), or `error` (the Keycloak write itself failed; logged server-side,
does not stop the rest of the batch). The batch is single-use: it is removed from the session
once this call returns, whether or not every entry succeeded.

**Error responses:**

| Status | Condition                                                              |
|--------|-------------------------------------------------------------------------|
| 403    | Caller is not a superuser                                               |
| 409    | No matching, unexpired batch for `batchId` in this session — run the dry run again |

<a name="import-cancel"></a>
### 5.4. Import — Cancel

```
DELETE /api/import/{batchId}
```

Discards a pending batch without creating anything.

**Response:** `204 No Content` on success.

**Error responses:**

| Status | Condition                                    |
|--------|-----------------------------------------------|
| 403    | Caller is not a superuser                     |
| 404    | No matching pending batch for `batchId`       |

---

For the conceptual model behind organizations, functions, users and rights, see
[Organization and Function-Based Rights Model](rights-model.md).

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
