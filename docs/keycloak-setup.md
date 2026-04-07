![Sweden Connect](images/sweden-connect.png)

# Keycloak Setup

For an overview of the rights model and key concepts, see
[Organization and Function-Based Rights Model](rights-model.md).

**Realm:** `orgiam`
**Keycloak Version:** 26.5.7

---

## Table of Contents

1. [**Automated Setup via Scripts**](#automated-setup-via-scripts)

2. [**How to Set Up Keycloak**](#how-to-set-up-keycloak)

    2.1. [Realm](#realm)

    2.2. [Top-Level Groups](#top-level-groups)

    2.3. [Realm Roles](#realm-roles)

    2.4. [The `personalIdentityNumber` Claim and Scope](#the-personalidentitynumber-claim-and-scope)

    2.4b. [The `phone_number` Claim and Scope](#the-phone_number-claim-and-scope)

    2.5. [The `org_rights` Protocol Mapper](#the-org_rights-protocol-mapper)

    2.6. [The Admin Application Client](#the-admin-application-client)

    2.7. [Example OIDC Client Registration](#example-oidc-client-registration)

    2.8. [Example Resource Server Registration](#example-resource-server-registration)

    2.8b. [The Resource Audience Mapper and Client Policy](#the-resource-audience-mapper-and-client-policy)

    2.9. [Scope Creation and Authorization Policies](#scope-creation-and-authorization-policies)

- [**Appendix A: Step-by-Step Setup with Examples**](#appendix-a-step-by-step-setup-with-examples)

- [**Appendix B: Keycloak Admin REST API Reference**](#appendix-b-keycloak-admin-rest-api-reference)

---

<a name="automated-setup-via-scripts"></a>
## 1. Automated Setup via Scripts

The `compose/keycloak-scripts/` directory contains wrapper scripts that automate the steps described in this document. See the
[Keycloak Scripts README](../compose/keycloak-scripts/README.md) for usage.

Key scripts:

- `bootstrap-realm.sh` — Creates the realm with all base configuration (sections 2.1–2.5 below)
- `create-admin-user.sh` — Creates the initial superuser account
- `add-oidc-client.sh` — Registers an OIDC/OAuth client
- `add-resource-server.sh` — Registers a resource server
- `set-client-functions.sh` — Assigns function identifiers to a resource server
- `set-iam-admin-managed.sh` — Marks a client as IAM-admin-managed
- `install-keycloak-plugins.sh` — Builds and installs Keycloak provider JARs

---

<a name="how-to-set-up-keycloak"></a>
## 2. How to Set Up Keycloak

Log in to the Keycloak Admin Console as a master realm administrator.

> For the Docker test environment the admin username is `admin` and the password is `keycloak`.

<a name="realm"></a>
### 2.1. Realm

Create a realm named `orgiam`. In the realm settings:

- **User registration:** disabled
- **Email as username:** disabled
- **Login with email:** disabled (unless required)

Usernames are assigned automatically by Keycloak as UUIDs. No username is collected from or
exposed to the user.

See [A.1, Create the Realm](#a1-create-the-realm) for details.

<a name="top-level-groups"></a>
### 2.2. Top-Level Groups

Create two root groups in the realm:

| Group name | Purpose |
|---|---|
| `orgs` | Parent for all organization groups |
| `functions` | Parent for all canonical function definitions |

These groups are never deleted. All organizations and functions are children of these two
groups respectively.

<a name="realm-roles"></a>
### 2.3. Realm Roles

Create a single realm-level role:

| Role | Description |
|---|---|
| `superuser` | Grants full access to all organizations, functions, and users in the admin application. Assigned directly to a user at the realm level, not via group membership. |

No other realm roles are used. All other authorization is derived from group membership.

<a name="the-personalidentitynumber-claim-and-scope"></a>
### 2.4. The `personalIdentityNumber` Claim and Scope

The personal identity number is emitted by the **OIDC Sweden** protocol mapper
(`swedish-oidc-claims-mapper`), a custom mapper JAR deployed to Keycloak. The mapper is
scope-driven: it checks which client scopes were applied to the session and emits claims
accordingly. For the personal identity number, it checks for the presence of the scope
`https://id.oidc.se/scope/naturalPersonNumber` among the applied scopes.

The `personalIdentityNumber` user profile attribute is defined under
**Realm settings → User Profile** with:

- **Enabled when:** Scopes are requested — `https://id.oidc.se/scope/naturalPersonNumber`
- **Permissions:** Admins can edit; admins and users can view.

**Client Scope:**

Create a Client Scope with the following settings:

| Setting | Value |
|---|---|
| Name | `https://id.oidc.se/scope/naturalPersonNumber` |
| Protocol | `openid-connect` |
| Include in token scope | `true` |

Add the **OIDC Sweden** mapper to this client scope:

| Setting | Value |
|---|---|
| Mapper type | `OIDC Sweden` |
| Name | `swedish-oidc-claims-mapper` |
| Add to ID token | `ON` |
| Add to access token | `ON` |
| Add to userinfo | `ON` |

**How to attach the scope to clients:**

The mapper fires only when the `naturalPersonNumber` scope is among the *applied* client
scopes for the session. This has different implications depending on the flow:

- **OIDC clients** (ID token issued): Add the scope as a **default scope** so it is always
  applied, or as an **optional scope** if it should only be included when the client
  explicitly requests it.
- **OAuth 2.0 clients** (access token only, no `openid`): Because no `openid` scope is
  requested, optional scopes are not evaluated from the authorization request in the same
  way. To ensure the personal identity number is always present in access tokens, add the
  `naturalPersonNumber` scope as a **default scope** on the OAuth client. This guarantees
  the scope is always applied and the mapper always fires, regardless of what API scopes
  the client requested.

See Sections 2.6 and 2.7 for the per-client configuration, and Appendix A for step-by-step
instructions.

<a name="the-phone_number-claim-and-scope"></a>
### 2.4b. The `phone_number` Claim and Scope

The phone number is an optional user attribute delivered via the standard OIDC `phone` scope.
It is stored as a custom user profile attribute `phoneNumber` and emitted as the `phone_number`
claim when the `phone` scope is requested.

The `phoneNumber` user profile attribute is defined under
**Realm settings → User Profile** with:

- **Enabled when:** Scopes are requested — `phone`
- **Permissions:** Admins can edit; admins and users can view.
- **Required:** No (optional attribute).

**Client Scope:**

Keycloak provides a built-in `phone` client scope in every realm. If it exists, configure it
as follows. If it has been removed, re-create it:

| Setting | Value |
|---|---|
| Name | `phone` |
| Protocol | `openid-connect` |
| Include in token scope | `true` |

Ensure the scope contains a mapper that emits the `phone_number` claim. The built-in scope
already includes a **User Attribute** mapper configured as:

| Setting | Value |
|---|---|
| Mapper type | `User Attribute` |
| Name | `phone number` |
| User Attribute | `phoneNumber` |
| Token Claim Name | `phone_number` |
| Add to ID token | `ON` |
| Add to access token | `ON` |
| Add to userinfo | `ON` |

If the mapper is missing or misconfigured, create or correct it with the settings above.

**How to attach the scope to clients:**

Add the `phone` scope as an **optional** client scope on OIDC/OAuth clients. As an optional
scope, the `phone_number` claim is only included in tokens when the client explicitly requests
the `phone` scope. If a user has no phone number set, the claim is simply absent from the token.

See [A.5e](#a5e-add-phone-scope-to-clients) for step-by-step instructions.

---

<a name="the-org_rights-protocol-mapper"></a>
### 2.5. The `org_rights` Protocol Mapper

The `org_rights` claim is produced by a custom protocol mapper. This mapper must be implemented
as a deployable JAR (implementing `OIDCProtocolMapper`) and deployed to Keycloak's providers
directory, or configured as a Script Mapper if scripting is enabled.

**Mapper logic:**

* Check if the user has the `superuser` realm role. If so, emit:
   ```json
   [{ "superuser": true }]
   ```
   and stop.

* Enumerate all groups the authenticated user belongs to.

* Group all relevant memberships by organization identifier. For each organization:

  - Load the org group's attributes to obtain `organization_identifier`, `organization_name#sv`,
      `organization_name#en`.
  - For each membership at path `orgs/{identifier}/_admin`, `/_write`, or `/_read`, add
      a `{ "function": "*", "right": "<right>" }` entry to the `functions` array for this org.
  - For each membership at path `orgs/{identifier}/{function}/_admin`, `/_write`, or
      `/_read`, add a `{ "function": "<function>", "right": "<right>" }` entry.
  - Emit one record per organization containing all collected function entries.

* A user may have both an org-level (`*`) entry and one or more function-level entries for
   the same organization. Both are emitted — consumers take the highest right across all
   matching entries when evaluating access to a specific function.

**Mapper configuration per client:**

| Client | Add to ID token | Add to access token |
|---|---|---|
| `https://local.dev.swedenconnect.se:17005` (IAM Admin App) | Yes | Yes |
| `https://local.dev.swedenconnect.se:16990` (Demo App) | Yes | No |

<a name="the-admin-application-client"></a>
### 2.6. The Admin Application Client (`https://local.dev.swedenconnect.se:17005`)

The IAM Admin App serves two roles: it authenticates users via OIDC (receiving `org_rights`
in the ID token), and it uses a service account for Keycloak administration tasks not tied
to a specific user session.

Create a client with the following settings:

| Setting | Value |
|---|---|
| Client ID | `https://local.dev.swedenconnect.se:17005` |
| Protocol | `openid-connect` |
| Client authentication | ON (confidential) |
| Client authenticator | `Signed Jwt` (`private_key_jwt`) |
| Standard flow | Enabled |
| Service accounts | Enabled |
| All other flows | Disabled |
| `iam_admin_managed` attribute | `true` |

**Client authentication — `private_key_jwt`:**

The client authenticates to Keycloak's token endpoint using a signed JWT assertion. The
application holds a private key; Keycloak fetches the corresponding public key from the
application's JWKS endpoint.

In the Keycloak Admin Console:

1. Go to the client → **Credentials** tab → set **Client Authenticator** to `Signed Jwt` →
   Save.
2. Go to the client → **Keys** tab → enable **Use JWKS URL** → set **JWKS URL** to
   `https://<host>/jwks` → Save.

Keycloak will fetch the public key from the JWKS URL on first use and cache it. The
application must be running and the `/jwks` endpoint reachable before the first token
request is made.

**Service account roles** (for background Keycloak admin operations):

Assign the following roles from the `realm-management` client to the service account:

- `manage-users`
- `query-groups`
- `view-users`
- `query-users`
- `manage-realm`
- `view-clients`
- `manage-clients`

**Protocol mappers on this client:**

- `org_rights` mapper — ID token and access token
- `personalIdentityNumber` claim — via the `https://id.oidc.se/scope/naturalPersonNumber`
  optional client scope (included when the client requests the scope)
- `organization_identifier` claim — via a dedicated Script or User Attribute mapper that
  extracts the org identifier from the granted `{org}:{function}:{right}` scope; present in
  access tokens only
- `resource-audience-mapper` — sets the `aud` claim based on the `resource` parameter and
  the function from the granted scope; present in access tokens only

**Scopes:**

- Add `https://id.oidc.se/scope/naturalPersonNumber` as an **optional** scope.
- Add `phone` as an **optional** scope.
- Add `{org}:{function}:{right}` scopes as **optional** scopes as they are created.

**Authorization behavior:**

The admin application uses the `org_rights` claim from the ID token to determine what
organizations the user may act on. The service account is used only for background tasks
(e.g. provisioning organizations and functions) that do not require a user session.

- Authorization Services must be enabled to enforce entitlement at scope-grant time.

<a name="example-oidc-client-registration"></a>
### 2.7. Example OIDC Client Registration

This section shows how to register a generic OIDC/OAuth client. The Demo App
(`https://local.dev.swedenconnect.se:16990`) is used as a concrete example.

Create a client with the following settings:

| Setting | Value |
|---|---|
| Client ID | `https://local.dev.swedenconnect.se:16990` (or your application's base URL) |
| Protocol | `openid-connect` |
| Client authentication | ON (confidential) |
| Client authenticator | `Signed Jwt` (`private_key_jwt`) |
| Standard flow | Enabled |
| All other flows | Disabled |
| `iam_admin_managed` attribute | `true` (if managed by the IAM admin app) |

**Client authentication — `private_key_jwt`:**

As with the Admin Application client, this client uses `private_key_jwt`. Configure the
Keycloak client identically: set **Client Authenticator** to `Signed Jwt` on the
Credentials tab, then set the **JWKS URL** on the Keys tab to the application's
`/jwks` endpoint.

**Protocol mappers on this client:**

- `org_rights` mapper — ID token only (not access token)
- `personalIdentityNumber` claim — via the `https://id.oidc.se/scope/naturalPersonNumber`
  optional client scope
- `organization_identifier` claim — access token only, extracted from the granted scope
- `resource-audience-mapper` — access token only, sets `aud` to
  `[resource_server_client_id, function]`

**Scopes:**

- Add `https://id.oidc.se/scope/naturalPersonNumber` as an **optional** scope.
- Add `phone` as an **optional** scope.
- Add `{org}:{function}:{right}` scopes as **optional** scopes as they are created.

Use `add-oidc-client.sh` from `compose/keycloak-scripts/` to automate registration. See the
[Keycloak Scripts README](../compose/keycloak-scripts/README.md) for details.

<a name="example-resource-server-registration"></a>
### 2.8. Example Resource Server Registration

This section shows how to register a generic OAuth resource server. The Demo Service
(`https://local.dev.swedenconnect.se:16995`) is used as a concrete example.

A resource server is registered in Keycloak as a client so that access tokens can carry it
as the `aud` claim via the `resource` parameter, but it has no flows, no service account,
and no Authorization Services.

Create a client with the following settings:

| Setting | Value |
|---|---|
| Client ID | `https://local.dev.swedenconnect.se:16995` (or your service's base URL) |
| Protocol | `openid-connect` |
| Client authentication | OFF |
| Standard flow | Disabled |
| All other flows | Disabled |
| Service accounts | Disabled |
| Authorization | Disabled |

**The `client_functions` attribute:**

If the resource server is scoped to specific functions, set the `client_functions` attribute
on the client. This attribute is a comma-separated list of function identifiers (e.g.,
`demo` or `demo,sweden-connect`). When the resource-aud plugin is deployed, it validates
at token issuance time that the function extracted from the requested scope matches the
`client_functions` attribute. If the attribute is absent or empty, the resource server is
treated as function-universal and accepts all functions.

Set the attribute using `add-resource-server.sh` with the `--functions` flag, or via
`set-client-functions.sh` after registration.

No protocol mappers, no client scopes, and no service account roles are needed. The service
validates incoming Bearer tokens by verifying the signature against Keycloak's JWKS endpoint,
checking the `aud` claim (a multi-valued array — see the [Rights Model](rights-model.md#oauth-resource-servers)),
and inspecting the `scope` and `organization_identifier` claims itself.

<a name="the-resource-audience-mapper-and-client-policy"></a>
### 2.8b. The Resource Audience Mapper and Client Policy

The `resource-aud-plugin` provides two Keycloak components that work together to handle the
OAuth 2.0 `resource` parameter (RFC 8707):

**Resource Audience Mapper** — a protocol mapper added to each OAuth client that calls
resource servers. It reads the `resource` parameter from the token request (or from an auth
session note if the parameter was provided on the authorization request), extracts the
function identifier from the granted scope, and sets the `aud` claim to a multi-valued
array: `[resource_server_client_id, function]`. If no `resource` parameter is present,
`aud` is set to `[function]`. If no org-scoped scope is present, the mapper does nothing.

The mapper is added automatically by `add-oidc-client.sh` to every registered OIDC/OAuth
client. It is configured with **Add to access token: ON** and **Add to ID token: OFF**.

**Resource Function Executor** — a Client Policy Executor that validates the `resource`
parameter against the target resource server's `client_functions` attribute. If the
resource server does not support the function extracted from the requested scope, the
request is rejected with an `invalid_target` error (RFC 8707).

The executor is activated via a Client Policy profile and policy, which are created
automatically by `bootstrap-realm.sh`. The policy applies to all confidential clients in
the realm.

**Client Policy configuration (created by `bootstrap-realm.sh`):**

| Component | Name | Description |
|---|---|---|
| Profile | `resource-function-profile` | Contains the `resource-function-executor` |
| Policy | `resource-function-policy` | Applies the profile to all confidential clients (`client-access-type = confidential`) |

These can also be configured manually via the Admin Console under **Realm Settings →
Client Policies**.

<a name="scope-creation-and-authorization-policies"></a>
### 2.9. Scope Creation and Authorization Policies

When a function is attached to an organization via the admin application, three client scopes
must be created automatically (via the Admin REST API):

```
{org_identifier}:{function}:read
{org_identifier}:{function}:write
{org_identifier}:{function}:admin
```

For each scope, an Authorization Services **Group Policy** must be created that evaluates
membership in the qualifying groups (see the [Rights Model](rights-model.md#scopes-for-api-access)
for the full list per right level). The policy uses `Decision Strategy: AFFIRMATIVE` and
`Logic: POSITIVE`.

A **Permission** must then be created linking each scope to its policy.

These policies and permissions must be created on **all** OIDC/OAuth clients that will
request these scopes. In the local development setup, this includes
`https://local.dev.swedenconnect.se:17005` (IAM Admin App) and any other registered client.
The scopes must also be added as optional client scopes on all such clients.

This creation is the responsibility of the admin application and must be done as part of the
"attach function to organization" operation.

---

<a name="appendix-a-step-by-step-setup-with-examples"></a>
## Appendix A: Step-by-Step Setup with Examples

This appendix walks through the complete initial setup of the `orgiam` realm, followed by
the creation of:

- Function: `demo` (Demo)
- Organization: `5590026042` — Litsec AB
- Attaching `demo` to Litsec AB
- Superuser: Internal admin without a personal identity number
- Regular user: `196911292032` — Martin Lindström, with `write` on `demo` under Litsec AB

---

<a name="a1-create-the-realm"></a>
### A.1. Create the Realm

1. Log in to the Keycloak Admin Console as a master realm administrator.
2. Click **Create realm**.
3. Set **Realm name** to `orgiam`.

  3.1. Display name: `"Organizations and Users IAM"`

  3.2. HTML display name: `"Organizations and Users IAM"`

4. For **ACR to LoA Mapping**:

  4.1. Add mapping between `0`(key) and `http://id.elegnamnden.se/loa/1.0/loa2` (value)

  4.2. Add mapping between `1`(key) and `http://id.swedenconnect.se/loa/1.0/uncertified-loa3` (value)

  4.3. Add mapping between `2`(key) and `http://id.elegnamnden.se/loa/1.0/loa3` (value)

  4.4. Add mapping between `3`(key) and `http://id.elegnamnden.se/loa/1.0/loa4` (value)

5. Set **Enabled** to `ON`.
6. Click **Create**.

In **Realm settings → Login**:

- Disable **User registration**

- Disable **Email as username**

- Disable **Forgot password** (unless needed)

In **Realm settings → User profile**:

* Click "Create attribute":

  - Assign the name `personalIdentityNumber`.
  - Set the display name to "Personal Identity Number".
  - Multi-valued: OFF
  - **Enabled when:** Scopes are requested — enter `https://id.oidc.se/scope/naturalPersonNumber`
  - Permissions should be set so that only admins can edit, but both users and admins can view.

> **Note:** Setting "Enabled when" to the `naturalPersonNumber` scope ensures the attribute
> is only surfaced in tokens when the client explicitly requests that scope. The scope
> `https://id.oidc.se/scope/naturalPersonNumber` must be created as a Client Scope in the
> realm (see [A.4](#a4-create-the-personalidentitynumber-client-scope)) before it can be
> referenced here.

---

### A.2. Create Top-Level Groups

Navigate to **Groups** in the left menu.

**Create `orgs`:**

1. Click **Create group**.
2. Name: `orgs`.
3. Click **Create**.

**Create `functions`:**

1. Click **Create group**.
2. Name: `functions`.
3. Click **Create**.

---

### A.3. Create the `superuser` Realm Role

1. Navigate to **Realm roles**.
2. Click **Create role**.
3. Role name: `superuser`.
4. Description: `Full access to all organizations, functions and users.`
5. Click **Save**.

---

### A.4. Create the `personalIdentityNumber` Client Scope

> **Prerequisite:** The `swedish-oidc-claims-mapper` JAR must be deployed to Keycloak before
> this mapper type becomes available. See
> `keycloak/swedish-oidc-claims-mapper/README.md` for build and installation instructions.

1. Navigate to **Client scopes**.
2. Click **Create client scope**.
3. Name: `https://id.oidc.se/scope/naturalPersonNumber`
4. Protocol: `OpenID Connect`
5. Display on consent screen: `ON`
6. Click **Save**.

Now add the OIDC Sweden mapper:

1. Go to the **Mappers** tab of this scope.
2. Click **Configure a new mapper**.
3. Select **OIDC Sweden** from the list.
4. Fill in:
    - Name: `swedish-oidc-claims-mapper`
    - Add to ID token: `ON`
    - Add to access token: `ON`
    - Add to userinfo: `ON`
5. Click **Save**.

The mapper reads the `personalIdentityNumber` user attribute and emits it as
`https://id.oidc.se/claim/personalIdentityNumber` whenever this scope is applied to a
session.

> **Note:** This scope must exist in the realm before configuring the `personalIdentityNumber`
> user profile attribute with "Enabled when: Scopes are requested". If you have already
> created the user profile attribute without this setting, go back to
> **Realm settings → User profile → personalIdentityNumber → Enabled when** and set it to
> this scope now.

---

### A.5. Create the Admin Application Client

1. Navigate to **Clients → Create client**.
2. Client ID: `https://local.dev.swedenconnect.se:17005`
3. Protocol: `OpenID Connect`
4. Click **Next**.
5. Set **Client authentication** to ON.
6. Enable **Standard flow** and **Service accounts roles**. All others should be disabled.
7. Click **Next**, set root URL to `https://local.dev.swedenconnect.se:17005` and the redirect URI to `/login/oauth2/code/*`.
8. Click **Save**.

**Configure `private_key_jwt` client authentication:**

This client uses signed JWT assertions for authentication rather than a client secret.

1. Go to the client → **Credentials** tab → set **Client Authenticator** to `Signed Jwt` →
   Save.
2. Go to the client → **Keys** tab → enable **Use JWKS URL** → set **JWKS URL** to
   `https://local.dev.swedenconnect.se:17005/jwks` → Save.

The application exposes its public key at `/jwks`. Keycloak fetches and caches the key from
this URL when processing the first token request. Ensure the application is running and the
endpoint is reachable before attempting a login.

**Set the `iam_admin_managed` attribute:**

Run the following script to mark this client as managed by the IAM admin application (see
`keycloak/scripts/README.md` for details):

```bash
./compose/keycloak-scripts/set-iam-admin-managed.sh <realm> \
    https://local.dev.swedenconnect.se:17005 <admin-username> <admin-password>
```

**Assign service account roles:**

1. Go to the client → **Service accounts roles** tab.
2. Click **Assign role**.
3. Filter by `realm-management` client.
4. Assign: `manage-users`, `query-groups`, `view-users`, `query-users`, `manage-realm`, `view-clients`, `manage-clients`.

**Add the naturalPersonNumber scope as optional:**

1. Go to the client → **Client scopes** tab.
2. Click **Add client scope**.
3. Select `https://id.oidc.se/scope/naturalPersonNumber` and add as **Optional**.

Adding this scope as optional means that the personal identity number claim will be included in a token if the `https://id.oidc.se/scope/naturalPersonNumber` is requested.

---

### A.5b. Create the Demo App Client (`https://local.dev.swedenconnect.se:16990`)

This demonstrates how to register an additional OIDC/OAuth client (the Demo App).

1. Navigate to **Clients → Create client**.
2. Client ID: `https://local.dev.swedenconnect.se:16990`
3. Protocol: `OpenID Connect`
4. Click **Next**.
5. Set **Client authentication** to ON.
6. Enable **Standard flow** only. All others should be disabled.
7. Click **Next**, set root URL to `https://local.dev.swedenconnect.se:16990` and redirect URI to `/login/oauth2/code/*`.
8. Click **Save**.

**Configure `private_key_jwt` client authentication:**

This client uses signed JWT assertions for authentication rather than a client secret.

1. Go to the client → **Credentials** tab → set **Client Authenticator** to `Signed Jwt` →
   Save.
2. Go to the client → **Keys** tab → enable **Use JWKS URL** → set **JWKS URL** to
   `https://local.dev.swedenconnect.se:16990/jwks` → Save.

**Set the `iam_admin_managed` attribute:**

Run the following script to mark this client as managed by the IAM admin application (see
`keycloak/scripts/README.md` for details):

```bash
./compose/keycloak-scripts/set-iam-admin-managed.sh <realm> \
    https://local.dev.swedenconnect.se:16990 <admin-username> <admin-password>
```

**Add the naturalPersonNumber scope as optional:**

1. Go to the client → **Client scopes** tab.
2. Click **Add client scope**.
3. Select `https://id.oidc.se/scope/naturalPersonNumber` and add as **Optional**.

---

### A.5c. Create the Demo Service Client (`https://local.dev.swedenconnect.se:16995`)

This demonstrates how to register an OAuth resource server (the Demo Service). The Demo
Service is registered in Keycloak solely so that access tokens can carry it as the `aud`
claim via the `resource` parameter. It has no flows, no service account, and no need to
authenticate to Keycloak.

1. Navigate to **Clients → Create client**.
2. Client ID: `https://local.dev.swedenconnect.se:16995`
3. Protocol: `OpenID Connect`
4. Click **Next**.
5. Leave **Client authentication** OFF.
6. Disable **all** capability flags (Standard flow, Direct access grants, Service accounts, etc.).
7. Click **Next** and **Save** (no redirect URIs needed).

**Set the `client_functions` attribute (optional):**

If the resource server only supports specific functions, set the attribute using the
`add-resource-server.sh` script with the `--functions` flag. For the Demo Service, which
supports the `demo` function:

```bash
./compose/keycloak-scripts/add-resource-server.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://local.dev.swedenconnect.se:16995 \
    --name "Demo Service" \
    --functions demo
```

---

### A.5d. Add `naturalPersonNumber` as Default Scope to `https://local.dev.swedenconnect.se:16990`

The `naturalPersonNumber` scope must be added as a **default** scope to the Demo App client
so that the personal identity number claim is always present in access tokens requested by
this client. It is not added to `17005` as default because `17005` is an OIDC client and
already has it as optional (A.5). It is not added to `16995` at all, as that client is a
pure resource server and never requests tokens.

1. Navigate to **Clients → `https://local.dev.swedenconnect.se:16990` → Client scopes** tab.
2. Click **Add client scope**.
3. Select `https://id.oidc.se/scope/naturalPersonNumber` and add as **Default**.

Because it is a default scope, Keycloak always applies it to the session regardless of what
API scopes the client requests. The OIDC Sweden mapper therefore fires unconditionally, and
the `https://id.oidc.se/claim/personalIdentityNumber` claim is always present in access
tokens issued by this client.

> **Note:** Adding the scope as **optional** would not work here. In an OAuth 2.0
> authorization request (without `openid`), the `scope` parameter carries only the API
> scopes such as `5590026042:demo:write`. The `naturalPersonNumber` scope would not be
> present in the request, so an optional scope would not be applied and the mapper would not
> fire. A default scope is applied by Keycloak regardless of what the client requests.

---

### A.5e. Add `phone` Scope to Clients

<a name="a5e-add-phone-scope-to-clients"></a>

The built-in `phone` client scope should already exist in the realm. If it does not, create it
first (see Section 4.4b).

**Add to `https://local.dev.swedenconnect.se:17005`:**

1. Navigate to **Clients → `https://local.dev.swedenconnect.se:17005` → Client scopes** tab.
2. Click **Add client scope**.
3. Select `phone` and add as **Optional**.

**Add to `https://local.dev.swedenconnect.se:16990`:**

1. Navigate to **Clients → `https://local.dev.swedenconnect.se:16990` → Client scopes** tab.
2. Click **Add client scope**.
3. Select `phone` and add as **Optional**.

With the scope added as optional, the `phone_number` claim is included in the ID token only
when the client includes `phone` in the `scope` parameter of the authorization request. If the
user has no `phoneNumber` attribute set, the claim is absent even when the scope is requested.

**Verify the mapper:**

1. Navigate to **Client scopes → phone → Mappers**.
2. Confirm a mapper is present with Token Claim Name `phone_number` and User Attribute `phoneNumber`.
3. Verify **Add to ID token** is `ON`.

---

### A.6. Create the Function `demo`

1. Navigate to **Groups**.
2. Click on the `functions` group.
3. Click **Create sub-group**.
4. Name: `demo`.
5. Click **Create**.
6. Go to the **Attributes** tab of the `demo` group.
7. Add the following attributes:
   - `name#sv` = `Demo`
   - `name#en` = `Demo`
   - `description#sv` = `Demofunktion` *(longer description, optional)*
   - `description#en` = `Demo function` *(longer description, optional)*
8. Click **Save**.

---

### A.7. Create Organization `5590026042` — Litsec AB

1. Navigate to **Groups**.
2. Click on the `orgs` group.
3. In the **Child groups** tab, click **Create group**.
4. Name: `5590026042`.
5. Click **Create**.
6. Go to the **Attributes** tab of this sub-group:
7. Add the following attributes: `organization_identifier`: `5590026042`, `organization_name#sv`: `Litsec AB` and `organization_name#en`: `Litsec AB`.

8. Click **Save**.

**Create child groups under `5590026042`:**

Repeat the following three times (creating `_admin`, `_write`, `_read`):

1. Click on the `5590026042` group.
2. In the **Child groups**, click **Create group**.
3. Name: `_admin` (then `_write`, then `_read`).
4. Click **Create**.

---

### A.8. Attach Function `demo` to Organization `5590026042`

1. Navigate to **Groups → orgs → 5590026042**.
2. In the **Child groups** tab, click **Create group**.
3. Name: `demo`.
4. Click **Create**.
5. Go to the **Attributes** tab of this sub-group.
6. Add attribute: `function_ref` = `demo`.
7. Click **Save**.

**Create right sub-groups under `5590026042/demo`:**

1. Click on the `demo` sub-group (under `5590026042`).
2. Create sub-groups `_admin`, `_write`, and `_read`.

**Create client scopes for this org/function combination:**

Create three client scopes via the Admin Console or REST API (see [Appendix B](#appendix-b-keycloak-admin-rest-api-reference)):

- `5590026042:demo:read`
- `5590026042:demo:write`
- `5590026042:demo:admin`

For each scope, create an Authorization Services Group Policy with the qualifying groups
as described in the [Rights Model](rights-model.md#scopes-for-api-access). Add each scope as an optional scope on the relevant client(s).

#### Details

**Create the client scope:**

1. Navigate to **Client scopes**.
2. Click **Create client scope**.
3. Name: `5590026042:demo:read`
4. Protocol: `OpenID Connect`
5. Include in token scope: `ON`
6. Display on consent screen: `OFF`
7. Click **Save**.

Repeat for `5590026042:demo:write` and `5590026042:demo:admin`.

**Add the scopes as optional to the relevant clients:**

1. Navigate to **Clients → `https://local.dev.swedenconnect.se:17005` → Client scopes** tab.
2. Click **Add client scope**.
3. Select `5590026042:demo:read` and add as **Optional**.
4. Repeat for `:write` and `:admin`.

Repeat steps 1–4 for client `https://local.dev.swedenconnect.se:16990`.

**Enable Authorization Services on the clients:**

1. Navigate to **Clients → `https://local.dev.swedenconnect.se:17005` → Settings** tab.
2. Enable **Authorization**.
3. Click **Save**.

Repeat for **Clients → `https://local.dev.swedenconnect.se:16990` → Settings** tab.

**Create a Group Policy for each scope:**

Before creating policies and permissions, the authorization scopes must first be registered
within Authorization Services (these are distinct from the client scopes created above).
This must be done for both clients:

1. Navigate to **Clients → `https://local.dev.swedenconnect.se:17005` → Authorization → Scopes** tab.
2. Click **Create authorization scope**.
3. Name: `5590026042:demo:read`
4. Click **Save**.
5. Repeat for `5590026042:demo:write` and `5590026042:demo:admin`.

Repeat steps 1–5 for **Clients → `https://local.dev.swedenconnect.se:16990` → Authorization → Scopes** tab.

Now create a Group Policy for each:

For `5590026042:demo:read`:

1. Navigate to **Clients → `https://local.dev.swedenconnect.se:17005` → Authorization** tab.
2. Click **Policies**.
3. Click **Create policy → Group**.
4. Name: `policy-5590026042-demo-read`
5. Under **Groups**, add:
   - `/orgs/5590026042/_read`
   - `/orgs/5590026042/_write`
   - `/orgs/5590026042/_admin`
   - `/orgs/5590026042/demo/_read`
   - `/orgs/5590026042/demo/_write`
   - `/orgs/5590026042/demo/_admin`
6. Logic: `Positive`
7. Click **Save**.

For `policy-5590026042-demo-write`, add only:
- `/orgs/5590026042/_write`
- `/orgs/5590026042/_admin`
- `/orgs/5590026042/demo/_write`
- `/orgs/5590026042/demo/_admin`

For `policy-5590026042-demo-admin`, add only:
- `/orgs/5590026042/_admin`
- `/orgs/5590026042/demo/_admin`

**Create a Permission linking each scope to its policy:**

For `5590026042:demo:read`:

1. Navigate to **Clients → `https://local.dev.swedenconnect.se:17005` → Authorization** tab.
2. Navigate to **Authorization → Permissions**.
3. Click **Create permission → Scope-based**.
4. Name: `permission-5590026042-demo-read`
5. Authorization scopes: select `5590026042:demo:read`
6. Policies: select `policy-5590026042-demo-read`
7. Decision strategy: `Affirmative`
8. Click **Save**.

Repeat for `:write` and `:admin`, linking each to its corresponding policy.

Repeat the entire **Create authorization scopes**, **Create a Group Policy**, and
**Create a Permission** sequence for client `https://local.dev.swedenconnect.se:16990`, using the
same scope names, policy names (prefixed with the client or kept identical — they are
per-client), and group lists.

---

### A.9. Create a Superuser

A superuser is an internal system administrator. They do not need to provide a personal
identity number. The username can be any chosen identifier — a name, an email address, or
any other memorable string — since there is no personal identity number to use as a natural
unique identifier.

> **Note:** As with regular users, when a SAML IdP is introduced the username will become
> irrelevant. Superusers may however continue to use password login if they are internal
> administrators who are not represented in the external IdP.

1. Navigate to **Users → Create new user**.
2. Fill in:
    - **Username:** set to a chosen identifier, e.g. `diggadmin` or an email address.
    - First name and last name as appropriate.
    - Email: (optional)
    - Do **not** add a `personalIdentityNumber` attribute.
3. Click **Create**.
4. Go to the **Credentials** tab.
5. Click **Set password**, enter a temporary password, and click **Save**.
6. Navigate to the **Role mappings** tab.
7. Click **Assign role**.
8. Filter by realm roles and select `superuser`.
9. Click **Assign**.

The user now has full access to all organizations and functions in the admin application.
Their `org_rights` claim will contain `[{ "superuser": true }]`. No personal identity number
will appear in any token issued for this user.

---

### A.10. Create a Regular User

> **Note:** Currently users log in with username and password. The username is set to the
> personal identity number so the user has something known and unique to type at the login
> screen. The `sub` claim in tokens is always Keycloak's internal UUID and is never derived
> from the username, so the personal identity number will not appear in any token claim other
> than `https://id.oidc.se/claim/personalIdentityNumber`. When a SAML IdP is introduced
> later, the username will become irrelevant — Keycloak will identify users by matching the
> incoming assertion against the `personalIdentityNumber` attribute.

1. Navigate to **Users → Create new user**.
2. Fill in:
    - **Username:** set to the personal identity number, e.g. `196911292032`.
    - First name: `Martin`
    - Last name: `Lindström`
    - Email: (optional)
    - Personal Identity Number: `196911292032`
3. Click **Create**.
4. Go to the **Attributes** tab.
5. Click **Save**.
6. Go to the **Credentials** tab.
7. Click **Set password**, enter a temporary password, and click **Save**.

---

### A.11. Assign Write Right on `demo` under Litsec AB

Martin should have `write` access to `demo` within organization `5590026042`.

1. Navigate to **Users → Martin Lindström**.
2. Go to the **Groups** tab.
3. Click **Join group**.
4. Navigate to: `orgs → 5590026042 → demo → _write`.
5. Select `_write` and click **Join**.

Martin is now a member of `orgs/5590026042/demo/_write`. The `org_rights` mapper will
produce the following entry in his token:

```json
{
  "organization_identifier": "5590026042",
  "organization_name#sv": "Litsec AB",
  "organization_name#en": "Litsec AB",
  "functions": [
    { "function": "demo", "right": "write" }
  ]
}
```

---

<a name="appendix-b-keycloak-admin-rest-api-reference"></a>
## Appendix B: Keycloak Admin REST API Reference

This appendix provides examples of all common operations using the Keycloak Admin REST API
against the `orgiam` realm.

Base URL for all Admin API calls:

```
https://<keycloak-host>/admin/realms/orgiam
```

---

### B.1. Obtain an Access Token (Service Account)

Client authentication uses `private_key_jwt`. The application signs a JWT assertion with
its private key and sends it to the token endpoint. Keycloak verifies the assertion using
the public key fetched from the application's JWKS endpoint.

The signed JWT assertion is constructed and sent automatically by the application (via
Spring Security's OAuth2 client support). For manual use or scripting, the assertion must
be a JWT signed with the client's private key and include the following claims:

| Claim | Value |
|---|---|
| `iss` | The client ID |
| `sub` | The client ID |
| `aud` | The token endpoint URL |
| `jti` | A unique identifier for this assertion |
| `exp` | Expiry (short-lived, typically 60 seconds) |

```http
POST /realms/orgiam/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_id=https%3A%2F%2F<host>%3A<port>
&client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer
&client_assertion=<signed-jwt>
```

Use the returned `access_token` as a Bearer token for all subsequent Admin API calls:

```
Authorization: Bearer <access_token>
```

---

### B.2. Functions

#### Get the `functions` Group ID

```http
GET /admin/realms/orgiam/groups?search=functions&exact=true
```

Returns an array. Use the `id` field of the `functions` entry.

#### Create a Function

```http
POST /admin/realms/orgiam/groups/<functions-group-id>/children
Content-Type: application/json

{
  "name": "demo",
  "attributes": {
    "description#en": ["Demo function"],
    "description#sv": ["Demofunktion"]
  }
}
```

#### List All Functions

```http
GET /admin/realms/orgiam/groups/<functions-group-id>/children
```

#### Get a Specific Function by Name

```http
GET /admin/realms/orgiam/groups?search=demo&exact=true
```

---

### B.3. Organizations

#### Get the `orgs` Group ID

```http
GET /admin/realms/orgiam/groups?search=orgs&exact=true
```

#### Create an Organization

This requires multiple calls: create the org group, then create its three right sub-groups.

**Step 1 — Create the organization group:**

```http
POST /admin/realms/orgiam/groups/<orgs-group-id>/children
Content-Type: application/json

{
  "name": "5590026042",
  "attributes": {
    "organization_identifier": ["5590026042"],
    "organization_name#sv": ["Litsec AB"],
    "organization_name#en": ["Litsec AB"]
  }
}
```

Note the `id` of the newly created group from the `Location` response header or by
subsequently searching for it.

**Step 2 — Create right sub-groups:**

```http
POST /admin/realms/orgiam/groups/<org-group-id>/children
Content-Type: application/json
{ "name": "_admin" }

POST /admin/realms/orgiam/groups/<org-group-id>/children
Content-Type: application/json
{ "name": "_write" }

POST /admin/realms/orgiam/groups/<org-group-id>/children
Content-Type: application/json
{ "name": "_read" }
```

#### List All Organizations

```http
GET /admin/realms/orgiam/groups/<orgs-group-id>/children?briefRepresentation=false
```

#### Get a Specific Organization by Identifier

```http
GET /admin/realms/orgiam/groups?search=5590026042&exact=true
```

#### Update Organization Metadata

```http
PUT /admin/realms/orgiam/groups/<org-group-id>
Content-Type: application/json

{
  "name": "5590026042",
  "attributes": {
    "organization_identifier": ["5590026042"],
    "organization_name#sv": ["Litsec AB — uppdaterat namn"],
    "organization_name#en": ["Litsec AB — updated name"]
  }
}
```

---

### B.4. Attaching a Function to an Organization

This requires creating the function sub-group under the org, its three right sub-groups,
and the three client scopes with their Authorization policies.

**Step 1 — Create function sub-group under the org:**

```http
POST /admin/realms/orgiam/groups/<org-group-id>/children
Content-Type: application/json

{
  "name": "demo",
  "attributes": {
    "function_ref": ["demo"]
  }
}
```

**Step 2 — Create right sub-groups under the function sub-group:**

```http
POST /admin/realms/orgiam/groups/<org-function-group-id>/children
Content-Type: application/json
{ "name": "_admin" }

POST /admin/realms/orgiam/groups/<org-function-group-id>/children
Content-Type: application/json
{ "name": "_write" }

POST /admin/realms/orgiam/groups/<org-function-group-id>/children
Content-Type: application/json
{ "name": "_read" }
```

**Step 3 — Create client scopes:**

```http
POST /admin/realms/orgiam/client-scopes
Content-Type: application/json

{
  "name": "5590026042:demo:read",
  "protocol": "openid-connect",
  "attributes": {
    "include.in.token.scope": "true",
    "display.on.consent.screen": "false"
  }
}
```

Repeat for `:write` and `:admin`.

**Step 4 — Create Authorization Services scopes** on each client's resource server.

Keycloak Authorization Services maintains its own scope registry per resource server,
completely separate from OAuth2 client scopes. Scope permissions must reference scopes from
this registry. Create each of the three scopes on **both** clients:

```http
POST /admin/realms/orgiam/clients/<client-id>/authz/resource-server/scope
Content-Type: application/json

{ "name": "5590026042:demo:read" }
```

Repeat for `:write` and `:admin`. The response body contains the created scope with its `id`.

**Step 5 — Create Authorization Services policies and permissions** for each scope.

First, enable Authorization Services on the relevant client if not already done. Then for
each scope, create a Group Policy via:

```http
POST /admin/realms/orgiam/clients/<client-id>/authz/resource-server/policy/group
Content-Type: application/json

{
  "name": "policy-5590026042-demo-read",
  "groups": [
    { "path": "/orgs/5590026042/_read",        "extendChildren": false },
    { "path": "/orgs/5590026042/_write",       "extendChildren": false },
    { "path": "/orgs/5590026042/_admin",       "extendChildren": false },
    { "path": "/orgs/5590026042/demo/_read",   "extendChildren": false },
    { "path": "/orgs/5590026042/demo/_write",  "extendChildren": false },
    { "path": "/orgs/5590026042/demo/_admin",  "extendChildren": false }
  ],
  "logic": "POSITIVE",
  "decisionStrategy": "AFFIRMATIVE"
}
```

For `:write`, include only `_write` and `_admin` groups (at both levels).
For `:admin`, include only `_admin` groups (at both levels).

> **Note:** Unlike group and scope creation endpoints, the Authorization Services policy and
> permission endpoints do **not** return a `Location` header. Instead, they return `201 Created`
> with the created resource as a JSON body. Extract the `id` field from the response body to
> obtain the policy ID.

Then create the corresponding scope permission:

```http
POST /admin/realms/orgiam/clients/<client-id>/authz/resource-server/permission/scope
Content-Type: application/json

{
  "name": "permission-5590026042-demo-read",
  "type": "scope",
  "scopes": ["5590026042:demo:read"],
  "policies": ["<policy-id>"],
  "decisionStrategy": "AFFIRMATIVE"
}
```

> **Note:** The `scopes` array must contain the **scope name** (e.g. `"5590026042:demo:read"`),
> not the scope UUID. This is inconsistent with the `policies` field which takes a UUID, but it
> is how the Keycloak Authorization Services API works.

Again, the permission `id` is in the response body, not a `Location` header.

Repeat the policy + permission pair for `:write` and `:admin` scopes, using the appropriate
group lists and scope names.

**Step 6 — Add scopes as optional to relevant clients:**

```http
PUT /admin/realms/orgiam/clients/<client-id>/optional-client-scopes/<scope-id>
```

---

### B.5. Users

#### Create a User

When creating a user via the REST API, omit the `username` field to let Keycloak generate a
UUID, or supply a UUID explicitly. The personal identity number is stored as an attribute only.

```http
POST /admin/realms/orgiam/users
Content-Type: application/json

{
  "enabled": true,
  "firstName": "Martin",
  "lastName": "Lindström",
  "attributes": {
    "personalIdentityNumber": ["196911292032"]
  }
}
```

> **Note:** Keycloak requires the `username` field in some versions even when UUID generation
> is intended. If the API rejects the request, supply an explicit UUID:
> `"username": "<generated-uuid>"`.

#### Find a User by Personal Identity Number

```http
GET /admin/realms/orgiam/users?q=personalIdentityNumber:196911292032&exact=true
```

#### Get User Details

```http
GET /admin/realms/orgiam/users/<user-id>
```

#### Update User Attributes

```http
PUT /admin/realms/orgiam/users/<user-id>
Content-Type: application/json

{
  "firstName": "Martin",
  "lastName": "Lindström",
  "attributes": {
    "personalIdentityNumber": ["196911292032"]
  }
}
```

#### List All Users

```http
GET /admin/realms/orgiam/users?max=100
```

---

### B.6. Assigning and Removing Rights

#### Get a Group ID by Path

```http
GET /admin/realms/orgiam/groups?search=_write&exact=true
```

Alternatively, traverse the tree:

```http
GET /admin/realms/orgiam/groups?search=5590026042&exact=true
```

Then navigate into sub-groups using the returned `subGroupCount` or:

```http
GET /admin/realms/orgiam/groups/<org-group-id>/children
```

#### Assign a Right to a User

Add the user to the appropriate right group. For `write` on `demo` under `5590026042`:

```http
PUT /admin/realms/orgiam/users/<user-id>/groups/<_write-group-id-under-demo>
```

No body is required. A `204 No Content` response indicates success.

#### Remove a Right from a User

```http
DELETE /admin/realms/orgiam/users/<user-id>/groups/<group-id>
```

#### List a User's Group Memberships

```http
GET /admin/realms/orgiam/users/<user-id>/groups
```

Returns all groups the user belongs to, including path information.

#### Assign the `superuser` Role to a User

First, retrieve the `superuser` role ID:

```http
GET /admin/realms/orgiam/roles/superuser
```

Then assign it:

```http
POST /admin/realms/orgiam/users/<user-id>/role-mappings/realm
Content-Type: application/json

[
  {
    "id": "<superuser-role-id>",
    "name": "superuser"
  }
]
```

#### Remove the `superuser` Role from a User

```http
DELETE /admin/realms/orgiam/users/<user-id>/role-mappings/realm
Content-Type: application/json

[
  {
    "id": "<superuser-role-id>",
    "name": "superuser"
  }
]
```

#### Check if a User Has the `superuser` Role

```http
GET /admin/realms/orgiam/users/<user-id>/role-mappings/realm
```

Look for `superuser` in the returned array.

---

### B.7. Querying Organizations and Functions

#### List Functions Attached to an Organization

```http
GET /admin/realms/orgiam/groups/<org-group-id>/children
```

Filter out `_admin`, `_write`, `_read` from the results — the remaining children are the
attached function sub-groups.

#### Find All Organizations That Have a Specific Function Attached

There is no direct reverse-lookup in Keycloak's group API. The admin application must fetch
all organizations and filter client-side:

```http
GET /admin/realms/orgiam/groups/<orgs-group-id>/children?briefRepresentation=false
```

Then for each organization, check if a child group exists with the desired function name.

#### List Members of a Right Group (Users with a Specific Right)

```http
GET /admin/realms/orgiam/groups/<group-id>/members
```

For example, to list all users with `write` right on `demo` under `5590026042`, obtain
the ID of `orgs/5590026042/demo/_write` and call the members endpoint.

---

### B.8. Updating Organization Attributes

Organization mutable attributes (names, contact info) are stored on the org group
representation and updated via a `PUT` to the group endpoint. The full group representation
must be supplied — Keycloak replaces the entire object, so always fetch first and merge.

#### Fetch the Organization Group

```http
GET /admin/realms/orgiam/groups/<org-group-id>
```

#### Update Organization Attributes

```http
PUT /admin/realms/orgiam/groups/<org-group-id>
Content-Type: application/json

{
  "id": "<org-group-id>",
  "name": "5590026042",
  "attributes": {
    "organization_identifier": ["5590026042"],
    "organization_name#sv": ["Litsec AB"],
    "organization_name#en": ["Litsec AB"],
    "contact_info": ["{\"email\":\"info@litsec.se\",\"phone_number\":\"+46701234567\"}"]
  }
}
```

The `contact_info` attribute is a single-element list containing a compact JSON string with
the optional members `email` and `phone_number`. Omit the attribute entirely if no contact
details are set. Always carry forward the existing `organization_identifier` and
`organization_name#*` attributes when only updating contact info, and vice versa — the PUT
replaces all attributes.

A `204 No Content` response indicates success.

---

### B.9. Updating and Deleting Users

#### Update a User's Profile Fields

```http
PUT /admin/realms/orgiam/users/<user-id>
Content-Type: application/json

{
  "firstName": "Martin",
  "lastName": "Lindström",
  "email": "martin@example.com",
  "attributes": {
    "personalIdentityNumber": ["196911292032"],
    "phoneNumber": ["+46701234567"]
  }
}
```

As with group updates, always fetch the current user representation first and merge the
changed fields. This preserves `personalIdentityNumber` and any other attributes not
being modified. Omit `phoneNumber` from the attributes map (or remove the key entirely)
to clear the phone number. Never modify `personalIdentityNumber` via this endpoint.

A `204 No Content` response indicates success.

#### Permanently Delete a User

```http
DELETE /admin/realms/orgiam/users/<user-id>
```

This permanently removes the user from the realm, including all group memberships. It cannot
be undone. Before calling this endpoint, the admin application must verify that the user is
not the last administrator for any organization or function.

A `204 No Content` response indicates success.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
