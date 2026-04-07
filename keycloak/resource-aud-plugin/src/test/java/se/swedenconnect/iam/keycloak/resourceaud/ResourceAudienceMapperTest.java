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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientProvider;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.representations.IDToken;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResourceAudienceMapper}.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResourceAudienceMapperTest {

  private ResourceAudienceMapper mapper;

  @Mock
  private KeycloakSession keycloakSession;

  @Mock
  private KeycloakContext keycloakContext;

  @Mock
  private HttpRequest httpRequest;

  @Mock
  private ClientSessionContext clientSessionCtx;

  @Mock
  private AuthenticatedClientSessionModel clientSession;

  @Mock
  private RealmModel realm;

  @Mock
  private ClientProvider clientProvider;

  @Mock
  private ClientModel resourceClient;

  @Mock
  private ProtocolMapperModel mappingModel;

  @Mock
  private UserSessionModel userSession;

  @BeforeEach
  void setUp() {
    mapper = new ResourceAudienceMapper();
    when(keycloakSession.getContext()).thenReturn(keycloakContext);
    when(keycloakContext.getRealm()).thenReturn(realm);
    when(keycloakContext.getHttpRequest()).thenReturn(httpRequest);
    when(keycloakSession.clients()).thenReturn(clientProvider);
    when(clientSessionCtx.getClientSession()).thenReturn(clientSession);
    when(clientSession.getNote(anyString())).thenReturn(null);
  }

  @Test
  void setClaim_resourceAndScope_setsAudArray() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://api.example.com");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientSessionCtx.getScopeString(false)).thenReturn("5590026042:demo:write");
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClient);
    when(resourceClient.getAttribute("client_functions")).thenReturn("demo");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    final String[] aud = token.getAudience();
    assertArrayEquals(new String[]{"https://api.example.com", "demo"}, aud);
  }

  @Test
  void setClaim_noResource_audNotModified() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientSessionCtx.getScopeString(false)).thenReturn("5590026042:demo:read");
    // clientSession.getNote returns null (set up in setUp)

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    assertNull(token.getAudience());
  }

  @Test
  void setClaim_resourceFromSessionNote() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientSession.getNote(ScopeUtils.SESSION_NOTE_KEY)).thenReturn("https://api.example.com");
    when(clientSessionCtx.getScopeString(false)).thenReturn("5590026042:demo:write");
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClient);
    when(resourceClient.getAttribute("client_functions")).thenReturn("demo");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    final String[] aud = token.getAudience();
    assertArrayEquals(new String[]{"https://api.example.com", "demo"}, aud);
  }

  @Test
  void setClaim_noOrgScope_audNotModified() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://api.example.com");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientSessionCtx.getScopeString(false)).thenReturn("openid");
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClient);
    when(resourceClient.getAttribute("client_functions")).thenReturn("demo");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    assertNull(token.getAudience());
  }

  @Test
  void setClaim_nullScope_audNotModified() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://api.example.com");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientSessionCtx.getScopeString(false)).thenReturn(null);
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClient);
    when(resourceClient.getAttribute("client_functions")).thenReturn("demo");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    assertNull(token.getAudience());
  }

  @Test
  void setClaim_formParamTakesPrecedenceOverNote() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://form.example.com");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientSession.getNote(ScopeUtils.SESSION_NOTE_KEY)).thenReturn("https://note.example.com");
    when(clientSessionCtx.getScopeString(false)).thenReturn("5590026042:demo:write");
    when(clientProvider.getClientByClientId(realm, "https://form.example.com")).thenReturn(resourceClient);
    when(resourceClient.getAttribute("client_functions")).thenReturn("demo");

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    final String[] aud = token.getAudience();
    assertArrayEquals(new String[]{"https://form.example.com", "demo"}, aud);
  }

  @Test
  void setClaim_multiFunction_noClientFunctions() {
    final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
    params.putSingle("resource", "https://api.example.com");
    when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    when(clientSessionCtx.getScopeString(false)).thenReturn("5590026042:demo:write 5561234567:walletreg:read");
    when(clientProvider.getClientByClientId(realm, "https://api.example.com")).thenReturn(resourceClient);
    when(resourceClient.getAttribute("client_functions")).thenReturn(null);

    final IDToken token = new IDToken();
    mapper.setClaim(token, mappingModel, userSession, keycloakSession, clientSessionCtx);

    final String[] aud = token.getAudience();
    final List<String> audList = Arrays.asList(aud);
    // aud must contain resource + both functions
    org.junit.jupiter.api.Assertions.assertTrue(audList.contains("https://api.example.com"));
    org.junit.jupiter.api.Assertions.assertTrue(audList.contains("demo"));
    org.junit.jupiter.api.Assertions.assertTrue(audList.contains("walletreg"));
    org.junit.jupiter.api.Assertions.assertEquals(3, aud.length);
  }
}
