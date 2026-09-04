# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Install parent POMs and all upstream dependencies (needed when local repo is cold)
mvn install -N -DskipTests                                    # root parent
mvn install -N -DskipTests -f iam-security/pom.xml
mvn install -pl iam-commons,iam-security/iam-security-base,iam-security/iam-security-spring-boot-starter -DskipTests

# Compile and test a subset of modules
mvn compile test -pl iam-commons
mvn compile test -pl iam-security/iam-security-base,iam-security/iam-security-spring-boot-starter
mvn compile test -pl keycloak/org-rights-mapper,keycloak/resource-aud-plugin,keycloak/scope-org-identifier-mapper

# iam-admin-app backend: install frontend zip first, then test backend
mvn install -N -DskipTests -f iam-admin-app/pom.xml
mvn package -pl iam-admin-app/frontend -DskipTests
mvn install -pl iam-admin-app/frontend -DskipTests
mvn compile test -pl iam-admin-app/backend

# Run a single test class (example)
mvn test -pl keycloak/org-rights-mapper -Dtest=OrgRightsMapperTest

# Build a local Docker image for iam-admin-app (defaults to linux/amd64)
mvn package -pl iam-admin-app/frontend -DskipTests
mvn install -pl iam-admin-app/frontend -DskipTests
mvn package -pl iam-admin-app/backend -DskipTests jib:dockerBuild@local

# On Apple Silicon (arm64), override the architecture:
mvn package -pl iam-admin-app/backend -DskipTests jib:dockerBuild@local -Djib.local.architecture=arm64
# To avoid passing the flag every time, add this to ~/.m2/settings.xml:
#   <profiles>
#     <profile>
#       <id>apple-silicon</id>
#       <properties>
#         <jib.local.architecture>arm64</jib.local.architecture>
#       </properties>
#     </profile>
#   </profiles>
#   <activeProfiles>
#     <activeProfile>apple-silicon</activeProfile>
#   </activeProfiles>

# Build Keycloak plugin JARs and install into compose/config/keycloak/spi/
./compose/keycloak-scripts/install-keycloak-plugins.sh

# Start demo apps locally (require the local Spring profile)
./demo/scripts/start-demo-app.sh
./demo/scripts/start-demo-service.sh
```

## Local Development Environment

Local services run under the domain `local.dev.swedenconnect.se`. Add this to `/etc/hosts`:
```
127.0.0.1  local.dev.swedenconnect.se
```

Port assignments:
- `17000` — Keycloak (HTTPS)
- `17005` — IAM Admin Application
- `16990` — Demo App
- `16995` — Demo Service
- `16905` — PostgreSQL

Start the stack with Docker Compose:
```bash
docker compose -f compose/docker-compose.yml up -d keycloak
```

Bootstrap the Keycloak realm after first start — see `compose/README.md` for the full step-by-step.

## Module Architecture

This is a Maven multi-module project implementing a centralized IAM system for managing organizational rights in the Sweden Connect ecosystem. Applications use Keycloak as the identity provider and delegate all group/role management through this system.

### Module dependency graph

```
iam-commons
  └── iam-security-base          (uses iam-commons types; no Spring dep)
        └── iam-security-spring-boot-starter  (Spring Boot auto-config)
              ├── iam-admin-app/backend
              ├── demo/demo-app/backend
              └── demo/demo-service

keycloak/* (SPI plugins)        (uses iam-security-base; no Spring dep)
```

### Module roles

**`iam-commons`** — Shared value types (`OrganizationID`, `ClientID`, `OperationID`, `LocalizedString`, etc.) and base classes. No Spring dependency so it can be used in Keycloak plugins.

**`iam-security-base`** — Claims model (`OrgRightsClaim`, `OrgRightsClaim.Entry`) and Spring Security authority types (`OrganizationalAuthority`, `FunctionScopedAuthority`). Also no Spring dependency.

**`iam-security-spring-boot-starter`** — Auto-configuration for both OAuth2 clients and resource servers. Key beans (all `@ConditionalOnMissingBean`):
- `OrgRightsOidcUserService` — parses `org_rights` claim from ID tokens into authorities
- `OAuthClientContext` (session-scoped) — holds the current org/function for scope resolution
- `OrgScopedAuthorizationRequestResolver` — replaces `{org}` and `{function}` placeholders in authorization scopes at request time
- `OrgRightsScopeConverter` — converts `{org}:{function}:{right}` scopes from access tokens into Spring Security authorities for resource servers
- `PkiCredential` + `JWK` beans — wires `private_key_jwt` client authentication from `iam.security.client.credential`

**`keycloak/org-rights-mapper`** — Keycloak protocol mapper that reads group memberships under `/orgs/{orgId}/{functionId}/{right}` and emits the `org_rights` claim.

**`keycloak/scope-org-identifier-mapper`** — Reads the first org-scoped scope from the token request and emits an `organization_identifier` claim, so resource servers can identify the requesting org without parsing scopes.

**`keycloak/resource-aud-plugin`** — Validates RFC 8707 `resource` parameter against the `client_functions` attribute and sets the `aud` claim to `[client_id, function(s)]`.

**`iam-admin-app`** — Spring Boot web application (backend + React frontend). The frontend is built by the `frontend` module into a `dist` ZIP artifact, then unpacked into the backend's `static/` directory during `process-resources` phase via `maven-dependency-plugin`.

**`demo`** — Two example Spring Boot apps showing how to integrate `iam-security-spring-boot-starter`: `demo-app` (OIDC client + OAuth2 client) and `demo-service` (resource server).

## Key Design Concepts

### Organizational rights model

Rights live in Keycloak groups following the path `orgs/{orgId}/{functionId}/{right}` where right is one of `_admin`, `_write`, `_read`. A function of `*` means org-wide access. The `org-rights-mapper` SPI plugin reads these at token time and emits the `org_rights` claim.

### Authority modes

The starter supports two authority modes controlled by `iam.security.function`:

- **Multi-function** (no property set): authorities are `{orgId}:{functionId}:{right}`, type `OrganizationalAuthority`. Used by the admin app.
- **Function-scoped** (`iam.security.function=demo`): authorities are `{orgId}:{right}`, type `FunctionScopedAuthority`. Used by single-function apps like the demo.
- **Superuser**: single authority `ROLE_SUPERUSER` when the `org_rights` claim contains `{"superuser": true}`.

### Managed clients

A managed client is a Keycloak client carrying `iam_admin_managed=true` (or listed in
`iam.admin.authz-client-ids`). `client_functions` is the complete list of functions it receives
artifacts for — an empty or absent attribute means **no** functions, never all of them. Functions are optional when
registering a client; one with none is inert until they are assigned.

The `org-rights-mapper`'s `id.token.claim` / `access.token.claim` say where `org_rights` is
emitted; both are settable on create and update, from the form as well as the API.

Service accounts are **script-only**. `iam_admin_service_account` records whether a client
keeps one. Keycloak enables `serviceAccountsEnabled` and creates the service account user by
itself for every client with Authorization Services on, so neither the flag nor the user's
existence is a signal — for a client without the attribute, the check is whether its service
account user holds `realm-management` roles (`hasAdminRoleMappings`). `ClientController` never creates, attaches or
removes a service account: create passes `false`, update passes the client's existing value
through, and delete refuses a client holding one with a `409`. The GUI shows it as a pill and
disables the delete button.

A client carries two independent roles. `iam_admin_managed=true` is the **OIDC client** role:
it requests tokens, takes the confidential/`client-jwt`/authz-services shape, and is
reconciled. `iam_admin_resource_server=true` is the **resource server** role: it may be named
in the OAuth2 `resource` parameter, needs no client settings of its own, and holds no
artifacts. Both may be set on one client. `resolveIamAdminManagedClients()` returns clients
holding the OIDC client role; `resolveAdministeredClients()` returns everything administered.
`ManagedClientInfo.reconcilable()` is the check to use before reconciling.

`ClientReconciliationService` creates whatever a managed client is missing — realm client
scopes, authz scopes, `policy-{org}-{func}-{level}`, `permission-{org}-{func}-{level}`, and
the optional client scope bindings. It runs on client create/update, on function
attach/detach, on demand via `/api/clients/reconcile`, and optionally on a cron schedule.
Artifacts for functions a client no longer handles are removed on every run.

Artifact names are produced by `KeycloakAdminClient.scopeName` / `policyName` /
`permissionName` — creation and removal must always go through them, never through inline
string concatenation.

### Client authentication

All clients use `private_key_jwt` (RFC 7523) rather than client secrets. The starter auto-wires the credential from `iam.security.client.credential` (JKS or PEM via the `credentials-support` library) and creates the Nimbus JWT converter automatically.

### Org-scoped token requests

Client registrations use scope placeholders like `{org}:{function}:read`. The `OAuthClientContext` (session-scoped bean) stores the current org. `OrgScopedAuthorizationRequestResolver` resolves the placeholders before each authorization request. This enables context-aware token acquisition without changing client registrations.

### Frontend / backend packaging

The frontend is a separate Maven module that produces a `*-dist.zip` classifier artifact (via `maven-assembly-plugin`). The backend's `pom.xml` uses `maven-dependency-plugin` to unpack this ZIP into `target/classes/static` during `process-resources`, so the Spring Boot fat JAR serves the frontend. This means **the frontend ZIP must be installed in the local Maven repo before the backend can compile**.

## Code Style

### JSpecify nullability

Every public method return type, parameter, and field must carry `@NonNull` or `@Nullable` from `org.jspecify.annotations`. Annotations are type-use and must appear immediately before the type:

```java
// Correct
public @NonNull List<String> getNames() { ... }
void process(final @NonNull String value) { ... }
private @Nullable String description;

// Wrong — annotation on wrong line or after modifier
@NonNull
public List<String> getNames() { ... }
```

### Coding conventions

For all new files, include a file header according to the one configured for the project.

When creating new Java classes, include the `@author` in the class-level JavaDoc. If you don't know the name, prompt the user.

Follow the code style defined in internal/spring-codestyle.xml.

All generated Java and JavaScript/TypeScript code must comply with the rules defined in the project's IntelliJ inspections configuration - internal/inspections.xml.

### Copyright header

All Java files must start with the Apache 2.0 header:
```java
/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */
```

### Logging levels

Use log levels according to their operational meaning. The guiding principle is:
**log level reflects the severity to the system and its operators, not whether the
event is surprising or interesting to a developer.**

Reference: https://betterstack.com/community/guides/logging/log-levels-explained/

#### Level guidelines

| Level   | When to use |
|---------|-------------|
| `ERROR` | The application cannot do what it was asked to do and a human must act. Unrecoverable failure, unreachable dependency, unhandled exception, data corruption. |
| `WARN`  | Something unexpected happened that the application handled gracefully, but an operator should be aware of and may want to investigate. Examples: retry succeeded after failure, deprecated code path reached, config fell back to a default. Do NOT use WARN for events that are expected in normal operation. |
| `INFO`  | Normal, noteworthy lifecycle events an operator would want to see in production: startup/shutdown, successful completion of significant business operations, configuration summaries. Keep INFO volume low. |
| `DEBUG` | Detailed diagnostics useful during development and troubleshooting: method inputs/outputs, branching decisions, state transitions. Safe to enable in production when investigating. |
| `TRACE` | Very fine-grained detail: method entry/exit, loop iterations, low-level protocol exchanges. Typically only enabled locally. |

#### Common mistakes to avoid

- **Do not use WARN for expected application behaviour.** A user who fails authentication,
  submits invalid input, or lacks required rights is a normal, handled event. Log at INFO.
    - ✗ `log.warn("User {} does not have admin rights", sub)`
    - ✓ `log.info("Login rejected for '{}': insufficient rights in org_rights claim", sub)`

- **Do not use ERROR for validation failures or business rule violations.** Those are
  handled outcomes, not system failures.

- **Include enough context** that the log is useful without a debugger:
    - ✓ `log.info("Login rejected for '{}': {}", sub, reason)`
    - ✗ `log.info("Login rejected")`

#### Quick reference for security/auth events

| Event | Level |
|-------|-------|
| User authenticated successfully | `DEBUG` |
| Login rejected — insufficient rights / bad input | `INFO` |
| Repeated failed logins (threshold exceeded) | `WARN` |
| Token signature validation failed | `WARN` |
| Identity provider / KeyCloak unreachable | `ERROR` |
| Security misconfiguration at startup | `ERROR` |

## Key Configuration Properties

```yaml
iam:
  security:
    function: <name>           # Optional; enables function-scoped authority mode
    debug: true                # Enables verbose claim/token logging
    client:
      credential:
        jks:                   # or pem:
          store: { location, password, type }
          key: { alias, key-password }
      registrations:
        <registration-id>:
          resource: https://...  # Target resource server URI for this registration
  admin:
    sso-login-path: /sso/login
    realm: orgiam
    admin-api-base: https://.../admin/realms/orgiam
    theme: digg
    theme-dir:                 # Optional external theme directory
    authz-client-ids: []       # Fallback list of managed client IDs
    pnr-userids: false         # Use personal identity number as Keycloak username
    allow-function-removal: false
    allow-org-rights: true
    client-reconciliation:
      enabled: false           # Scheduled drift repair for managed clients
      cron: "0 */15 * * * *"
```
