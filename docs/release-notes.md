![Sweden Connect](images/sweden-connect.png)

# Organizations and Users IAM - Release Notes

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) ![Maven Central](https://img.shields.io/maven-central/v/se.swedenconnect.iam/iam-parent.svg)

### Version 0.9.3

**Date:** 

- **Org-scoped scopes are now checked against the user's rights at token issuance.**
  The `resource-function-executor` Client Policy executor in the `resource-aud-plugin` rejects a
  token request with `invalid_scope` if the user is not entitled to a requested scope of the form
  `{org}:{function}:{right}`.

  This closes a gap in how Keycloak treats optional client scopes: it grants one to whoever asks
  for it, and it never evaluates the Authorization Services scope permissions during standard
  token issuance. Those are only consulted by the `uma-ticket` grant and the policy evaluation
  API. The group policies created alongside each scope were therefore not enforcing anything at
  token time, and any authenticated user of a managed client could obtain any organisation's
  scope.

  A scope is granted if the user holds the `superuser` realm role, or is a member of at least one
  qualifying group under `/orgs/{org}`. A higher right qualifies for a lower one, and an
  organisation-wide group qualifies for every function of that organisation:

  | Requested right | Qualifying groups |
  |-----------------|-------------------|
  | `read` | `/orgs/{org}/_read`, `/orgs/{org}/_write`, `/orgs/{org}/_admin`, `/orgs/{org}/{function}/_read`, `/orgs/{org}/{function}/_write`, `/orgs/{org}/{function}/_admin` |
  | `write` | `/orgs/{org}/_write`, `/orgs/{org}/_admin`, `/orgs/{org}/{function}/_write`, `/orgs/{org}/{function}/_admin` |
  | `admin` | `/orgs/{org}/_admin`, `/orgs/{org}/{function}/_admin` |

  This is the same rule the admin application uses when it builds the group policies for a newly
  attached function, so no rights that were correct before are lost.

  Entitlement is read from live group memberships rather than from a claim, so a right revoked
  after login takes effect on the next token request. Scopes that are not org-scoped are
  untouched. Service account tokens (`client_credentials`) are exempt, since they are issued to the
  client, not to a user. A refresh token keeps the scopes it was issued with until it expires.

  **Deployment:** deploy the new `resource-aud-plugin` JAR as usual; a `start --optimized`
  installation needs an explicit `kc.sh build`. No realm configuration changes are required, but
  the check only runs where the Client Policy profile containing `resource-function-executor` is
  present. A realm missing it performs no entitlement check at all. Verify the profile after
  upgrading Keycloak or restoring a realm.

- **Managed clients can be administered from the IAM admin application.** A superuser can
  register, edit and delete OIDC clients under a new **Services** tab, instead of running
  `add-oidc-client.sh` and `set-iam-admin-managed.sh` against the Keycloak host. A client is
  registered with the same settings the script applies: `private_key_jwt` authentication,
  Authorization Services, the three protocol mappers, and the `naturalPersonNumber` and
  `phone` optional scopes. Redirect URIs must be exact, wildcards are rejected. Deletion is
  permanent, and is open to any superuser.

- **Clients and resource servers are administered in one place, and a client can be both.**
  The **Services** tab lists everything the application administers. A client carries two
  independent roles, set with toggles when it is registered: *OIDC client* (logs users in and
  requests org-scoped tokens) and *resource server* (may be named as an OAuth2 `resource`
  target and appears in `aud`). Redirect URIs and client keys are asked for only when the OIDC
  client role is on. A client with both roles is the shape for a service that answers requests
  and calls another service onwards. The resource server role is marked with the new
  `iam_admin_resource_server=true` attribute, which `add-resource-server.sh` now sets as well;
  resource servers registered before this release need the attribute set once before they
  appear in the application.

- **Client artifacts are reconciled instead of only created on attach.** A managed client is
  now brought in line with the org/function topology whenever it is created or updated, when
  a function is attached to or detached from an organization, on demand from the Clients tab,
  and, when `iam.admin.client-reconciliation.enabled` is set, on a schedule. This repairs
  the case a client registered *after* functions were already attached to organizations,
  which previously left the client without any of the scopes and policies its users need, as
  well as drift from partial failures and manual edits in the Keycloak admin console.
  Reconciliation only creates by default; removing the artifacts of functions a client no
  longer handles requires opting in to pruning.

- **`client_functions` now scopes which functions a client receives artifacts for.** The
  attribute was previously honoured only by the `resource-aud-plugin` at token issuance; the
  admin application gave every managed client artifacts for every function. The attribute is
  now the complete list of functions a client handles, and an empty or absent attribute means
  **no functions, not all of them**. Functions are optional when registering a client; one
  registered without them is inert until functions are assigned.

  **Upgrade action required.** A managed client that carries no `client_functions` attribute,
  which includes every client registered with `add-oidc-client.sh` before this release, stops
  receiving artifacts for newly attached functions. Its existing scopes, policies and
  permissions are left in place, so nothing breaks immediately, but the client will not pick up
  functions attached from now on. Assign functions to such clients from the admin application,
  or with `set-client-functions.sh`. Unscoped clients are flagged in the application and named
  in a warning on every reconciliation run.

- **Fixed: scope permissions were never removed when a function was detached or deleted.**
  Permissions are created as `permission-{org}-{function}-{right}`, but the cleanup paths
  looked for `permission-{org}:{function}:{right}`. The lookup never matched, so every
  function detach and function deletion left its scope permissions behind in Keycloak. Both
  paths now derive the name from the same place. Permissions orphaned by earlier releases are
  removed by a reconciliation run with pruning enabled.

---

### Version 0.9.2

**Date:** 2026-08-28

- **Organisation-level rights are now expanded per function in the `org_rights` claim.**
  Previously a right granted at the organisation level was emitted as the wildcard
  `{"function": "*", "right": "..."}`. The wildcard was documented to mean "all functions
  currently attached to the organisation", but the claim never carried the attachment set, so
  consumers could not evaluate that and treated it as "all functions". The `org-rights-mapper`
  Keycloak plugin now emits one entry per attached function instead, and records the
  organisation-level grant in a new `org_level_right` field. The wildcard is gone.

  `org_level_right` is **provenance only**: it says *how* a right was granted, never *what* the
  user may do. Effective rights come exclusively from the `functions` array. Its one legitimate
  use is deciding whether someone may administer the organisation itself, such as granting or
  revoking organisation-level rights or editing the organisation record.

  **Behaviour change:** organisation-level `admin` on an organisation with no attached functions
  previously granted access to every function; it now grants none. More generally, a
  function-scoped relying party no longer accepts an organisation-level right for a function that
  was never attached to that organisation. Rights that were correct before remain unchanged. An
  organisation-level right still covers every function actually attached.

  The OAuth scope path is unaffected: Keycloak already enforced attachment there, since the
  `{org}:{function}:{right}` client scopes are created on attach and deleted on detach.

  No Keycloak configuration changes are required. The realm, group attributes, client scopes and
  protocol-mapper instances are all unchanged. Deploy the new `org-rights-mapper` JAR as usual;
  a `start --optimized` installation needs an explicit `kc.sh build`.

- **New `OrgRightsClaim.organizations()` helper in `iam-security-base`.** Listing the
  organisations a user is associated with is a different question from deciding what the user may
  do, and it needs a different source. Granted authorities are derived per function, so an
  organisation with no attached functions produces no authority and is invisible to a consumer
  that enumerates authorities, even when the user administers it. `organizations()` enumerates
  the claim entries instead, returning the organisation identifier, localised name and the
  nullable organisation-level right. Authority construction is unchanged. For a superuser the
  claim carries no organisation entries, so the method returns an empty list and the full list
  must be fetched from `/iam-api/v1/organizations`.

---

### Version 0.9.1

**Date:** 2026-06-12

- The admin application can now be deployed under a custom context path
  (e.g. `/iam-admin/`) in addition to the root path, without requiring a
  separate build.
- New API endpoint for listing the rights holders of a given function within
  an organisation. See the API documentation for details.
- When a user's session expires while using the application, API calls now
  detect the expired session and redirect to the login page with a clear
  "session expired" message, instead of silently failing or showing an
  unexpected error.
- Fixed a bug where removing a user's right at the organisation level failed
  with an unexpected error. The delete operation was silently dropping the
  required `right` query parameter before sending the request to the backend.
- Implemented pagination for organizations, system can handle more that 10 organizations.
  Loading of users and organizations is now done in smaller sections. There is a backend implemented
  cache that handles search on organization names. When a write is made it is invalidated, and reloaded. 
- Added debug statements to org-rights mapper Keycloak plugin. Fixed some ordering issues. Replace N calls to 
  getSubGroupsStream() with a single lookup map (one Keycloak query regardless of how many orgs the user belongs to)

---

### Version 0.9.0

**Date:** 2026-04-09

- First release

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
