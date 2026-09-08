# Design: Managed Clients in `iam-admin-app`

**Document Version:** 1.0  
**Status:** Design / Not Yet Implemented  
**Relates to:** [issue #58](https://github.com/swedenconnect/organizations-iam-app/issues/58)  
**Supersedes:** `internal/design/client-functions-design.md` (Java-side parts; the shell
scripts described there are implemented and stay)

---

## Table of Contents

1. [**Background**](#1-background)
2. [**The Problem**](#2-the-problem)
3. [**Solution Overview**](#3-solution-overview)
4. [**Data Model**](#4-data-model)
5. [**Reconciliation**](#5-reconciliation)
6. [**REST API**](#6-rest-api)
7. [**Scheduled Reconciliation**](#7-scheduled-reconciliation)
8. [**Frontend**](#8-frontend)
9. [**Configuration**](#9-configuration)
10. [**Files to Create or Modify**](#10-files-to-create-or-modify)
11. [**Testing**](#11-testing)
12. [**Risks**](#12-risks)
13. [**Out of Scope**](#13-out-of-scope)
14. [**Implementation Order**](#14-implementation-order)

---

## 1. Background

A *managed client* is a Keycloak client carrying the attribute `iam_admin_managed=true`.
`iam-admin-app` maintains the OAuth2/authorization artifacts for such clients: when a
function is attached to an organization, the app creates — for every managed client —
three realm client scopes, three Authorization Services scopes, three group policies,
three scope permissions, and registers the three scopes as optional client scopes
(`KeycloakAdminClient.attachFunctionToOrg`). Detach and function deletion remove the same
artifacts.

Today a managed client can only be created by running
`keycloak/scripts/add-oidc-client.sh` against the realm. The script sets
`clientAuthenticatorType=client-jwt`, enables Authorization Services, registers the three
protocol mappers (`org-rights-mapper`, `scope-org-identifier-mapper`,
`resource-audience-mapper`), removes the service account user, and adds the
`naturalPersonNumber` and `phone` optional scopes.

The `client_functions` attribute (comma-separated function identifiers) already exists and
is honoured by `resource-aud-plugin` at token issuance, and can be set with
`keycloak/scripts/set-client-functions.sh`. It is **not** honoured by
`iam-admin-app` — every managed client receives artifacts for every function.

## 2. The Problem

1. **No client management from the admin application.** Registering a new relying party
   requires shell access to the Keycloak host and admin credentials.
2. **Late-registered clients are incomplete.** A client created after functions were
   already attached to organizations has none of the corresponding scopes, policies or
   permissions. Users can never obtain a `{org}:{func}:{right}` token from it, even with
   correct group memberships.
3. **No function filtering in the app.** A client relevant only to `demo` still receives
   artifacts for every function in the realm.
4. **No drift repair.** Partial failures (`attachFunctionToOrg` performs no rollback),
   intermittent Keycloak errors, or manual edits in the Keycloak admin console leave the
   realm inconsistent, with no way to detect or repair it.

## 3. Solution Overview

Add managed-client CRUD to `iam-admin-app`, backed by a single idempotent
**reconciliation** routine that computes the artifacts a managed client should have and
creates whatever is missing.

The same routine is invoked from every path that can change the desired state:

| Trigger | Scope of reconciliation |
|---|---|
| Client created via the admin app | That client |
| `client_functions` or JWKS changed | That client |
| Function attached to / detached from an org | Managed clients that handle the function |
| Function deleted | Managed clients that handle the function |
| Explicit reconcile from the UI/API | One client or all |
| Scheduled cron job | All managed clients |

Keycloak remains the single source of truth — no new persistence is introduced.
The scheduled job exists solely to repair drift; correctness does not depend on it.

## 4. Data Model

### 4.1. Keycloak client attributes

| Attribute | Meaning |
|---|---|
| `iam_admin_managed` | `"true"` — the client is managed by `iam-admin-app` |
| `client_functions` | Comma-separated function identifiers. Always written by the app — a client created or edited in the admin app has at least one function. Absent/empty is only possible for clients provisioned outside the app; see 4.5 |
| `use.jwks.url` + `jwks.url` | JWKS by reference |
| `use.jwks.string` + `jwks.string` | JWKS inline |

Exactly one of the two JWKS forms is set by the app; setting one clears the other.

### 4.2. `ManagedClientInfo`

New record in `se.swedenconnect.iam.admin.keycloak.model`:

```java
public record ManagedClientInfo(
    @NonNull String uuid,
    @NonNull String clientId,
    @Nullable String name,
    @NonNull Set<String> functions,
    @NonNull List<String> redirectUris,
    @Nullable String jwksUri,
    @Nullable String jwksString,
    boolean enabled) {

  /** Returns true if this client handles the given function. */
  public boolean handles(final @NonNull String functionId) {
    return this.functions.isEmpty() || this.functions.contains(functionId);
  }

  /** Returns true if this client was provisioned without a client_functions attribute. */
  public boolean legacyUnscoped() {
    return this.functions.isEmpty();
  }
}
```

### 4.3. Client shape created by the app

Managed clients are OIDC relying parties / OAuth2 clients. The shape is identical to what
`add-oidc-client.sh` produces, so scripted and app-created clients are indistinguishable:

- `protocol=openid-connect`, `publicClient=false`, `enabled=true`
- `clientAuthenticatorType=client-jwt` (private_key_jwt, RFC 7523)
- `standardFlowEnabled=true`, `implicitFlowEnabled=false`, `directAccessGrantsEnabled=false`
- `authorizationServicesEnabled=true`
- `redirectUris` as supplied. `rootUrl` is never written: it is read to expand relative redirect
  URIs for display and otherwise left as the client has it, including having none
- Attributes per 4.1
- Protocol mappers: `org-rights-mapper`, `scope-org-identifier-mapper`,
  `resource-audience-mapper`
- Optional client scopes: `https://id.oidc.se/scope/naturalPersonNumber`, `phone`
- Service account: created by Keycloak, then deleted unless `serviceAccount` is requested
  (mirrors the script's `--service-account` flag; default `false`)

### 4.4. Validation

| Field | Rule |
|---|---|
| `clientId` | Required, non-blank, must not already exist in the realm |
| `redirectUris` | Required, at least one entry. Each must be an absolute URI. A `*` is accepted **only as the final character**, which is the form Keycloak matches; a `*` in the scheme, the host, the middle of the path or a query is rejected |
| `functions` | Optional. Each identifier must resolve via `fetchAllFunctions()`. An empty list means the client handles no functions — never all of them |
| JWKS | Exactly one of `jwksUri` / `jwksString`. `jwksUri` must be an absolute `https` URI; `jwksString` must parse as a JWK Set |

Validation failures are client errors (`400`) and are logged at `INFO` — they are handled
outcomes, not system failures.

### 4.5. Clients without `client_functions`

`client_functions` is the complete list: a client handles exactly what it declares, and a
client declaring nothing handles nothing. An empty attribute is **never** read as "every
function".

Functions are optional on create and on edit — a client may be registered before it is known
which functions it will serve, and is inert until they are assigned. The same applies to
clients provisioned outside the app (`add-oidc-client.sh` without a following
`set-client-functions.sh`). Such clients receive no artifacts; any they were given earlier
stay until a pruning run removes them. They are flagged in the list view as *unscoped*, and a
`WARN` is logged once per reconciliation run naming them.

This is a behaviour change for existing realms — see the upgrade note in the release
notes.

---

### 4.6. Client roles

A client plays one or both of two independent roles:

| | OIDC client | Resource server |
|---|---|---|
| Marker attribute | `iam_admin_managed=true` | `iam_admin_resource_server=true` |
| Keycloak shape | confidential, `client-jwt`, standard flow, authz services | none of its own |
| Required fields | redirect URIs, JWKS | — |
| Holds artifacts | Yes | No |
| Reconciled | Yes | No |
| Role of `client_functions` | Which functions it receives artifacts for | Which functions it accepts as a `resource` target |

Both roles are set on the same create/update request (`oidcClient`, `resourceServer`), and at
least one is required. Redirect URIs and JWKS are validated only when the OIDC client role is
on, and are cleared from the client when it is off.

The OIDC client role decides the client's Keycloak shape; the resource server role only adds
a marker attribute. A client with both therefore takes the OIDC client shape and carries both
markers — that is the dual-role case a service needs when it both answers requests and calls
another service onwards.

Two transitions have side effects worth knowing:

- Turning the OIDC client role **on** for an existing client adds the protocol mappers and
  base optional scopes it lacked, then reconciles it.
- Turning it **off** disables Authorization Services, which makes Keycloak discard that
  client's policies and permissions.

`resolveIamAdminManagedClients()` returns clients holding the OIDC client role — this is what
the reconciler consumes, so a resource-server-only client can never be picked up and have
authz scopes created on a client with Authorization Services disabled.
`resolveAdministeredClients()` returns both, and backs the list endpoint.

`add-resource-server.sh` sets the resource server marker, so scripted and
application-registered resource servers are indistinguishable. Resource servers registered
before the attribute existed are invisible to the application until it is set on them.

---

## 5. Reconciliation

New service `se.swedenconnect.iam.admin.service.ClientReconciliationService`.

### 5.1. Desired state

For a managed client `C`:

- Client-level settings and mappers per section 4.3.
- For each function `F` where `C.handles(F)`, for each organization `O` that has `F`
  attached, for each level in `read`, `write`, `admin`:
  - realm client scope `{O}:{F}:{level}` exists
  - Authorization Services scope `{O}:{F}:{level}` exists on `C`
  - group policy `policy-{O}-{F}-{level}` exists on `C`, referencing the qualifying groups
  - scope permission `permission-{O}:{F}:{level}` exists on `C`, bound to policy and scope
  - the realm client scope is registered as an **optional** client scope on `C`

Qualifying groups per level (unchanged from `attachFunctionToOrg`):

- `read`: `/orgs/{O}/_read`, `/_write`, `/_admin`, `/orgs/{O}/{F}/_read`, `/_write`, `/_admin`
- `write`: `/orgs/{O}/_write`, `/_admin`, `/orgs/{O}/{F}/_write`, `/_admin`
- `admin`: `/orgs/{O}/_admin`, `/orgs/{O}/{F}/_admin`

### 5.2. Actual state

Fetched in bulk, once per run, not per organization:

- `GET /client-scopes` (all realm scopes)
- per client: `GET /clients/{uuid}`, `/protocol-mappers/models`, `/optional-client-scopes`,
  `/authz/resource-server/scope`, `/authz/resource-server/policy`,
  `/authz/resource-server/permission`
- org/function topology from the existing group traversal (`orgs` → org → function)

### 5.3. Plan and apply

```java
static ReconciliationPlan plan(
    List<ManagedClientInfo> clients, Map<String, Set<String>> topology);
ReconciliationReport apply(ReconciliationPlan plan);
ReconciliationReport reconcileAll();
ReconciliationReport reconcileClient(String clientId);
```

- `plan()` is a pure function of the managed clients and the org/function topology — no
  Keycloak calls, directly unit testable. Whether an individual artifact already exists is
  settled in `apply()`, against a per-client `ClientArtifactState` snapshot.
- `apply()` creates everything missing, skipping what the snapshot already contains. A
  failure on one target is recorded in the report and the run continues, so one broken
  client does not block the others.
- Every run also removes artifacts for org/function combinations the client no longer
  handles, i.e. when `client_functions` is narrowed. Narrowing `client_functions` is
  therefore enough to have the artifacts taken away on the next run.
- `ReconciliationReport` carries per-client created / removed / unchanged counts and any
  per-artifact errors, and is returned by the API endpoints.

### 5.3.1. Artifact naming

Artifact names are produced by `KeycloakAdminClient.scopeName`, `policyName` and
`permissionName`, and are the contract between creation and removal:

| Artifact | Name |
|---|---|
| Realm client scope / authz scope | `{org}:{func}:{level}` |
| Group policy | `policy-{org}-{func}-{level}` |
| Scope permission | `permission-{org}-{func}-{level}` |

The dash form for policies and permissions is what `docs/keycloak-setup.md` documents and
what creation has always used. Removal previously looked for
`permission-{org}:{func}:{level}` (colons), which never matched, so scope permissions were
left behind on every detach and function deletion. Centralising the names fixes that; the
orphaned permissions in existing realms are cleaned up by a reconciliation run with
pruning enabled, or by re-attaching and detaching the function.

### 5.4. Extraction from `attachFunctionToOrg`

The per-client artifact block inside `attachFunctionToOrg`
(`KeycloakAdminClient.java` step 5) is extracted to

```java
void ensureFunctionArtifacts(String clientUuid, String orgIdentifier, String functionId);
```

made idempotent (existence check before each create), and called from both
`attachFunctionToOrg` and `ClientReconciliationService`. No duplicated artifact logic.

### 5.5. Function filtering

`resolveIamAdminManagedClientUuids()` is replaced by

```java
List<ManagedClientInfo> resolveIamAdminManagedClients();
```

which parses `client_functions` and paginates `GET /clients` (see section 12).
`attachFunctionToOrg`, `detachFunctionFromOrg` and `deleteFunction` filter with
`handles(functionId)` before touching a client.

### 5.6. Logging

| Event | Level |
|---|---|
| Reconciliation run made changes | `INFO`, with counts per client |
| Reconciliation run found nothing to do | `DEBUG` |
| Individual artifact created/removed | `DEBUG` |
| Single artifact failed, run continued | `WARN` |
| Reconciliation could not reach Keycloak | `ERROR` |
| Client create/update rejected by validation | `INFO` |

## 6. REST API

New `se.swedenconnect.iam.admin.controllers.ClientController`, base path `/api/clients`.
**All endpoints are superuser-only** — same gate as function creation. Non-superusers get
`403`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/clients` | List managed clients and resource servers, each with a `type` |
| `GET` | `/api/clients/{id}` | Single managed client |
| `POST` | `/api/clients` | Create client, then reconcile it |
| `PUT` | `/api/clients/{id}` | Update name / redirect URIs / functions / JWKS, then reconcile |
| `DELETE` | `/api/clients/{id}` | Hard-delete the client from Keycloak |
| `POST` | `/api/clients/{id}/reconcile` | Reconcile one client, return report |
| `POST` | `/api/clients/reconcile` | Reconcile all managed clients, return report |

`{id}` is the client's **Keycloak UUID**, not its client_id. A client_id is a URL, and a
URL-encoded one in a path segment (`https%3A%2F%2F…`) is rejected with `400` by Spring
Security's `StrictHttpFirewall` before the request reaches the controller — the encoded
slashes are what it objects to. The UUID is returned as `id` on every
`ManagedClientResponse`, so the caller never has to construct it.

`DELETE` performs a **hard delete** (`DELETE /clients/{uuid}`), which removes the client
and all its authz artifacts. It is open to any superuser. The realm-level client scopes are
shared between clients and are **not** deleted.

New DTOs in `controllers/dto`:

- `ManagedClientResponse(id, clientId, name, functions, redirectUris, jwksUri, jwksString, serviceAccount, enabled)`
- `CreateManagedClientRequest(clientId, name, redirectUris, functions, jwksUri, jwksString, serviceAccount, orgRightsIdToken, orgRightsAccessToken)`
- `UpdateManagedClientRequest(name, redirectUris, functions, jwksUri, jwksString)`
- `ReconciliationReportResponse(clients, created, removed, unchanged, errors)`

## 7. Scheduled Reconciliation

- `@EnableScheduling` on a new `ClientReconciliationScheduler`, annotated
  `@ConditionalOnProperty(prefix = "iam.admin.client-reconciliation", name = "enabled")`.
- `@Scheduled(cron = "${iam.admin.client-reconciliation.cron}")` runs `reconcileAll()`.
- **Disabled by default** — opt-in per deployment.
- Multi-instance: no leader election. Every operation is idempotent and `409`-tolerant, so
  concurrent runs on several instances converge to the same state. This is documented, not
  enforced with ShedLock (there is no database to lock against).

## 8. Frontend

New superuser-only **Clients** tab in `iam-admin-app/frontend`, following the existing
Functions tab pattern (`App.tsx`, rendered only when `sessionData.superuser`):

| File | Purpose |
|---|---|
| `src/services/clientService.ts` | API calls |
| `src/app/components/ClientList.tsx` | Table, per-row edit / delete / reconcile |
| `src/app/components/ClientForm.tsx` | Create/edit dialog |

Form fields: client id, display name, redirect URIs (repeatable, `*` rejected client-side
with an inline error mirroring the server rule), function multi-select populated from
`functionService` (required, at least one), and a JWKS mode toggle (URI vs inline JWK Set).

A global **Reconcile** action plus a per-row one; both surface the returned report.
Delete asks for confirmation and states that the client is permanently removed from
Keycloak. Swedish and English strings added for all new labels.

## 9. Configuration

```yaml
iam:
  admin:
    client-reconciliation:
      enabled: false                 # scheduled drift repair
      cron: "0 */15 * * * *"
```

## 10. Files to Create or Modify

### New

| File | Description |
|---|---|
| `.../keycloak/model/ManagedClientInfo.java` | Managed client record |
| `.../keycloak/model/ClientArtifactState.java` | Snapshot of the artifacts a client currently holds |
| `.../service/model/ReconciliationTarget.java` | One (client, org, function) combination |
| `.../service/model/ReconciliationPlan.java` | The ensure/remove work of a run |
| `.../service/model/ReconciliationReport.java` | Counts and per-target errors |
| `.../service/ClientReconciliationService.java` | Plan/apply engine |
| `.../service/ClientReconciliationScheduler.java` | Cron trigger |
| `.../controllers/ClientController.java` | REST API |
| `.../controllers/dto/ManagedClientResponse.java` | DTO |
| `.../controllers/dto/CreateManagedClientRequest.java` | DTO |
| `.../controllers/dto/UpdateManagedClientRequest.java` | DTO |
| `.../controllers/dto/ReconciliationReportResponse.java` | DTO |
| `frontend/src/services/clientService.ts` | Frontend service |
| `frontend/src/app/components/ClientList.tsx` | Client table |
| `frontend/src/app/components/ClientForm.tsx` | Create/edit dialog |

### Modified

| File | Change |
|---|---|
| `.../keycloak/KeycloakAdminClient.java` | `resolveIamAdminManagedClients()`, paginated client fetch, `createManagedClient`, `updateManagedClient`, `deleteClient`, `ensureFunctionArtifacts`, function filtering in `attachFunctionToOrg` / `detachFunctionFromOrg` / `deleteFunction` |
| `.../config/IamAdminProperties.java` | `clientReconciliation` block |
| `frontend/src/app/App.tsx` | Clients tab |
| `frontend/src/types.ts` | Client types |
| `docs/iam-admin-configuration.md` | Add the `client-reconciliation` block to the *IAM Admin Application Configuration* property table, and to the *Example Configuration* YAML |
| `docs/keycloak-setup.md` | Section 4.9 — `client_functions` semantics and the fact that a client is never implicitly scoped to every function. Sections 4.6/4.7 — note that managed clients can now be created and reconciled from the admin app instead of by script |
| `docs/iam-integration-guide.md` | Section 2.1 (*Keycloak Registration*) — admin-app registration as an alternative to `add-oidc-client.sh`, and the note that a client registered after functions were attached is repaired by reconciliation rather than `set-client-functions.sh` |
| `docs/rights-model.md` | The paragraph on `client_functions` (currently near the resource-server discussion) — align with the "never implicitly all functions" rule |
| `docs/release-notes.md` | Entry under the current unreleased version, in the existing prose style (what changed, why, what operators must do) |
| `keycloak/scripts/README.md` | Note on `set-client-functions.sh` that the catch-up it performs is also available via the reconcile endpoint; scripts remain for bootstrap and non-interactive provisioning. `compose/keycloak-scripts/README.md` is **not** updated — it is marked deprecated and states that the wrappers will not be updated going forward |
| `.claude/CLAUDE.md` | Configuration property block |

## 11. Testing

- `ClientReconciliationService.plan()` — unit tests over fixture state snapshots covering:
  nothing missing, missing scope, missing policy, missing permission, missing optional
  scope binding, client narrowed to a subset of functions.
- `ManagedClientInfo.handles()` — exact match, non-match, legacy empty set.
- Request validation — redirect URI wildcard accepted at the end and rejected elsewhere,
  relative redirect URI expanded against the root URL and rejected without one,
  both/neither JWKS form rejected,
  empty function list rejected, unknown function rejected, duplicate client id rejected.
- `ClientController` MockMvc tests following `UserRightsControllerAdminGateTest`:
  superuser gate (`403` for non-superusers on every endpoint), including on `DELETE`.

## 12. Risks

| Risk | Mitigation |
|---|---|
| `resolveIamAdminManagedClientUuids()` uses `GET /clients?max=500` — silently truncates past 500 clients | Paginate the client fetch while replacing the method |
| `iam-admin-sa` may lack `manage-clients` on `realm-management` | Verify at bootstrap; if absent, client CRUD fails with `403`. Log at `ERROR` on startup check |
| Client creation is multi-step and has no rollback | Creation ends with a reconcile of the same client, so a partial creation is repaired on the next reconcile rather than left broken |
| Hard delete is irreversible | Superuser-only, plus UI confirmation |

## 13. Out of Scope

- **Org-scoped client management** — client management is superuser-only. There is no
  per-org client ownership attribute and no org-admin delegation. Should delegation be
  wanted later, an org-ownership attribute must both gate visibility *and* narrow the
  reconciler's desired state, otherwise an org admin could create a client that reaches
  another organization's data.
- **Service-account org grants** — how a dual-role service obtains a downstream org-scoped
  token when no user session is present (placing the service-account user in
  `/orgs/{org}/{function}/_{right}`, or token exchange). The client shape supports the
  dual role; how it authenticates onwards is not decided here.
- Rotation or validation of client keys beyond the JWKS reference/inline value.
- Changes to the `org_rights` claim, `OrgRightsScopeConverter`, or any Keycloak SPI plugin.
- Removing `add-oidc-client.sh` / `set-client-functions.sh` — both remain for bootstrap and
  scripted provisioning.

## 14. Implementation Order

1. `ManagedClientInfo` + `resolveIamAdminManagedClients()` + `handles()` filtering in
   `attachFunctionToOrg`, `detachFunctionFromOrg`, `deleteFunction`. Paginate client fetch.
   *(Standalone; completes the unimplemented Java half of `client-functions-design.md`.)*
2. Extract `ensureFunctionArtifacts`, build `ClientReconciliationService` (plan/apply),
   route `attachFunctionToOrg` through it.
3. `ClientController` + DTOs + validation + client create/update/delete in
   `KeycloakAdminClient`.
4. `ClientReconciliationScheduler` + configuration properties.
5. Frontend Clients tab.
6. Documentation and tests.

Documentation is part of the definition of done, not a follow-up. It follows the
conventions already used in `docs/`: numbered sections with `<a name="...">` anchors and a
matching table of contents entry, settings presented as tables, YAML and shell examples
that are runnable as written, and the same voice as the surrounding text. New material is
added to the existing documents listed in section 10 — no new top-level document, so
`docs/index.md` is unchanged. API endpoints are additionally covered by the springdoc
annotations on `ClientController`, consistent with the other controllers.
