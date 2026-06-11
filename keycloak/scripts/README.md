![Sweden Connect](../../docs/images/sweden-connect.png)

# Keycloak Admin Scripts

Standalone scripts for configuring a Keycloak server via the Admin REST API.
No Docker, no `kcadm`, and no running containers required — only `curl` and `python3`.

These scripts are the standalone equivalent of the `compose/keycloak-scripts/` wrappers.
They target any reachable Keycloak instance directly, making them suitable for both
local development and remote or production deployments.

All scripts are idempotent and safe to re-run.

---

## Table of Contents

1. [**Prerequisites**](#prerequisites)
2. [**Common Options**](#common-options)
3. [**Scripts**](#scripts)

   3.1. [`bootstrap-realm.sh`](#bootstrap-realm) — Full realm bootstrap

   3.2. [`create-admin-user.sh`](#create-admin-user) — Create the initial superuser

   3.3. [`add-oidc-client.sh`](#add-oidc-client) — Register an OAuth/OIDC client

   3.4. [`add-resource-server.sh`](#add-resource-server) — Register a resource server

   3.5. [`set-client-functions.sh`](#set-client-functions) — Set `client_functions` on an existing client

   3.6. [`set-iam-admin-managed.sh`](#set-iam-admin-managed) — Mark a client as IAM-admin-managed

4. [**Typical Setup Sequence**](#typical-setup-sequence)

---

<a name="prerequisites"></a>
## 1. Prerequisites

| Requirement | Notes |
|---|---|
| `curl` | Used for all Admin REST API calls |
| `python3` | Used for JSON construction and parsing |
| Keycloak 26.x running | Scripts target the Admin REST API v2 (no legacy `/auth` path by default) |
| Keycloak provider JARs deployed | `bootstrap-realm.sh` will warn if any required JAR is missing; see below |

**Required provider JARs** — must be built from this repository and deployed to
Keycloak's providers directory before running `bootstrap-realm.sh`:

| Provider type | JAR |
|---|---|
| `oidc-sweden-claims-mapper` | `oidc-sweden-claims-plugin-*.jar` |
| `org-rights-mapper` | `org-rights-mapper-*.jar` |
| `scope-org-identifier-mapper` | `scope-org-identifier-mapper-*.jar` |
| `resource-audience-mapper` | `resource-aud-plugin-*.jar` |

The JARs are published to the project's artifact repository and should be fetched from
there and placed in Keycloak's `providers/` directory. After deployment, Keycloak must
be rebuilt (`kc.sh build`) for the new providers to be recognized.

The scripts will proceed even when JARs are missing, but affected mappers and Client Policy
executors will not be fully configured until the JARs are deployed and Keycloak has been
rebuilt.

---

<a name="common-options"></a>
## 2. Common Options

Every script accepts the following options in addition to its own specific parameters.
All options may be provided on the command line; missing required values are prompted
for interactively.

| Option | Required | Default | Description |
|---|---|---|---|
| `--url <url>` | Yes | prompt | Keycloak base URL, e.g. `https://keycloak.example.com` |
| `--base-path <path>` | No | *(empty)* | URL path prefix when Keycloak is configured with `--http-relative-path`, e.g. `/auth` |
| `--realm <realm>` | Yes | prompt | Target realm name |
| `--username <user>` | No | `admin` | Master realm admin username |
| `--password <pass>` | Yes | prompt (silent) | Master realm admin password |
| `--cacert <file>` | No | *(system CA)* | CA certificate file for TLS verification |
| `--insecure` | No | off | Skip TLS verification — for local development only |

---

<a name="scripts"></a>
## 3. Scripts

<a name="bootstrap-realm"></a>
### 3.1. bootstrap-realm.sh

Bootstraps a new Keycloak realm with the full base configuration required by the IAM
system, as described in `docs/keycloak-setup.md` sections 2.1–2.5 and 2.8b.

**What it sets up:**

- The realm (login settings, ACR-to-LoA mappings, Fine-Grained Admin Permissions)
- Top-level groups: `orgs` and `functions`
- Realm role: `superuser`
- User profile attribute: `personalIdentityNumber`
- Client scope: `https://id.oidc.se/scope/naturalPersonNumber` with the OIDC Sweden mapper
- Client scope: `phone` (built-in; created if missing, with `phone_number` mapper)
- Client Policy profile: `resource-function-profile` (contains the `resource-function-executor`)
- Client Policy: `resource-function-policy` (applies the profile to all confidential clients)

Does **not** create clients or users. All steps are idempotent — safe to re-run.

As a pre-flight step the script checks that the required provider JARs are deployed and
recognized by Keycloak. Missing providers are reported as warnings; the script continues
so that all realm-level steps are not blocked by an undeployed JAR.

**Usage:**

```bash
./keycloak/scripts/bootstrap-realm.sh [OPTIONS]
```

**Additional options:**

| Option | Required | Default | Description |
|---|---|---|---|
| `--display-name <name>` | No | *(realm name)* | Human-readable display name for the realm |

**Example:**

```bash
./keycloak/scripts/bootstrap-realm.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --display-name "Organizations and Users IAM"
```

**Example — local development with self-signed certificate:**

```bash
./keycloak/scripts/bootstrap-realm.sh \
    --url https://local.dev.swedenconnect.se:17000 \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --cacert compose/config/keycloak/certs/ca.crt
```

---

<a name="create-admin-user"></a>
### 3.2. create-admin-user.sh

Creates a user in the target realm and assigns the `superuser` realm role. Run this
immediately after `bootstrap-realm.sh` to create the initial administrator account
required to log in to the IAM admin application.

Re-running the script against an existing user resets the password to the supplied value.

**Prerequisites:** The realm must already be bootstrapped (`bootstrap-realm.sh`), as the
`superuser` role must exist before it can be assigned.

**Usage:**

```bash
./keycloak/scripts/create-admin-user.sh [OPTIONS]
```

**Additional options:**

| Option | Required | Description |
|---|---|---|
| `--new-username <user>` | Yes | Username for the new account |
| `--new-password <pass>` | Yes | Password for the new account (prompted silently if omitted) |
| `--email <email>` | No | Email address for the new account |

**Example:**

```bash
./keycloak/scripts/create-admin-user.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --new-username diggadmin \
    --new-password changeme \
    --email admin@example.com
```

---

<a name="add-oidc-client"></a>
### 3.3. add-oidc-client.sh

Registers an OAuth/OIDC client in the target realm. An OIDC client is an application that
authenticates users via the authorization code flow and/or obtains access tokens to call
downstream APIs on behalf of users.

**What it sets up:**

- The client with `private_key_jwt` authentication and Authorization Services enabled
- The `org-rights-mapper` protocol mapper (ID token and/or access token, configurable)
- The `scope-org-identifier-mapper` on the access token
- The `resource-audience-mapper` on the access token
- Optional client scopes: `https://id.oidc.se/scope/naturalPersonNumber` and `phone`
- Service account with `realm-management` roles (if `--service-account` is passed)

A re-run against an existing client overwrites `rootUrl`, `redirectUris`, the
`iam_admin_managed` attribute, and the JWKS URL with the values from the current
invocation. Redirect URI patterns added manually in the Keycloak UI will be removed on
re-run.

`{org}:{function}:{right}` scopes and their Authorization Services policies are **not**
created by this script — they are managed automatically by the IAM admin application when
functions are attached to organizations.

**Prerequisites:**

- The realm must already be bootstrapped (`bootstrap-realm.sh`).
- The `org-rights-mapper`, `scope-org-identifier-mapper`, and `resource-audience-mapper`
  JARs must be deployed in Keycloak's providers directory.
- The application must be running and its JWKS endpoint reachable before the first token
  request is made.

**Usage:**

```bash
./keycloak/scripts/add-oidc-client.sh [OPTIONS]
```

**Additional options:**

| Option | Required | Default | Description |
|---|---|---|---|
| `--client-id <id>` | Yes | prompt | OAuth2 `client_id` — typically the application's base URL |
| `--name <name>` | No | | Display name shown in the Keycloak admin console |
| `--redirect-uri <pattern>` | Yes | prompt | Redirect URI pattern. May be repeated (see note below). |
| `--jwks-url <url>` | No | `{client-id}/jwks` | JWKS endpoint for `private_key_jwt` client authentication |
| `--service-account` | No | off | Enable service account and assign `realm-management` roles |
| `--no-org-rights-id-token` | No | *(included)* | Exclude `org_rights` from the ID token |
| `--no-org-rights-access-token` | No | *(included)* | Exclude `org_rights` from the access token |

> **Multiple redirect URIs:** `--redirect-uri` may be repeated to register more than one
> allowed redirect URI pattern. This is necessary when the application uses different
> callback base paths for OIDC login and OAuth2 API flows — for example
> `/login/oauth2/code/*` for OIDC and `/callback/oauth2/code/*` for OAuth2 client flows.
> A plain OIDC relying party typically needs only a single `--redirect-uri`.

**Example — standard OIDC/OAuth client:**

```bash
./keycloak/scripts/add-oidc-client.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --name "My App" \
    --redirect-uri '/login/oauth2/code/*'
```

**Example — IAM admin application (needs service account for Keycloak Admin API access):**

```bash
./keycloak/scripts/add-oidc-client.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://iam.example.com \
    --name "IAM Admin Application" \
    --redirect-uri '/login/oauth2/code/*' \
    --service-account
```

**Example — client where `org_rights` is only needed in the ID token (not the access token):**

```bash
./keycloak/scripts/add-oidc-client.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --redirect-uri '/login/oauth2/code/*' \
    --no-org-rights-access-token
```

---

<a name="add-resource-server"></a>
### 3.4. add-resource-server.sh

Registers a passive OAuth resource server in the target realm. A resource server is an
API that receives and validates Bearer access tokens but never initiates authentication
or token flows itself. It is registered as a client solely so that access tokens can
carry it as the `aud` claim via the OAuth2 `resource` parameter (RFC 8707).

The client is created with all flows disabled, client authentication off, no service
account, no Authorization Services, and no protocol mappers.

**Prerequisites:** The realm must already be bootstrapped (`bootstrap-realm.sh`).

**Usage:**

```bash
./keycloak/scripts/add-resource-server.sh [OPTIONS]
```

**Additional options:**

| Option | Required | Description |
|---|---|---|
| `--client-id <id>` | Yes | OAuth2 `client_id` for the resource server — typically its base URL |
| `--name <name>` | No | Display name shown in the Keycloak admin console |
| `--functions <list>` | No | Comma-separated list of functions to set as the `client_functions` attribute (e.g. `demo,walletreg`). If omitted, the resource server is treated as function-universal. |

**The `client_functions` attribute:**

When `--functions` is supplied, the value is stored as the `client_functions` attribute
on the client. The `resource-aud-plugin` validates at token issuance time that the
function extracted from the requested `{org}:{function}:{right}` scope matches one of
the listed functions. If the attribute is absent or empty, any function is accepted.

**Example:**

```bash
./keycloak/scripts/add-resource-server.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://api.example.com \
    --name "My API" \
    --functions demo
```

---

<a name="set-client-functions"></a>
### 3.5. set-client-functions.sh

Sets the `client_functions` attribute on an existing Keycloak client. Use this when the
resource server was registered without the `--functions` option (e.g. via
`add-resource-server.sh`) and the functions need to be assigned or updated later.

The attribute is a comma-separated list of function identifiers. The `resource-aud-plugin`
validates at token issuance time that the function extracted from the requested scope
matches one of the listed functions. Setting the attribute to an empty string effectively
removes the restriction and makes the resource server function-universal.

**Usage:**

```bash
./keycloak/scripts/set-client-functions.sh [OPTIONS]
```

**Additional options:**

| Option | Required | Description |
|---|---|---|
| `--client-id <id>` | Yes | OAuth2 `client_id` of the target client |
| `--functions <list>` | Yes | Comma-separated list of functions (e.g. `demo,walletreg`) |

**Example:**

```bash
./keycloak/scripts/set-client-functions.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://api.example.com \
    --functions demo,walletreg
```

---

<a name="set-iam-admin-managed"></a>
### 3.6. set-iam-admin-managed.sh

Sets the `iam_admin_managed=true` custom attribute on an existing Keycloak client.

The IAM admin application uses this attribute to discover which clients require
Authorization Services policies and permissions to be created or deleted when a function
is attached to or detached from an organization.

Apply this to every **OAuth client** that may request `{org}:{function}:{right}` scopes
on behalf of users — typically OIDC relying parties and OAuth clients that call downstream
APIs. Do **not** apply it to passive resource servers, which only receive and validate
access tokens but never request them.

> **Note:** `add-oidc-client.sh` sets `iam_admin_managed=true` automatically. This script
> is only needed for clients that were registered without `add-oidc-client.sh`, or for
> updating clients registered by other means.

**Usage:**

```bash
./keycloak/scripts/set-iam-admin-managed.sh [OPTIONS]
```

**Additional options:**

| Option | Required | Description |
|---|---|---|
| `--client-id <id>` | Yes | OAuth2 `client_id` of the target client |

**Example:**

```bash
./keycloak/scripts/set-iam-admin-managed.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com
```

---

<a name="typical-setup-sequence"></a>
## 4. Typical Setup Sequence

The following sequence sets up a complete environment from scratch. All scripts target
the same Keycloak instance; replace `https://keycloak.example.com` and credentials with
your actual values.

**Step 1 — Bootstrap the realm:**

```bash
./keycloak/scripts/bootstrap-realm.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --display-name "Organizations and Users IAM"
```

**Step 2 — Create the initial superuser account:**

```bash
./keycloak/scripts/create-admin-user.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --new-username diggadmin \
    --new-password changeme
```

**Step 3 — Register the IAM admin application client:**

```bash
./keycloak/scripts/add-oidc-client.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://iam.example.com \
    --name "IAM Admin Application" \
    --redirect-uri '/login/oauth2/code/*' \
    --service-account
```

**Step 4 — Register any additional OIDC/OAuth clients:**

```bash
./keycloak/scripts/add-oidc-client.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --name "My App" \
    --redirect-uri '/login/oauth2/code/*' \
    --no-org-rights-access-token
```

**Step 5 — Register any resource servers:**

```bash
./keycloak/scripts/add-resource-server.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://api.example.com \
    --name "My API" \
    --functions demo
```

Organizations and functions are created and managed by the IAM admin application after
it starts up. See [docs/keycloak-setup.md](../../docs/keycloak-setup.md) for the full
configuration reference.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
