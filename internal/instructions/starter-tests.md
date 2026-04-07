# Unit tests for iam-security-spring-boot-starter

## Context

The `iam-security-spring-boot-starter` module has no tests. This instruction adds unit
tests for the classes with meaningful testable logic. The test dependency
`spring-boot-starter-test` is already in the POM (brings JUnit 5, AssertJ, Mockito).

Follow the existing test style from `iam-security-base` — plain JUnit 5 + AssertJ,
no unnecessary Spring context loading.

## Copyright header

All new test files must use the Apache 2.0 copyright header:

```
/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

## Target directory

All tests go under:
```
iam-security/iam-security-spring-boot-starter/src/test/java/se/swedenconnect/iam/security/
```

Create the directory structure if it does not exist.

---

## 1. `server/OrgRightsScopeConverterTest.java`

Read `server/OrgRightsScopeConverter.java` before writing tests.

Tests the resource server scope-to-authority converter. Use `Jwt.withTokenValue(...)`
builder to construct JWT instances (no mocking needed — `Jwt` has a builder).

### Test cases

- **validOrgScopes_parsedToAuthorities**: JWT with `scope` claim
  `"5590026042:demo:write 5561234567:demo:read"` → produces two
  `OrganizationalAuthority` instances with correct org/function/right.

- **mixedScopes_onlyOrgScopesParsed**: JWT with `scope` claim
  `"openid profile 5590026042:demo:write"` → produces exactly one
  `OrganizationalAuthority`; `openid` and `profile` are silently ignored.

- **nullScope_returnsEmpty**: JWT with no `scope` claim → empty authorities.

- **blankScope_returnsEmpty**: JWT with `scope` claim `""` → empty authorities.

- **invalidFormat_silentlyIgnored**: JWT with `scope` claim
  `"5590026042:write"` (two segments, not three) → empty authorities (caught by
  `OrganizationalAuthority.parse` throwing `IllegalArgumentException`).

- **singleValidScope_parsedCorrectly**: JWT with `scope` claim
  `"5590026042:*:admin"` → single authority with function `*` and right `admin`.

### Implementation notes

Build `Jwt` instances like:

```java
Jwt jwt = Jwt.withTokenValue("token")
    .header("alg", "none")
    .claim("scope", "5590026042:demo:write")
    .build();
```

Call `converter.convert(jwt)` and assert on the resulting
`AbstractAuthenticationToken.getAuthorities()`.

---

## 2. `client/ResourceParameterConverterTest.java`

Read `client/ResourceParameterConverter.java` and
`autoconfigure/IamSecurityProperties.java` before writing tests.

Tests that the resource parameter (RFC 8707) is correctly added to token requests.

### Test cases

- **resourceConfigured_addsParameter**: Create `IamSecurityProperties` with a
  registration `"my-read"` having `resource = "https://my-service.example.com"`.
  Mock an `OAuth2AuthorizationCodeGrantRequest` whose `ClientRegistration` returns
  `registrationId = "my-read"`. Convert → result contains `resource` key with the
  configured URI.

- **noResourceConfigured_returnsEmptyMap**: Same setup but the registration has no
  `resource` set → converter returns empty `MultiValueMap`.

- **unknownRegistration_returnsEmptyMap**: Properties have a registration for
  `"my-read"`, but the grant request's registration ID is `"unknown"` → empty map.

### Implementation notes

`IamSecurityProperties` and its inner classes use Lombok getters/setters — construct
directly:

```java
IamSecurityProperties props = new IamSecurityProperties();
IamSecurityProperties.Client.RegistrationProperties regProps =
    new IamSecurityProperties.Client.RegistrationProperties();
regProps.setResource("https://my-service.example.com");
props.getClient().getRegistrations().put("my-read", regProps);
```

For the grant request, use Mockito to mock `OAuth2AuthorizationCodeGrantRequest` and
`ClientRegistration`:

```java
ClientRegistration clientReg = mock(ClientRegistration.class);
when(clientReg.getRegistrationId()).thenReturn("my-read");
OAuth2AuthorizationCodeGrantRequest grantRequest = mock(OAuth2AuthorizationCodeGrantRequest.class);
when(grantRequest.getClientRegistration()).thenReturn(clientReg);
```

---

## 3. `client/PromptLoginAuthorizationRequestResolverTest.java`

Read `client/PromptLoginAuthorizationRequestResolver.java` before writing tests.

Tests that `prompt=login` is added only for the configured registration.

### Test cases

- **matchingRegistration_addsPromptLogin**: Delegate returns a non-null
  `OAuth2AuthorizationRequest`. Request URI ends with the matching registration ID.
  Resolved request has `prompt=login` in additional parameters.

- **nonMatchingRegistration_passesThrough**: Same setup but request URI ends with a
  different registration ID → resolved request does NOT contain `prompt` parameter.

- **delegateReturnsNull_returnsNull**: Delegate returns `null` → resolver returns
  `null`.

- **resolveByRegistrationId_matchingId_addsPromptLogin**: Call the two-argument
  `resolve(request, clientRegistrationId)` with the matching ID → `prompt=login`
  is added.

- **resolveByRegistrationId_nonMatchingId_passesThrough**: Two-argument resolve
  with a different ID → no `prompt` parameter.

### Implementation notes

Mock the delegate `OAuth2AuthorizationRequestResolver` and `HttpServletRequest`.

Build an `OAuth2AuthorizationRequest` via its builder:

```java
OAuth2AuthorizationRequest baseRequest = OAuth2AuthorizationRequest.authorizationCode()
    .clientId("my-client")
    .authorizationUri("https://keycloak/auth")
    .redirectUri("https://my-app/callback")
    .build();
```

For `resolve(HttpServletRequest)`, mock `request.getRequestURI()` to return
`"/oauth2/authorization/my-registration"` so the resolver can extract the
registration ID from the last path segment.

---

## 4. `client/OrgScopedAuthorizationRequestResolverTest.java`

Read `client/OrgScopedAuthorizationRequestResolver.java` before writing tests.

Tests scope placeholder resolution and resource parameter injection.

### Test cases

- **orgAndFunctionPlaceholders_resolved**: `OAuthClientContext` has `org="5590026042"`,
  `function="demo"`. Delegate returns a request with scopes `["{org}:{function}:read"]`.
  Resolved request has scope `"5590026042:demo:read"`.

- **functionFallsBackToProperty**: Context has `org="5590026042"` but no function set.
  `IamSecurityProperties.function = "demo"`. Resolved scope is `"5590026042:demo:read"`.

- **noPlaceholders_passesThrough**: Delegate returns scopes `["openid", "profile"]` →
  resolved request is identical (no modification).

- **resourceInjected**: Properties have a registration with `resource` set. Resolved
  request has `resource` in additional parameters.

- **noOrgSet_warnsAndPassesUnresolved**: Context has no org, scope contains `{org}` →
  scope is NOT resolved (left as-is with placeholder). The resolver logs a warning.

- **delegateReturnsNull_returnsNull**: Delegate returns `null` → `null`.

### Implementation notes

Mock the delegate resolver, `HttpServletRequest`, and `OAuthClientContext`. Build
`OAuth2AuthorizationRequest` instances via the builder with scopes set to the
placeholder values.

For the `resolve(HttpServletRequest)` overload, mock `request.getRequestURI()` to
end with the registration ID (e.g. `"/oauth2/authorization/demo-service-read"`).

---

## 5. `client/OrgRightsOidcUserServiceTest.java`

Read `client/OrgRightsOidcUserService.java` before writing tests.

Tests that the OIDC user service correctly delegates and builds authorities.

### Test cases

- **fullMode_producesOrganizationalAuthorities**: Construct the service with `functionId
  = null`. Provide an `OidcUserRequest` whose ID token contains an `org_rights` claim
  with one org entry having function `"demo"` + right `"write"`. The returned `OidcUser`
  has an `OrganizationalAuthority` of `"5590026042:demo:write"`.

- **functionScopedMode_producesFunctionScopedAuthorities**: Construct with
  `functionId = "demo"`. Same claim → returned user has a `FunctionScopedAuthority`
  of `"5590026042:write"`.

- **superuser_receivesRoleSuperuser**: Claim is `[{ "superuser": true }]`. Returned
  user has authority `ROLE_SUPERUSER`.

- **noOrgRightsClaim_producesEmptyAuthorities**: ID token has no `org_rights` claim →
  returned user has empty authorities.

### Implementation notes

This test is the most complex because `OrgRightsOidcUserService` internally creates an
`OidcUserService` delegate that calls the userinfo endpoint. To avoid this, **subclass
`OrgRightsOidcUserService`** in the test and override `loadUser` at the delegate level,
OR use Mockito to spy/mock the inner delegate.

A simpler approach: use `@MockitoSettings` and mock the delegate `OidcUserService` bean
via reflection or by extracting the delegate field. However, the simplest strategy is:

1. Build an `OidcIdToken` with the `org_rights` claim using `OidcIdToken.withTokenValue(...)`.
2. Build a `DefaultOidcUser` from that token.
3. Since the real `OidcUserService.loadUser()` would call the userinfo endpoint (which
   we can't do in a unit test), create a **testable subclass** that overrides the call
   to the delegate:

```java
class TestableOrgRightsOidcUserService extends OrgRightsOidcUserService {
    private final OidcUser fakeUser;

    TestableOrgRightsOidcUserService(OrgRightsClaimParser parser, String functionId, OidcUser fakeUser) {
        super(parser, functionId);
        this.fakeUser = fakeUser;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        // Skip the delegate.loadUser() call and use the fake user directly.
        // Re-implement the authority-building logic from the parent.
        ...
    }
}
```

**Alternative (preferred)**: Since the `delegate` field is private and final, the cleanest
approach is to make the delegate injectable for testing. However, since we don't want to
change production code for testability alone, instead use **Mockito's `mockConstruction`**
to intercept the `OidcUserService` delegate created inside the constructor:

```java
try (var mocked = mockConstruction(OidcUserService.class, (mock, context) -> {
    when(mock.loadUser(any())).thenReturn(fakeOidcUser);
})) {
    OrgRightsOidcUserService service = new OrgRightsOidcUserService(parser, functionId);
    OidcUser result = service.loadUser(userRequest);
    // assert authorities
}
```

Build the `OidcUserRequest` by mocking `ClientRegistration` and `OAuth2AccessToken`, and
constructing an `OidcIdToken` with claims:

```java
Map<String, Object> claims = Map.of(
    "sub", "test-user",
    "iss", "https://keycloak/realms/orgiam",
    "org_rights", List.of(Map.of(
        "organization_identifier", "5590026042",
        "organization_name#sv", "Test Org",
        "functions", List.of(Map.of("function", "demo", "right", "write"))
    ))
);
OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(300), claims);
```

---

## 6. `autoconfigure/IamSecurityAutoConfigurationTest.java`

Uses `ApplicationContextRunner` to verify bean registration.

### Test cases

- **defaultConfiguration_registersClaimParser**: Run with no extra config → context
  contains a `OrgRightsClaimParser` bean.

- **customClaimParser_suppressesDefault**: Register a custom `OrgRightsClaimParser`
  bean → the auto-configured one is suppressed.

### Implementation notes

```java
private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(IamSecurityAutoConfiguration.class));

@Test
void defaultConfiguration_registersClaimParser() {
    this.contextRunner.run(context -> {
        assertThat(context).hasSingleBean(OrgRightsClaimParser.class);
    });
}
```

---

## 7. `autoconfigure/IamSecurityResourceServerAutoConfigurationTest.java`

Uses `ApplicationContextRunner` to verify the resource server beans.

### Test cases

- **withResourceServerOnClasspath_registersScopeConverter**: Run with
  `JwtAuthenticationConverter` on the classpath → context contains an
  `OrgRightsScopeConverter` bean.

- **customScopeConverter_suppressesDefault**: Register a custom `OrgRightsScopeConverter`
  bean → the auto-configured one is suppressed.

### Implementation notes

Since `JwtAuthenticationConverter` is already on the test classpath (via the optional
`spring-security-oauth2-resource-server` dependency), the `@ConditionalOnClass` should
be satisfied. If not, add `spring-security-oauth2-resource-server` as a test dependency
in the POM.

---

## POM changes

Read `iam-security/iam-security-spring-boot-starter/pom.xml` before editing.

The following test dependencies may need to be added if not already transitively
available via `spring-boot-starter-test`:

- `spring-security-oauth2-client` (test scope) — needed for `OidcUserRequest`,
  `ClientRegistration`, etc. in the OIDC user service test. It is currently `optional`
  in the compile scope, which means it IS on the test classpath already. Verify this
  and only add a separate test-scope dependency if tests fail to compile.

- `spring-security-oauth2-resource-server` (test scope) — same situation, currently
  `optional`. Should be available for tests.

- `spring-security-oauth2-jose` (test scope) — needed for `Jwt` builder. Currently
  `optional`. Should be available.

If any of these are NOT on the test classpath, add them explicitly with `<scope>test</scope>`.

---

## Verification

```
mvn test -pl iam-security/iam-security-spring-boot-starter
```

All tests must pass. Verify that `mvn test` from the project root also passes.
