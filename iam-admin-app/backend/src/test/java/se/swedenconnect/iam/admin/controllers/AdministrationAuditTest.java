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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import se.swedenconnect.iam.admin.audit.RecordingAuditPublisher;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.CreateFunctionRequest;
import se.swedenconnect.iam.admin.controllers.dto.CreateManagedClientRequest;
import se.swedenconnect.iam.admin.controllers.dto.CreateOrganizationRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateFunctionRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateManagedClientRequest;
import se.swedenconnect.iam.admin.controllers.dto.UpdateOrganizationRequest;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportExportBundle;
import se.swedenconnect.iam.admin.keycloak.AdminSessionBootstrapHandler;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.AdminSessionData;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.keycloak.model.RightHolder;
import se.swedenconnect.iam.admin.service.ClientReconciliationService;
import se.swedenconnect.iam.admin.service.ImportExportService;
import se.swedenconnect.iam.admin.service.OrganizationService;
import se.swedenconnect.iam.admin.service.model.ReconciliationReport;
import se.swedenconnect.iam.commons.types.LocalizedString;
import se.swedenconnect.iam.commons.types.OrganizationID;
import se.swedenconnect.iam.security.claims.OrgRightsClaim;
import se.swedenconnect.iam.security.claims.OrgRightsClaimParser;
import se.swedenconnect.spring.audit.AuditEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static se.swedenconnect.iam.admin.audit.RecordingAuditPublisher.object;

/**
 * Tests the audit events of the organization, function, client, export and IAM service API
 * endpoints.
 *
 * @author Martin Lindström
 */
class AdministrationAuditTest {

  private static final String ICA = "5565552014";
  private static final String FUNC = "demo";
  private static final String CLIENT_UUID = "client-uuid";

  private final RecordingAuditPublisher publisher = new RecordingAuditPublisher();
  private final KeycloakAdminClient keycloakAdminClient = mock(KeycloakAdminClient.class);
  private final OrganizationService organizationService = mock(OrganizationService.class);
  private final ClientReconciliationService reconciliationService = mock(ClientReconciliationService.class);
  private final IamAdminProperties properties = new IamAdminProperties();

  private MockHttpServletRequest request;
  private MockHttpSession session;

  @BeforeEach
  void setUp() {
    this.session = new MockHttpSession();
    this.request = new MockHttpServletRequest();
    this.request.setSession(this.session);
    this.superuser();
    when(this.keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(
        new FunctionInfo(FUNC, new LocalizedString(Map.of("sv", "Demo", "en", "Demo")), null)));
  }

  @AfterEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
  }

  // Organizations

  private OrganizationController organizationController() {
    return new OrganizationController(this.organizationService, this.keycloakAdminClient, this.publisher);
  }

  @Test
  void createOrganization_organizationCreated() {
    this.organizationController().createOrganization(
        new CreateOrganizationRequest(ICA, "ICA AB", null, null, null, null), this.request);

    assertThat(this.publisher.single("organization_created").getData()).containsExactly(
        Map.entry("organization_identifier", ICA), Map.entry("organization_legal_name", "ICA AB"));
  }

  @Test
  void createOrganization_notSuperuser_accessDenied() {
    this.regularAdmin();

    this.organizationController().createOrganization(
        new CreateOrganizationRequest(ICA, "ICA AB", null, null, null, null), this.request);

    final AuditEvent event = this.publisher.single("access_denied");
    assertThat(object(event, "operation")).containsEntry("reason", "not_superuser");
    assertThat(event.getData()).containsEntry("organization_identifier", ICA);
  }

  @Test
  void updateOrganization_changedFields() {
    when(this.organizationService.findByOrgNumber(ICA)).thenReturn(Optional.of(org("ICA AB", null)));
    when(this.organizationService.update(anyString(), any(), any(), any(), any(), any()))
        .thenReturn(org("ICA AB", "info@ica.se"));

    this.organizationController().updateOrganization(ICA,
        new UpdateOrganizationRequest(null, null, null, "info@ica.se", null), this.request);

    final AuditEvent event = this.publisher.single("organization_updated");
    assertThat(event.getData()).containsEntry("organization_identifier", ICA);
    assertThat(object(event, "change")).containsExactly(Map.entry("changed_fields", List.of("contact_email")));
  }

  @Test
  void updateOrganization_nothingChanged_noEvent() {
    when(this.organizationService.findByOrgNumber(ICA)).thenReturn(Optional.of(org("ICA AB", null)));
    when(this.organizationService.update(anyString(), any(), any(), any(), any(), any()))
        .thenReturn(org("ICA AB", null));

    this.organizationController().updateOrganization(ICA,
        new UpdateOrganizationRequest("ICA AB", null, null, null, null), this.request);

    assertThat(this.publisher.events()).isEmpty();
  }

  @Test
  void updateOrganization_nameChangeByNonSuperuser_accessDenied() {
    this.regularAdmin();

    this.organizationController().updateOrganization(ICA,
        new UpdateOrganizationRequest("New name", null, null, null, null), this.request);

    assertThat(object(this.publisher.single("access_denied"), "operation"))
        .containsExactly(Map.entry("name", "update_organization"), Map.entry("reason", "name_change_not_allowed"));
  }

  @Test
  void deleteOrganization_removedRightsReadBeforeDeletion() {
    when(this.organizationService.exists(ICA)).thenReturn(true);
    when(this.organizationService.findByOrgNumber(ICA)).thenReturn(Optional.of(org("ICA AB", null)));
    when(this.keycloakAdminClient.fetchRightHolders(ICA, null))
        .thenReturn(List.of(new RightHolder("u1", "Klara", ICA, null, "admin")));

    this.organizationController().deleteOrganization(ICA, this.request);

    final AuditEvent event = this.publisher.single("organization_deleted");
    assertThat(event.getData()).containsEntry("organization_legal_name", "ICA AB");
    assertThat(event.getData().get("removed_rights")).isEqualTo(List.of(Map.of(
        "user_id", "u1", "display_name", "Klara", "organization_identifier", ICA, "right", "admin")));
  }

  @Test
  void deleteOrganization_hasFunctions_operationRejected() {
    when(this.organizationService.exists(ICA)).thenReturn(true);
    when(this.organizationService.hasFunctionsAttached(ICA)).thenReturn(true);

    this.organizationController().deleteOrganization(ICA, this.request);

    assertThat(object(this.publisher.single("operation_rejected"), "operation"))
        .containsEntry("reason", "organization_has_functions");
  }

  /** An ordinary 404 is not audited. */
  @Test
  void deleteOrganization_notFound_notAudited() {
    this.organizationController().deleteOrganization(ICA, this.request);

    assertThat(this.publisher.events()).isEmpty();
  }

  // Functions

  private FunctionController functionController() {
    return new FunctionController(this.keycloakAdminClient, this.properties, this.organizationService, this.publisher);
  }

  @Test
  void createFunction_functionCreated() {
    this.functionController().createFunction(
        new CreateFunctionRequest("newfunc", "Ny", "New", null, null), this.request);

    assertThat(this.publisher.single("function_created").getData()).containsExactly(Map.entry("function_id", "newfunc"));
  }

  /** A function that cannot be added to the all-functions clients raises a system alert. */
  @Test
  void createFunction_allFunctionsClientsFailure_systemAlert() {
    when(this.keycloakAdminClient.materializeAllFunctions("newfunc")).thenThrow(new KeycloakAdminException("down"));

    this.functionController().createFunction(
        new CreateFunctionRequest("newfunc", "Ny", "New", null, null), this.request);

    assertThat(this.publisher.types()).containsExactly("function_created", "system_alert");
  }

  @Test
  void createFunction_conflict_notAudited() {
    when(this.keycloakAdminClient.functionExists("newfunc")).thenReturn(true);

    this.functionController().createFunction(
        new CreateFunctionRequest("newfunc", "Ny", "New", null, null), this.request);

    assertThat(this.publisher.events()).isEmpty();
  }

  @Test
  void updateFunction_changedFields() {
    when(this.keycloakAdminClient.functionExists(FUNC)).thenReturn(true);

    this.functionController().updateFunction(FUNC, new UpdateFunctionRequest("Demo", "Demo", "Beskrivning", null),
        this.request);

    assertThat(object(this.publisher.single("function_updated"), "change"))
        .containsExactly(Map.entry("changed_fields", List.of("description_sv")));
  }

  @Test
  void deleteFunction_removedRightsFromEveryOrganization() {
    this.properties.setAllowFunctionRemoval(true);
    when(this.keycloakAdminClient.functionExists(FUNC)).thenReturn(true);
    when(this.keycloakAdminClient.fetchOrgIdentifiersForFunction(FUNC)).thenReturn(List.of(ICA, "2021006883"));
    when(this.keycloakAdminClient.fetchRightHolders(ICA, FUNC))
        .thenReturn(List.of(new RightHolder("u1", null, ICA, FUNC, "read")));
    when(this.keycloakAdminClient.fetchRightHolders("2021006883", FUNC))
        .thenReturn(List.of(new RightHolder("u2", null, "2021006883", FUNC, "admin")));

    this.functionController().deleteFunction(FUNC, this.request);

    assertThat((List<?>) this.publisher.single("function_deleted").getData().get("removed_rights")).hasSize(2);
  }

  @Test
  void deleteFunction_removalDisabled_accessDenied() {
    this.functionController().deleteFunction(FUNC, this.request);

    assertThat(object(this.publisher.single("access_denied"), "operation"))
        .containsEntry("reason", "function_removal_disabled");
  }

  @Test
  void attachFunction_functionAttached() {
    when(this.keycloakAdminClient.organizationExists(ICA)).thenReturn(true);
    when(this.keycloakAdminClient.functionExists(FUNC)).thenReturn(true);

    new OrganizationFunctionController(this.keycloakAdminClient, this.organizationService, this.publisher)
        .attachFunction(ICA, FUNC, this.request);

    assertThat(this.publisher.types()).containsExactly("function_attached");
  }

  /** A failed attachment is recorded as failed, not as attached. */
  @Test
  void attachFunction_keycloakError_operationFailedOnly() {
    when(this.keycloakAdminClient.organizationExists(ICA)).thenReturn(true);
    when(this.keycloakAdminClient.functionExists(FUNC)).thenReturn(true);
    doThrow(new KeycloakAdminException("down")).when(this.keycloakAdminClient).attachFunctionToOrg(ICA, FUNC);

    new OrganizationFunctionController(this.keycloakAdminClient, this.organizationService, this.publisher)
        .attachFunction(ICA, FUNC, this.request);

    assertThat(this.publisher.types()).containsExactly("operation_failed");
  }

  @Test
  void detachFunction_removedRights() {
    when(this.keycloakAdminClient.isFunctionAttachedToOrg(ICA, FUNC)).thenReturn(true);
    when(this.keycloakAdminClient.fetchRightHolders(ICA, FUNC))
        .thenReturn(List.of(new RightHolder("u1", "Klara", ICA, FUNC, "write")));

    new OrganizationFunctionController(this.keycloakAdminClient, this.organizationService, this.publisher)
        .detachFunction(ICA, FUNC, this.request);

    assertThat(this.publisher.single("function_detached").getData()).containsKey("removed_rights");
  }

  // Clients

  private ClientController clientController() {
    return new ClientController(this.keycloakAdminClient, this.reconciliationService, this.publisher);
  }

  @Test
  void createClient_clientCreatedWithSettings_noSeparateReconciliationEvent() {
    when(this.keycloakAdminClient.createManagedClient(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
        any(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(client("https://a.example.com/cb"));
    when(this.reconciliationService.reconcileClient("c1")).thenReturn(new ReconciliationReport(1, 3, 0, List.of()));

    this.clientController().createClient(createRequest(), this.request);

    assertThat(this.publisher.types()).containsExactly("client_created");
    assertThat(object(this.publisher.single("client_created"), "client"))
        .containsEntry("client_id", "c1")
        .containsEntry("functions", List.of(FUNC))
        .containsEntry("redirect_uris", List.of("https://a.example.com/cb"))
        .containsEntry("org_rights_id_token", true)
        .doesNotContainKey("jwks_string");
  }

  /** The client exists although reconciliation failed, so both events are written. */
  @Test
  void createClient_reconciliationFails_createdAndFailed() {
    when(this.keycloakAdminClient.createManagedClient(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
        any(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(client("https://a.example.com/cb"));
    when(this.reconciliationService.reconcileClient("c1")).thenThrow(new KeycloakAdminException("down"));

    final var response = this.clientController().createClient(createRequest(), this.request);

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(this.publisher.types()).containsExactly("client_created", "operation_failed");
  }

  @Test
  void updateClient_oldAndNewValues() {
    when(this.keycloakAdminClient.findManagedClientByUuid(CLIENT_UUID))
        .thenReturn(Optional.of(client("https://a.example.com/cb")));
    when(this.keycloakAdminClient.updateManagedClient(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
        any(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(client("https://b.example.com/cb"));
    when(this.reconciliationService.reconcileClient("c1")).thenThrow(new KeycloakAdminException("down"));

    this.clientController().updateClient(CLIENT_UUID, updateRequest("https://b.example.com/cb"), this.request);

    assertThat(this.publisher.types()).containsExactly("client_updated", "operation_failed");
    final AuditEvent event = this.publisher.single("client_updated");
    assertThat(object(event, "client")).containsExactly(Map.entry("client_id", "c1"));
    assertThat(object(event, "change"))
        .containsEntry("changed_fields", List.of("redirect_uris"))
        .containsEntry("old", Map.of("redirect_uris", List.of("https://a.example.com/cb")))
        .containsEntry("new", Map.of("redirect_uris", List.of("https://b.example.com/cb")));
  }

  @Test
  void updateClient_nothingChanged_noEvent() {
    when(this.keycloakAdminClient.findManagedClientByUuid(CLIENT_UUID))
        .thenReturn(Optional.of(client("https://a.example.com/cb")));
    when(this.keycloakAdminClient.updateManagedClient(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(),
        any(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(client("https://a.example.com/cb"));
    when(this.reconciliationService.reconcileClient("c1")).thenReturn(new ReconciliationReport(1, 0, 0, List.of()));

    this.clientController().updateClient(CLIENT_UUID, updateRequest("https://a.example.com/cb"), this.request);

    assertThat(this.publisher.events()).isEmpty();
  }

  @Test
  void deleteClient_serviceAccount_operationRejected() {
    final ManagedClientInfo client = client("https://a.example.com/cb");
    when(this.keycloakAdminClient.findManagedClientByUuid(CLIENT_UUID)).thenReturn(Optional.of(new ManagedClientInfo(
        client.uuid(), client.clientId(), client.name(), true, false, client.functions(), false,
        client.redirectUris(), client.jwksUri(), null, true, true, true, true)));

    this.clientController().deleteClient(CLIENT_UUID, this.request);

    final AuditEvent event = this.publisher.single("operation_rejected");
    assertThat(object(event, "operation")).containsEntry("reason", "service_account_client");
    assertThat(object(event, "client")).containsExactly(Map.entry("client_id", "c1"));
  }

  @Test
  void deleteClient_clientDeleted() {
    when(this.keycloakAdminClient.findManagedClientByUuid(CLIENT_UUID))
        .thenReturn(Optional.of(client("https://a.example.com/cb")));

    this.clientController().deleteClient(CLIENT_UUID, this.request);

    assertThat(object(this.publisher.single("client_deleted"), "client")).containsExactly(Map.entry("client_id", "c1"));
  }

  @Test
  void reconcileClient_manualRun() {
    when(this.keycloakAdminClient.findManagedClientByUuid(CLIENT_UUID))
        .thenReturn(Optional.of(client("https://a.example.com/cb")));
    when(this.reconciliationService.reconcileClient("c1")).thenReturn(new ReconciliationReport(1, 0, 0, List.of()));

    this.clientController().reconcileClient(CLIENT_UUID, this.request);

    final AuditEvent event = this.publisher.single("clients_reconciled");
    assertThat(object(event, "reconciliation")).containsExactly(
        Map.entry("trigger", "manual"), Map.entry("client_count", 1), Map.entry("created", 0), Map.entry("removed", 0));
    assertThat(object(event, "client")).containsExactly(Map.entry("client_id", "c1"));
  }

  @Test
  void reconcileAll_manualRunWithErrors() {
    when(this.reconciliationService.reconcileAll()).thenReturn(new ReconciliationReport(2, 1, 0, List.of("oops")));

    this.clientController().reconcileAll(this.request);

    final AuditEvent event = this.publisher.single("clients_reconciled");
    assertThat(object(event, "reconciliation")).containsEntry("errors", List.of("oops"));
    assertThat(event.getData()).doesNotContainKey("client");
  }

  @Test
  void clients_noSession_accessDenied() {
    this.session.clearAttributes();

    this.clientController().getClients(this.request);

    assertThat(object(this.publisher.single("access_denied"), "operation"))
        .containsExactly(Map.entry("name", "list_clients"), Map.entry("reason", "no_admin_session"));
  }

  // Export and import

  @Test
  void export_dataExported() {
    final ImportExportService service = mock(ImportExportService.class);
    when(service.exportAll()).thenReturn(new ImportExportBundle(ImportExportBundle.SCHEMA_VERSION, "now",
        List.of(), List.of(), List.of()));

    new ImportExportController(service, this.publisher).exportAll(this.request);

    assertThat(object(this.publisher.single("data_exported"), "export")).containsExactly(
        Map.entry("function_count", 0), Map.entry("organization_count", 0), Map.entry("user_count", 0));
  }

  @Test
  void cancelImport_importCancelled() {
    final ImportExportService service = mock(ImportExportService.class);
    when(service.cancel(any(), any())).thenReturn(true);

    new ImportExportController(service, this.publisher).cancel("b1", this.request);

    assertThat(object(this.publisher.single("import_cancelled"), "import")).containsExactly(Map.entry("batch_id", "b1"));
  }

  @Test
  void confirmImport_notSuperuser_accessDeniedWithBatch() {
    this.regularAdmin();

    new ImportExportController(mock(ImportExportService.class), this.publisher).confirm("b1", this.request);

    assertThat(object(this.publisher.single("access_denied"), "import")).containsExactly(Map.entry("batch_id", "b1"));
  }

  // IAM service API

  /** A refusal on the IAM service API carries the name of the token's authentication as principal. */
  @Test
  void rightsHolders_refused_accessDeniedWithTokenPrincipal() {
    final Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("service-client")
        .claim("org_rights", List.of()).build();
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated("service-client", null, List.of()));

    new IamServiceOrganizationController(this.keycloakAdminClient, new OrgRightsClaimParser(), this.publisher)
        .listRightsHolders(ICA, FUNC, jwt);

    final AuditEvent event = this.publisher.single("access_denied");
    assertThat(event.getPrincipal()).isEqualTo("service-client");
    assertThat(event.getCorrelationId()).isNull();
    assertThat(object(event, "operation"))
        .containsExactly(Map.entry("name", "list_rights_holders"), Map.entry("reason", "insufficient_rights"));
  }

  // Helpers

  private void superuser() {
    this.session.setAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR, new AdminSessionData(
        true, null, null, List.of(), Set.of(), new OrgRightsClaim(true, List.of())));
  }

  private void regularAdmin() {
    this.session.setAttribute(AdminSessionBootstrapHandler.SESSION_DATA_ATTR, new AdminSessionData(
        false, null, null, List.of(), Set.of(ICA), new OrgRightsClaim(false, List.of(
        new OrgRightsClaim.OrgEntry(OrganizationID.of(ICA), "Org", new LocalizedString(), "admin", List.of())))));
  }

  private static OrganizationInfo org(final String legalName, final String contactEmail) {
    return new OrganizationInfo(ICA, legalName, null, "group", List.of(), contactEmail, null);
  }

  private static ManagedClientInfo client(final String redirectUri) {
    return new ManagedClientInfo(CLIENT_UUID, "c1", "Client", true, false, Set.of(FUNC), false,
        List.of(redirectUri), "https://c.example.com/jwks", null, false, true, true, true);
  }

  private static CreateManagedClientRequest createRequest() {
    return new CreateManagedClientRequest("c1", "Client", true, false, List.of("https://a.example.com/cb"),
        Set.of(FUNC), "https://c.example.com/jwks", null, null, null);
  }

  private static UpdateManagedClientRequest updateRequest(final String redirectUri) {
    return new UpdateManagedClientRequest("Client", true, false, List.of(redirectUri), Set.of(FUNC),
        "https://c.example.com/jwks", null, null, null);
  }

}
