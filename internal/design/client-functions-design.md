# Design: `client_functions` — Function-Scoped Client Management

**Status:** Design  
**Relates to:** `docs/keycloak-setup.md` sections 4.6, 4.7, 4.9; `iam-admin-app/backend/.../KeycloakAdminClient.java`

---

## 1. Problem

When a new `iam_admin_managed` client is registered in a Keycloak realm where functions,
organizations and users already exist, the client is missing all the Keycloak artifacts
that were created when those functions were attached to organizations:

- The three realm-level OAuth2 client scopes per org/function combination
  (`{org}:{func}:read`, `{org}:{func}:write`, `{org}:{func}:admin`) have not been added
  as optional scopes on the new client.
- The three Authorization Services scopes per org/function combination do not exist on
  the new client's resource server.
- The three group policies and scope permissions per org/function combination do not exist
  on the new client.

As a result, users can never obtain a token scoped to `{org}:{func}:{right}` from the new
client, even if they hold the correct group memberships, because Keycloak has no policy to
evaluate and no scope to grant.

Additionally, today's behaviour is that **all** `iam_admin_managed` clients receive
artifacts for **all** org/function combinations. For a client that is only relevant to
a specific function (e.g. `demo`), this means it unnecessarily receives scopes and
policies for every other function in the realm.

---

## 2. Proposed Solution

### 2.1. New client attribute: `client_functions`

Introduce a new optional Keycloak client attribute `client_functions`. Its value is a
comma-separated list of function identifiers, e.g. `demo` or `demo,walletreg`.

**Semantics:**

| Value | Behaviour |
|---|---|
| Absent or empty string | Client is treated as function-universal — receives artifacts for all functions (today's behaviour, unchanged). |
| One or more function identifiers | Client only receives artifacts for the listed functions. |

The attribute is read by the iam-admin-app whenever it resolves which clients to manage.

### 2.2. Changes to `resolveIamAdminManagedClientUuids` / client filtering

The existing `resolveIamAdminManagedClientUuids()` method returns all clients where
`iam_admin_managed=true`. This method (or the callers of it) must be extended to also
return the `client_functions` attribute for each resolved client, so that callers can
filter the org/function combinations they act on.

A new method or wrapper is needed:

```java
record ManagedClient(String uuid, Set<String> functions) {
  /** Returns true if this client handles the given functionId. */
  boolean handles(String functionId) {
    return functions.isEmpty() || functions.contains(functionId);
  }
}

List<ManagedClient> resolveIamAdminManagedClients()
```

All callers of `resolveIamAdminManagedClientUuids()` in `KeycloakAdminClient` —
`attachFunctionToOrg`, `detachFunctionFromOrg`, `deleteFunction` — are updated to use
`resolveIamAdminManagedClients()` and filter by `handles(functionId)` before acting on
each client.

### 2.3. New script: `set-client-functions.sh`

A new script `compose/keycloak-scripts/set-client-functions.sh` that:

1. Sets the `client_functions` attribute on the specified client.
2. Scans all org groups under `orgs/` and for each org, checks which function sub-groups
   are attached. For each attached function that matches the `client_functions` list, it
   runs the same artifact creation logic as `attachFunctionToOrg` would have run if the
   client had existed at the time:
   - Creates the three OAuth2 realm client scopes if they do not already exist (idempotent).
   - Creates the three Authorization Services scopes on the client if they do not exist.
   - Creates the group policy and scope permission for each level if they do not exist.
   - Adds the three OAuth2 client scopes as optional scopes on the client if not already
     present.

This script is the "catch-up" mechanism for late-registered clients.

**Usage:**
```bash
./compose/keycloak-scripts/set-client-functions.sh \
    --realm ptsdev \
    --username admin \
    --password keycloak \
    --client-id https://dev-wallet.pts.se:16990 \
    --functions demo
```

**Options:**

| Option | Required | Description |
|---|---|---|
| `--realm` | Yes | Keycloak realm name |
| `--username` | Yes | Keycloak admin username |
| `--password` | Yes | Keycloak admin password |
| `--client-id` | Yes | The OAuth2 client_id string |
| `--functions` | Yes | Comma-separated list of function identifiers |

The script is idempotent — safe to re-run against an existing client.

---

## 3. Impact on existing behaviour

### `attachFunctionToOrg`

When a function is attached to an organization, the loop over managed clients is filtered:
only clients where `handles(functionId)` is true receive artifacts. This means a
`client_functions=demo` client only gets policies and scopes when `demo` is attached to
an org, not for `walletreg`, `sweden-connect`, etc.

### `detachFunctionFromOrg` / `deleteFunction`

Same filter applies when removing artifacts — a `client_functions=demo` client is not
touched when a `walletreg` function is detached.

### Existing clients without `client_functions`

Behaviour is completely unchanged. Absent or empty `client_functions` means the client
continues to receive all artifacts for all functions.

---

## 4. Documents to update

| Document | What to add/change |
|---|---|
| `docs/keycloak-setup.md` | Section 4.9 — describe `client_functions` attribute and its semantics. Section 4.6 / 4.7 — note that `client_functions` can be set to restrict scope management. New section or appendix entry for `set-client-functions.sh`. |
| `compose/keycloak-scripts/README.md` | New section documenting `set-client-functions.sh` (usage, options, example, idempotency note). |
| `docs/iam-integration-guide.md` | Section 2.1 (Keycloak registration) — note that `set-client-functions.sh` should be run when the client is scoped to specific functions, and must be run after registration if the realm already has functions attached to organizations. |

---

## 5. Files to create or modify

### New files

| File | Description |
|---|---|
| `compose/config/keycloak/scripts/set-client-functions.sh` | Inner script — sets attribute and creates all missing artifacts |
| `compose/keycloak-scripts/set-client-functions.sh` | Outer wrapper (thin Docker Compose runner, same pattern as `add-oidc-client.sh`) |

### Modified files

| File | Change |
|---|---|
| `iam-admin-app/backend/src/main/java/se/pts/wallet/iam/keycloak/KeycloakAdminClient.java` | Add `ManagedClient` record, add `resolveIamAdminManagedClients()`, update `attachFunctionToOrg`, `deleteFunction`, and the detach method to use function-filtered client list |
| `docs/keycloak-setup.md` | See section 4 above |
| `compose/keycloak-scripts/README.md` | See section 4 above |
| `docs/iam-integration-guide.md` | See section 4 above |

---

## 6. Implementation notes for `set-client-functions.sh`

The inner script (runs inside the `keycloak-setup` container) must:

1. Authenticate against master realm.
2. Resolve the client UUID from `--client-id`.
3. Set `attributes.client_functions` on the client via `kcadm update`.
4. Query all org groups under the `orgs` top-level group.
5. For each org group, fetch children and identify function sub-groups (those not starting
   with `_`).
6. For each attached function that is in the `--functions` list:
   a. Ensure the three realm-level OAuth2 client scopes exist (create if missing).
   b. Ensure the three Authorization Services scopes exist on the client (create if missing).
   c. Ensure the three group policies exist on the client (create if missing). Each policy
      must reference the correct qualifying groups (see `keycloak-setup.md` section 2.7).
   d. Ensure the three scope permissions exist on the client (create if missing).
   e. Ensure the three OAuth2 client scopes are added as optional scopes on the client.
7. All steps are idempotent — check for existence before creating.

The group path logic for policies mirrors exactly what the iam-admin-app does in
`createPolicyAndPermission`. The qualifying groups for each right level are:

- `read`: `/orgs/{org}/_read`, `/_write`, `/_admin`, `/orgs/{org}/{func}/_read`,
  `/_write`, `/_admin`
- `write`: `/orgs/{org}/_write`, `/_admin`, `/orgs/{org}/{func}/_write`, `/_admin`
- `admin`: `/orgs/{org}/_admin`, `/orgs/{org}/{func}/_admin`

The script resolves group UUIDs from paths using `kcadm get groups` with search, as the
existing scripts already do.

---

## 7. Out of scope

- No change to the `add-oidc-client.sh` script — `client_functions` is set separately via
  `set-client-functions.sh` after registration.
- No UI changes in iam-admin-app for managing `client_functions` — the attribute is
  managed via script only.
- No change to the `org_rights` claim or the `OrgRightsScopeConverter` — these are
  unchanged.
