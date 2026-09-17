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
package se.swedenconnect.iam.admin.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import se.swedenconnect.iam.admin.controllers.dto.CreateManagedClientRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateManagedClientRequest;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;
import se.swedenconnect.iam.admin.service.ClientReconciliationService;
import se.swedenconnect.iam.admin.service.model.ReconciliationReport;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the authorization gates and request validation of {@link ClientController}.
 *
 * <p>A client plays one or both of two roles — OIDC client and resource server — and the fields a
 * request must carry depend on which roles are set.</p>
 *
 * @author Felix Hellman
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClientControllerTest {

  private static final String CLIENT_UUID = "b8f1c0e2-0000-0000-0000-000000000001";
  private static final String SERVICE_UUID = "b8f1c0e2-0000-0000-0000-000000000002";
  private static final String CLIENT_ID = "https://demo-app.example.se";
  private static final String SERVICE_ID = "https://registry.example.se";
  private static final String REDIRECT_URI = "https://demo-app.example.se/login/oauth2/code/orgiam";
  private static final String JWKS_URI = "https://demo-app.example.se/jwks";

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  @Mock
  private ClientReconciliationService reconciliationService;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpSession session;

  private ClientController controller;

  @BeforeEach
  void setUp() {
    this.controller = new ClientController(this.keycloakAdminClient, this.reconciliationService);

    when(this.request.getSession(false)).thenReturn(this.session);
    when(this.keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(
        new FunctionInfo("demo", new LocalizedString(Map.of("sv", "Demo", "en", "Demo")), null)));
  }

  // ---------------------------------------------------------------------------
  // Authorization gates
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Every endpoint is closed to non-superusers")
  void nonSuperuserIsRejectedEverywhere() {
    setupSession(false);

    assertThat(this.controller.getClients(this.request).getStatusCode().value()).isEqualTo(403);
    assertThat(this.controller.getClient(CLIENT_UUID, this.request).getStatusCode().value()).isEqualTo(403);
    assertThat(this.controller.createClient(validCreateRequest(), this.request)
        .getStatusCode().value()).isEqualTo(403);
    assertThat(this.controller.updateClient(CLIENT_UUID, validUpdateRequest(), this.request)
        .getStatusCode().value()).isEqualTo(403);
    assertThat(this.controller.reconcileClient(CLIENT_UUID, this.request)
        .getStatusCode().value()).isEqualTo(403);
    assertThat(this.controller.reconcileAll(this.request).getStatusCode().value()).isEqualTo(403);

    verifyNoClientCreated();
    verify(this.reconciliationService, never()).reconcileAll();
  }

  @Test
  @DisplayName("Deletion is refused for a non-superuser")
  void deletionRequiresSuperuser() {
    setupSession(false);

    assertThat(this.controller.deleteClient(CLIENT_UUID, this.request).getStatusCode().value())
        .isEqualTo(403);
    verify(this.keycloakAdminClient, never()).deleteManagedClient(anyString());
  }

  // ---------------------------------------------------------------------------
  // Roles
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("A client with neither role is rejected")
  void noRoleIsRejected() {
    setupSession(true);

    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(false, false, null, Set.of("demo"), null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("OIDC client, a resource server, or both");
    verifyNoClientCreated();
  }

  @Test
  @DisplayName("A resource-server-only client needs no redirect URIs or client keys")
  void resourceServerOnlyNeedsNoRedirectUrisOrKeys() {
    setupSession(true);
    when(this.keycloakAdminClient.clientExists(SERVICE_ID)).thenReturn(false);
    whenCreateReturns(resourceServer());

    final ResponseEntity<?> response = this.controller.createClient(
        new CreateManagedClientRequest(SERVICE_ID, "Registry", false, true, null, Set.of("demo"),
            null, null, null, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createManagedClient(
        SERVICE_ID, "Registry", false, true, List.of(), Set.of("demo"), null, null, false, true, true);
    verify(this.reconciliationService, never()).reconcileClient(anyString());
  }

  @Test
  @DisplayName("A client may hold both roles at once, and is reconciled")
  void bothRolesAreAllowed() {
    setupSession(true);
    when(this.keycloakAdminClient.clientExists(CLIENT_ID)).thenReturn(false);
    whenCreateReturns(dualRoleClient());

    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(true, true, List.of(REDIRECT_URI), Set.of("demo"), JWKS_URI, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createManagedClient(
        CLIENT_ID, "Demo", true, true, List.of(REDIRECT_URI), Set.of("demo"), JWKS_URI, null,
        false, true, true);
    verify(this.reconciliationService).reconcileClient(CLIENT_ID);
  }

  @Test
  @DisplayName("Reconciling a resource-server-only client is rejected — it holds no artifacts")
  void reconcilingAResourceServerIsRejected() {
    setupSession(true);
    when(this.keycloakAdminClient.findManagedClientByUuid(SERVICE_UUID))
        .thenReturn(Optional.of(resourceServer()));

    final ResponseEntity<?> response =
        this.controller.reconcileClient(SERVICE_UUID, this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    verify(this.reconciliationService, never()).reconcileClient(anyString());
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("A redirect URI ending in a wildcard is accepted and reaches Keycloak unchanged")
  void trailingWildcardRedirectUriIsAccepted() {
    setupSession(true);
    when(this.keycloakAdminClient.clientExists(CLIENT_ID)).thenReturn(false);
    whenCreateReturns(managedClient());

    final String uri = "https://demo-app.example.se/login/oauth2/code/*";
    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(true, false, List.of(uri), Set.of("demo"), JWKS_URI, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createManagedClient(
        anyString(), any(), anyBoolean(), anyBoolean(), eq(List.of(uri)), anySet(), any(), any(),
        anyBoolean(), anyBoolean(), anyBoolean());
  }

  /**
   * Keycloak treats a redirect URI as a wildcard pattern only when the {@code *} is the last
   * character and the pattern carries no query string. Anywhere else it is matched literally, which
   * would produce a client whose callbacks silently never match, so each of these forms is refused.
   */
  @ParameterizedTest
  @DisplayName("A wildcard anywhere but at the end is rejected")
  @ValueSource(strings = {
      "https://*.example.se/cb",
      "https://demo-app.example.se/*/cb",
      "https://demo-app.example.se/cb?next=*",
      "*://demo-app.example.se/cb",
      "https://demo-app.example.se/cb/**"
  })
  void misplacedWildcardRedirectUriIsRejected(final String uri) {
    setupSession(true);

    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(true, false, List.of(uri), Set.of("demo"), JWKS_URI, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("only allowed as the last character");
  }

  @Test
  @DisplayName("A relative redirect URI is rejected — it must be completed first")
  void relativeRedirectUriIsRejected() {
    setupSession(true);

    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(true, false, List.of("/login/oauth2/code/orgiam"), Set.of("demo"),
            JWKS_URI, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("absolute URI");
  }

  @Test
  @DisplayName("A relative redirect URI ending in a wildcard is rejected too")
  void relativeWildcardRedirectUriIsRejected() {
    setupSession(true);

    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(true, false, List.of("/login/oauth2/code/*"), Set.of("demo"),
            JWKS_URI, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("absolute URI");
  }

  @Test
  @DisplayName("A client may be created with no functions — it simply receives no artifacts")
  void emptyFunctionListIsAccepted() {
    setupSession(true);
    when(this.keycloakAdminClient.clientExists(CLIENT_ID)).thenReturn(false);
    whenCreateReturns(managedClient());

    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(true, false, List.of(REDIRECT_URI), Set.of(), JWKS_URI, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createManagedClient(
        CLIENT_ID, "Demo", true, false, List.of(REDIRECT_URI), Set.of(), JWKS_URI, null,
        false, true, true);
  }

  @Test
  @DisplayName("An omitted function list is accepted as no functions")
  void nullFunctionListIsAccepted() {
    setupSession(true);
    when(this.keycloakAdminClient.clientExists(SERVICE_ID)).thenReturn(false);
    whenCreateReturns(resourceServer());

    final ResponseEntity<?> response = this.controller.createClient(
        new CreateManagedClientRequest(SERVICE_ID, "Registry", false, true, null, null,
            null, null, null, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createManagedClient(
        SERVICE_ID, "Registry", false, true, List.of(), Set.of(), null, null, false, true, true);
  }

  @Test
  @DisplayName("An unknown function is rejected")
  void unknownFunctionIsRejected() {
    setupSession(true);

    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(true, false, List.of(REDIRECT_URI), Set.of("walletreg"), JWKS_URI, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("unknown function");
  }

  @Test
  @DisplayName("Supplying both JWKS forms is rejected")
  void bothJwksFormsAreRejected() {
    setupSession(true);

    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(true, false, List.of(REDIRECT_URI), Set.of("demo"), JWKS_URI, "{\"keys\":[]}"),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("exactly one of");
  }

  @Test
  @DisplayName("Supplying neither JWKS form is rejected for an OIDC client")
  void neitherJwksFormIsRejected() {
    setupSession(true);

    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(true, false, List.of(REDIRECT_URI), Set.of("demo"), null, null), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("exactly one of");
  }

  @Test
  @DisplayName("A plain http JWKS URI is rejected")
  void nonHttpsJwksUriIsRejected() {
    setupSession(true);

    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(true, false, List.of(REDIRECT_URI), Set.of("demo"),
            "http://demo-app.example.se/jwks", null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("absolute https URI");
  }

  @Test
  @DisplayName("An unparsable inline JWK Set is rejected")
  void malformedJwksStringIsRejected() {
    setupSession(true);

    final ResponseEntity<?> response = this.controller.createClient(
        createRequest(true, false, List.of(REDIRECT_URI), Set.of("demo"), null, "not-a-jwk-set"),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).asString().contains("valid JWK Set");
  }

  @Test
  @DisplayName("A taken client_id yields 409")
  void duplicateClientIdIsRejected() {
    setupSession(true);
    when(this.keycloakAdminClient.clientExists(CLIENT_ID)).thenReturn(true);

    assertThat(this.controller.createClient(validCreateRequest(), this.request)
        .getStatusCode().value()).isEqualTo(409);
    verifyNoClientCreated();
  }

  @Test
  @DisplayName("A valid request creates the client and reconciles it")
  void validRequestCreatesAndReconciles() {
    setupSession(true);
    when(this.keycloakAdminClient.clientExists(CLIENT_ID)).thenReturn(false);
    whenCreateReturns(managedClient());

    final ResponseEntity<?> response = this.controller.createClient(validCreateRequest(), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createManagedClient(
        CLIENT_ID, "Demo", true, false, List.of(REDIRECT_URI), Set.of("demo"), JWKS_URI, null,
        false, true, true);
    verify(this.reconciliationService).reconcileClient(CLIENT_ID);
  }

  @Test
  @DisplayName("An update carries the org_rights switches, keeping the client's own when omitted")
  void updateCarriesTokenSettings() {
    setupSession(true);
    when(this.keycloakAdminClient.findManagedClientByUuid(CLIENT_UUID))
        .thenReturn(Optional.of(managedClient()));
    when(this.keycloakAdminClient.updateManagedClient(
        anyString(), any(), anyBoolean(), anyBoolean(), anyList(), anySet(), any(), any(),
        anyBoolean(), anyBoolean(), anyBoolean()))
        .thenReturn(managedClient());

    assertThat(this.controller.updateClient(CLIENT_UUID, validUpdateRequest(), this.request)
        .getStatusCode().value()).isEqualTo(200);
    verify(this.keycloakAdminClient).updateManagedClient(
        CLIENT_ID, "Demo", true, false, List.of(REDIRECT_URI), Set.of("demo"), JWKS_URI, null,
        false, true, true);

    this.controller.updateClient(CLIENT_UUID,
        new UpdateManagedClientRequest("Demo", true, false, List.of(REDIRECT_URI),
            Set.of("demo"), JWKS_URI, null, false, false),
        this.request);
    verify(this.keycloakAdminClient).updateManagedClient(
        CLIENT_ID, "Demo", true, false, List.of(REDIRECT_URI), Set.of("demo"), JWKS_URI, null,
        false, false, false);
  }

  @Test
  @DisplayName("Updating a resource-server-only client passes its display name through")
  void updateOfAResourceServerCarriesTheDisplayName() {
    setupSession(true);
    when(this.keycloakAdminClient.findManagedClientByUuid(SERVICE_UUID))
        .thenReturn(Optional.of(resourceServer()));
    when(this.keycloakAdminClient.updateManagedClient(
        anyString(), any(), anyBoolean(), anyBoolean(), anyList(), anySet(), any(), any(),
        anyBoolean(), anyBoolean(), anyBoolean()))
        .thenReturn(resourceServer());

    final ResponseEntity<?> response = this.controller.updateClient(SERVICE_UUID,
        new UpdateManagedClientRequest("Client Registry", false, true, null, Set.of("demo"),
            null, null, null, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(this.keycloakAdminClient).updateManagedClient(
        SERVICE_ID, "Client Registry", false, true, List.of(), Set.of("demo"), null, null,
        false, true, true);
  }

  @Test
  @DisplayName("A resource server may be saved with no display name")
  void resourceServerWithoutADisplayNameIsAccepted() {
    setupSession(true);
    when(this.keycloakAdminClient.clientExists(SERVICE_ID)).thenReturn(false);
    whenCreateReturns(resourceServer());

    final ResponseEntity<?> response = this.controller.createClient(
        new CreateManagedClientRequest(SERVICE_ID, null, false, true, null, Set.of("demo"),
            null, null, null, null),
        this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(this.keycloakAdminClient).createManagedClient(
        SERVICE_ID, null, false, true, List.of(), Set.of("demo"), null, null, false, true, true);
  }

  @Test
  @DisplayName("An update never touches the client's service account")
  void updateLeavesTheServiceAccountAlone() {
    setupSession(true);
    when(this.keycloakAdminClient.findManagedClientByUuid(CLIENT_UUID))
        .thenReturn(Optional.of(serviceAccountClient()));
    when(this.keycloakAdminClient.updateManagedClient(
        anyString(), any(), anyBoolean(), anyBoolean(), anyList(), anySet(), any(), any(),
        anyBoolean(), anyBoolean(), anyBoolean()))
        .thenReturn(serviceAccountClient());

    this.controller.updateClient(CLIENT_UUID, validUpdateRequest(), this.request);

    verify(this.keycloakAdminClient).updateManagedClient(
        CLIENT_ID, "Demo", true, false, List.of(REDIRECT_URI), Set.of("demo"), JWKS_URI, null,
        true, true, true);
  }

  @Test
  @DisplayName("A client registered here never keeps a service account")
  void createNeverKeepsAServiceAccount() {
    setupSession(true);
    when(this.keycloakAdminClient.clientExists(CLIENT_ID)).thenReturn(false);
    whenCreateReturns(managedClient());

    this.controller.createClient(validCreateRequest(), this.request);

    verify(this.keycloakAdminClient).createManagedClient(
        CLIENT_ID, "Demo", true, false, List.of(REDIRECT_URI), Set.of("demo"), JWKS_URI, null,
        false, true, true);
  }

  @Test
  @DisplayName("A client holding a service account cannot be deleted")
  void serviceAccountClientCannotBeDeleted() {
    setupSession(true);
    when(this.keycloakAdminClient.findManagedClientByUuid(CLIENT_UUID))
        .thenReturn(Optional.of(serviceAccountClient()));

    final ResponseEntity<?> response = this.controller.deleteClient(CLIENT_UUID, this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    verify(this.keycloakAdminClient, never()).deleteManagedClient(anyString());
  }

  // ---------------------------------------------------------------------------
  // Addressing
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Per-client endpoints address the client by its Keycloak UUID, not its client_id")
  void perClientEndpointsResolveByUuid() {
    setupSession(true);
    when(this.keycloakAdminClient.findManagedClientByUuid(CLIENT_UUID))
        .thenReturn(Optional.of(managedClient()));
    when(this.reconciliationService.reconcileClient(CLIENT_ID))
        .thenReturn(new ReconciliationReport(1, 3, 0, List.of()));

    assertThat(this.controller.getClient(CLIENT_UUID, this.request).getStatusCode().value()).isEqualTo(200);
    assertThat(this.controller.reconcileClient(CLIENT_UUID, this.request)
        .getStatusCode().value()).isEqualTo(200);
    assertThat(this.controller.deleteClient(CLIENT_UUID, this.request).getStatusCode().value()).isEqualTo(204);

    verify(this.reconciliationService).reconcileClient(CLIENT_ID);
    verify(this.keycloakAdminClient).deleteManagedClient(CLIENT_ID);
  }

  @Test
  @DisplayName("An unknown UUID yields 404 on every per-client endpoint")
  void unknownUuidYields404() {
    setupSession(true);
    when(this.keycloakAdminClient.findManagedClientByUuid(anyString())).thenReturn(Optional.empty());

    assertThat(this.controller.getClient("missing", this.request).getStatusCode().value()).isEqualTo(404);
    assertThat(this.controller.updateClient("missing", validUpdateRequest(), this.request)
        .getStatusCode().value()).isEqualTo(404);
    assertThat(this.controller.reconcileClient("missing", this.request)
        .getStatusCode().value()).isEqualTo(404);
    assertThat(this.controller.deleteClient("missing", this.request).getStatusCode().value()).isEqualTo(404);
    verify(this.keycloakAdminClient, never()).deleteManagedClient(anyString());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void whenCreateReturns(final ManagedClientInfo client) {
    when(this.keycloakAdminClient.createManagedClient(
        anyString(), any(), anyBoolean(), anyBoolean(), anyList(), anySet(), any(), any(),
        anyBoolean(), anyBoolean(), anyBoolean()))
        .thenReturn(client);
  }

  private void verifyNoClientCreated() {
    verify(this.keycloakAdminClient, never()).createManagedClient(
        anyString(), any(), anyBoolean(), anyBoolean(), anyList(), anySet(), any(), any(),
        anyBoolean(), anyBoolean(), anyBoolean());
  }

  private static ManagedClientInfo managedClient() {
    return new ManagedClientInfo(CLIENT_UUID, CLIENT_ID, "Demo", true, false,
        Set.of("demo"), false, List.of(REDIRECT_URI), JWKS_URI, null, false, true, true, true);
  }

  private static ManagedClientInfo serviceAccountClient() {
    return new ManagedClientInfo(CLIENT_UUID, CLIENT_ID, "Demo", true, false,
        Set.of("demo"), false, List.of(REDIRECT_URI), JWKS_URI, null, true, true, true, true);
  }

  private static ManagedClientInfo dualRoleClient() {
    return new ManagedClientInfo(CLIENT_UUID, CLIENT_ID, "Demo", true, true,
        Set.of("demo"), false, List.of(REDIRECT_URI), JWKS_URI, null, false, true, true, true);
  }

  private static ManagedClientInfo resourceServer() {
    return new ManagedClientInfo(SERVICE_UUID, SERVICE_ID, "Registry", false, true,
        Set.of("demo"), false, List.of(), null, null, false, true, true, true);
  }

  private static CreateManagedClientRequest validCreateRequest() {
    return createRequest(true, false, List.of(REDIRECT_URI), Set.of("demo"), JWKS_URI, null);
  }

  private static UpdateManagedClientRequest validUpdateRequest() {
    return new UpdateManagedClientRequest("Demo", true, false, List.of(REDIRECT_URI),
        Set.of("demo"), JWKS_URI, null, null, null);
  }

  private static CreateManagedClientRequest createRequest(
      final boolean oidcClient, final boolean resourceServer,
      final List<String> redirectUris, final Set<String> functions,
      final String jwksUri, final String jwksString) {

    return new CreateManagedClientRequest(
        CLIENT_ID, "Demo", oidcClient, resourceServer, redirectUris, functions,
        jwksUri, jwksString, null, null);
  }

  private void setupSession(final boolean superuser) {
    final AdminSessionData data = new AdminSessionData(
        superuser, null, null, List.of(), Set.of(),
        new OrgRightsClaim(superuser, List.of()));
    when(this.session.getAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR)).thenReturn(data);
  }
}
