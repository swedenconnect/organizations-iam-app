![logo](images/sweden-connect.png)

# IAM Integration Guide

---

## Table of Contents

1. [**Overview**](#overview)

2. [**Building an OIDC Relying Party**](#building-an-oidc-relying-party)

    2.1. [Keycloak Registration](#keycloak-registration)

    2.2. [Spring Boot Configuration](#spring-boot-configuration)

    2.3. [The iam-security Starter](#the-iam-security-starter)

    2.4. [Authority Model](#authority-model)

    2.5. [Forcing Re-authentication](#forcing-re-authentication)

3. [**Building an OAuth Client (Calling Downstream APIs)**](#building-an-oauth-client-calling-downstream-apis)

    3.1. [Keycloak Registration](#keycloak-registration-oauth)

    3.2. [Spring Boot Configuration](#spring-boot-configuration-oauth)

    3.3. [Session Context](#session-context)

    3.4. [Calling the Resource Server](#calling-the-resource-server)

    3.5. [Separating OIDC and OAuth2 Callbacks](#separating-oidc-and-oauth2-callbacks)

4. [**Building an OAuth Resource Server**](#building-an-oauth-resource-server)

    4.1. [Keycloak Registration](#keycloak-registration-rs)

    4.2. [Spring Boot Configuration](#spring-boot-configuration-rs)

    4.3. [Validating Tokens](#validating-tokens)

    4.4. [Enforcing Access Control](#enforcing-access-control)

5. [**Delegating Administration to the IAM Admin App**](#delegating-administration-to-the-iam-admin-app)

    5.1. [The SSO Login Entry Point](#the-sso-login-entry-point)

    5.2. [Constructing the Redirect URL](#constructing-the-redirect-url)

6. [**Using the Demo**](#using-the-demo)

    6.1. [Prerequisites](#prerequisites)

    6.2. [Registering demo-app and demo-service in Keycloak](#registering-demo-app-and-demo-service-in-keycloak)

    6.3. [Setting Up the Demo Function](#setting-up-the-demo-function)

    6.4. [Running the Demo](#running-the-demo)

---

<a name="overview"></a>
## 1. Overview

This guide explains how to build applications that integrate with the IAM model. The
model is defined in full in [rights-model.md](rights-model.md); the summary below covers the concepts
that are directly relevant when writing an application.

**Functions** are named administrative domains. Each function represents an area of
operation, for example `demo` or `sweden-connect`. An application is scoped to one or more
functions. Functions are defined centrally in Keycloak and can be attached to any number
of organizations.

**Organizations** are identified by their ten-digit Swedish organizational number (e.g.
`5590026042`). An organization participates in a function by having it attached, at which
point users can be granted rights on that function within that organization.

**Rights** come in three levels: `read`, `write`, and `admin`. They are hierarchical:
`admin` implies `write` and `read`; `write` implies `read`. A user may hold a right at
organization level — implicitly covering all attached functions — or on a specific function
within an organization.

**The `org_rights` claim** is a JSON array present in ID tokens. It provides a structured
description of all rights the authenticated user holds across all organizations and
functions. OIDC relying parties use this claim to determine what the user may act on in the
application's UI.

**OAuth2 scopes for API access** follow the pattern `{orgId}:{function}:{right}`, for
example `5590026042:demo:write`. When a client application needs to call a downstream API
on behalf of a user, it requests a scope of this form. Keycloak evaluates the user's group
membership at token issuance time and either grants or denies the scope. Resource servers
can therefore trust the granted scopes directly — no further authorization callback to
Keycloak is required.

**The `organization_identifier` claim** is present in access tokens and contains the
ten-digit organizational number extracted from the granted scope. Resource servers use this
claim to identify which organization the token was issued for, without having to parse the
scope string.

---

<a name="building-an-oidc-relying-party"></a>
## 2. Building an OIDC Relying Party

An OIDC relying party (RP) authenticates users via the standard authorization code flow and
receives an ID token containing the `org_rights` claim. The application uses this claim to
determine which organizations and functions the user may act on, and at what right level.

<a name="keycloak-registration"></a>
### 2.1. Keycloak Registration

Register the application using the `add-oidc-client.sh` script. This script creates the
client in Keycloak with `private_key_jwt` client authentication, adds the `org_rights`
protocol mapper to the ID token, and adds the `scope-org-identifier-mapper` to the access
token. See `compose/keycloak-scripts/README.md` for the full option reference.

```bash
./compose/keycloak-scripts/add-oidc-client.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-app.example.com \
    --name "My App" \
    --redirect-uri '/login/oauth2/code/*'
```

After registration, run `set-iam-admin-managed.sh` against the client:

```bash
./compose/keycloak-scripts/set-iam-admin-managed.sh \
    --realm orgiam \
    --client-id https://my-app.example.com \
    --username admin \
    --password keycloak
```

This sets the `iam_admin_managed=true` attribute on the client. The IAM admin application
uses this attribute to discover which clients require Authorization Services policies when
a function is attached to or detached from an organization. Without it, the client will
never receive the org-scoped scopes it needs to call downstream APIs.

<a name="spring-boot-configuration"></a>
### 2.2. Spring Boot Configuration

The application authenticates to Keycloak using `private_key_jwt`. It holds a private key;
Keycloak fetches the corresponding public key from the application's `/jwks` endpoint. The
key material is configured via `iam.security.client.credential` using the
`credentials-support` library format (JKS, PEM, or bundle reference — see
`commons/iam-security/README.md` for all three styles).

A minimal `application.yml` for a function-scoped application:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          my-app:
            client-name: "My App"
            provider: my-app
            scope:
              - openid
              - profile
              - https://id.oidc.se/scope/naturalPersonNumber
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            client-authentication-method: private_key_jwt

iam:
  security:
    function: my-function
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

The `client-id` and `provider.my-app.issuer-uri` are environment-specific and belong in
`application-local.yml`:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          my-app:
            client-id: https://my-app.example.com
        provider:
          my-app:
            issuer-uri: https://keycloak.example.com/realms/orgiam
```

Setting `iam.security.function` to the function identifier enables function-scoped mode in
the iam-security starter. See Section 2.4 for what this means for authorities.

The application must expose a `/jwks` endpoint so Keycloak can fetch the public key for
`private_key_jwt` verification. A minimal controller (see `JwksController` in
`iam-admin-app`) constructs the JWKS document from the auto-configured `JWK oidcClientJwk`
bean and serves it at `GET /jwks`:

```java
@RestController
public class JwksController {

  private final String jwksJson;

  public JwksController(final JWK oidcClientJwk) {
    this.jwksJson = new JWKSet(oidcClientJwk.toPublicJWK()).toString();
  }

  @GetMapping("/jwks")
  public ResponseEntity<String> jwks() {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(this.jwksJson);
  }
}
```

Ensure `/jwks` is permitted in the security filter chain without authentication.

<a name="the-iam-security-starter"></a>
### 2.3. The iam-security Starter

Add this dependency to the application's POM:

```xml
<dependency>
  <groupId>se.swedenconnect.iam</groupId>
  <artifactId>iam-security-spring-boot-starter</artifactId>
  <version>${project.version}</version>
</dependency>
```

When `spring-security-oauth2-client` is on the classpath, the starter auto-configures:

- `OrgRightsOidcUserService` — an `OAuth2UserService` that parses the `org_rights` claim
  from the ID token and populates the authenticated user's `GrantedAuthority` set.
  Automatically operates in function-scoped mode when `iam.security.function` is set.
- `JWK oidcClientJwk` — loaded from `iam.security.client.credential` via
  `JwkTransformerFunction`. Throws `IllegalStateException` at startup if the credential is
  not configured.
- `NimbusJwtClientAuthenticationParametersConverter` beans for the `authorization_code`,
  `refresh_token`, and `client_credentials` grant types — for signing `private_key_jwt`
  client assertions.
- `RestClientRefreshTokenTokenResponseClient` — a token response client for the refresh
  token grant, pre-configured with `private_key_jwt` authentication. Wired into the
  auto-configured `OAuth2AuthorizedClientManager` so that expired access tokens are
  refreshed transparently without any per-application configuration.
- `ResourceParameterConverter` — adds the `resource` parameter (RFC 8707) to
  authorization code token requests based on
  `iam.security.client.registrations.{id}.resource` properties. Must be wired onto the
  `RestClientAuthorizationCodeTokenResponseClient` via `addParametersConverter()`.

All auto-configured beans are `@ConditionalOnMissingBean`. Define your own bean of the same
type to override any default.

Wire the user service and token response client into the `SecurityFilterChain`:

```java
@Bean
RestClientAuthorizationCodeTokenResponseClient authCodeTokenClient(
    NimbusJwtClientAuthenticationParametersConverter<OAuth2AuthorizationCodeGrantRequest>
        authCodeJwtConverter,
    ResourceParameterConverter resourceParameterConverter) {

  var client = new RestClientAuthorizationCodeTokenResponseClient();
  client.addParametersConverter(authCodeJwtConverter);
  client.addParametersConverter(resourceParameterConverter);
  return client;
}

// In the SecurityFilterChain:
http.oauth2Login(oauth -> oauth
    .userInfoEndpoint(u -> u.oidcUserService(oidcUserService))
    .tokenEndpoint(t -> t.accessTokenResponseClient(authCodeTokenClient))
);
```

The `authCodeJwtConverter` bean is auto-configured by the starter — inject it directly
rather than re-creating it.

<a name="authority-model"></a>
### 2.4. Authority Model

The iam-security library supports two authority modes. The mode is determined by whether
`iam.security.function` is set.

**Function-scoped mode** (`iam.security.function` is set)

The starter filters the `org_rights` claim to entries relevant to the configured function
(both direct function rights and org-wide `*` rights that implicitly cover the function).
When both `*` and an exact function entry exist for the same organization, the highest
effective right is used. The resulting authorities are `FunctionScopedAuthority` instances
with the simplified form `{orgId}:{right}` — the function identifier is implicit.

Example: a user has `{ "function": "*", "right": "read" }` and
`{ "function": "demo", "right": "write" }` for organization `5590026042`. The effective
right is `write`. The resulting authority is `5590026042:write`.

Use this mode for applications that serve a single function.

**Full mode** (`iam.security.function` is not set)

All organizational rights are included as `OrganizationalAuthority` instances with the form
`{orgId}:{functionId}:{right}`. The `*` function identifier means an org-wide right covering
all attached functions. Use this mode for applications that deal with multiple functions,
such as the IAM admin application.

**Superusers** receive the single authority `ROLE_SUPERUSER` in both modes. Applications
that support superuser login must include `hasRole('SUPERUSER')` alongside their regular
authority checks.

`@PreAuthorize` examples:

```java
// Function-scoped mode
@PreAuthorize("hasRole('SUPERUSER') or hasAuthority(#orgId + ':write')")

// Full mode
@PreAuthorize("hasRole('SUPERUSER') or hasAuthority(#orgId + ':my-function:write')")
```

<a name="forcing-re-authentication"></a>
### 2.5. Forcing Re-authentication

By default, Keycloak reuses an existing SSO session when the application redirects to the
login page. For applications where this is not desired — where the user must always
authenticate explicitly — the iam-security starter provides
`PromptLoginAuthorizationRequestResolver`.

This class adds `prompt=login` to every Keycloak authorization request, forcing
re-authentication regardless of whether an active session exists. It is not
auto-configured; the application instantiates it explicitly as a `@Bean` and wires it into
`oauth2Login`:

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

An application that has both a standard login path (force re-authentication) and an SSO
entry point (reuse session) implements its own `OAuth2AuthorizationRequestResolver` that
inspects the request and delegates to `PromptLoginAuthorizationRequestResolver` or passes
the request through unmodified depending on the path. See `commons/iam-security/README.md`
for the full pattern.

---

<a name="building-an-oauth-client-calling-downstream-apis"></a>
## 3. Building an OAuth Client (Calling Downstream APIs)

A client application that needs to call a downstream API on behalf of the user uses a
separate authorization code flow to obtain an org-scoped access token. This is a pure
OAuth 2.0 flow — the `openid` scope is not requested, so no ID token is issued.

The same Keycloak client registration handles both the OIDC login flow (which produces the
ID token with `org_rights`) and the OAuth API flow (which produces the org-scoped access
token). No separate Keycloak client is needed.

Keycloak enforces entitlement at token issuance time — if the user does not hold the
required right on the requested organization and function, the token request is denied and
no token is issued.

<a name="keycloak-registration-oauth"></a>
### 3.1. Keycloak Registration

Use the same `add-oidc-client.sh` and `set-iam-admin-managed.sh` commands described in
Section 2.1. The `iam_admin_managed` attribute is required: it tells the IAM admin
application to create the org-scoped Keycloak scopes and their Authorization Services
policies when a function is attached to an organization. Without these policies, Keycloak
will deny any token request for a `{orgId}:{function}:{right}` scope regardless of the
user's group membership.

<a name="spring-boot-configuration-oauth"></a>
### 3.2. Spring Boot Configuration

Configure one Spring Security client registration per right level per resource server.
Each registration uses a **placeholder scope** (`{org}:{function}:read` or
`{org}:{function}:write`) that is resolved to the actual org and function at token
acquisition time. The `resource` parameter (RFC 8707), which binds the token to a specific
resource server, is configured per registration under `iam.security.client.registrations`.

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          my-service-read:
            provider: my-app
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/callback/oauth2/code/{registrationId}"
            client-authentication-method: private_key_jwt
            scope: "{org}:{function}:read"
          my-service-write:
            provider: my-app
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/callback/oauth2/code/{registrationId}"
            client-authentication-method: private_key_jwt
            scope: "{org}:{function}:write"

iam:
  security:
    client:
      registrations:
        my-service-read:
          resource: https://my-service.example.com
        my-service-write:
          resource: https://my-service.example.com
```

The `{org}` and `{function}` placeholders are resolved at runtime from `OAuthClientContext`
(see Section 3.3). The `resource` URI sets the `aud` claim in the resulting access token,
which the resource server validates.

The iam-security starter auto-configures an `OAuth2AuthorizedClientManager` bean of type
`DefaultOAuth2AuthorizedClientManager`. This manager is request-bound — it has access to
`HttpServletRequest` and `HttpServletResponse` and can redirect the browser to Keycloak when
no valid token is cached for the current session. It is pre-configured with a
`contextAttributesMapper` that resolves scope placeholders (`{org}`, `{function}`) from
`OAuthClientContext` and injects the `resource` parameter. Applications using
`OAuth2ClientHttpRequestInterceptor` do not need to handle token acquisition themselves.

Wire `OAuth2ClientHttpRequestInterceptor` onto the `RestClient` bean used for resource
server calls:

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

<a name="session-context"></a>
### 3.3. Session Context

The org and function needed for scope resolution are stored in `OAuthClientContext`, a
session-scoped bean provided by the iam-security starter. The application sets the org
when the user selects an organization. For single-function applications
(`iam.security.function` is set), the function is resolved automatically.

```java
// Inject:
private final OAuthClientContext oAuthClientContext;

// When the user selects an organization:
oAuthClientContext.setOrg(selectedOrgId);

// For multi-function applications, also set the function:
oAuthClientContext.setFunction("my-function");

// On logout:
oAuthClientContext.clear();
```

The `contextAttributesMapper` on `OAuth2AuthorizedClientManager` reads from
`OAuthClientContext` at token acquisition time and resolves `{org}` and `{function}` in
the placeholder scope. If either value is missing, the token request fails — the
application must ensure the context is populated before any resource server call is made.

<a name="calling-the-resource-server"></a>
### 3.4. Calling the Resource Server

Select the appropriate registration explicitly on each `RestClient` call using
`clientRegistrationId`. The registration determines the scope (read or write) and the
resource server the token is bound to.

```java
import static org.springframework.security.oauth2.client.web.client
    .RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

// GET — use read registration
MyData result = restClient.get()
    .uri("https://my-service.example.com/api/{orgId}/data", orgId)
    .attributes(clientRegistrationId("my-service-read"))
    .retrieve()
    .body(MyData.class);

// PUT — use write registration
restClient.put()
    .uri("https://my-service.example.com/api/{orgId}/data", orgId)
    .attributes(clientRegistrationId("my-service-write"))
    .body(data)
    .retrieve()
    .toBodilessEntity();
```

`OAuth2ClientHttpRequestInterceptor` intercepts each call, invokes
`OAuth2AuthorizedClientManager.authorize()` with the specified registration, and injects
the Bearer token. If no valid token is cached for the current session, Spring Security
initiates a new authorization code flow to Keycloak with the resolved scope and resource
parameter, redirecting the user transparently. On return, the token is cached and the
original request is retried.

The right level — `read` or `write` — is a business decision per call site, not derived
from the HTTP method. Select the registration that matches the operation being performed.

**SPA + REST backend**

When the application has a JavaScript SPA frontend that calls the Spring backend via
`fetch`, the `ClientAuthorizationRequiredException` thrown by
`OAuth2ClientHttpRequestInterceptor` must be caught in the controller and translated into
an HTTP `401` response with a JSON body containing the authorization URL. Using a `302`
redirect does not work because the Fetch API treats redirects as opaque — the `Location`
header is not accessible to JavaScript, so the frontend cannot determine where to navigate.

```java
try {
  return this.resourceServerRestClient.get()
      .uri(...)
      .attributes(clientRegistrationId("my-service-read"))
      .exchange(...);
} catch (ClientAuthorizationRequiredException e) {
  return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
      .contentType(MediaType.APPLICATION_JSON)
      .body("{\"authorizationUrl\":\"/oauth2/authorization/my-service-read\"}");
}
```

The frontend detects the `401` response, reads the `authorizationUrl` from the JSON body,
and performs a full browser navigation to that URL. Spring Security handles the Keycloak
redirect and callback entirely. The SPA never needs to be aware of OAuth details.

```typescript
async function apiFetch(url: string, options?: RequestInit): Promise<Response> {
  const resp = await fetch(url, { ...options, credentials: 'include' });
  if (resp.status === 401) {
    const body = await resp.json();
    if (body.authorizationUrl) {
      window.location.href = body.authorizationUrl;
      return new Promise(() => {}); // never resolves — page is navigating away
    }
  }
  return resp;
}
```

**Preserving pending writes across redirects.** If the user submits a form and the backend
returns `401` (no write token cached), the SPA should save the pending data in
`sessionStorage` before navigating. After the OAuth2 redirect cycle, the SPA reloads and
can detect the pending data, restore the form, and retry the save automatically — so the
user does not have to re-enter data.

The SPA router must not intercept `/oauth2/**` or `/login/oauth2/**` paths. These must
always reach the backend as full page navigations so Spring Security can process them.

The request cache must be configured to skip API paths (`/api/**`) and OAuth2 callback
paths (`/callback/oauth2/**`) so that after the OAuth callback, Spring redirects to `/`
rather than attempting to restore the XHR request or the callback URL itself. If the
callback path is saved and then restored, the browser is redirected back to it after the
token exchange completes — but without the `code` and `state` parameters, resulting in
a 404:

```java
final HttpSessionRequestCache requestCache = new HttpSessionRequestCache() {
  @Override
  public void saveRequest(HttpServletRequest request, HttpServletResponse response) {
    final String uri = request.getRequestURI();
    if (uri != null && (uri.startsWith("/api/") || uri.startsWith("/callback/oauth2/"))) return;
    super.saveRequest(request, response);
  }
};
http.requestCache(cache -> cache.requestCache(requestCache));
```

**OAuth2 callback URIs**

The redirect URI paths (e.g. `/login/oauth2/code/*`) must be permitted without
authentication in the application's `SecurityFilterChain`:

```java
.requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
```

Without this, Spring Security's `OAuth2AuthorizationCodeGrantFilter` cannot process
the Keycloak callback and the token flow fails.

---

<a name="separating-oidc-and-oauth2-callbacks"></a>
### 3.5. Separating OIDC and OAuth2 Callbacks

When an application uses both `oauth2Login` (OIDC user authentication) and `oauth2Client`
(OAuth2 API token flows) in the same `SecurityFilterChain`, a subtle but critical
configuration step is required.

Spring Security's `OAuth2LoginAuthenticationFilter` listens by default on
`/login/oauth2/code/*` — a wildcard that matches ALL registrations' callback paths,
including those used by OAuth2 API flows. When the browser is redirected back from
Keycloak after an API token flow, `OAuth2LoginAuthenticationFilter` intercepts the
callback and tries to process it as an OIDC login response. This fails because API
token responses carry no ID token, and Spring Security redirects to `failureUrl`
(e.g. `/?loginError`).

The fix is to restrict `OAuth2LoginAuthenticationFilter` to only the OIDC registration's
callback path using `redirectionEndpoint().baseUri()`:

```java
.oauth2Login(oauth -> oauth
    .redirectionEndpoint(r -> r
        .baseUri("/login/oauth2/code/my-oidc-registration"))
    ...
)
```

With this, only `/login/oauth2/code/my-oidc-registration` is processed by
`OAuth2LoginAuthenticationFilter`. All other callbacks fall through to
`OAuth2AuthorizationCodeGrantFilter` from `oauth2Client`, which handles them correctly
and stores the token.

For clarity, it is recommended that OAuth2 API registrations use a distinct callback
base path (e.g. `/callback/oauth2/code/{registrationId}`) rather than sharing
`/login/oauth2/code/` with the OIDC registration. This makes the separation explicit
and avoids any ambiguity. If separate base paths are used, the Keycloak client must
allow both patterns as redirect URIs — pass `--redirect-uri` twice to
`add-oidc-client.sh`:

```bash
./compose/keycloak-scripts/add-oidc-client.sh \
    --realm orgiam \
    --client-id https://my-app.example.com \
    --redirect-uri '/login/oauth2/code/*' \
    --redirect-uri '/callback/oauth2/code/*'
```

The separate callback base path must also be permitted without authentication in the
security filter chain:

```java
.requestMatchers("/oauth2/**", "/login/oauth2/**", "/callback/oauth2/**").permitAll()
```

**Shared `OAuth2AuthorizationRequestRepository`**

When combining `oauth2Login` and `oauth2Client` in the same filter chain, both DSLs must
share the same `OAuth2AuthorizationRequestRepository` instance. Without this, the
authorization request saved when initiating an OAuth2 API flow is stored in `oauth2Login`'s
repository, but `OAuth2AuthorizationCodeGrantFilter` from `oauth2Client` looks in its own
separate repository and finds nothing, causing the callback to fall through to a 404.

Declare a shared repository bean and wire it into both:

```java
@Bean
AuthorizationRequestRepository<OAuth2AuthorizationRequest>
    authorizationRequestRepository() {
  return new HttpSessionOAuth2AuthorizationRequestRepository();
}

// oauth2Login:
.authorizationEndpoint(a -> a
    .authorizationRequestRepository(authorizationRequestRepository)
    ...
)

// oauth2Client:
.authorizationCodeGrant(g -> g
    .authorizationRequestRepository(authorizationRequestRepository)
    ...
)
```

---

<a name="building-an-oauth-resource-server"></a>
## 4. Building an OAuth Resource Server

A resource server is an API that receives and validates Bearer access tokens issued by
Keycloak. It never initiates authentication or token flows itself.

<a name="keycloak-registration-rs"></a>
### 4.1. Keycloak Registration

Register the resource server using `add-resource-server.sh`:

```bash
./compose/keycloak-scripts/add-resource-server.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://my-service.example.com \
    --name "My Service" \
    --functions my-function
```

This creates a Keycloak client with all flows disabled, no service account, and no
Authorization Services. The client is registered solely so that access tokens can carry it
as the `aud` claim via the OAuth2 `resource` parameter (RFC 8707).

The `--functions` flag sets the `client_functions` attribute on the client, declaring which
functions this resource server supports. When the resource-aud Keycloak plugin is deployed,
it validates at token issuance time that the function extracted from the requested scope
matches the `client_functions` attribute of the resource server indicated by the `resource`
parameter. If the function is not supported, the token request is rejected with an
`invalid_target` error (RFC 8707). If `--functions` is omitted, the resource server is
treated as function-universal and accepts all functions.

Do not run `set-iam-admin-managed.sh` for resource servers — they never request scopes and
do not need Authorization Services policies.

<a name="spring-boot-configuration-rs"></a>
### 4.2. Spring Boot Configuration

Add the same `iam-security-spring-boot-starter` dependency. The starter auto-configures
`OrgRightsScopeConverter` when `spring-security-oauth2-resource-server` is on the classpath.

`iam.security.function` is not set for resource servers. Minimal `application.yml`:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://keycloak.example.com/realms/orgiam
```

The `audience` value (the resource server's own client ID) is environment-specific and
belongs in `application-local.yml`.

<a name="validating-tokens"></a>
### 4.3. Validating Tokens

Three checks are mandatory (see also [OAuth Resource Servers](rights-model.md#oauth-resource-servers)):

**1. Signature and expiry** — handled automatically by the `NimbusJwtDecoder` that Spring
Boot creates from `issuer-uri`. No additional configuration is needed.

**2. Audience** — the `aud` claim is a multi-valued array containing the resource server's
client ID and the function identifier (e.g., `["https://my-service.example.com", "demo"]`).
The resource server must verify that its own client ID is present in the array.
Configure this as an additional validator on the decoder:

```java
NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
OAuth2TokenValidator<Jwt> audienceValidator =
    new JwtClaimValidator<List<String>>("aud",
        aud -> aud != null && aud.contains(expectedAudience));
decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
    JwtValidators.createDefaultWithIssuer(issuerUri),
    audienceValidator));
```

**3. Scope entitlement** — `OrgRightsScopeConverter` reads `{orgId}:{functionId}:{right}`
entries from the `scope` claim and produces `OrganizationalAuthority` granted authorities.
It is auto-configured by the starter; inject it directly.

Wire everything into the `SecurityFilterChain`:

```java
http.oauth2ResourceServer(rs -> rs
    .jwt(jwt -> jwt
        .decoder(jwtDecoder)
        .jwtAuthenticationConverter(orgRightsScopeConverter)));
```

<a name="enforcing-access-control"></a>
### 4.4. Enforcing Access Control

Resource servers receive access tokens (not ID tokens), so authorities always have the full
`{orgId}:{functionId}:{right}` form produced by `OrgRightsScopeConverter`. Use
`@PreAuthorize` with Spring Security SpEL:

```java
@GetMapping("/{orgId}/data")
@PreAuthorize("hasRole('SUPERUSER') " +
              "or hasAuthority(#orgId + ':my-function:read') " +
              "or hasAuthority(#orgId + ':my-function:write') " +
              "or hasAuthority(#orgId + ':my-function:admin')")
public ResponseEntity<MyData> getData(
    @PathVariable String orgId,
    JwtAuthenticationToken token) { ... }
```

In addition to the scope check, verify that the `organization_identifier` claim in the
access token matches the `{orgId}` path variable. This prevents a token legitimately issued
for organization A from being used to access organization B's data. A superuser token
carries no `organization_identifier` claim and must be exempted from this check:

```java
private boolean orgIdMatchesToken(String orgId, JwtAuthenticationToken token) {
  boolean isSuperuser = token.getAuthorities().stream()
      .anyMatch(a -> "ROLE_SUPERUSER".equals(a.getAuthority()));
  if (isSuperuser) {
    return true;
  }
  String claimedOrg = token.getToken().getClaimAsString("organization_identifier");
  return orgId.equals(claimedOrg);
}
```

Return `403 Forbidden` if the check fails.

---

<a name="delegating-administration-to-the-iam-admin-app"></a>
## 5. Delegating Administration to the IAM Admin App

Application users who need to manage their organization's settings — such as attaching
functions, managing users, or adjusting rights — do so through the IAM admin application.
An application can offer a "Delegate administration" button that takes the user there
directly, reusing the existing Keycloak session.

<a name="the-sso-login-entry-point"></a>
### 5.1. The SSO Login Entry Point

The IAM admin application exposes an SSO login endpoint whose path is configured via
`iam.admin.sso-login-path` (default `/sso/login`). When an external application redirects
a user to this endpoint, the IAM admin app initiates an authorization code flow without
`prompt=login`, allowing Keycloak to reuse an existing session. The user lands directly
inside the admin application without a re-authentication prompt, provided they are already
authenticated in the same Keycloak realm.

The endpoint accepts two optional query parameters:

| Parameter | Description |
|---|---|
| `org` | Ten-digit organization identifier. If present, the IAM admin app verifies that the user has admin rights on this organization before granting access. |
| `func` | Function identifier (e.g. `demo`). If present, the IAM admin app verifies that the user has admin rights on this function and restricts the session to only this function — the user can only view and manage rights for this function, not for other functions in the organization. |

<a name="constructing-the-redirect-url"></a>
### 5.2. Constructing the Redirect URL

The calling application needs two configuration properties pointing to the IAM admin app.
The property names are application-specific — the demo-app uses the prefix
`demo.app.iam-admin`:

```yaml
demo:
  app:
    iam-admin:
      base-url: https://local.dev.swedenconnect.se:17005
      sso-login-path: /sso/login
```

The redirect URL is composed as:

```
{base-url}{sso-login-path}?org={orgId}&func={function}
```

Example:

```
https://local.dev.swedenconnect.se:17005/sso/login?org=5590026042&func=demo
```

The recommended pattern is for the backend to construct and return this URL via an API
endpoint, so the frontend does not hardcode configuration values:

```java
// GET /api/{orgId}/admin-url
@GetMapping("/{orgId}/admin-url")
public Map<String, String> adminUrl(@PathVariable String orgId) {
  String url = iamAdminBaseUrl + ssoLoginPath + "?org=" + orgId + "&func=demo";
  return Map.of("url", url);
}
```

The frontend then calls this endpoint on button click and navigates to the returned URL:

```typescript
const { url } = await fetchAdminUrl(orgId);
window.location.href = url;
```

When `func` is included, the IAM admin application restricts the user's session to the
specified function. The user can view and manage user rights for that function only.
The function management page (create/delete functions) is not available in a
function-restricted session.

---

<a name="using-the-demo"></a>
## 6. Using the Demo

The `demo/` directory contains two applications that illustrate all of the integration
patterns described in this guide:

- **`demo-app`** (port 16990) — an OIDC relying party and OAuth client, scoped to the
  `demo` function. Authenticates users, displays organization info and contact data, and
  delegates administration to the IAM admin app.
- **`demo-service`** (port 16995) — a pure OAuth resource server. Exposes GET and PUT
  endpoints for organization contact data (address, telephone number, email address) with
  in-memory storage.

<a name="prerequisites"></a>
### 6.1. Prerequisites

The `orgiam` Keycloak realm must be bootstrapped. If it has not been set up yet:

```bash
./compose/keycloak-scripts/bootstrap-realm.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --display-name "Organizations and Users IAM"
```

At least one superuser account must exist to log in to the IAM admin application:

```bash
./compose/keycloak-scripts/create-admin-user.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --new-username diggadmin \
    --new-password changeme
```

All Keycloak provider JARs must be deployed and Keycloak rebuilt before running. See
`compose/keycloak-scripts/README.md` for installation instructions.

<a name="registering-demo-app-and-demo-service-in-keycloak"></a>
### 6.2. Registering demo-app and demo-service in Keycloak

Register `demo-app` as an OIDC client. The `--no-org-rights-access-token` flag is passed
because the demo-app does not need `org_rights` in access tokens — it uses the ID token
for UI decisions and requests org-scoped access tokens separately for API calls to
demo-service:

```bash
./compose/keycloak-scripts/add-oidc-client.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://local.dev.swedenconnect.se:16990 \
    --name "Demo App" \
    --redirect-uri '/login/oauth2/code/*' \
    --redirect-uri '/callback/oauth2/code/*' \
    --no-org-rights-access-token
```

Mark it as IAM-admin-managed so the IAM admin application will manage its Authorization
Services policies:

```bash
./compose/keycloak-scripts/set-iam-admin-managed.sh \
    --realm orgiam \
    --client-id https://local.dev.swedenconnect.se:16990 \
    --username admin \
    --password keycloak
```

Register `demo-service` as a passive resource server:

```bash
./compose/keycloak-scripts/add-resource-server.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://local.dev.swedenconnect.se:16995 \
    --name "Demo Service" \
    --functions demo
```

<a name="setting-up-the-demo-function"></a>
### 6.3. Setting Up the Demo Function

Log in to the IAM admin application at `https://local.dev.swedenconnect.se:17005` as a superuser.

1. Navigate to **Functions** and create a new function with identifier `demo`, Swedish name
   `Demo`, English name `Demo`.

2. Navigate to **Organizations** and create or select an organization.

3. On the organization's detail page, click **Attach function** and select `demo`. The IAM
   admin application will automatically create the three Keycloak scopes
   (`{orgId}:demo:read`, `{orgId}:demo:write`, `{orgId}:demo:admin`) and their
   Authorization Services policies on all `iam_admin_managed` clients — including
   `https://local.dev.swedenconnect.se:16990`.

4. Navigate to **Users** and select or create the user who will log in to the demo. Assign
   a right on `demo` for the organization — for example `write`.

<a name="running-the-demo"></a>
### 6.4. Running the Demo

Start both applications with the `local` Spring profile active. The `local` profile enables
TLS, sets the correct port, and points to the local Keycloak instance.

```bash
# Terminal 1 — demo-service (port 16995)
cd demo/demo-service
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 2 — demo-app backend (port 16990)
cd demo/demo-app/backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Open `https://local.dev.swedenconnect.se:16990` in a browser. Click **Log in** to authenticate
via Keycloak. After a successful login the application displays the organization's name,
the user's right level, and a contact data card. Changes to address, telephone number, and
email address are saved to `demo-service` via an access token scoped to
`{orgId}:demo:write`.

Click **Delegate administration** to open the IAM admin application. If the user is already
authenticated in the same Keycloak realm, no re-authentication prompt is shown.

Because `demo-app` passes `func=demo` in the redirect URL, the IAM admin application
restricts the session to the `demo` function. The administrator can only manage user rights
for `demo`, not for other functions that may be attached to the same organization. To access
the full IAM admin application without function restrictions, log in directly at
`https://local.dev.swedenconnect.se:17005`.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
