# Keycloak Scripts

Utility scripts for managing Keycloak realms via the Docker Compose setup.

Each script in this directory is a thin wrapper that runs the corresponding inner script
inside the `keycloak-setup` Docker Compose service. The inner scripts live in
`compose/config/keycloak/scripts/` and are mounted into the container at `/scripts`.

Scripts can be run from anywhere — they resolve the `compose/` directory automatically.
The Docker Compose stack does not need to be fully running, but the `keycloak` service
must be up and reachable on the internal network.

---

## bootstrap-realm.sh

Bootstraps a new Keycloak realm with the base configuration required by the IAM admin
application, as described in `docs/keycloak-setup.md` sections 4.1–4.5.

Sets up:
- The realm (login settings and ACR-to-LoA mappings)
- Top-level groups: `orgs` and `functions`
- Realm role: `superuser`
- User profile attribute: `personalIdentityNumber`
- Client scope: `https://id.oidc.se/scope/naturalPersonNumber` with the OIDC Sweden mapper
- Client scope: `phone` (built-in; created if missing)

As a pre-flight step the script also verifies that the three required provider JARs are
deployed and recognized by Keycloak:

| Provider type                  | JAR                                        |
|--------------------------------|--------------------------------------------|
| `oidc-sweden-claims-mapper`    | `oidc-sweden-claims-plugin-*.jar`          |
| `org-rights-mapper`            | `org-rights-mapper-*.jar`                  |
| `scope-org-identifier-mapper`  | `scope-org-identifier-mapper-*.jar`        |

Missing providers are reported as warnings and the script continues, but the affected
mappers will not be fully configured until the JARs are deployed and Keycloak is rebuilt
(`kc.sh build`).

Does **not** set up clients or users. All steps are idempotent — safe to re-run.

**Prerequisites:**

The `oidc-sweden-claims-plugin` JAR must be deployed in Keycloak's providers directory
before running this script. If it is not deployed, the script will complete but warn that
the mapper could not be added to the `naturalPersonNumber` scope. Use
`compose/config/keycloak/install-keycloak-plugins.sh` to install all required JARs.

**Usage:**

```bash
./compose/keycloak-scripts/bootstrap-realm.sh \
    --realm <realm> \
    --username <username> \
    --password <password> \
    [--display-name <name>]
```

**Options:**

| Option           | Required | Description                                                       |
|------------------|----------|-------------------------------------------------------------------|
| `--realm`        | Yes      | Keycloak realm name to create                                     |
| `--username`     | Yes      | Keycloak master realm admin username                              |
| `--password`     | Yes      | Keycloak master realm admin password                              |
| `--display-name` | No       | Human-readable display name (defaults to the realm name)          |

**Example:**

```bash
./compose/keycloak-scripts/bootstrap-realm.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --display-name "Organizations and Users IAM"
```

---

## set-iam-admin-managed.sh

Sets the custom client attribute `iam_admin_managed=true` on a Keycloak client.

The IAM admin application uses this attribute to discover which clients require
Authorization Services policies and permissions to be created or deleted when a function
is attached to or detached from an organization.

This script should be run against every **OAuth client** that may request
`{org}:{function}:{right}` scopes on behalf of users — typically OIDC relying parties
and OAuth clients that call downstream APIs protected by the authorization model. It
should **not** be applied to passive resource servers, which only receive and validate
access tokens but never request them.

**Usage:**

```bash
./compose/keycloak-scripts/set-iam-admin-managed.sh \
    --realm <realm> \
    --client-id <clientId> \
    --username <username> \
    --password <password>
```

**Options:**

| Option        | Required | Description                                           |
|---------------|----------|-------------------------------------------------------|
| `--realm`     | Yes      | Keycloak realm name                                   |
| `--client-id` | Yes      | The OAuth2 `client_id` string (not the Keycloak UUID) |
| `--username`  | Yes      | Keycloak admin username                               |
| `--password`  | Yes      | Keycloak admin password                               |

**Example:**

```bash
./compose/keycloak-scripts/set-iam-admin-managed.sh \
    --realm orgiam \
    --client-id https://my-app.example.com \
    --username admin \
    --password secret
```

Run the script once for each client that should be managed by the IAM admin application.

---

## add-oidc-client.sh

Registers an OAuth/OIDC client in a Keycloak realm, as described in
`docs/keycloak-setup.md` sections 3.1, 3.2, 4.6, 4.7 and 4.9.

An OAuth/OIDC client is an application that authenticates users via the standard
authorization code flow and/or obtains access tokens to call downstream APIs on behalf
of users.

Sets up:
- The client with `private_key_jwt` authentication and Authorization Services enabled
- The `org_rights` protocol mapper on ID token and/or access token
- The `scope-org-identifier-mapper` on the access token (always)
- Optional client scopes: `https://id.oidc.se/scope/naturalPersonNumber` and `phone`
- Service account with `realm-management` roles (if `--service-account` is passed)

All steps are idempotent — safe to re-run against an existing client. A re-run overwrites
`rootUrl`, `redirectUris`, the `iam_admin_managed` attribute, and (if `--name` was passed)
the display name with the values from the current invocation. Redirect URI patterns added
manually in the Keycloak UI will be removed on re-run.

**Prerequisites:**

- The realm must already be bootstrapped (`bootstrap-realm.sh`).
- The `org-rights-mapper` and `scope-org-identifier-mapper` JARs must be deployed in
  Keycloak's providers directory.
- The application must be running and its JWKS endpoint reachable before the first token
  request is made.

`{org}:{function}:{right}` scopes and their Authorization Services policies are **not**
created by this script — they are managed automatically by the IAM admin application when
functions are attached to organizations (see `docs/keycloak-setup.md` section 4.9).

**Usage:**

```bash
./compose/keycloak-scripts/add-oidc-client.sh [OPTIONS]
```

All options may be omitted — the script will prompt interactively for any required value
that is missing.

**Options:**

| Option                        | Required | Default               | Description                                                                                                                            |
|-------------------------------|----------|-----------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `--realm`                     | Yes      | prompt                | Keycloak realm name                                                                                                                    |
| `--username`                  | Yes      | prompt                | Keycloak admin username                                                                                                                |
| `--password`                  | Yes      | prompt (silent)       | Keycloak admin password                                                                                                                |
| `--client-id`                 | Yes      | prompt                | The OAuth2 `client_id` (typically the application's base URL)                                                                          |
| `--name`                      | No       |                       | Display name shown in the Keycloak admin console                                                                                       |
| `--redirect-uri`              | Yes      | prompt                | Redirect URI pattern. May be specified multiple times (see note below).                                                                |
| `--jwks-url`                  | No       | `{client-id}/jwks`    | JWKS endpoint for `private_key_jwt` client authentication                                                                              |
| `--service-account`           | No       | off                   | Enable if the application needs to read or write Keycloak realm information directly via the Admin REST API. Not required for ordinary clients. |
| `--no-org-rights-id-token`    | No       | included              | Exclude the `org_rights` claim from the ID token                                                                                       |
| `--no-org-rights-access-token`| No       | included              | Exclude the `org_rights` claim from the access token                                                                                   |

**Example — standard OIDC/OAuth client:**

```bash
./compose/keycloak-scripts/add-oidc-client.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --name "My App" \
    --redirect-uri '/login/oauth2/code/*'
```

**Example — client with service account (needs Keycloak Admin API access):**

```bash
./compose/keycloak-scripts/add-oidc-client.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --redirect-uri '/login/oauth2/code/*' \
    --service-account
```

**Example — combined OIDC login and OAuth2 client flows (separate callback base paths):**

```bash
./compose/keycloak-scripts/add-oidc-client.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --redirect-uri '/login/oauth2/code/*' \
    --redirect-uri '/callback/oauth2/code/*'
```

> **Multiple redirect URIs:** `--redirect-uri` may be repeated to register more than
> one allowed redirect URI pattern on the Keycloak client. This is only necessary when
> the application combines OIDC login and OAuth2 client flows that use different
> callback base paths — for example `/login/oauth2/code/*` for OIDC and
> `/callback/oauth2/code/*` for OAuth2 API token flows. A plain OIDC relying party
> needs only a single `--redirect-uri`.

---

## add-resource-server.sh

Registers a passive OAuth resource server in a Keycloak realm, as described in
`docs/keycloak-setup.md` sections 3.3 and 4.8.

A resource server is an API that receives and validates Bearer access tokens issued by
Keycloak, but never initiates authentication or token flows itself. It is registered as
a client solely so that access tokens can carry it as the `aud` claim via the OAuth2
`resource` parameter (RFC 8707).

The client is created with all flows disabled, client authentication off, no service
account, no Authorization Services, and no protocol mappers.

All steps are idempotent — safe to re-run against an existing client.

**Prerequisites:**

The realm must already be bootstrapped (`bootstrap-realm.sh`).

**Usage:**

```bash
./compose/keycloak-scripts/add-resource-server.sh [OPTIONS]
```

All options may be omitted — the script will prompt interactively for any required value
that is missing.

**Options:**

| Option        | Required | Description                                                                 |
|---------------|----------|-----------------------------------------------------------------------------|
| `--realm`     | Yes      | Keycloak realm name                                                         |
| `--username`  | Yes      | Keycloak admin username                                                     |
| `--password`  | Yes      | Keycloak admin password                                                     |
| `--client-id` | Yes      | The OAuth2 `client_id` for the resource server (typically its base URL)     |
| `--name`      | No       | Display name shown in the Keycloak admin console                            |

**Example:**

```bash
./compose/keycloak-scripts/add-resource-server.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://api.example.com \
    --name "My API"
```

---

## create-admin-user.sh

Creates a user in a Keycloak realm and assigns the `superuser` realm role.

Run this immediately after `bootstrap-realm.sh` to create the initial administrator
account required to log in to the IAM admin application.

All steps are idempotent — safe to re-run. Re-running will reset the user's password
to the supplied value.

**Prerequisites:**

The realm must already be bootstrapped (`bootstrap-realm.sh`), as the `superuser` role
must exist before it can be assigned.

**Usage:**

```bash
./compose/keycloak-scripts/create-admin-user.sh \
    --realm <realm> \
    --username <admin-username> \
    --password <admin-password> \
    --new-username <new-username> \
    --new-password <new-password> \
    [--email <email>]
```

**Options:**

| Option           | Required | Description                              |
|------------------|----------|------------------------------------------|
| `--realm`        | Yes      | Keycloak realm name                      |
| `--username`     | Yes      | Keycloak master realm admin username     |
| `--password`     | Yes      | Keycloak master realm admin password     |
| `--new-username` | Yes      | Username for the new account             |
| `--new-password` | Yes      | Password for the new account             |
| `--email`        | No       | Email address for the new account        |

**Example:**

```bash
./compose/keycloak-scripts/create-admin-user.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --new-username diggadmin \
    --new-password changeme \
    --email admin@example.com
```
