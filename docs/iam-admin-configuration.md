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
| `iam.admin.sso-login-path` | The request path that initiates an SSO login (without forced re-authentication). External applications redirect users to this path, optionally with `org` and `func` query parameters. | `String` | `/sso/login` |
| `iam.admin.realm` | Keycloak realm name. Used to construct the Admin REST API base URL. | `String` | `orgiam` |
| `iam.admin.admin-api-base` | Base URL of the Keycloak Admin REST API for the configured realm, e.g. `https://keycloak.example.com/admin/realms/orgiam`. | `String` | - |
| `iam.admin.theme` | UI theme / white-label profile. Controls which CSS variables and logo assets are served under `/theme/`. See [IAM Admin Themes](iam-admin-themes.md). | `String` | `digg` |
| `iam.admin.theme-dir` | Optional filesystem path to an external theme directory. When set, static theme assets and `footer.json` are served from this directory instead of the classpath, enabling theme changes without rebuilding the JAR. | `String` | - |
| `iam.admin.authz-client-ids` | Optional fallback list of client IDs to include as managed clients, in addition to any clients discovered dynamically via the `iam_admin_managed` Keycloak client attribute. The final managed set is the union of both sources. | `List<String>` | - |
| `iam.admin.pnr-userids` | When `true`, the personal identity number (12 digits) is used as the Keycloak username for newly created users instead of a random UUID. Useful during local development to allow username/password login. | `Boolean` | `false` |
| `iam.admin.allow-function-removal` | When `true`, superusers are permitted to permanently delete a function definition and all its Keycloak artifacts (group, org sub-groups, client scopes, authorization policies and permissions). | `Boolean` | `false` |
| `iam.admin.allow-org-rights` | When `true`, users may be assigned rights at the organization level, implicitly covering all functions. When `false`, only function-level assignments are permitted. Existing org-level memberships remain visible and removable. | `Boolean` | `true` |

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
    pnr-userids: true
    allow-function-removal: true
    allow-org-rights: true
```

---

Copyright &copy; 2026, [Myndigheten f&ouml;r digital f&ouml;rvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
