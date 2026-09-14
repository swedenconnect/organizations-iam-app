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

A duplicate is always **skipped entirely**, so if a person in the file already exists, neither
the person nor any rights listed for them are created. Managed OAuth clients are out of scope
for this feature; they have a separate lifecycle and are managed under `/api/clients` (see
[Registering a Client](registering-a-client.md)).

Superuser accounts (`superuser: true`) are never included in an export, and there is no way to
grant superuser status through import, since user creation has no such field. See
[Rights Model — Users](rights-model.md#users) for what a superuser account is.

---

<a name="the-bundle-format"></a>
## 2. The Bundle Format

The same JSON shape is used for both directions: `GET /api/export` returns it, and
`POST /api/import/dry-run` accepts a file in this shape.

```json
{
  "schema_version": "1.0",
  "exported_at": "2026-09-14T10:00:00Z",
  "functions": [ /* BundleFunctionEntry[] */ ],
  "organizations": [ /* BundleOrganizationEntry[] */ ],
  "users": [ /* BundleUserEntry[] */ ]
}
```

| Field           | Type              | Description                                                                |
|-----------------|-------------------|----------------------------------------------------------------------------|
| `schema_version`| string            | Must be exactly `"1.0"`. An import with any other value is rejected outright. |
| `exported_at`   | string \| null    | ISO-8601 timestamp of when an export was taken. Informational only, ignored on import. |
| `functions`     | array             | See [Functions](#functions). May be empty or omitted.                       |
| `organizations` | array             | See [Organizations](#organizations). May be empty or omitted.               |
| `users`         | array             | See [Users](#users). May be empty or omitted.                               |

**Every key is snake_case**, matching the Keycloak group attributes the bundle is built from and
the claims the rest of the system emits.

**A localized value is carried as one key per language**, tagged with the language after a `#`,
again the same form the group attributes and the claims use: `name#sv`, `name#en`,
`description#sv`, `description#en`. Swedish (`sv`) and English (`en`) are the only tags the
application knows, and a key tagged with any other language is ignored, on import and on export
alike. An organization's legal name is not a localized value, so `legal_name` is a plain
untagged key.

A file need not cover every entity type, and an import that only adds new functions, with empty
`organizations` and `users` arrays, is valid.

**Processing order:** `functions` → `organizations` → `users`. Later entries may reference
earlier ones: `organizations[].attached_functions` refers to a function `id`, and
`users[].rights[].function_id` refers to a function attached to
`users[].rights[].org_identifier`. A reference may resolve either to something already in
Keycloak, or to an entry earlier in the same file.

<a name="full-example"></a>
### 2.1. Full Example

```json
{
  "schema_version": "1.0",
  "exported_at": "2026-09-14T10:00:00Z",
  "functions": [
    {
      "id": "walletreg",
      "name#sv": "Walletregistrering",
      "name#en": "Wallet registration",
      "description#sv": "Registrering av digitala plånböcker",
      "description#en": "Registration of digital wallets"
    },
    {
      "id": "demo",
      "name#sv": "Demo",
      "name#en": "Demo",
      "description#sv": null,
      "description#en": null
    }
  ],
  "organizations": [
    {
      "org_identifier": "2021006883",
      "legal_name": "Myndigheten för Digital förvaltning",
      "name#sv": "Digg - Myndigheten för Digital förvaltning",
      "name#en": "Digg - Authority for Digital Government",
      "contact_email": "info@digg.se",
      "contact_phone": null,
      "attached_functions": ["walletreg", "demo"]
    },
    {
      "org_identifier": "5561234567",
      "legal_name": "Exempel Aktiebolag",
      "name#sv": null,
      "name#en": null,
      "contact_email": null,
      "contact_phone": "+46701234567",
      "attached_functions": ["demo"]
    }
  ],
  "users": [
    {
      "name": "Anna Andersson",
      "username": "anna.andersson",
      "email": "anna.andersson@digg.se",
      "personal_identity_number": "197001011234",
      "org_affiliation": null,
      "phone_number": "+46701112233",
      "rights": [
        { "org_identifier": "2021006883", "function_id": "walletreg", "right": "admin" },
        { "org_identifier": "2021006883", "function_id": null, "right": "read" }
      ]
    },
    {
      "name": "Bertil Bengtsson",
      "username": null,
      "email": "bertil@exempel.se",
      "personal_identity_number": null,
      "org_affiliation": "bertil@5561234567",
      "phone_number": null,
      "rights": [
        { "org_identifier": "5561234567", "function_id": "demo", "right": "write" }
      ]
    }
  ]
}
```

Anna is granted `admin` on `walletreg` specifically, plus organization-wide `read` on
`2021006883` (which also covers `demo`, the org's other attached function). Bertil, identified
by organizational affiliation rather than a personal identity number, is granted `write` on
`demo` only. Anna's entry names the username she is to get; Bertil's does not, so Keycloak
assigns him a random UUID. See [The Username](#the-username) for when a named username is
honoured.

<a name="functions"></a>
### 2.2. Functions — `BundleFunctionEntry`

| Field            | Type            | Description                                                           |
|------------------|-----------------|-------------------------------------------------------------------------|
| `id`             | string          | The function identifier. Must match `^[a-z0-9_-]+$` and be unique in the realm. This is what the task description calls "function_group_id"; there is no separate function-group concept. |
| `name#sv`        | string \| null  | Swedish display name. Must not be blank for a new function.             |
| `name#en`        | string \| null  | English display name. Must not be blank for a new function.             |
| `description#sv` | string \| null  | Optional longer description in Swedish.                                 |
| `description#en` | string \| null  | Optional longer description in English.                                 |

<a name="organizations"></a>
### 2.3. Organizations — `BundleOrganizationEntry`

| Field                | Type            | Description                                                          |
|----------------------|-----------------|--------------------------------------------------------------------------|
| `org_identifier`     | string          | Ten-digit Swedish organizational number, no dash. The organization's identity. |
| `legal_name`         | string          | The name registered at Bolagsverket. Mandatory for a new organization.   |
| `name#sv`            | string \| null  | Optional Swedish display name.                                           |
| `name#en`            | string \| null  | Optional English display name.                                           |
| `contact_email`      | string \| null  | Optional contact email.                                                  |
| `contact_phone`      | string \| null  | Optional contact phone.                                                  |
| `attached_functions` | string[]        | Function `id`s attached to this organization. Each must resolve per [Processing order](#the-bundle-format). |

<a name="users"></a>
### 2.4. Users — `BundleUserEntry`

| Field                       | Type            | Description                                                           |
|-----------------------------|-----------------|-----------------------------------------------------------------------|
| `name`                      | string          | Full name, split on the first space into first/last name, the same convention `POST /api/users` uses. Must not be blank. |
| `username`                  | string \| null  | The Keycloak username. See [The Username](#the-username).             |
| `email`                     | string \| null  | Optional email address. Must contain `@` if given.                    |
| `personal_identity_number`  | string \| null  | 12-digit Swedish personal identity number.                            |
| `org_affiliation`           | string \| null  | Organizational affiliation, format `userID@organization-number`.      |
| `phone_number`              | string \| null  | Optional phone number.                                                |
| `rights`                    | array           | `{ org_identifier, function_id, right }`. `function_id: null` means the right is organization-wide. `right` is `read`, `write`, or `admin`. |

**At least one of `personal_identity_number` or `org_affiliation` is required**, see
[Duplicate Detection](#duplicate-detection). A user entry with neither is rejected as an error,
regardless of the deployment's `eidAttributeRequired` setting for the manual registration form.

**HSA-ID and EFOS-ID are planned.** They are eID attributes the application will support, along
with the two above, and the registration settings already name them (`hsa-id-enabled` and
`efos-id-enabled`, see
[User Registration Settings](iam-admin-configuration.md#user-registration-settings)). They are
not part of the bundle format yet. When the attributes themselves are implemented, they will be
added to a user entry as `hsa_id` and `efos_id`, and they are expected to satisfy the
requirement above in the same way the personal identity number and the organizational
affiliation do today.

There is no `superuser` field: superuser status cannot be granted through import.

<a name="the-username"></a>
#### The Username

An export always carries the Keycloak username of every exported person, so that a realm
exported and imported elsewhere keeps its usernames instead of every imported person getting a
random UUID.

On import the username is **optional**, and it is honoured only when the deployment lets an
administrator choose the user ID, that is when
`iam.admin.user-registration.allow-select-user-id` is `true` (see
[User Registration Settings](iam-admin-configuration.md#user-registration-settings)). For one
person the outcomes are decided in this order:

1. **The person already exists**, matched on personal identity number or organizational
   affiliation. The entry is skipped as a duplicate and no username rule is applied to it.
   Re-importing an export into the realm it came from is therefore quiet, whatever the setting
   is and whatever usernames the file carries.
2. **`allow-select-user-id` is off and the entry carries a username.** The entry is an error,
   reported as usernames not being accepted in an import file for this deployment.
3. **The username is already taken in Keycloak.** The entry is an error. A taken username is
   never a reason to treat the entry as a duplicate person, because the two are different
   people.
4. **Otherwise** the username is assigned to the created user.

An entry without a username is created the way any other user without a chosen user ID is, with
Keycloak assigning a random UUID as username. The username is **not** a duplicate key: duplicate
detection is on personal identity number and organizational affiliation only, and the key
reported for a user entry is unchanged.

---

<a name="duplicate-detection"></a>
## 3. Duplicate Detection

| Entity type   | Duplicate key                                                   |
|---------------|-------------------------------------------------------------------|
| Function      | `id`                                                               |
| Organization  | `org_identifier`                                                   |
| User          | `personal_identity_number` if given, otherwise `org_affiliation`   |

Functions and organizations are matched on their natural identifier, which is the only sane
choice since it is also what every reference to them uses.

Users are matched on `personal_identity_number` / `org_affiliation`, the same two eID attributes
`POST /api/users` already enforces uniqueness on when a user is created through the regular
form (see [Rights Model — Users](rights-model.md#users)). **Email is deliberately not used as a
duplicate key**: it is a plain profile field with no uniqueness constraint elsewhere in the
system, so using it here would be a new and inconsistent rule. **Neither is `username`**: a
username already taken belongs to a different person, and is reported as an error rather than as
a duplicate (see [The Username](#the-username)).

A duplicate is **skipped in full**. If a person in the file already exists, none of the rights
listed for them are applied either, not even ones the existing account does not yet hold. This
keeps the outcome simple to reason about and to report: every entry in the preview and the
final report is exactly `new`/`created`, `skipped_duplicate`, or `error`, never partially
applied.

---

<a name="validation"></a>
## 4. Validation

Besides the field constraints in the tables above, a `rights` entry is rejected as an error on
the whole user entry if:

- `right` is not one of `read`, `write`, `admin`; or
- `org_identifier` does not resolve to an organization already in Keycloak or earlier in the
  same file; or
- `function_id` is set but is not attached to that organization, either already in Keycloak or
  by an `attached_functions` entry earlier in the same file.

A user entry is also an error when its `username` cannot be honoured, see
[The Username](#the-username).

An uploaded file is rejected outright (nothing is validated per-entry) if it is not valid JSON,
`schema_version` is anything other than `"1.0"`, or any one of `functions`, `organizations`,
`users` exceeds 5000 entries. A file written against an older, camelCase version of this format
carries no `schema_version` key at all and is rejected on exactly that ground.

State can change between the dry run and the confirm step (for example, another administrator
creates the same organization in the meantime, or somebody registers the username a user entry
names). Confirm re-checks every entry immediately before creating it, and re-reports it as
`skipped_duplicate` or, for a username taken in the meantime, as `error` rather than failing the
whole batch.

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
`Cache-Control: no-store`. The body is an [`ImportExportBundle`](#the-bundle-format) covering
every function, every organization, and every non-superuser user with their rights and username.

<a name="import-dry-run"></a>
### 5.2. Import — Dry Run

```
POST /api/import/dry-run
Content-Type: multipart/form-data; boundary=...

--...
Content-Disposition: form-data; name="file"; filename="import.json"
Content-Type: application/json

{ "schema_version": "1.0", ... }
--...--
```

**Response:** `200 OK`

```json
{
  "batch_id": "3f9c1a2e-...-8b7d6e5f4a3c",
  "functions": [
    { "key": "walletreg", "status": "new", "reason": null }
  ],
  "organizations": [
    { "key": "2021006883", "status": "skipped_duplicate", "reason": "organization already exists" }
  ],
  "users": [
    { "key": "197001011234", "status": "new", "reason": null },
    { "key": "row-2", "status": "error", "reason": "at least one of personal_identity_number or org_affiliation must be given" }
  ]
}
```

Every entry in the uploaded file gets exactly one outcome, `status` one of `new`,
`skipped_duplicate`, or `error`. `key` is the entity's duplicate key (see
[Duplicate Detection](#duplicate-detection)); for a user with neither eID attribute it falls
back to `row-N` (1-based position in the file) so the entry can still be located. `reason` is
set for `skipped_duplicate` and `error`.

`batch_id` identifies the validated, duplicate-filtered batch held server-side (in the caller's
session, bound to the confirming superuser, for 15 minutes) for the confirm step.

**Error responses:**

| Status | Condition                                                          |
|--------|---------------------------------------------------------------------|
| 400    | File missing/empty, not valid JSON, unsupported `schema_version`, or an entry-count limit exceeded |
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
`status` is now `created`, `skipped_duplicate` (state changed since the dry run, see
[Validation](#validation)), or `error` (the username was taken in the meantime, or the Keycloak
write itself failed; logged server-side, and does not stop the rest of the batch). The batch is single-use: it is removed from the session
once this call returns, whether or not every entry succeeded.

**Error responses:**

| Status | Condition                                                              |
|--------|-------------------------------------------------------------------------|
| 403    | Caller is not a superuser                                               |
| 409    | No matching, unexpired batch for `batchId` in this session, so the dry run has to be run again |

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
