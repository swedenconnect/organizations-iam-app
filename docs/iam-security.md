![Sweden Connect](images/sweden-connect.png)

# IAM Security

Shared security library for applications in the Sweden Connect IAM system.

Provides ready-to-use support for:
- Parsing the `org_rights` OIDC claim.
- Building Spring Security authorities from organizational rights.
- `private_key_jwt` client authentication (OIDC clients and OAuth2 clients).
- Resource server access token validation with organizational scope extraction.

## Modules

| Module | Purpose |
| :--- | :--- |
| `iam-security-base` | Plain Java — claim model, parser, authority types. No Spring Boot dependency. |
| `iam-security-spring-boot-starter` | Spring Boot auto-configuration for OIDC clients and resource servers. |

## Dependency

For OIDC clients and OAuth2 clients (Spring Boot apps):

```xml
<dependency>
  <groupId>se.swedenconnect.iam</groupId>
  <artifactId>iam-security-spring-boot-starter</artifactId>
</dependency>
```

For resource servers (Spring Boot apps):

```xml
<dependency>
  <groupId>se.swedenconnect.iam</groupId>
  <artifactId>iam-security-spring-boot-starter</artifactId>
</dependency>
```

For non-Spring-Boot consumers (e.g., Keycloak provider JARs):

```xml
<dependency>
  <groupId>se.swedenconnect.iam</groupId>
  <artifactId>iam-security-base</artifactId>
</dependency>
```

---

## Configuration properties

All properties are under the `iam.security` prefix.

### Top-level properties

| Property | Required | Default | Description |
| :--- | :--- | :--- | :--- |
| `iam.security.function` | No | — | Function identifier this app is scoped to (e.g. `walletreg`). When set, enables function-scoped mode — see below. |

### Client properties (`iam.security.client`)

These properties apply to OIDC and OAuth2 clients that authenticate to Keycloak using
`private_key_jwt`.

The client credential is configured via `iam.security.client.credential` using the
`credentials-support` library format, which supports three styles:

**Style 1 — bundle reference** (credential pre-registered in credentials-support bundles):

```yaml
credential:
  bundles:
    credentials:
      my-oidc-key:
        jks:
          resource: classpath:oidc-client.jks
          password: secret
          alias: client
          key-password: secret

iam:
  security:
    client:
      credential:
        bundle: my-oidc-key
```

**Style 2 — inline JKS** (no pre-registration needed):

```yaml
iam:
  security:
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
```

**Style 3 — inline PEM**:

```yaml
iam:
  security:
    client:
      credential:
        pem:
          private-key: classpath:oidc-client-key.pem
          certificates: classpath:oidc-client-cert.pem
```

**Full example with Spring Security registration:**

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          my-app:
            client-id: https://my-app.example.com
            client-authentication-method: private_key_jwt
            authorization-grant-type: authorization_code
            ...

iam:
  security:
    function: walletreg          # optional — enables function-scoped mode
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
```

---

## Authority modes

The library supports two authority modes depending on whether `iam.security.function` is set.

### Full mode (default)

Used by applications that deal with multiple functions, such as the IAM admin
application. The full `org_rights` claim is parsed and all organizational rights across
all functions are represented as `OrganizationalAuthority` instances with the form
`{orgId}:{functionId}:{right}`.

Example authorities after login:
- `5590026042:walletreg:write`
- `5590026042:*:admin` — org-wide admin right, implicitly covers all functions
- `5561234567:sweden-connect:read`

Checking access:
```java
@PreAuthorize("hasAuthority('5590026042:walletreg:write')")
```

### Function-scoped mode

Used by applications that serve a single function. Set `iam.security.function` to the
function identifier the application cares about:

```yaml
iam:
  security:
    function: walletreg
```

In this mode the library:
- Filters the `org_rights` claim to entries relevant to the configured function — both
  direct function rights and org-wide (`*`) rights that implicitly cover the function
- Resolves the **highest** effective right per organization when multiple matching
  entries exist (e.g. `*:read` and `walletreg:write` → effective right is `write`)
- Produces `FunctionScopedAuthority` instances with the simplified form `{orgId}:{right}`
  — the function identifier is implicit and not encoded in the authority string

Example: a user has `org_rights` with `{ "function": "*", "right": "read" }` and
`{ "function": "walletreg", "right": "write" }` for organization `5590026042`. The
effective right is `write`. The resulting authority is `5590026042:write`.

Example authorities after login:
- `5590026042:write`
- `5561234567:admin`

Checking access:
```java
@PreAuthorize("hasAuthority('5590026042:write')")
```

Dynamically, using the organization identifier from the session or token:
```java
@PreAuthorize("hasAuthority(#orgId + ':write')")
```

### Superusers in both modes

A superuser has `org_rights: [{ "superuser": true }]` and can log into any application
regardless of which function it is scoped to. In both modes, superusers receive the
single authority `ROLE_SUPERUSER`.

Applications that support superuser login must include `hasRole('SUPERUSER')` alongside
their regular authority checks:

```java
@PreAuthorize("hasRole('SUPERUSER') or hasAuthority('5590026042:write')")
```

Or dynamically:
```java
@PreAuthorize("hasRole('SUPERUSER') or hasAuthority(#orgId + ':write')")
```

---

## What the starter auto-configures

### For OIDC clients (when `spring-security-oauth2-client` is on the classpath)

- `OrgRightsClaimParser` bean — parses the `org_rights` claim from ID tokens
- `OrgRightsOidcUserService` bean — Spring Security `OAuth2UserService` that populates
  authorities from the `org_rights` claim. Automatically uses function-scoped mode when
  `iam.security.function` is set. Wire into your `SecurityFilterChain`:
  ```java
  .userInfoEndpoint(u -> u.oidcUserService(oidcUserService))
  ```
- `PkiCredential oidcClientCredential` bean — loaded from the configured credential
  (when `iam.security.client.credential` is set)
- `JWK oidcClientJwk` bean — derived from the credential via `JwkTransformerFunction`
- `NimbusJwtClientAuthenticationParametersConverter` beans for `authorization_code` and
  `client_credentials` grant types — wire onto your token response clients:
  ```java
  client.addParametersConverter(authCodeJwtConverter);
  ```

### For resource servers (when `spring-security-oauth2-resource-server` is on the classpath)

- `OrgRightsScopeConverter` bean — a `JwtAuthenticationConverter` that reads
  `{org}:{function}:{right}` entries from the access token `scope` claim and produces
  `OrganizationalAuthority` granted authorities. Wire into your `SecurityFilterChain`:
  ```java
  .oauth2ResourceServer(rs -> rs
      .jwt(jwt -> jwt.jwtAuthenticationConverter(orgRightsScopeConverter)))
  ```

### Overriding auto-configured beans

All auto-configured beans are `@ConditionalOnMissingBean`. Define your own bean of the
same type to override any default.

---

## Controlling authentication behaviour

### `PromptLoginAuthorizationRequestResolver`

`PromptLoginAuthorizationRequestResolver` adds `prompt=login` to Keycloak authorization
requests for a specific registration, forcing the user to re-authenticate at Keycloak even
if an active SSO session already exists.

`prompt=login` is only added when the authorization request's registration ID matches
the ID passed to the constructor. Requests for other registrations (e.g. OAuth2 API
client registrations) pass through unmodified, allowing Keycloak to reuse the existing
session for those flows.

It is **not** auto-configured and must be instantiated explicitly. This is intentional —
an application may have multiple login paths, some that should force re-authentication and
others that should reuse an existing session. The resolver is wired only for the paths
where forced re-authentication is required.

Wiring pattern:

```java
@Bean
OAuth2AuthorizationRequestResolver authorizationRequestResolver(
    ClientRegistrationRepository clientRegistrationRepository) {
  return new PromptLoginAuthorizationRequestResolver(
      clientRegistrationRepository, "my-registration-id");
}

// In the SecurityFilterChain:
.oauth2Login(oauth -> oauth
    .authorizationEndpoint(a -> a
        .authorizationRequestResolver(authorizationRequestResolver))
    ...
)
```

### Mixed paths (some force authn, some allow SSO)

An application that has both a standard login path (force re-authentication) and an SSO
entry point (reuse an existing Keycloak session) must implement its own
`OAuth2AuthorizationRequestResolver`. The custom resolver inspects each incoming request —
for example via a session attribute set by the SSO controller — and either calls through
to `PromptLoginAuthorizationRequestResolver` or passes the request through unmodified. No
auto-configuration is provided for this case because the session attribute and routing
logic are application-specific.

---

## Calling resource servers with org-scoped tokens

### Overview

Applications in this system use two OAuth2 flows against the same Keycloak client. The
**OIDC login flow** authenticates the user and returns an ID token containing the `org_rights`
claim, from which the application derives the user's authorities. The **OAuth API flow**
obtains an access token scoped to `{orgId}:{function}:{right}` that is presented to a
downstream resource server. Both flows use the same `ClientRegistration` and the same
`private_key_jwt` credential.

The org-scoped access token is obtained lazily — Spring Security requests it when the
application makes the first call to the resource server. If a valid token is already
cached in `OAuth2AuthorizedClientService`, it is reused; if it has expired and a refresh
token is available, it is refreshed automatically. The refresh token grant uses
`private_key_jwt` client authentication, which is configured by the starter's
auto-configured `RestClientRefreshTokenTokenResponseClient`.

### `OAuthClientContext`

`OAuthClientContext` is a session-scoped bean that holds the current `orgId` and, for
multi-function applications, the current `function`. The auto-configured
`OAuth2AuthorizedClientManager` reads from this context to resolve scope placeholders
before requesting tokens from Keycloak.

The application must set the org when the user selects an organization. For single-function
applications (`iam.security.function` is set), the function is resolved automatically from
configuration and does not need to be set explicitly.

```java
// Inject and use:
private final OAuthClientContext oAuthClientContext;

// When user selects an organization:
oAuthClientContext.setOrg(selectedOrgId);

// For multi-function apps, also set the function:
oAuthClientContext.setFunction("my-function");

// On logout:
oAuthClientContext.clear();
```

### Registration configuration

Use one registration per right level per resource server. The scope contains placeholders
that the auto-configured `contextAttributesMapper` resolves at token-request time. Configure
the `resource` URI per registration so that access tokens are bound to the target API (RFC 8707).

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          my-service-read:
            provider: my-provider
            authorization-grant-type: authorization_code
            client-authentication-method: private_key_jwt
            scope: "{org}:{function}:read"   # resolved at runtime
          my-service-write:
            provider: my-provider
            authorization-grant-type: authorization_code
            client-authentication-method: private_key_jwt
            scope: "{org}:{function}:write"  # resolved at runtime

iam:
  security:
    function: my-function   # single-function apps: function resolved automatically
    client:
      registrations:
        my-service-read:
          resource: https://my-service.example.com
        my-service-write:
          resource: https://my-service.example.com
```

The caller selects the registration explicitly on each `RestClient` call:

```java
import static org.springframework.security.oauth2.client.web.client
    .RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

restClient.get()
    .uri("https://my-service.example.com/api/{orgId}/data", orgId)
    .attributes(clientRegistrationId("my-service-read"))
    .retrieve()
    .body(MyData.class);

restClient.put()
    .uri("https://my-service.example.com/api/{orgId}/data", orgId)
    .attributes(clientRegistrationId("my-service-write"))
    .body(data)
    .retrieve()
    .toBodilessEntity();
```

### `OrgScopedAuthorizationRequestResolver`

When a client registration uses a placeholder scope such as `{org}:{function}:read`,
the raw placeholder would be sent to Keycloak verbatim during the browser-initiated
authorization code flow — because `DefaultOAuth2AuthorizationRequestResolver` reads the
scope directly from the `ClientRegistration` without consulting the manager's
`contextAttributesMapper`.

`OrgScopedAuthorizationRequestResolver` wraps any delegate resolver and post-processes the
resulting authorization request: if the scope contains `{org}` or `{function}` placeholders,
they are resolved from `OAuthClientContext` (with fallback to `iam.security.function`).
The `resource` parameter (RFC 8707) is also injected at this point if configured.

Wire it as the delegate of `PromptLoginAuthorizationRequestResolver`, or directly if
`prompt=login` is not needed:

```java
@Bean
OrgScopedAuthorizationRequestResolver orgScopedResolver(
    ClientRegistrationRepository repo,
    OAuthClientContext ctx,
    IamSecurityProperties props) {
  return new OrgScopedAuthorizationRequestResolver(
      new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization"),
      ctx, props);
}

@Bean
OAuth2AuthorizationRequestResolver authorizationRequestResolver(
    OrgScopedAuthorizationRequestResolver orgScopedResolver) {
  return new PromptLoginAuthorizationRequestResolver(orgScopedResolver, "my-oidc-registration");
}
```

### `OAuth2ClientHttpRequestInterceptor` wiring

`OAuth2ClientHttpRequestInterceptor` must be added to the `RestClient` bean so that the
access token is injected into outgoing requests automatically. Wire it against the
auto-configured `OAuth2AuthorizedClientManager`:

```java
@Bean
RestClient resourceServerRestClient(
    OAuth2AuthorizedClientManager authorizedClientManager) {

  OAuth2ClientHttpRequestInterceptor interceptor =
      new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);

  return RestClient.builder()
      .requestInterceptor(interceptor)
      .build();
}
```

### Separating OIDC and OAuth2 callbacks

If the application uses both `oauth2Login` and `oauth2Client` in the same filter chain,
two configuration steps are required:

1. Configure `redirectionEndpoint().baseUri()` on `oauth2Login` to restrict
   `OAuth2LoginAuthenticationFilter` to only the OIDC login callback path. Without this,
   it intercepts OAuth2 API callbacks and treats them as failed OIDC login attempts.

2. Declare a shared `OAuth2AuthorizationRequestRepository` bean and wire it into both
   `oauth2Login` and `oauth2Client`. Without this, the authorization request saved when
   initiating an OAuth2 API flow cannot be found by `OAuth2AuthorizationCodeGrantFilter`,
   causing the callback to fall through to a 404.

See `docs/iam-integration-guide.md` section 3.5 for the full explanation and
configuration examples.

### OAuth2 callback URIs must be permitted without authentication

When using OAuth2 client flows (authorization code grant for API access tokens), the
redirect URI paths (e.g. `/login/oauth2/code/*`) must be permitted without authentication
in the application's own `SecurityFilterChain`. This ensures that Spring Security's
`OAuth2AuthorizationCodeGrantFilter` — which is part of the application's own chain — can
process the Keycloak callback and store the token. Include the following in the
application's permit list:

```java
.requestMatchers("/oauth2/**", "/login/oauth2/**", "/callback/oauth2/**").permitAll()
```

The starter does not configure this automatically since it cannot inject into the
application's `HttpSecurity` configuration.

### Exclude OAuth2 callback paths from the request cache

The application's `HttpSessionRequestCache` must skip both API paths (`/api/**`) and
OAuth2 callback paths (`/callback/oauth2/**`). If the callback path is saved to the
cache, the browser will be redirected back to it after the token exchange completes —
but without the `code` and `state` parameters, resulting in a 404:

```java
final HttpSessionRequestCache requestCache = new HttpSessionRequestCache() {
  @Override
  public void saveRequest(
      final HttpServletRequest request, final @NonNull HttpServletResponse response) {
    final String uri = request.getRequestURI();
    if (uri != null && (uri.startsWith("/api/") || uri.startsWith("/callback/oauth2/"))) {
      return;
    }
    super.saveRequest(request, response);
  }
};
http.requestCache(cache -> cache.requestCache(requestCache));
```

### When to define your own `OAuth2AuthorizedClientManager`

The auto-configured `OAuth2AuthorizedClientManager` is `DefaultOAuth2AuthorizedClientManager`.
It is request-bound — it has access to `HttpServletRequest` and `HttpServletResponse` and
can redirect the browser to Keycloak when no valid token is cached. This makes it the correct
choice for applications that serve interactive user flows via `OAuth2ClientHttpRequestInterceptor`.

Applications that make only background service-account calls — no interactive user flows,
no browser redirects — should define their own `OAuth2AuthorizedClientManager` bean using
`AuthorizedClientServiceOAuth2AuthorizedClientManager` with only the `clientCredentials`
provider. Defining their own bean suppresses the starter's via `@ConditionalOnMissingBean`
and is appropriate for those applications.

### The `resource` parameter

The `resource` parameter (RFC 8707) binds an access token to a specific resource server.
Keycloak sets the `aud` claim in the token to the value of the `resource` parameter, allowing
resource servers to validate the audience. Configure it per registration under
`iam.security.client.registrations.{registrationId}.resource`.

---

## Debug logging

Set `iam.security.debug=true` to enable `OidcTokenEndpointLoggingInterceptor`, which
logs all OAuth2/OIDC token endpoint requests and responses at TRACE level. Sensitive
fields (`access_token`, `refresh_token`, `id_token`, `client_secret`) are redacted.
For OIDC flows, the ID token payload is decoded and logged at DEBUG level. For pure
OAuth2 flows (no `id_token` in the response), the access token payload is decoded and
logged at DEBUG level instead.

**Never enable in production.**

To see the token endpoint log output, set the `OIDC` logger to TRACE in
`application-local.yml`:

```yaml
logging:
  level:
    OIDC: TRACE
```

For full visibility into Spring Security's OAuth2 filter chain (authorization requests,
callbacks, token storage), also set:

```yaml
logging:
  level:
    OIDC: TRACE
    org.springframework.security.oauth2: DEBUG
    org.springframework.security.oauth2.client.web: TRACE
```

The `org.springframework.security.oauth2.client.web: TRACE` level logs the detailed
behaviour of `OAuth2AuthorizationRequestRedirectFilter`,
`OAuth2AuthorizationCodeGrantFilter`, and `OAuth2LoginAuthenticationFilter` — useful
when debugging callback processing and token storage issues.

---

## The org_rights claim

The `org_rights` claim is a JSON array produced by a custom Keycloak protocol mapper.

For a regular user:
```json
"org_rights": [
  {
    "organization_identifier": "5590026042",
    "organization_name#sv": "Litsec AB",
    "organization_name#en": "Litsec AB",
    "functions": [
      { "function": "*",         "right": "read"  },
      { "function": "walletreg", "right": "write" }
    ]
  }
]
```

For a superuser:
```json
"org_rights": [{ "superuser": true }]
```

---

## Authority types

### `OrganizationalAuthority` (full mode)

Authority string form: `{orgIdentifier}:{functionId}:{right}`

Examples: `5590026042:walletreg:write`, `5590026042:*:admin`

The `*` function identifier means an org-wide right covering all functions.

```java
OrganizationalAuthority.parse("5590026042:walletreg:write");
OrganizationalAuthority.of(orgId, funcId, OrganizationRight.WRITE);
```

### `FunctionScopedAuthority` (function-scoped mode)

Authority string form: `{orgIdentifier}:{right}`

The function is implicit — it is the value of `iam.security.function`.

Examples: `5590026042:write`, `5561234567:admin`

```java
FunctionScopedAuthority.parse("5590026042:write");
FunctionScopedAuthority.of(orgId, OrganizationRight.WRITE);
```

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
