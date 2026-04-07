![Sweden Connect](images/sweden-connect.png)

# Organization and Function-Based Rights Model

---

## Table of Contents

1. [**Overview**](#overview)

2. [**Model and Key Concepts**](#model-and-key-concepts)

   2.1. [Functions](#functions)

   2.2. [Organizations](#organizations)

   2.3. [Users](#users)

   2.4. [Rights](#rights)

   2.5. [Group Structure](#group-structure)

   2.6. [The `org_rights` Claim](#the-org_rights-claim)

   2.7. [Scopes for API Access](#scopes-for-api-access)

3. [**Usage for OIDC RPs, OAuth Clients and OAuth Resource Servers**](#usage-for-oidc-rps-oauth-clients-and-oauth-resource-servers)

   3.1. [OIDC Relying Parties](#oidc-relying-parties)

   3.2. [OAuth Clients Calling APIs](#oauth-clients-calling-apis)

   3.3. [OAuth Resource Servers](#oauth-resource-servers)

---

<a name="overview"></a>

## 1. Overview

The Organizations and Users IAM system manages organizations, functions, and users for
administrative purposes across one or more service domains (called "functions"). It is built
on Keycloak with custom protocol mappers and a Spring Boot admin application.

The system supports the following requirements:

- Applications need to determine whether a user has the right to administer a particular
  organization, or a specific function within an organization.
- Users may hold rights on multiple organizations and/or functions, at different right levels
  (admin, write, read).
- An administrative application allows privileged users to manage organizations, functions,
  and user rights via the Keycloak REST API.
- Client applications authenticate users via OIDC and receive structured claims describing
  the user's rights.
- Client applications calling downstream APIs obtain access tokens whose granted scopes
  prove the user's entitlement for a specific organization, function, and right level.
  Keycloak enforces entitlement at token issuance time.
- Swedish personal identity numbers are used as a user attribute and identity claim, following the
  [Claims and Scopes Specification for the Swedish OpenID Connect Profile](https://www.oidc.se/specifications/swedish-oidc-claims-specification-1_0.html).
  Usernames are assigned by Keycloak as UUIDs and carry no semantic meaning.

---

<a name="model-and-key-concepts"></a>

## 2. Model and Key Concepts

<a name="functions"></a>

### 2.1. Functions

A **function** is a named administrative domain. It represents an area to which organizations
and users are related. Examples are `demo` (Demo) or `sweden-connect`
(Sweden Connect Federation).

Functions are defined once in the realm and can then be attached to any number of organizations.
In Keycloak, functions are represented as sub-groups under the top-level group `functions`.
The group name serves as the unique function identifier (e.g. `demo`), while the human-readable
display names are stored as group attributes `name#sv` and `name#en`.

<a name="organizations"></a>

### 2.2. Organizations

An **organization** is identified uniquely by its `organization_identifier`, which is a
ten-digit Swedish organizational number (no dash, e.g. `5590026042`). The organization's
names (Swedish and English) are stored as metadata attributes.

An organization may have one or more functions attached to it, meaning the organization
participates in that function's domain (for example, has signed an agreement to join a
federation).

In Keycloak, organizations are represented as sub-groups under the top-level group `orgs`,
named by their `organization_identifier`.

<a name="users"></a>

### 2.3. Users

A **user** is a person who has been provisioned in the realm. Usernames are assigned by
Keycloak as UUIDs and carry no semantic meaning. Users are looked up by their
`personalIdentityNumber` attribute when needed.

Most users hold a full name and a `personalIdentityNumber` attribute, which stores the
Swedish civic registration number in 12-digit format (e.g. `196911292032`). Optionally, a
`phoneNumber` attribute can be set and delivered as the `phone_number` claim when the `phone`
scope is requested.

**Superusers** are a special category. A superuser is assigned the `superuser` realm role
and has full administrative access to all organizations, functions, and users. Superusers
are typically internal system administrators and are not required to provide a personal
identity number. The `personalIdentityNumber` attribute is therefore optional for superuser
accounts.

**In OIDC flows**, the personal identity number claim is released only when the requesting
client includes the scope `https://id.oidc.se/scope/naturalPersonNumber`. This governs
inclusion in the ID token and UserInfo response, following the Swedish OIDC profile. Since
superusers do not have a personal identity number, clients must not request this scope for
superuser sessions, or must tolerate its absence in the token.

**In OAuth 2.0 flows** (access token only, no `openid`), the same scope-driven mechanism is
used. Because the `naturalPersonNumber` scope is configured as a **default scope** on OAuth
clients, it is always applied to the session and the claim is always present in access tokens
for regular users. For superuser sessions the attribute will be absent from the token.
Resource servers must handle this gracefully.

In both cases the claim name, when present, is:

```
https://id.oidc.se/claim/personalIdentityNumber
```

See
the [Swedish OIDC Claims Specification](https://www.oidc.se/specifications/swedish-oidc-claims-specification-1_0.html)
for the full definition.

<a name="rights"></a>

### 2.4. Rights

A user can hold one of three right levels on either an organization as a whole, or on a
specific function within an organization:

| Right   | Description                                                            |
|---------|------------------------------------------------------------------------|
| `admin` | Full administrative rights. Can manage users, functions, and settings. |
| `write` | Can create and modify data but cannot manage users or structure.       |
| `read`  | Read-only access.                                                      |

Rights are hierarchical: `admin` implies `write` and `read`; `write` implies `read`.

A user with a right on an **organization as a whole** implicitly holds that right on all
functions currently attached to that organization.

A user with a right on a **specific function within an organization** holds that right only
for that function.

A special `superuser` role exists at the realm level. A user holding this role can administer
all organizations, all functions, and all users, regardless of group memberships.

<a name="group-structure"></a>

### 2.5. Group Structure

All authorization state is encoded in Keycloak's group tree. There are no realm roles other
than `superuser`. Rights are determined entirely by which group a user is a member of.

```
orgs/
  <organization_identifier>/         e.g. 5590026042
    _admin/                           org-level admin right
    _write/                           org-level write right
    _read/                            org-level read right
    <function-name>/                  e.g. demo (attached function)
      _admin/                         function-level admin right
      _write/                         function-level write right
      _read/                          function-level read right

functions/
  <function-name>/                    canonical function definitions
    ...
```

**Organization group attributes:**

| Attribute                 | Description                                           | Example                                                    |
|---------------------------|-------------------------------------------------------|------------------------------------------------------------|
| `organization_identifier` | Ten-digit org number, no dash                         | `5590026042`                                               |
| `organization_name#sv`    | Organization name in Swedish                          | `Litsec AB`                                                |
| `organization_name#en`    | Organization name in English                          | `Litsec AB`                                                |
| `contact_info`            | JSON object with optional contact details (see below) | `{"email":"info@litsec.se","phone_number":"+46701234567"}` |

The `contact_info` attribute is a single-element list containing a compact JSON string with
the following optional members:

| Member         | Description                                                         | Example          |
|----------------|---------------------------------------------------------------------|------------------|
| `email`        | Contact email address for the organization                          | `info@litsec.se` |
| `phone_number` | Contact phone number (E.164-style, digits and optional leading `+`) | `+46701234567`   |

Both members are optional. The attribute may be absent entirely if neither is set. It is
written and read exclusively by the IAM admin application via the Keycloak Admin REST API —
it is not exposed in any OIDC token or claim.

**Function group attributes (canonical definition under `functions/`):**

| Attribute        | Description                   | Example    |
|------------------|-------------------------------|------------|
| `name#sv`        | Display name in Swedish       | `Demo`     |
| `name#en`        | Display name in English       | `Demo`     |
| `description#sv` | Longer description in Swedish | (optional) |
| `description#en` | Longer description in English | (optional) |

**Function sub-group attribute (under an org — the attachment marker):**

| Attribute      | Description                                                     | Example |
|----------------|-----------------------------------------------------------------|---------|
| `function_ref` | Canonical function name (refers back to the `functions/` group) | `demo`  |

<a name="the-org_rights-claim"></a>

### 2.6. The `org_rights` Claim

A custom protocol mapper produces the `org_rights` claim in tokens. This claim provides a
structured description of all rights held by the authenticated user.

**For a superuser:**

```json
"org_rights": [{"superuser": true}]
```

**For a regular user:**

```json
"org_rights": [
{
"organization_identifier": "5590026042",
"organization_name#sv": "Litsec AB",
"organization_name#en": "Litsec AB",
"functions": [
{"function": "demo", "right": "write"}
]
},
{
"organization_identifier": "5561234567",
"organization_name#sv": "Exempel AB",
"organization_name#en": "Example Corp",
"functions": [
{"function": "*", "right": "admin"}
]
}
]
```

Rights are expressed per function within each organization entry. The special value
`"function": "*"` means the right was granted at the organization level and applies to all
functions currently attached to that organization. A named function entry means the right was
granted on that specific function only.

A user may hold different right levels on different functions within the same organization.
For example, `read` at org level (expressed as `*`) and `write` on a specific function are
both represented within the same organization entry:

```json
{
  "organization_identifier": "5590026042",
  "organization_name#sv": "Litsec AB",
  "organization_name#en": "Litsec AB",
  "functions": [
    {
      "function": "*",
      "right": "read"
    },
    {
      "function": "demo",
      "right": "write"
    }
  ]
}
```

When evaluating the effective right for a specific function, a consumer must take the
**highest right** among all entries that match the function (either the exact function name
or `*`). Rights are hierarchical: `admin` > `write` > `read`.

There is no top-level `right` or `scope` field on the organization entry. All right
information lives inside the `functions` array.

**Token placement:**

| Token                        | `org_rights` included?          |
|------------------------------|---------------------------------|
| ID token (admin app)         | Yes                             |
| Access token (admin app)     | Yes                             |
| ID token (end-user apps)     | Yes                             |
| Access token (end-user apps) | No — governed by scopes instead |

<a name="scopes-for-api-access"></a>

### 2.7. Scopes for API Access

For client applications calling downstream APIs, authorization is expressed via OAuth 2.0
scopes rather than the `org_rights` claim. This allows Keycloak to enforce entitlement at
token issuance time, and resource servers to trust the token's granted scopes directly.

Scope names follow the pattern:

```
{organization_identifier}:{function}:{right}
```

Examples:

```
5590026042:demo:read
5590026042:demo:write
5590026042:demo:admin
```

**Entitlement evaluation** (what qualifies for `5590026042:demo:read`):

Keycloak checks whether the user is a member of **any one** of the following groups,
or holds the `superuser` realm role:

```
orgs/5590026042/_read
orgs/5590026042/_write
orgs/5590026042/_admin
orgs/5590026042/demo/_read
orgs/5590026042/demo/_write
orgs/5590026042/demo/_admin
```

The general rule is:

- A `:read` scope is satisfied by `_read`, `_write`, or `_admin` at function level or org level.
- A `:write` scope is satisfied by `_write` or `_admin` at function level or org level.
- An `:admin` scope is satisfied by `_admin` at function level or org level only.

These scopes are created dynamically via the Admin REST API whenever a function is attached to
an organization. The admin application is responsible for creating the three scope objects
(`:<function>:read`, `:<function>:write`, `:<function>:admin`) and the corresponding
Authorization Services policies at that time.

The OAuth 2.0 `resource` parameter (RFC 8707) is used by clients to bind access tokens to a
specific API (resource server). When the resource-aud plugin is deployed, the `aud` claim
in the access token is set to a multi-valued array:

- If the `resource` parameter is present: `aud` = `[resource_server_client_id, function]`
- If the `resource` parameter is absent: `aud` = `[function]`

The function identifier is extracted from the granted scope (`{org}:{function}:{right}`).

The plugin also validates that the resource server indicated by the `resource` parameter
supports the requested function. Each resource server client can declare its supported
functions via the `client_functions` attribute (see the Keycloak Setup document). If the
resource server does not support the requested function, the token request is rejected with an
`invalid_target` error.

**The `organization_identifier` claim in access tokens:**

Resource servers need to know which organization the access token was issued for, without
having to parse the scope string. Access tokens therefore include an `organization_identifier`
claim containing the ten-digit organizational number extracted from the granted scope.

Since a token is always scoped to a single organization/function/right combination, this
value is unambiguous. It is the responsibility of the OAuth client to request only one
org-scoped scope per token request. The `organization_identifier` claim is added by a
dedicated protocol mapper on each OAuth client.

The recommended approach is for the mapper to extract the organization identifier from the
first matching `{org}:{function}:{right}` scope in the token, rather than reading it from a
user attribute (since a user may belong to multiple organizations).

---

<a name="usage-for-oidc-rps-oauth-clients-and-oauth-resource-servers"></a>

## 3. Usage for OIDC RPs, OAuth Clients and OAuth Resource Servers

<a name="oidc-relying-parties"></a>

### 3.1. OIDC Relying Parties

An OIDC Relying Party (RP) authenticates users via the standard authorization code flow.
The RP receives an ID token containing:

- Standard claims: `sub`, `name`, `given_name`, `family_name`
- `https://id.oidc.se/claim/personalIdentityNumber` — when the scope
  `https://id.oidc.se/scope/naturalPersonNumber` is requested
- `org_rights` — a structured array describing the user's rights across all organizations
  and functions

The RP uses `org_rights` to determine which organizations and functions the user may act on,
and what level of access they hold. The RP must not grant access beyond what the claim
permits.

**Example authorization request:**

```
GET /realms/orgiam/protocol/openid-connect/auth
  ?client_id=my-rp
  &response_type=code
  &scope=openid%20profile%20https%3A%2F%2Fid.oidc.se%2Fscope%2FnaturalPersonNumber
  &redirect_uri=https://my-rp.example.com/callback
  &state=...
  &nonce=...
```

The RP then exchanges the code for tokens and reads `org_rights` from the ID token.

<a name="oauth-clients-calling-apis"></a>

### 3.2. OAuth Clients Calling APIs

A client application that needs to call a downstream API on behalf of the user uses the
authorization code flow with a resource-specific scope. The scope encodes the organization,
function, and required right level. The `resource` parameter binds the token to the target API.

This is a pure OAuth 2.0 flow — the `openid` scope is not requested, so no ID token is
issued. The response contains an access token only.

For example, the Demo App (`https://local.dev.swedenconnect.se:16990`) acts as an OAuth
client when calling the Demo Service (`https://local.dev.swedenconnect.se:16995`).

**Example authorization request for API access:**

```
GET /realms/orgiam/protocol/openid-connect/auth
  ?client_id=https://local.dev.swedenconnect.se:16990
  &response_type=code
  &scope=5590026042%3Ademo%3Awrite
  &resource=https://local.dev.swedenconnect.se:16995
  &redirect_uri=https://local.dev.swedenconnect.se:16990/login/oauth2/code/*
  &state=...
```

Keycloak evaluates whether the user is entitled to the requested scope at token issuance time.
If the user does not hold a sufficient right on `demo` for organization `5590026042`,
the token request is denied and no token is issued.

The resulting access token will contain:

```json
{
  "aud": [
    "https://local.dev.swedenconnect.se:16995",
    "demo"
  ],
  "scope": "5590026042:demo:write",
  "organization_identifier": "5590026042",
  "https://id.oidc.se/claim/personalIdentityNumber": "196911292032",
  ...
}
```

The `personalIdentityNumber` claim is present for regular users because the `naturalPersonNumber`
scope is configured as a default scope on OAuth clients. For superuser sessions this claim
will be absent. The `organization_identifier` claim is always present and is extracted from
the granted scope by a dedicated protocol mapper.

The client passes this access token as a Bearer token to the resource server.

<a name="oauth-resource-servers"></a>

### 3.3. OAuth Resource Servers

An OAuth resource server receives Bearer access tokens from OAuth clients and must validate:

1. **Signature and expiry** — validate the token against Keycloak's JWKS endpoint:
   ```
   GET /realms/orgiam/protocol/openid-connect/certs
   ```

2. **Audience** — the `aud` claim is a multi-valued array (e.g.,
   `["https://local.dev.swedenconnect.se:16995", "demo"]`). Verify that the array contains the
   resource server's own client ID. The function identifier is also present in the array but
   does not need to be checked separately — it is already encoded in the scope.

3. **Scope entitlement** — for each protected endpoint, the resource server checks that the
   token's `scope` claim contains a matching entry of the form
   `{organization}:{function}:{required-right-or-higher}`.

   Example: a `PUT /{organization}/data` endpoint requires `:write`. The resource server
   checks that the token contains either `{org}:{function}:write` or `{org}:{function}:admin`
   in its scope claim. If not, it returns `HTTP 403 Forbidden`.

4. **Organization** — use the `organization_identifier` claim to identify which organization
   the token was issued for. This allows the resource server to enforce that the operation
   targets the correct organization without parsing the scope string.

Because Keycloak enforces entitlement at issuance time, the resource server can trust that
any scope present in the token was legitimately granted. The resource server does not need to
call back to Keycloak for authorization decisions on individual requests.

A resource server is registered as a client in Keycloak (Keycloak has no separate resource
server concept), but with no standard flow, no service account, and no Authorization Services.
It is a passive validator only.

---

For Keycloak setup instructions, see [Keycloak Setup](keycloak-setup.md).

For integrating your Spring Boot application, see the [IAM Integration Guide](iam-integration-guide.md).

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
