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
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.clientpolicy.ClientPolicyContext;
import org.keycloak.services.clientpolicy.ClientPolicyEvent;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    params.putSingle("scope", "5590026042:demo:write");
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
    params.putSingle("scope", "5590026042:demo:write");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClientModel);
    when(resourceClientModel.getAttribute("client_functions")).thenReturn("demo,walletreg");

    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.TOKEN_REQUEST);
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void tokenRequest_functionNotSupported_throwsInvalidTarget() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://api.example.com");
    params.putSingle("scope", "5590026042:demo:write");
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
    params.putSingle("scope", "5590026042:demo:write");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClientModel);
    when(resourceClientModel.getAttribute("client_functions")).thenReturn(null);

    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.TOKEN_REQUEST);
    assertDoesNotThrow(() -> executor.executeOnEvent(ctx));
  }

  @Test
  void tokenRequest_blankClientFunctions_passes() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://api.example.com");
    params.putSingle("scope", "5590026042:demo:write");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClientModel);
    when(resourceClientModel.getAttribute("client_functions")).thenReturn("  ");

    final ClientPolicyContext ctx = eventContext(ClientPolicyEvent.TOKEN_REQUEST);
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
    queryParams.putSingle("scope", "5590026042:demo:write");
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
    formParams.putSingle("scope", "5590026042:demo:write");
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
    formParams.putSingle("scope", "5590026042:demo:write");
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
  // Helper
  // ---------------------------------------------------------------------------

  private static ClientPolicyContext eventContext(final ClientPolicyEvent event) {
    return () -> event;
  }
}
