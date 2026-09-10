![Sweden Connect](images/sweden-connect.png)

# Organizations and Users IAM - Release Notes

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) ![Maven Central](https://img.shields.io/maven-central/v/se.swedenconnect.iam/iam-parent.svg)

### Version 0.9.3

**Date:** 

- **Installing the Keycloak providers is now a single step.** The provider JARs are published
  as one distribution archive, and the Keycloak scripts fetch and install it for you. See
  [Keycloak Plugins and Scripts](https://github.com/swedenconnect/organizations-iam-app/blob/main/keycloak/README.md)
  and the [Keycloak Scripts README](https://github.com/swedenconnect/organizations-iam-app/blob/main/keycloak/scripts/README.md).

- **User registration is now configurable.** The new `iam.admin.user-registration` settings
  control which identity fields the Create user dialogue offers and what is written to
  Keycloak. See [Configuration](iam-admin-configuration.md).

- **`iam_admin_managed` now means what its name says.** In Keycloak everything registered is a
  client, whichever role it plays, so the attribute was misnamed: it carried the *OIDC client
  role* rather than "administered by the IAM admin application", and a resource server the
  application had itself created did not carry it. The roles are now separate:

  | Attribute | Meaning |
  |---|---|
  | `iam_admin_managed=true` | The application administers this client, in either role |
  | `iam_admin_oidc_client=true` | It plays the OIDC client role, and is reconciled |
  | `iam_admin_resource_server=true` | It plays the resource server role |

  **Existing realms need no migration.** Where `iam_admin_oidc_client` is absent, the application
  reads `iam_admin_managed` with its old meaning, so clients registered before this release keep
  the roles they had, dual-role clients included. Each client is migrated the next time it is
  written, from the **Services** tab, the API, or by re-running its registration script.

  `add-oidc-client.sh` and `set-iam-admin-managed.sh` now set `iam_admin_oidc_client=true`
  alongside `iam_admin_managed=true`. `add-resource-server.sh` sets `iam_admin_managed=true` and
  `iam_admin_oidc_client=false`. The kcadm variant of `add-resource-server.sh` additionally sets
  `iam_admin_resource_server=true`, which it never did, so resource servers registered through
  the Docker Compose wrapper were invisible to the application.

- **Registering a resource-server-only client from the application no longer fails.** Saving a
  client with the resource server role alone answered `500` with
  *"Client '...' is not managed after creation"*. The client was written to Keycloak correctly;
  the read-back at the end of the create resolved only the OIDC clients, and the resource server
  role does not set `iam_admin_managed`, so the newly created client was never among them. The
  same fault hit an update that turned the OIDC client role off. The lookup now covers both roles,
  as the by-UUID lookup already did.

  A client created before this fix is present in Keycloak despite the error, which is why a retry
  answered `409`. It needs no repair: open it from the **Services** tab and save, or delete it in
  the Keycloak admin console and register it again.

- **The IAM admin application is registered as a resource server for every function.**
  The application is not only an OIDC client: it exposes `/iam-api`, and other clients name it in
  the OAuth2 `resource` parameter. Registering it with `add-oidc-client.sh` left it with no
  `client_functions` at all, so it received no scopes, policies or permissions from
  reconciliation, and it was not marked as a resource server. Token requests naming it as their
  resource only worked because `resource-aud-plugin` treats a blank attribute as function-universal.

  A new script, `add-iam-admin-app.sh`, registers it with all three roles it actually plays:
  `iam_admin_oidc_client=true`, `iam_admin_resource_server=true` and
  `iam_admin_all_functions=true`. It delegates the OIDC client registration to
  `add-oidc-client.sh`, always keeps the service account with its `realm-management` roles, and
  seeds `client_functions` from every function that exists.

  The all-functions marker covers functions that do not exist yet, which no fixed list can
  express. A client carrying it is never *unscoped*, receives artifacts for every organization
  and function attached to it, and never has any pruned. Creating a function now appends it to
  every marked client. The **Services** tab shows such a client with an **All functions** pill
  and a disabled function picker.

  **Upgrading:** re-run `add-iam-admin-app.sh` against an existing admin application client. It is
  idempotent, and it adds the two missing markers and seeds the attribute from the functions
  already in the realm.

- **`add-function.sh` adds a function to an already registered service.** Where
  `set-client-functions.sh` replaces the whole `client_functions` list, the new script appends to
  it and keeps the functions the client already declares. It refuses a function that does not
  exist in the realm rather than writing a value that would never match anything, and it reports
  and skips a client marked `iam_admin_all_functions=true`. An OIDC client needs
  `POST /api/clients/reconcile` afterwards to receive the artifacts for the new function; a
  resource server holds none and needs nothing further.

- **An admin can no longer create further admins.** The new
  `iam.admin.allow-admin-assigning-admin` setting, which defaults to `false`, restricts a caller
  who is not a superuser to the `read` and `write` rights. Such a caller can neither grant nor
  remove the `admin` right, nor lower an existing admin to a lesser right, at the organization
  level or at the organization/function level.

  The intended consequence is that the admin population of an organization or a function does not
  change without a superuser. Combined with the existing rule that the last admin of a scope
  cannot be removed, an organization whose only admin is not a superuser keeps exactly that one
  admin until a superuser intervenes.

- **An organization now carries a legal name, and the two existing names become optional display
  names.** The legal name is the registered name. It is mandatory and is what
  identifies the organization. The Swedish and English names become optional display names, used for
  presentation only; the legal name is shown where none is set. See
  [Rights Model](rights-model.md) for the group attributes and the claim.

  **Affects integrations.** Each `org_rights` organization entry gains `organization_legal_name`,
  which is what to read for the registered name, and `OrgRightsClaim` gains a matching `legalName`.
  `GET /iam-api/v1/organizations` gains `legal_name`; `name#sv` and `name#en` become the display
  names, with the legal name substituted into `name#sv` when no Swedish display name is set so that
  existing callers keep working.

  **Upgrade action required.** No migration is run. An organization created before this release has
  no legal name; one is derived for display and the derivation is logged, but the registered name
  has to be entered by hand in the admin application for each such organization.

- **Org-scoped scopes are now checked against the user's rights at token issuance.** The
  `resource-function-executor` Client Policy executor in the `resource-aud-plugin` rejects a token
  request with `invalid_scope` if the user is not entitled to a requested scope of the form
  `{org}:{function}:{right}`. Previously any authenticated user of a managed client could obtain any
  organization's scope. Entitlement is read from live group memberships, so a right revoked after
  login takes effect on the next token request. Scopes that are not org-scoped and service account
  tokens are unaffected, and a refresh token keeps the scopes it was issued with.

  **Deployment.** Deploy the new `resource-aud-plugin` JAR; a `start --optimized` installation needs
  an explicit `kc.sh build`. The check only runs where the Client Policy profile containing
  `resource-function-executor` is present, so verify the profile after upgrading Keycloak or
  restoring a realm.

- **Managed clients can be administered from the IAM admin application.** A superuser can register,
  edit and delete OIDC clients under a new **Services** tab, instead of running
  `add-oidc-client.sh` and `set-iam-admin-managed.sh` against the Keycloak host. A client is
  registered with the same settings the script applies.

- **Clients and resource servers are administered in one place, and a client can be both.** A client
  carries two independent roles, set with toggles when it is registered: *OIDC client*, which logs
  users in and requests org-scoped tokens, and *resource server*, which may be named as an OAuth2
  `resource` target. Both may be set on one client.

  **Upgrade action required.** The resource server role is marked with the new
  `iam_admin_resource_server=true` attribute, which `add-resource-server.sh` now sets. Resource
  servers registered before this release need the attribute set once before they appear in the
  application.

- **Client artifacts are reconciled instead of only created on attach.** A managed client is brought
  in line with the org/function topology whenever it is created or updated, when a function is
  attached or detached, on demand from the Services tab, and, when
  `iam.admin.client-reconciliation.enabled` is set, on a schedule. This repairs clients registered
  after functions were already attached, as well as drift from partial failures and manual edits in
  the Keycloak admin console. Reconciliation only creates by default; removing the artifacts of
  functions a client no longer handles requires opting in to pruning.

- **`client_functions` now scopes which functions a client receives artifacts for.** The attribute
  is the complete list of functions a client handles, and an empty or absent attribute means **no
  functions, not all of them**. Functions are optional when registering a client; one registered
  without them is inert until functions are assigned.

  **Upgrade action required.** A managed client carrying no `client_functions` attribute, which
  includes every client registered with `add-oidc-client.sh` before this release, stops receiving
  artifacts for newly attached functions. Existing artifacts are left in place, so nothing breaks
  immediately. Assign functions to such clients from the admin application or with
  `set-client-functions.sh`. Unscoped clients are flagged in the application and named in a warning
  on every reconciliation run.

- **The `iam.admin.authz-client-ids` setting has been removed.** The attribute
  `iam_admin_oidc_client=true` is now the only thing that makes a Keycloak client managed. The setting
  was a fallback from before clients could be registered from the admin application, and a client
  listed in it was managed without anything in Keycloak saying so. A deployment that still sets the
  property starts as before, and the property has no effect.

- **Fixed: scope permissions were never removed when a function was detached or deleted.** Every
  function detach and function deletion left its scope permissions behind in Keycloak. Permissions
  orphaned by earlier releases are removed by a reconciliation run with pruning enabled.

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
