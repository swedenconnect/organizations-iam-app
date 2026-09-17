![Sweden Connect](https://docs.swedenconnect.se/organizations-iam-app/images/sweden-connect.png)

# Keycloak Admin Scripts

Scripts for configuring a Keycloak server via the Admin REST API. No Docker, no `kcadm`, and
no running containers required, only `curl` and `python3`.

This is the single implementation of each of these scripts. They talk to any reachable
Keycloak, so the same scripts serve the local Docker Compose environment, a shared test
server, and a production installation.

For the compose environment there are wrappers in
[`compose/keycloak-scripts/`](../../compose/keycloak-scripts/README.md) that call these
scripts with the local URL and CA certificate already filled in. They are a shorthand,
nothing more; anything the wrappers can do can be done by calling these scripts with
`--url` and `--cacert`.

All scripts are idempotent and safe to re-run.

The scripts are also published as a ZIP, so a Keycloak host does not need a checkout of the
repository to be set up. See [The scripts ZIP](../README.md#scripts-distribution).

---

## Table of Contents

1. [**Prerequisites**](#prerequisites)
2. [**Common Options**](#common-options)
3. [**Scripts**](#scripts)

   3.1. [`bootstrap-realm.sh`](#bootstrap-realm): Full realm bootstrap

   3.2. [`create-admin-user.sh`](#create-admin-user): Create the initial superuser

   3.3. [`add-iam-admin-app.sh`](#add-iam-admin-app): Register the IAM admin application

   3.4. [`add-oidc-client.sh`](#add-oidc-client): Register an OAuth/OIDC client

   3.5. [`add-resource-server.sh`](#add-resource-server): Register a resource server

   3.6. [`add-function.sh`](#add-function): Add a function to an existing client

   3.7. [`set-client-functions.sh`](#set-client-functions): Set `client_functions` on an existing client

   3.8. [`set-iam-admin-managed.sh`](#set-iam-admin-managed): Mark a client as IAM-admin-managed

   3.9. [`set-iam-admin-resource-server.sh`](#set-iam-admin-resource-server): Mark a client as a resource server

   3.10. [`get-keycloak-plugins.sh`](#get-keycloak-plugins): Fetch the provider JARs for a version

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

**Required provider JARs**: see
[Provider JARs](../README.md#provider-jars) for the full list: which provider type each JAR
registers, and whether it is built from this repository or fetched from Maven Central. Most
are built here; `oidc-sweden-claims-plugin` is an external artifact at a pinned version.

These scripts configure any reachable Keycloak, so they cannot deploy the providers
themselves. Before running `bootstrap-realm.sh`, the JARs must be in that server's
`providers/` directory and Keycloak must have been rebuilt (`kc.sh build`) and restarted. A
provider that has not been built into the distribution is invisible to Keycloak, however the
JAR got there. See
[Deploying to Keycloak](../README.md#deploying-to-keycloak) for the steps.

To obtain the JARs for a given version of this project, use
[`get-keycloak-plugins.sh`](#get-keycloak-plugins), which fetches the distribution ZIP and
unpacks it.

The scripts will proceed even when JARs are missing, but affected mappers and Client Policy
executors will not be fully configured until the JARs are deployed and Keycloak has been
rebuilt. `bootstrap-realm.sh` warns for each provider type it cannot find, and continues.

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
| `--insecure` | No | off | Skip TLS verification, for local development only |

---

<a name="scripts"></a>
## 3. Scripts

<a name="bootstrap-realm"></a>
### 3.1. bootstrap-realm.sh

Bootstraps a new Keycloak realm with the full base configuration required by the IAM
system, as described in
[Keycloak Setup](https://docs.swedenconnect.se/organizations-iam-app/keycloak-setup.html)
sections 2.1–2.5 and 2.8b.

**What it sets up:**

- The realm (login settings, ACR-to-LoA mappings, Fine-Grained Admin Permissions)
- Top-level groups: `orgs` and `functions`
- Realm role: `superuser`
- Client scopes, each with its protocol mapper emitting into the ID token, the access token
  and the UserInfo response:
    - `https://id.oidc.se/scope/naturalPersonInfo` (`natural-person-info-mapper`)
    - `https://id.oidc.se/scope/naturalPersonNumber` (`oidc-sweden-claims-mapper`)
    - `https://id.oidc.se/scope/naturalPersonOrgId` (`oidc-sweden-claims-mapper`)
- User profile attribute groups: `oidc-sweden-natural-person` and `oidc-sweden-org-id`
- User profile attributes: `middleName`, `birthdate`, `personalIdentityNumber`,
  `coordinationNumber`, `coordinationNumberLevel`, `previousCoordinationNumber`,
  `orgAffiliation`, `orgName`, `orgNumber` and `orgUnit`
- Client scope: `phone` (built-in; created if missing, with `phone_number` mapper)
- Client Policy profile: `resource-function-profile` (contains the `resource-function-executor`)
- Client Policy: `resource-function-policy` (applies the profile to all confidential clients)

The `oidc-sweden-claims-plugin` JAR registers the two protocol mapper types and nothing
else, so the scopes, attribute groups and attributes above are all created by this script.
Only what is missing is added: an attribute already present keeps its definition, so a realm
bootstrapped by an earlier version of the script keeps the scope selector that version put
on `personalIdentityNumber`.

Does **not** create clients or users. All steps are idempotent and safe to re-run.

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

**Example, the local compose Keycloak with its self-signed certificate:**

```bash
./keycloak/scripts/bootstrap-realm.sh \
    --url https://local.dev.swedenconnect.se:17000 \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --cacert compose/config/common/tls.crt
```

`compose/config/common/tls.crt` is the certificate the compose Keycloak serves. The wrapper
`./compose/keycloak-scripts/bootstrap-realm.sh` runs exactly this command, so the two are
interchangeable.

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

<a name="add-iam-admin-app"></a>
### 3.3. add-iam-admin-app.sh

Registers the IAM Admin application. Use this rather than `add-oidc-client.sh`, which gives
the application only one of the three roles it needs.

The IAM Admin application is not an ordinary OIDC client:

| Role | Marker | What it means |
|---|---|---|
| OIDC client | `iam_admin_oidc_client=true` | It logs administrators in and obtains org-scoped tokens |
| Resource server | `iam_admin_resource_server=true` | It exposes `/iam-api`, and other clients name it in the OAuth2 `resource` parameter |
| All functions | `iam_admin_all_functions=true` | Its API serves every function, including the ones not created yet |

The script delegates the OIDC client registration to `add-oidc-client.sh` and then sets the
other two markers, seeding `client_functions` with every function that exists at the time.

The application appends each new function to `client_functions` as it is created. See
[Keycloak Setup](https://docs.swedenconnect.se/organizations-iam-app/keycloak-setup.html#managed-clients-and-reconciliation) for why both
the marker and the attribute are kept.

A service account with `realm-management` roles is always created. The application administers
the realm through the Keycloak Admin API and cannot work without one, so there is no flag to
turn it off.

All steps are idempotent. Re-run the script to bring an application registered with
`add-oidc-client.sh` alone up to the full three-role shape.

**Prerequisites:**

The realm must already be bootstrapped (`bootstrap-realm.sh`), because the script reads the
`/functions` group to seed the attribute.

**Usage:**

```bash
./keycloak/scripts/add-iam-admin-app.sh [OPTIONS]
```

**Additional options:**

| Option | Required | Description |
|---|---|---|
| `--client-id <id>` | Yes | OAuth2 `client_id`, the application's base URL |
| `--name <name>` | No | Display name in the Keycloak admin UI (default: `IAM Admin`) |
| `--redirect-uri <pattern>` | No | Redirect URI pattern, repeatable (default: `/login/oauth2/code/*`) |
| `--root-url <url>` | No | Client root URL (default: the client ID) |
| `--jwks-url <url>` | No | JWKS endpoint URL (default: `<client-id>/jwks`) |

**Example:**

```bash
./keycloak/scripts/add-iam-admin-app.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://iam.example.com \
    --name "IAM Admin Application"
```

---

<a name="add-oidc-client"></a>
### 3.4. add-oidc-client.sh

Registers an OAuth/OIDC client in the target realm. An OIDC client is an application that
authenticates users via the authorization code flow and/or obtains access tokens to call
downstream APIs on behalf of users.

**What it sets up:**

- The client with `private_key_jwt` authentication and Authorization Services enabled
- The `org-rights-mapper` protocol mapper (ID token and/or access token, configurable)
- The `scope-org-identifier-mapper` on the access token
- The `resource-audience-mapper` on the access token
- Optional client scopes: `https://id.oidc.se/scope/naturalPersonNumber`,
  `https://id.oidc.se/scope/naturalPersonOrgId` and `phone`
- Service account with `realm-management` roles (if `--service-account` is passed)

A re-run against an existing client overwrites `redirectUris`, the iam-admin marker
attributes, and the JWKS URL with the values from the current invocation. Redirect URI
patterns added manually in the Keycloak UI will be removed on re-run. `rootUrl` is only
written when the invocation supplies one (see below); otherwise a root URL already on the
client is left untouched.

`{org}:{function}:{right}` scopes and their Authorization Services policies are **not**
created by this script. They are managed by the IAM admin application when
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
| `--client-id <id>` | Yes | prompt | OAuth2 `client_id`, typically the application's base URL |
| `--name <name>` | No | | Display name shown in the Keycloak admin console |
| `--redirect-uri <pattern>` | Yes | prompt | Redirect URI pattern. May be repeated (see note below). |
| `--root-url <url>` | No | prompt when a redirect URI is a path | Client root URL. Written only when supplied (see note below). |
| `--jwks-url <url>` | No | `{client-id}/jwks` | JWKS endpoint for `private_key_jwt` client authentication |
| `--service-account` | No | off | Enable service account and assign `realm-management` roles |
| `--no-org-rights-id-token` | No | *(included)* | Exclude `org_rights` from the ID token |
| `--no-org-rights-access-token` | No | *(included)* | Exclude `org_rights` from the access token |

> **Multiple redirect URIs:** `--redirect-uri` may be repeated to register more than one
> allowed redirect URI pattern. This is necessary when the application uses different
> callback base paths for OIDC login and OAuth2 API flows, for example
> `/login/oauth2/code/*` for OIDC and `/callback/oauth2/code/*` for OAuth2 client flows.
> A plain OIDC relying party typically needs only a single `--redirect-uri`.

> **The root URL follows the redirect URIs.** Keycloak resolves a redirect URI given as a
> path against the client's root URL, and ignores the root URL when the redirect URIs are
> complete. The script therefore writes a root URL only when the invocation supplies one:
> if at least one `--redirect-uri` starts with `/` and `--root-url` was not given, it asks
> for the root URL once, offering the client ID as the default. If every redirect URI is a
> complete URI and `--root-url` was not given, nothing is asked and no root URL is sent, so
> whatever the client already has stays as it is. `--root-url` is always honoured without a
> prompt, whatever form the redirect URIs take.

**Example: standard OIDC/OAuth client (complete redirect URI, no root URL involved):**

```bash
./keycloak/scripts/add-oidc-client.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --name "My App" \
    --redirect-uri 'https://my-app.example.com/login/oauth2/code/*'
```

**Example: redirect URI given as a path (root URL supplied so nothing is prompted for):**

```bash
./keycloak/scripts/add-oidc-client.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --name "My App" \
    --redirect-uri '/login/oauth2/code/*' \
    --root-url https://my-app.example.com
```

**Example: IAM admin application (needs service account for Keycloak Admin API access):**

```bash
./keycloak/scripts/add-oidc-client.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://iam.example.com \
    --name "IAM Admin Application" \
    --redirect-uri 'https://iam.example.com/login/oauth2/code/*' \
    --service-account
```

**Example: client where `org_rights` is only needed in the ID token (not the access token):**

```bash
./keycloak/scripts/add-oidc-client.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --redirect-uri 'https://my-app.example.com/login/oauth2/code/*' \
    --no-org-rights-access-token
```

---

<a name="add-resource-server"></a>
### 3.5. add-resource-server.sh

Registers a passive OAuth resource server in the target realm. A resource server is an
API that receives and validates Bearer access tokens but never initiates authentication
or token flows itself. It is registered as a client solely so that access tokens can
carry it as the `aud` claim via the OAuth2 `resource` parameter (RFC 8707).

The client is created with all flows disabled, client authentication off, no service
account, no Authorization Services, and no protocol mappers.

> **Never run this script against a client that is already an OIDC client.** Its sync step
> always runs, whether it created the client or found an existing one, and that step
> unconditionally sets `publicClient=true` and turns off the standard flow, the implicit
> flow, direct access grants, the service account and Authorization Services. Applied to an
> OIDC client it strips exactly what makes that client work, and Keycloak discards the
> client's policies and permissions along with Authorization Services. Running
> `add-oidc-client.sh` afterwards does not repair it, because that script writes
> `publicClient` only when it creates a client.
>
> To give an existing client the resource server role, use
> [`set-iam-admin-resource-server.sh`](#set-iam-admin-resource-server), which sets the
> marker and changes nothing else. To build a client that holds both roles, see
> [Registering a Client, Section 4.4](https://docs.swedenconnect.se/organizations-iam-app/registering-a-client.html#a-client-with-both-roles).

**Prerequisites:** The realm must already be bootstrapped (`bootstrap-realm.sh`).

**Usage:**

```bash
./keycloak/scripts/add-resource-server.sh [OPTIONS]
```

**Additional options:**

| Option | Required | Description |
|---|---|---|
| `--client-id <id>` | Yes | OAuth2 `client_id` for the resource server, typically its base URL |
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

<a name="add-function"></a>
### 3.6. add-function.sh

Adds one or more functions to the `client_functions` attribute of an existing client, keeping
the functions it already declares.

This is the difference from `set-client-functions.sh`, which replaces the whole list. Use this
script when a service that is already registered gains a function: the functions it had are
kept, and only the new ones are appended.

Every function given must already exist as a group under `/functions` in the realm; the script
refuses one that does not, rather than writing a value that would never match anything. Create
the function in the IAM Admin application first.

A client marked `iam_admin_all_functions=true` already handles every function, so the script
reports that and makes no change.

All steps are idempotent. A function the client already declares is left alone.

**Usage:**

```bash
./keycloak/scripts/add-function.sh [OPTIONS]
```

**Additional options:**

| Option | Required | Description |
|---|---|---|
| `--client-id <id>` | Yes | OAuth2 `client_id` of the target client |
| `--function <function>` | Yes* | Function to add; repeatable |
| `--functions <list>` | Yes* | Comma-separated list of functions to add |

\* At least one of `--function` and `--functions` is required. The two may be combined, and the
lists are merged.

**Example:**

```bash
./keycloak/scripts/add-function.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --function walletreg
```

**After running:** an OIDC client needs the scopes, policies and permissions for the new
functions. Trigger the reconciliation:

```bash
curl -X POST https://iam.example.com/api/clients/reconcile
```

A resource server holds no artifacts and needs no reconciliation.

---

<a name="set-client-functions"></a>
### 3.7. set-client-functions.sh

Sets the `client_functions` attribute on an existing Keycloak client. Use this when the
resource server was registered without the `--functions` option (e.g. via
`add-resource-server.sh`) and the functions need to be assigned or updated later.

The attribute is a comma-separated list of function identifiers. The `resource-aud-plugin`
validates at token issuance time that the function extracted from the requested scope
matches one of the listed functions. Setting the attribute to an empty string effectively
removes the restriction and makes the resource server function-universal.
> **Note:** The IAM admin application can set `client_functions` directly, through the
> **Functions** field on a managed client in the **Services** tab, and reconciles the client
> afterwards, so
> the artifacts for the newly declared functions are created without running this script. The
> script remains the route for bootstrap and non-interactive provisioning, and for resource
> servers, which the admin application does not manage.


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
### 3.8. set-iam-admin-managed.sh

Hands an existing Keycloak client the OIDC client role, by setting `iam_admin_managed=true`
(the IAM admin application administers this client) and `iam_admin_oidc_client=true` (it plays
the OIDC client role).

The application uses the role attribute to discover which clients require Authorization
Services policies and permissions to be created or deleted when a function is attached to or
detached from an organization.

Apply this to every **OAuth client** that may request `{org}:{function}:{right}` scopes
on behalf of users, typically OIDC relying parties and OAuth clients that call downstream
APIs. Do **not** apply it to passive resource servers, which only receive and validate
access tokens but never request them.

> **Note:** `add-oidc-client.sh` sets both attributes automatically. This script is only
> needed for clients that were registered without `add-oidc-client.sh`, or for updating clients
> registered by other means.

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

<a name="set-iam-admin-resource-server"></a>
### 3.9. set-iam-admin-resource-server.sh

Sets the `iam_admin_resource_server=true` custom attribute on an existing Keycloak client,
and changes nothing else about it.

The IAM admin application uses this attribute to discover which clients may be named in the
OAuth2 `resource` parameter and therefore appear in the `aud` claim. A resource server holds
no Authorization Services artifacts of its own.

The two roles are independent, so this script leaves `iam_admin_managed` as it is. Running it
against a managed OIDC client produces a client holding both markers, which is a supported
state. It does not touch `client_functions` either; that remains `set-client-functions.sh`.

> **Note:** The client must already exist. `add-resource-server.sh` is what creates one, and
> it forces the client into the audience-only shape: public, all flows disabled, no service
> account. Use this script instead when the client is already registered, and in particular
> when it is also an OIDC client whose settings must not be rewritten.

**Usage:**

```bash
./keycloak/scripts/set-iam-admin-resource-server.sh [OPTIONS]
```

**Additional options:**

| Option | Required | Description |
|---|---|---|
| `--client-id <id>` | Yes | OAuth2 `client_id` of the target client |

**Example:**

```bash
./keycloak/scripts/set-iam-admin-resource-server.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://api.example.com
```

---

<a name="get-keycloak-plugins"></a>
### 3.10. get-keycloak-plugins.sh

Fetches the Keycloak provider JARs for a given version of this project and unpacks them into a
directory, ready to be copied to a Keycloak host.

The JARs come from the distribution ZIP,
`se.swedenconnect.iam.keycloak:keycloak-plugin-distribution:<version>:zip:plugins`, which
defines the provider set of that release. See
[The distribution ZIP](../README.md#plugin-distribution).

Unlike every other script here, this one does not need a Keycloak and never talks to one. It
needs `mvn` and `unzip`.

**Where the ZIP comes from:**

| Version | Source |
|---|---|
| A release, for example `0.9.3` | Maven Central, or the local Maven repository if it is already cached there |
| A snapshot, for example `0.9.3-SNAPSHOT` | The local Maven repository only, since snapshots of this project are never published |

Given a snapshot that is not in the local Maven repository, the script stops and says so: the
answer is to build and install the project first, with `mvn -DskipTests install`.

**Usage:**

```bash
./keycloak/scripts/get-keycloak-plugins.sh [OPTIONS]
```

**Options:**

| Option | Required | Description |
|---|---|---|
| `--version <version>` | Yes | Version of this project to fetch, for example `0.9.3` or `0.9.3-SNAPSHOT` |
| `--output-dir <dir>` | Yes | Directory to unpack the JARs into. Created if missing |

Both are prompted for when missing, as with the other scripts.

**Example:**

```bash
./keycloak/scripts/get-keycloak-plugins.sh \
    --version 0.9.3 \
    --output-dir ./keycloak-plugins
```

The script finishes by stating what has to happen next on the Keycloak host: copy the JARs
into `providers/`, run `kc.sh build`, and restart Keycloak. Nothing it does affects a running
Keycloak by itself.

---

<a name="typical-setup-sequence"></a>
## 4. Typical Setup Sequence

The following sequence sets up a complete environment from scratch. All scripts target
the same Keycloak instance; replace `https://keycloak.example.com` and credentials with
your actual values.

**Step 1: Bootstrap the realm:**

```bash
./keycloak/scripts/bootstrap-realm.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --display-name "Organizations and Users IAM"
```

**Step 2: Create the initial superuser account:**

```bash
./keycloak/scripts/create-admin-user.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --new-username diggadmin \
    --new-password changeme
```

**Step 3: Register the IAM admin application:**

```bash
./keycloak/scripts/add-iam-admin-app.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://iam.example.com \
    --name "IAM Admin Application"
```

Not `add-oidc-client.sh`: the admin application is an OIDC client, a resource server, and a
client handling all functions, and only `add-iam-admin-app.sh` sets all three markers. See
[3.3](#add-iam-admin-app).

**Step 4: Register any additional OIDC/OAuth clients:**

```bash
./keycloak/scripts/add-oidc-client.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --name "My App" \
    --redirect-uri 'https://my-app.example.com/login/oauth2/code/*' \
    --no-org-rights-access-token
```

**Step 5: Register any resource servers:**

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

**Step 6: Give a client a function it gained later:**

```bash
./keycloak/scripts/add-function.sh \
    --url https://keycloak.example.com \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --function walletreg
```

The function must exist first, so this step comes after the function has been created in the
IAM admin application. Follow it with `POST /api/clients/reconcile` for an OIDC client; a
resource server needs nothing further. Use `set-client-functions.sh` instead when the whole
list is to be replaced rather than added to.

Organizations and functions are created and managed by the IAM admin application after
it starts up. See [Keycloak Setup](https://docs.swedenconnect.se/organizations-iam-app/keycloak-setup.html) for the full
configuration reference.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
