![Sweden Connect](images/sweden-connect.png)

# Configuration of the IAM Admin Application

---

The IAM Admin application is a Spring Boot web application whose configuration is divided
into three distinct areas:

1. **Spring Boot configuration** - standard server settings such as TLS, ports, sessions,
   logging, and OAuth2 client registrations. See the
   [Spring Boot Reference Documentation](https://docs.spring.io/spring-boot/reference/)
   for the full list of available properties.

2. **IAM Security configuration** (`iam.security.*`) - shared security library settings
   for OIDC client authentication, function-scoped mode, and debug logging. These
   properties are documented in full in the
   [IAM Security Library](iam-security.md) reference.

3. **IAM Admin configuration** (`iam.admin.*`) - application-specific settings described
   in detail in this document.

4. **Audit logging** (`audit.*`) - where the audit events are written. See
   [Audit Logging](#audit-logging).

5. **Management endpoints** (`management.*`) - the Spring Boot Actuator endpoints. See
   [Management Endpoints](#management-endpoints).

Also check the
[application.yml](../iam-admin-app/backend/src/main/resources/application.yml) and
[application-local.yml](../iam-admin-app/backend/src/main/resources/application-local.yml)
files for examples of how to configure the application.

<a name="oauth2-client"></a>
## Spring Security OAuth2 Client Configuration

The admin application uses two Spring Security OAuth2 client registrations, both
authenticating with `private_key_jwt` (the credential is configured under
`iam.security.client.credential`):

- `iam-admin` - the OIDC login registration. Uses the `authorization_code` grant
  to authenticate administrators via Keycloak.
- `iam-admin-sa` - the service account registration. Uses the `client_credentials`
  grant for server-to-server calls to the Keycloak Admin REST API.

Both registrations reference the same provider (`iam-admin`) whose `issuer-uri` points to
the Keycloak realm. A minimal registration structure looks like this:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          iam-admin:
            client-id: https://my-admin.example.com
            client-name: "IAM Admin"
            provider: iam-admin
            scope:
              - openid
              - profile
              - email
              - https://id.oidc.se/scope/naturalPersonNumber
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            client-authentication-method: private_key_jwt
          iam-admin-sa:
            client-id: https://my-admin.example.com
            client-name: "IAM Admin Service Account"
            provider: iam-admin
            authorization-grant-type: client_credentials
            client-authentication-method: private_key_jwt
        provider:
          iam-admin:
            issuer-uri: https://keycloak.example.com/realms/orgiam
```

<a name="iam-security"></a>
## IAM Security Configuration

The `iam.security.*` properties are documented in the
[IAM Security Library](iam-security.md) reference. The table below provides a quick
overview of the top-level properties.

| Property | Description | Type | Default value |
| :--- | :--- | :--- | :--- |
| `iam.security.function` | Function identifier this application is scoped to. When set, enables function-scoped authority mode. The IAM admin app typically does **not** set this property (it operates in full mode across all functions). | `String` | - |
| `iam.security.debug` | When `true`, enables trace-level logging of OAuth2/OIDC token endpoint requests and responses. Sensitive fields are redacted.<br />**Never enable in production.** | `Boolean` | `false` |
| `iam.security.client.*` | Client credential and per-registration properties. See [IAM Security Library](iam-security.md). | - | - |

<a name="iam-admin"></a>
## IAM Admin Application Configuration

Application-specific properties under the `iam.admin` prefix. See
[IamAdminProperties](../iam-admin-app/backend/src/main/java/se/swedenconnect/iam/admin/config/IamAdminProperties.java)
for the source definition.

| Property | Description | Type | Default value |
| :--- | :--- | :--- | :--- |
| `iam.admin.sso-login-path` | The request path that initiates an SSO login (without forced re-authentication). External applications redirect users to this path, optionally with `org` and `func` query parameters, and an optional `correlation-id` parameter. A `correlation-id` becomes the correlation ID of the audit events of the session. It may contain letters, digits, `-`, `_`, `.` and `:`, and at most 128 characters. An invalid value is ignored and does not fail the login, and a session that already has a correlation ID keeps it. See [Audit Events](audit-events.md#common-structure). | `String` | `/sso/login` |
| `iam.admin.realm` | Keycloak realm name. Used to construct the Admin REST API base URL. | `String` | `orgiam` |
| `iam.admin.admin-api-base` | Base URL of the Keycloak Admin REST API for the configured realm, e.g. `https://keycloak.example.com/admin/realms/orgiam`. | `String` | - |
| `iam.admin.theme` | UI theme / white-label profile. Controls which CSS variables and logo assets are served under `/theme/`. See [IAM Admin Themes](iam-admin-themes.md). | `String` | `digg` |
| `iam.admin.theme-dir` | Optional filesystem path to an external theme directory. When set, static theme assets and `footer.json` are served from this directory instead of the classpath, enabling theme changes without rebuilding the JAR. | `String` | - |
| `iam.admin.pnr-userids` | **Deprecated**. Use `iam.admin.user-registration.allow-select-user-id` instead. | `Boolean` | `false` |
| `iam.admin.allow-function-removal` | When `true`, superusers are permitted to permanently delete a function definition and all its Keycloak artifacts (group, org sub-groups, client scopes, authorization policies and permissions). | `Boolean` | `false` |
| `iam.admin.allow-org-rights` | When `true`, users may be assigned rights at the organization level, implicitly covering all functions. When `false`, only function-level assignments are permitted. Existing org-level memberships remain visible and removable. | `Boolean` | `true` |
| `iam.admin.allow-admin-assigning-admin` | When `false`, a user who is not a superuser cannot grant, remove or downgrade the `admin` right, at either the organization level or the organization/function level. Only `read` and `write` are available to such a caller. When `true`, an admin may manage the `admin` right within the scope they administer. Superusers are never affected. | `Boolean` | `false` |
| `iam.admin.client-reconciliation.enabled` | When `true`, managed clients are reconciled against the org/function topology on a schedule. Reconciliation also runs whenever a client is created or updated, and whenever a function is attached to or detached from an organization, so the schedule only exists to repair drift. | `Boolean` | `false` |
| `iam.admin.client-reconciliation.cron` | Cron expression controlling how often scheduled reconciliation runs. Only used when reconciliation is enabled. | `String` | `0 */15 * * * *` |
| `iam.admin.user-registration.*` | Settings controlling how users are registered in the system. See [User Registration Settings](#user-registration-settings). | See below. | - |

<a name="user-registration-settings"></a>
### User Registration Settings

The settings under `iam.admin.user-registration` are used to control how users are created/registered in the system.
The following properties are available:

| Property | Description | Type | Default value |
| :--- | :--- | :--- | :--- |
| `allow-select-user-id` | When `true`, the administrator assigns the Keycloak user ID for the user being created. Any unused ID is permitted, including a personal identity number. When `false`, a random UUID is used and no field is shown. Maninly intended for testing environments. | `Boolean` | `false` |
| `allow-temporary-password` | When `true`, the administrator may set an initial password, which Keycloak then requires the user to change at first login. Only meaningful together with `allow-select-user-id`. Username and password logins should normally not be used in production. | `Boolean` | `false` |
| `eid-attribute-required` | When `true`, at least one of the enabled eID attributes must be given for a new user. | `Boolean` | `true` |
| `personal-number-enabled` | When `true`, a "Personal identity number" field is offered when a user is registered. This means that the user is expected to login using his or her Swedish eID. | `Boolean` | `true` |
| `hsa-id-enabled` | When `true`, an "HSA-ID" field is offered when a user is registered. This means that the user is expected to login using his or her SITHS eID. | `Boolean` | `false` |
| `org-affiliation-enabled` | When `true`, an "Organizational affiliation" field is offered when a user is registered. The value is on the format `userID@organization-number`, where the organization number is 10 digits. Enables user login with an Organizational eID according to Sweden Connect. | `Boolean` | `false` |
| `efos-id-enabled` | When `true`, an "EFOS-ID" field is offered when a user is registered. This means that the user is expected to login using his or her EFOS eID. | `Boolean` | `false` |

> **Note:** At least one eID attribute must be enabled when `eid-attribute-required` is `true`.
> The application refuses to start otherwise.

<a name="management-endpoints"></a>
## Management Endpoints

The application includes [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/index.html).
A deployment should serve its endpoints on a port of their own, by setting `management.server.port`, so that
they can be kept off the network that the application is reached through and exposed only to its monitoring.

The application's own configuration sets the following. Each can be overridden by the deployment.

| Property | Description | Value |
| :--- | :--- | :--- |
| `management.endpoints.web.exposure.include` | The endpoints exposed over HTTP. | `health,info` |
| `management.endpoint.health.show-details` | Whether the health endpoint shows the state of each component. | `never` |
| `management.endpoint.health.probes.enabled` | Exposes the liveness and readiness groups, `/actuator/health/liveness` and `/actuator/health/readiness`, for container orchestration. | `true` |

**The exposed endpoints require no authentication**, on the management port as well as anywhere else. This is why
only `health` and `info` are exposed, and why health details are not shown.

Other endpoints a deployment may want to expose, on a management port that is not reachable from outside:

| Endpoint | Use | Note |
| :--- | :--- | :--- |
| `auditevents` | Reading the most recent audit events. | Contains personal data, see [Audit Events](audit-events.md). Only returns events from a queryable repository, that is, not from a file or syslog repository. |
| `metrics` | Reading JVM and HTTP request metrics. | Reveals load and usage of the application. Exporting to a monitoring system, such as Prometheus, needs a Micrometer registry dependency in addition. |
| `loggers` | Reading and changing log levels at runtime. | Lets anyone reaching the port change the logging, for example turn on debug logging of request contents. |

`env`, `configprops` and `heapdump` should not be exposed, since they can reveal credentials and personal data.

The port is set with `management.server.port`. The endpoints use the TLS settings of the application unless
`management.server.ssl.*` is set. When the port is not set, the endpoints are served on the application's port and
are reachable on the same network as the application, still without authentication.

<a name="audit-logging"></a>
## Audit Logging

The application writes an audit event for every login, every change it makes, and every refused or failed
operation. The events are described in [Audit Events](audit-events.md). Audit logging is provided by
[spring-audit-support](https://docs.swedenconnect.se/spring-audit-support/) and configured with its `audit.*`
properties, which are described in the library's
[Configuration](https://docs.swedenconnect.se/spring-audit-support/configuration.html) page. Where the events can be
written, and what each backend requires, is described under
[Repositories](https://docs.swedenconnect.se/spring-audit-support/repositories.html).

**Default behaviour.** The application's own configuration sets `audit.default-principal` to `system` and
configures no repository. The events are then only kept in memory, a bounded number of the most recent ones, and
are lost when the application stops. The local and compose profiles write the events to a file, one JSON event
per line.

The application's configuration also filters out the security events that Spring Boot creates by itself, by
listing their types in `audit.repository.exclude-events`: `AUTHENTICATION_SUCCESS`, `AUTHENTICATION_FAILURE`,
`AUTHENTICATION_SWITCH`, `AUTHORIZATION_FAILURE` and `LOGOUT_SUCCESS`. The application's own events cover what they
report. A deployment that assigns its own `audit.repository.exclude-events` replaces this list, so it must repeat
these types to keep them out of the log.

**Production must configure a durable repository**, for example a database, a file collected by the log
infrastructure, or syslog.

**Write failures.** `audit.repository.throw-on-write-fail` keeps the library default `true`. A missing audit entry
is considered worse than a failed request, so when an event cannot be written the operation fails:

- The caller gets an error, even if the change has already been made in Keycloak.
- A login whose `login_succeeded` event cannot be written is stopped.
- An import stops, with status `failed`, so that no further changes are made without an audit trail.

Setting the property to `false` makes a write failure log an error and let the operation through instead.

The audit events can be read through the actuator's `auditevents` endpoint, which is not exposed by default. See
[Management Endpoints](#management-endpoints).

Changes made directly in Keycloak, or through the scripts, do not pass through the application and are not
audited.

The following example writes the events to a file and to a PostgreSQL database. The `jdbc` backend uses the
`DataSource` configured with `spring.datasource.*`. The application does not include `spring-jdbc` or a database
driver, so these must be added to the classpath, see the library's documentation.

```yaml
spring:
  application:
    name: iam-admin
  datasource:
    url: jdbc:postgresql://db.example.com:5432/iam_audit
    username: iam_audit
    password: ${AUDIT_DB_PASSWORD}

audit:
  default-principal: system
  repository:
    file:
      log-file: /var/log/iam-admin/audit.log
    jdbc:
      table-name: audit_events
```

<a name="example"></a>
## Example Configuration

The following YAML shows a typical local development configuration combining server,
OAuth2, IAM security, and IAM admin settings:

```yaml
server:
  port: 17005
  ssl:
    enabled: true
    bundle: local
  servlet:
    context-path: /iam-admin
    session:
      cookie:
        same-site: LAX
        secure: true
  compression:
    enabled: true
    mime-types: application/json,text/html,text/xml,text/plain,text/css,application/javascript
    min-response-size: 1024

spring:
  ssl:
    bundle:
      jks:
        local:
          keystore:
            location: classpath:local/tls.jks
            password: secret
            type: JKS
  security:
    oauth2:
      client:
        registration:
          # OIDC login registration (authorization_code + private_key_jwt)
          iam-admin:
            client-id: https://local.dev.swedenconnect.se:17005
            client-name: "IAM Admin"
            provider: iam-admin
            scope:
              - openid
              - profile
              - email
              - https://id.oidc.se/scope/naturalPersonNumber
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            client-authentication-method: private_key_jwt

          # Service account registration (client_credentials + private_key_jwt)
          iam-admin-sa:
            client-id: https://local.dev.swedenconnect.se:17005
            client-name: "IAM Admin Service Account"
            provider: iam-admin
            authorization-grant-type: client_credentials
            client-authentication-method: private_key_jwt

        provider:
          iam-admin:
            issuer-uri: https://local.dev.swedenconnect.se:17000/realms/orgiam

      resourceserver:
        jwt:
          issuer-uri: https://local.dev.swedenconnect.se:17000/realms/orgiam
          audiences:
            - "https://local.dev.swedenconnect.se:17005"

# IAM Security — client credential for private_key_jwt authentication
iam:
  security:
    debug: true
    client:
      credential:
        jks:
          store:
            location: classpath:oidc-client.jks
            password: secret
            type: JKS
          key:
            alias: client
            key-password: secret

  # IAM Admin application settings
  admin:
    realm: orgiam
    admin-api-base: https://local.dev.swedenconnect.se:17000/admin/realms/orgiam
    theme: digg
    allow-function-removal: true
    allow-org-rights: true
    allow-admin-assigning-admin: false
    user-registration:
      allow-select-user-id: true
      allow-temporary-password: true
      eid-attribute-required: true
      personal-number-enabled: true
      org-affiliation-enabled: true
    client-reconciliation:
      enabled: false
      cron: "0 */15 * * * *"
```

---

Copyright &copy; 2026, [Myndigheten f&ouml;r digital f&ouml;rvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
