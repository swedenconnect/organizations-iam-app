/*
 * Copyright 2026 Sweden Connect
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.swedenconnect.iam.keycloak.resourceaud;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.utils.OAuth2CodeParser;
import org.keycloak.services.clientpolicy.ClientPolicyContext;
import org.keycloak.services.clientpolicy.ClientPolicyEvent;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.context.TokenRequestContext;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResourceFunctionExecutor}.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResourceFunctionExecutorTest {

  @Mock
  private KeycloakSession session;

  @Mock
  private KeycloakContext context;

  @Mock
  private HttpRequest httpRequest;

  @Mock
  private RealmModel realm;

  @Mock
  private ClientProvider clientProvider;

  @Mock
  private ClientModel resourceClientModel;

  @Mock
  private AuthenticationSessionModel authSession;

  @Mock
  private UriInfo uriInfo;

  @Mock
  private AuthenticatedClientSessionModel clientSession;

  @Mock
  private UserSessionModel userSession;

  @Mock
  private UserModel user;

  @Mock
  private RoleModel superuserRole;

  private ResourceFunctionExecutor executor;

  @BeforeEach
  void setUp() {
    executor = new ResourceFunctionExecutor(session);
    when(session.getContext()).thenReturn(context);
    when(context.getHttpRequest()).thenReturn(httpRequest);
    when(context.getRealm()).thenReturn(realm);
    when(session.clients()).thenReturn(clientProvider);
    when(context.getAuthenticationSession()).thenReturn(authSession);
    // default: URI query params return empty map
    final MultivaluedMap<String, String> emptyParams = new MultivaluedHashMap<>();
    when(httpRequest.getUri()).thenReturn(uriInfo);
    when(uriInfo.getQueryParameters()).thenReturn(emptyParams);
  }

  // ---------------------------------------------------------------------------
  // TOKEN_REQUEST tests
  // ---------------------------------------------------------------------------

  @Test
  void tokenRequest_noResource_passes() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);

    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.TOKEN_REQUEST);
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void tokenRequest_noScope_passes() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://api.example.com");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);

    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.TOKEN_REQUEST);
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void tokenRequest_clientNotFound_throwsInvalidRequest() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://unknown.example.com");
    params.putSingle("scope", "2021006883:demo:write");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientProvider.getClientByClientId(realm, "https://unknown.example.com")).thenReturn(null);

    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.TOKEN_REQUEST);
    final ClientPolicyException ex = assertThrows(ClientPolicyException.class,
        () -> executor.executeOnEvent(ctx));
    assertTrue(ex.getError().contains("invalid_request") || ex.getError().contains("invalid"),
        "Expected invalid_request error but got: " + ex.getError());
  }

  @Test
  void tokenRequest_functionMatches_passes() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://api.example.com");
    params.putSingle("scope", "2021006883:demo:write");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClientModel);
    when(resourceClientModel.getAttribute("client_functions")).thenReturn("demo,walletreg");

    final ClientPolicyContext ctx = tokenRequest("2021006883:demo:write", "/orgs/2021006883/demo/_write");
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void tokenRequest_functionNotSupported_throwsInvalidTarget() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://api.example.com");
    params.putSingle("scope", "2021006883:demo:write");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClientModel);
    when(resourceClientModel.getAttribute("client_functions")).thenReturn("walletreg");

    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.TOKEN_REQUEST);
    final ClientPolicyException ex = assertThrows(ClientPolicyException.class,
        () -> executor.executeOnEvent(ctx));
    assertTrue("invalid_target".equals(ex.getError()),
        "Expected invalid_target error but got: " + ex.getError());
  }

  @Test
  void tokenRequest_noClientFunctions_passes() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://api.example.com");
    params.putSingle("scope", "2021006883:demo:write");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClientModel);
    when(resourceClientModel.getAttribute("client_functions")).thenReturn(null);

    final ClientPolicyContext ctx = tokenRequest("2021006883:demo:write", "/orgs/2021006883/demo/_write");
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void tokenRequest_blankClientFunctions_passes() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://api.example.com");
    params.putSingle("scope", "2021006883:demo:write");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClientModel);
    when(resourceClientModel.getAttribute("client_functions")).thenReturn("  ");

    final ClientPolicyContext ctx = tokenRequest("2021006883:demo:write", "/orgs/2021006883/demo/_write");
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  // ---------------------------------------------------------------------------
  // AUTHORIZATION_REQUEST tests
  // ---------------------------------------------------------------------------

  @Test
  void authRequest_resourceInQueryParam_validates() {
    // No form params for resource; resource comes from query params
    final MultivaluedMap<String, String> formParams = new MultivaluedHashMap<>();
    when(httpRequest.getDecodedFormParameters()).thenReturn(formParams);

    final MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
    queryParams.putSingle("resource", "https://api.example.com");
    queryParams.putSingle("scope", "2021006883:demo:write");
    when(uriInfo.getQueryParameters()).thenReturn(queryParams);

    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClientModel);
    when(resourceClientModel.getAttribute("client_functions")).thenReturn("demo");

    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.AUTHORIZATION_REQUEST);
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void authRequest_storesSessionNote() throws ClientPolicyException {
    final MultivaluedMap<String, String> formParams = new MultivaluedHashMap<>();
    formParams.putSingle("resource", "https://api.example.com");
    formParams.putSingle("scope", "2021006883:demo:write");
    when(httpRequest.getDecodedFormParameters()).thenReturn(formParams);

    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClientModel);
    when(resourceClientModel.getAttribute("client_functions")).thenReturn("demo");

    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.AUTHORIZATION_REQUEST);
    executor.executeOnEvent(ctx);

    verify(authSession).setClientNote(ScopeUtils.SESSION_NOTE_KEY, "https://api.example.com");
  }

  @Test
  void authRequest_functionNotSupported_throws() {
    final MultivaluedMap<String, String> formParams = new MultivaluedHashMap<>();
    formParams.putSingle("resource", "https://api.example.com");
    formParams.putSingle("scope", "2021006883:demo:write");
    when(httpRequest.getDecodedFormParameters()).thenReturn(formParams);

    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClientModel);
    when(resourceClientModel.getAttribute("client_functions")).thenReturn("walletreg");

    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.AUTHORIZATION_REQUEST);
    final ClientPolicyException ex = assertThrows(ClientPolicyException.class,
        () -> executor.executeOnEvent(ctx));
    assertTrue("invalid_target".equals(ex.getError()),
        "Expected invalid_target error but got: " + ex.getError());
  }

  // ---------------------------------------------------------------------------
  // Unhandled event
  // ---------------------------------------------------------------------------

  @Test
  void unhandledEvent_skipped() {
    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.TOKEN_REFRESH);
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
    // Ensure no client lookup happened
    verify(clientProvider, never()).getClientByClientId(any(), anyString());
  }

  // ---------------------------------------------------------------------------
  // Scope entitlement tests
  // ---------------------------------------------------------------------------

  @Test
  void entitlement_functionLevelRight_passes() {
    final ClientPolicyContext ctx =
        tokenRequest("2021006883:demo:write", "/orgs/2021006883/demo/_write");
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void entitlement_orgWideRight_passes() {
    final ClientPolicyContext ctx = tokenRequest("2021006883:demo:read", "/orgs/2021006883/_read");
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void entitlement_adminGrantsWrite_passes() {
    final ClientPolicyContext ctx =
        tokenRequest("2021006883:demo:write", "/orgs/2021006883/demo/_admin");
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void entitlement_adminGrantsRead_passes() {
    final ClientPolicyContext ctx =
        tokenRequest("2021006883:demo:read", "/orgs/2021006883/demo/_admin");
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void entitlement_writeGrantsRead_passes() {
    final ClientPolicyContext ctx =
        tokenRequest("2021006883:demo:read", "/orgs/2021006883/demo/_write");
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void entitlement_writeDoesNotGrantAdmin_throwsInvalidScope() {
    assertInvalidScope(tokenRequest("2021006883:demo:admin", "/orgs/2021006883/demo/_write"));
  }

  @Test
  void entitlement_readDoesNotGrantWrite_throwsInvalidScope() {
    assertInvalidScope(tokenRequest("2021006883:demo:write", "/orgs/2021006883/demo/_read"));
  }

  @Test
  void entitlement_rightInAnotherOrg_throwsInvalidScope() {
    assertInvalidScope(tokenRequest("2021006883:demo:read", "/orgs/1234567890/demo/_admin"));
  }

  @Test
  void entitlement_rightInAnotherFunction_throwsInvalidScope() {
    assertInvalidScope(tokenRequest("2021006883:demo:read", "/orgs/2021006883/walletreg/_admin"));
  }

  @Test
  void entitlement_noGroups_throwsInvalidScope() {
    assertInvalidScope(tokenRequest("2021006883:demo:read"));
  }

  @Test
  void entitlement_superuserWithoutGroups_passes() {
    final ClientPolicyContext ctx = tokenRequest("2021006883:demo:admin");
    when(realm.getRole("superuser")).thenReturn(superuserRole);
    when(user.hasRole(superuserRole)).thenReturn(true);

    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void entitlement_oneOfSeveralScopesUnentitled_throwsInvalidScope() {
    assertInvalidScope(tokenRequest(
        "openid 2021006883:demo:read 2021006883:demo:admin", "/orgs/2021006883/demo/_read"));
  }

  @Test
  void entitlement_nonOrgScopesIgnored_passes() {
    final ClientPolicyContext ctx = tokenRequest("openid profile https://id.oidc.se/scope/x");
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void entitlement_unknownRightLevelIgnored_passes() {
    final ClientPolicyContext ctx = tokenRequest("2021006883:demo:delete");
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void entitlement_noUserSession_throwsInvalidScope() {
    final TokenRequestContext ctx = mock(TokenRequestContext.class);
    when(ctx.getEvent()).thenReturn(ClientPolicyEvent.TOKEN_REQUEST);
    final OAuth2CodeParser.ParseResult parseResult = mock(OAuth2CodeParser.ParseResult.class);
    when(ctx.getParseResult()).thenReturn(parseResult);
    when(parseResult.getClientSession()).thenReturn(clientSession);
    when(clientSession.getNote(OIDCLoginProtocol.SCOPE_PARAM)).thenReturn("2021006883:demo:read");
    when(clientSession.getUserSession()).thenReturn(null);
    when(httpRequest.getDecodedFormParameters()).thenReturn(new MultivaluedHashMap<>());

    assertInvalidScope(ctx);
  }

  @Test
  void entitlement_scopeReadFromFormParamWhenNoteAbsent() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("scope", "2021006883:demo:admin");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);

    assertInvalidScope(tokenRequest(null, "/orgs/2021006883/demo/_read"));
  }

  @Test
  void entitlement_serviceAccountTokenRequest_skipped() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("scope", "2021006883:demo:admin");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);

    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.SERVICE_ACCOUNT_TOKEN_REQUEST);
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static ClientPolicyContext eventContext(final ClientPolicyEvent event) {
    return () -> event;
  }

  private void assertInvalidScope(final ClientPolicyContext ctx) {
    final ClientPolicyException ex = assertThrows(ClientPolicyException.class,
        () -> executor.executeOnEvent(ctx));
    assertEquals("invalid_scope", ex.getError());
  }

  /**
   * Builds a TOKEN_REQUEST context carrying an authenticated user with the given group memberships.
   *
   * @param scopeNote the scope held as a client session note, or {@code null} to force the
   *     executor to fall back to the scope form parameter
   * @param groupPaths the full paths of the groups the user is a direct member of
   * @return the context; the group stream is answered rather than returned, because a
   *     {@link java.util.stream.Stream} can only be consumed once
   */
  private ClientPolicyContext tokenRequest(final String scopeNote, final String... groupPaths) {
    final TokenRequestContext ctx = mock(TokenRequestContext.class);
    when(ctx.getEvent()).thenReturn(ClientPolicyEvent.TOKEN_REQUEST);

    final OAuth2CodeParser.ParseResult parseResult = mock(OAuth2CodeParser.ParseResult.class);
    when(ctx.getParseResult()).thenReturn(parseResult);
    when(parseResult.getClientSession()).thenReturn(clientSession);
    when(clientSession.getNote(OIDCLoginProtocol.SCOPE_PARAM)).thenReturn(scopeNote);
    when(clientSession.getUserSession()).thenReturn(userSession);
    when(userSession.getUser()).thenReturn(user);
    when(user.getUsername()).thenReturn("test-user");
    when(user.getGroupsStream()).thenAnswer(
        invocation -> Arrays.stream(groupPaths).map(ResourceFunctionExecutorTest::groupOf));

    if (httpRequest.getDecodedFormParameters() == null) {
      when(httpRequest.getDecodedFormParameters()).thenReturn(new MultivaluedHashMap<>());
    }
    return ctx;
  }

  /** Builds a group mock chain from a full path, e.g. {@code /orgs/2021006883/demo/_admin}. */
  private static GroupModel groupOf(final String path) {
    GroupModel parent = null;
    GroupModel current = null;
    for (final String segment : path.substring(1).split("/")) {
      current = mock(GroupModel.class);
      when(current.getName()).thenReturn(segment);
      when(current.getParent()).thenReturn(parent);
      parent = current;
    }
    return current;
  }
}
