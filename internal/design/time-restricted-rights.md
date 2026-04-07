![PTS logo](img/pts.png)

# Time Restricted Rights

**Document Version:** 1.0  
**Status:** Design / Not Yet Implemented

---

## Table of Contents

1. [**Background**](#1-background)

2. [**The Problem**](#2-the-problem)

3. [**Considered Approaches**](#3-considered-approaches)

    3.1. [Option A — Mapper Filtering at Token Issuance](#31-option-a--mapper-filtering-at-token-issuance)

    3.2. [Option B — Scheduled Cleanup Job](#32-option-b--scheduled-cleanup-job)

    3.3. [Option C — KeyCloak Session/Token Policies](#33-option-c--keycloak-sessiontoken-policies)

    3.4. [Option D — Separate Time-Limited Group Hierarchy](#34-option-d--separate-time-limited-group-hierarchy)

4. [**Recommended Approach: A + B Combined**](#4-recommended-approach-a--b-combined)

    4.1. [Data Model](#41-data-model)

    4.2. [Changes to the `org_rights` Protocol Mapper](#42-changes-to-the-org_rights-protocol-mapper)

    4.3. [Scheduled Cleanup Job](#43-scheduled-cleanup-job)

    4.4. [`iam-admin-app` Changes](#44-iam-admin-app-changes)

5. [**Summary**](#5-summary)

6. [**Instructions to Claude Code**](#6-instructions-to-claude-code)

---

<a name="1-background"></a>
## 1. Background

In the current KeyCloak setup, users are granted rights on organizations and functions by
being added as members of the corresponding rights groups (e.g.
`orgs/5590026042/walletreg/_write`). Group membership is binary: a user either belongs to a
group or does not. Rights are permanent until an administrator explicitly removes the
membership.

See `docs/keycloak-setup.md` for the full description of the group structure, the `org_rights`
claim, and how Authorization Services scope policies are built.

---

<a name="2-the-problem"></a>
## 2. The Problem

There is a requirement to support **optional time restrictions** on rights. For example:

> User A has `write` rights on function `walletreg` under organization `5590026042` until 2026-07-01.

After the expiry date the right should be treated as if it had never been granted: it must
not appear in the `org_rights` claim, and KeyCloak must not grant the corresponding OAuth
scope (`5590026042:walletreg:write`) to that user.

KeyCloak's group membership model has no native concept of temporal validity. This document
describes how time-restricted rights can be layered on top of the existing architecture
without restructuring KeyCloak.

---

<a name="3-considered-approaches"></a>
## 3. Considered Approaches

<a name="31-option-a--mapper-filtering-at-token-issuance"></a>
### 3.1. Option A — Mapper Filtering at Token Issuance

Store expiry metadata as a user attribute. The custom `org_rights` protocol mapper (which is
already a deployable JAR under our control) reads the attribute at token issuance time and
suppresses any right whose expiry date has passed.

**User attribute `rights-expiry`** holds a JSON array, one entry per time-restricted
membership:

```json
[
  { "group_path": "orgs/5590026042/walletreg/_write", "expires": "2026-07-01" },
  { "group_path": "orgs/5561234567/_read",            "expires": "2027-01-01" }
]
```

The `group_path` is the canonical KeyCloak group path, matching the paths the mapper already
enumerates. The `expires` value is an ISO 8601 date (inclusive last day, i.e., access is
valid through the end of that date).

The mapper evaluates each resolved group membership:

1. Check whether a matching `group_path` entry exists in `rights-expiry`.
2. If an entry exists and `LocalDate.now()` is after `expires`, skip the membership.
3. Emit the remaining memberships as `org_rights` entries as before.

Optionally, the `expires` date can be included in the emitted claim for transparency:

```json
{ "function": "walletreg", "right": "write", "expires": "2026-07-01" }
```

**Advantages:**
- No changes to the group structure or the Authorization Services setup.
- Filtering is immediate — takes effect on the next token issuance, with no lag.
- Expiry data lives on the user, manageable via the KeyCloak Admin REST API.

**Disadvantages:**
- The user remains a *group member* even after expiry. The group membership list in the
  KeyCloak admin console is misleading.
- **Critical gap:** Authorization Services scope policies (used when KeyCloak decides whether
  to grant `5590026042:walletreg:write` as an OAuth 2.0 scope) evaluate raw group membership,
  completely bypassing the `org_rights` mapper. An expired right would still allow the user
  to obtain a scoped access token. This makes Option A alone insufficient.

---

<a name="32-option-b--scheduled-cleanup-job"></a>
### 3.2. Option B — Scheduled Cleanup Job

A background job, running in `iam-admin-app`, periodically queries the expiry metadata for
all users and removes group memberships that have passed their expiry date via the KeyCloak
Admin REST API.

The expiry intent is stored the same way as in Option A (user attribute `rights-expiry`).
The scheduler reads the attribute, identifies memberships past their expiry date, calls
`DELETE /admin/realms/ptsdev/users/{userId}/groups/{groupId}`, and removes the entry from
the attribute.

**Advantages:**
- KeyCloak's actual group membership state is kept correct after cleanup.
- Authorization Services scope policies reflect the expiry correctly, since the membership
  is genuinely removed.
- No changes to the mapper are needed for the OAuth scope path to work correctly.
- The user's rights history in KeyCloak remains auditable.

**Disadvantages:**
- Expiry is not enforced in real time — there is a lag equal to the scheduler interval
  (e.g., up to one hour). During this window, a user could still obtain an access token
  with an expired scope if they request one before the cleanup runs.
- The scheduler must be reliable and monitored.

---

<a name="33-option-c--keycloak-sessiontoken-policies"></a>
### 3.3. Option C — KeyCloak Session/Token Policies

KeyCloak has realm-level token lifespan and session policies, but these apply uniformly to
all tokens in the realm. There is no built-in mechanism to make a group membership expire at
a specific date. This option is a dead end for this use case.

---

<a name="34-option-d--separate-time-limited-group-hierarchy"></a>
### 3.4. Option D — Separate Time-Limited Group Hierarchy

One could imagine a parallel group tree specifically for time-limited memberships, with per-user
leaf groups carrying expiry attributes. However, KeyCloak groups do not support
per-membership attributes (only per-group attributes), so the expiry would have to be stored
as a group attribute on a leaf group that contains only one user. This approach is unwieldy
and does not scale. Not recommended.

---

<a name="4-recommended-approach-a--b-combined"></a>
## 4. Recommended Approach: A + B Combined

The recommended solution combines Option A and Option B:

- **Option A (mapper filtering)** provides immediate, token-level enforcement for the
  `org_rights` claim. Even before the scheduler has run, a newly expired right will not
  appear in the claim, and the relying party will not grant access based on it.
- **Option B (scheduled cleanup)** ensures that KeyCloak's group membership state is
  eventually correct, which in turn makes Authorization Services scope enforcement (for
  OAuth access tokens) accurate as well.

Together, the two mechanisms provide defense in depth: mapper filtering closes the gap during
the scheduler lag window for the `org_rights` path, while the scheduler closes the gap for
the OAuth scope path.

<a name="41-data-model"></a>
### 4.1. Data Model

Expiry information is stored as a user attribute named `rights-expiry` on the KeyCloak user.
The value is a JSON array serialized to a string. Each element describes one time-restricted
group membership:

```json
[
  {
    "group_path": "orgs/5590026042/walletreg/_write",
    "expires": "2026-07-01"
  },
  {
    "group_path": "orgs/5561234567/_read",
    "expires": "2027-01-01"
  }
]
```

| Field | Type | Description |
|---|---|---|
| `group_path` | `string` | Canonical KeyCloak group path, matching the path used by the `org_rights` mapper when enumerating group memberships. |
| `expires` | `string` | ISO 8601 date (`YYYY-MM-DD`). The right is valid through the end of this date; access is denied from the following day onwards. |

Rights without a time restriction have no entry in `rights-expiry`. The attribute is absent
entirely if the user holds no time-restricted rights.

When a time-restricted right is removed before its expiry date (e.g., by an administrator),
both the group membership and the corresponding `rights-expiry` entry must be removed
atomically.

<a name="42-changes-to-the-org_rights-protocol-mapper"></a>
### 4.2. Changes to the `org_rights` Protocol Mapper

The mapper (in `keycloak/`) must be extended to:

1. Read the `rights-expiry` user attribute and parse it as a `List<ExpiryEntry>`.
2. For each group membership that would otherwise produce a `functions` entry, check whether
   a matching `group_path` entry exists in the parsed list.
3. If a matching entry exists and `LocalDate.now(ZoneId.of("Europe/Stockholm"))` is after
   `LocalDate.parse(entry.expires)`, skip this membership — do not add it to the claim.
4. If no matching entry exists (right is unrestricted), emit the entry as before.

Optionally, include the `expires` field in the emitted `functions` entry so that relying
parties can display a warning to the user when their access is approaching its expiry date:

```json
{
  "function": "walletreg",
  "right": "write",
  "expires": "2026-07-01"
}
```

Whether to include `expires` in the claim is a product decision and should be confirmed
before implementation.

<a name="43-scheduled-cleanup-job"></a>
### 4.3. Scheduled Cleanup Job

A Spring `@Scheduled` component in `iam-admin-app`'s backend (recommended interval: every
hour, configurable) performs the following steps:

1. Retrieve all users from KeyCloak via the Admin REST API.
2. For each user, read the `rights-expiry` attribute.
3. For each entry where `LocalDate.now()` is after `expires`:
   a. Resolve the `group_path` to a KeyCloak group ID.
   b. Call `DELETE /admin/realms/{realm}/users/{userId}/groups/{groupId}` to remove the
      membership.
   c. Remove the entry from the `rights-expiry` list.
4. If the `rights-expiry` list is now empty, remove the attribute from the user entirely.
5. Persist the updated `rights-expiry` attribute via `PUT /admin/realms/{realm}/users/{userId}`.

All Admin REST API calls use the existing service account client credentials grant
(`KeycloakAdminClient.java`).

The job must be idempotent: if a membership has already been removed (e.g., by an
administrator), the cleanup step for that entry should succeed silently.

Errors during cleanup of an individual user should be logged at `WARN` level and must not
abort the cleanup run for remaining users.

<a name="44-iam-admin-app-changes"></a>
### 4.4. `iam-admin-app` Changes

The following changes are required in the `iam-admin-app` frontend and backend:

**Backend:**

- When assigning a right (adding a user to a group), accept an optional `expiresOn`
  (`LocalDate`) parameter alongside the existing right assignment parameters.
- If `expiresOn` is provided, after adding the group membership, read the current
  `rights-expiry` user attribute, append the new entry, and write the attribute back.
- When revoking a right (removing a user from a group), also remove the corresponding entry
  from `rights-expiry` if one exists.
- Expose the `rights-expiry` data as part of the user rights response so the frontend can
  display it.

**Frontend:**

- In `AddUserToOrgDialog.tsx` and `AddUserToFunctionDialog.tsx`, add an optional date picker
  field ("Access expires on") to the right assignment form.
- In the user rights display (e.g., `UserList.tsx` or a dedicated rights view), show the
  expiry date next to any time-restricted right, with a visual indicator when the expiry is
  approaching (e.g., within 30 days).

---

<a name="5-summary"></a>
## 5. Summary

| Aspect | Detail |
|---|---|
| Storage | User attribute `rights-expiry` (JSON array) on the KeyCloak user |
| Immediate enforcement | `org_rights` mapper filters expired rights at token issuance |
| Full enforcement | Scheduled cleanup job removes expired group memberships hourly |
| OAuth scope enforcement | Correct after cleanup job runs (lag ≤ scheduler interval) |
| Admin REST API | Existing `KeycloakAdminClient.java` service account is used throughout |
| New dependencies | None — no new KeyCloak plugins or external services required |

The combination of mapper filtering and scheduled cleanup provides defense in depth. The
`org_rights` claim path is enforced immediately; the OAuth scope path is enforced within the
scheduler lag window. Neither path requires real-time KeyCloak group membership changes by
the user-facing request thread.

---

<a name="6-instructions-to-claude-code"></a>
## 6. Instructions to Claude Code

This section provides implementation guidance for the A+B combination described above. Read
all referenced source files before making any changes.

### Prerequisites — Files to Read First

Before writing any code, read the following files in their entirety:

- `docs/keycloak-setup.md` — full description of group structure, `org_rights` claim format,
  Authorization Services scope policies, and the Admin REST API patterns used in this project.
- `docs/time-restricted-rights.md` — this document.
- `keycloak/` — identify the `org_rights` protocol mapper source file(s) and read them.
- `iam-admin-app/backend/src/` — read `KeycloakAdminClient.java` (full file) and any
  existing right-assignment service/controller classes.
- `iam-admin-app/frontend/src/` — read `AddUserToOrgDialog.tsx`, `AddUserToFunctionDialog.tsx`,
  and any user rights display components.

### Step 1 — Data Model Class

Create a new class `RightsExpiry` (or equivalent record) in the `commons` or
`iam-admin-app/backend` module:

```java
public record RightsExpiry(String groupPath, LocalDate expires) {}
```

Add a utility method to serialize/deserialize a `List<RightsExpiry>` to/from the JSON string
format used in the `rights-expiry` user attribute. Use Jackson or Gson — whichever is already
on the classpath.

### Step 2 — `org_rights` Protocol Mapper Extension

Locate the mapper source file (in `keycloak/`). Extend the group-membership-to-claim
mapping logic as follows:

- After loading the user's group list and before building the `functions` array for each
  organization, parse the `rights-expiry` attribute from the user's attributes map.
- For each group membership being considered, check whether a `RightsExpiry` entry exists
  with a matching `group_path`.
- If an entry exists and `LocalDate.now(ZoneId.of("Europe/Stockholm"))` is strictly after
  `expires`, skip this membership.
- Do not include the `expires` field in the emitted claim at this time (defer that decision).

Do not change the claim structure for non-expiring rights.

### Step 3 — Backend: Right Assignment with Optional Expiry

In the right-assignment service/controller:

- Add an optional `expiresOn` (`LocalDate`, nullable) parameter to the method(s) that add a
  user to a rights group.
- After the group membership is added via `KeycloakAdminClient`, if `expiresOn` is non-null:
  1. Fetch the user's current `rights-expiry` attribute via `KeycloakAdminClient`.
  2. Parse it (empty list if absent).
  3. Remove any existing entry for the same `group_path` (handle re-assignment).
  4. Append a new `RightsExpiry(groupPath, expiresOn)` entry.
  5. Serialize the list back to JSON and write it to the user attribute.
- In the right-revocation path, always remove the corresponding `rights-expiry` entry if
  one exists, regardless of whether `expiresOn` was originally set.

All attribute reads and writes must use the existing service account token retry logic in
`KeycloakAdminClient.java`.

### Step 4 — Scheduled Cleanup Job

Create a new Spring `@Component` class `ExpiredRightsCleanupJob` in `iam-admin-app/backend`:

```java
@Component
public class ExpiredRightsCleanupJob {

    @Scheduled(cron = "${app.cleanup.expired-rights-cron:0 0 * * * *}")
    public void run() { ... }
}
```

The job must:

1. Fetch all users from KeyCloak via `KeycloakAdminClient` (use pagination if the API
   requires it — check existing patterns in the client).
2. For each user, read and parse the `rights-expiry` attribute.
3. Identify entries where `LocalDate.now()` is strictly after `expires`.
4. For each expired entry:
   a. Resolve the `group_path` string to a KeyCloak group ID. Use a cached group-path-to-ID
      lookup; refresh the cache if a group is not found (group may have been renamed or
      deleted).
   b. Call the Admin REST API to remove the user from the group. If the user is already not
      a member, log at `DEBUG` and continue.
5. Remove all expired entries from the `rights-expiry` list.
6. If the list changed, write the updated (or removed) attribute back to the user.
7. Log a summary at `INFO` level: number of users processed, number of memberships removed.
8. Log per-user errors at `WARN` level; do not abort the entire run on a single user failure.

Cron expression is configurable via `application.yml` / `application-local.yml` with a
default of every full hour.

### Step 5 — Frontend: Expiry Date Picker

In `AddUserToOrgDialog.tsx` and `AddUserToFunctionDialog.tsx`:

- Add an optional date picker field labelled "Access expires on" (or the Swedish equivalent)
  below the role selector, in both the "Select existing user" and "Create new user" tabs.
- The field is optional — leaving it empty means no time restriction.
- The selected date must be in the future; add client-side validation to enforce this.
- Pass the selected date (or `null`) to the backend right-assignment endpoint.
- Add translation keys for the new field label and validation message to `LanguageContext.tsx`
  (both English and Swedish).

In the user rights display:

- Where rights are listed per user, show the expiry date next to time-restricted rights.
- Add a visual indicator (e.g., an amber badge or icon) for rights expiring within 30 days.

### General Implementation Notes

- Follow all existing code conventions: Lombok, constructor injection, `jspecify` nullability
  annotations, copyright header `/* Copyright (c) 2025-2026. PTS */`.
- Do not introduce new Maven dependencies unless strictly necessary; prefer libraries already
  on the classpath.
- After each step, run `build_project` to confirm the module compiles before proceeding to
  the next step.
- Write targeted, minimal changes. Do not refactor unrelated code.
