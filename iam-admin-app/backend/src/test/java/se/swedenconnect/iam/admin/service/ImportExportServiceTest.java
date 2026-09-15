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
package se.swedenconnect.iam.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportExportBundle;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportItemOutcome;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportPreviewReport;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportReport;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;
import se.swedenconnect.iam.commons.types.LocalizedString;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the export and import bundle format: the snake_case keys, the language-tagged keys for
 * localized values, and the Keycloak username, including the precedence between the duplicate
 * outcome and the two username outcomes.
 *
 * @author Martin Lindström
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImportExportServiceTest {

  private static final String PIN = "197001011234";
  private static final String ORG = "2021006883";
  private static final String CREATED_ID = "created-uuid";

  @Mock
  private KeycloakAdminClient keycloakAdminClient;

  @Mock
  private OrganizationService organizationService;

  private ObjectMapper objectMapper;

  private IamAdminProperties properties;

  private ImportExportServiceImpl service;

  private MockHttpSession session;

  @BeforeEach
  void setUp() {
    this.objectMapper = new ObjectMapper();
    this.properties = new IamAdminProperties();
    this.service = new ImportExportServiceImpl(
        this.keycloakAdminClient, this.organizationService, this.objectMapper, this.properties);
    this.session = new MockHttpSession();

    // A realm holding the organization and the function every test file references, and nobody else.
    when(this.keycloakAdminClient.fetchAllFunctions()).thenReturn(List.of(functionInfo()));
    when(this.keycloakAdminClient.fetchAllOrganizationGroups()).thenReturn(List.of(organizationInfo()));
    when(this.keycloakAdminClient.fetchAllUsers()).thenReturn(List.of());
    when(this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(anyString())).thenReturn(Optional.empty());
    when(this.keycloakAdminClient.findUserIdByOrgAffiliation(anyString())).thenReturn(Optional.empty());
    when(this.keycloakAdminClient.usernameExists(anyString())).thenReturn(false);
    when(this.keycloakAdminClient.createUser(any(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(CREATED_ID);
  }

  // ---------------------------------------------------------------------------
  // Export
  // ---------------------------------------------------------------------------

  @Test
  void exportUsesSnakeCaseKeysWithTaggedLanguagesAndCarriesTheUsername() {
    when(this.keycloakAdminClient.fetchAllUsers()).thenReturn(List.of(userInfo("anna", false)));
    when(this.keycloakAdminClient.fetchUserRights("anna-id"))
        .thenReturn(List.of(new UserRight(ORG, "walletreg", "admin"), new UserRight(ORG, null, "read")));

    final Map<String, Object> json = this.asJson(this.service.exportAll());

    assertThat(json).containsOnlyKeys("schema_version", "exported_at", "functions", "organizations", "users");
    assertThat(firstOf(json, "functions"))
        .containsOnlyKeys("id", "name#sv", "name#en", "description#sv", "description#en")
        .containsEntry("name#sv", "Walletregistrering")
        .containsEntry("name#en", "Wallet registration");
    assertThat(firstOf(json, "organizations"))
        .containsOnlyKeys("org_identifier", "legal_name", "name#sv", "name#en",
            "contact_email", "contact_phone", "attached_functions")
        .containsEntry("legal_name", "Myndigheten för Digital förvaltning")
        .containsEntry("name#sv", "Digg");

    final Map<String, Object> user = firstOf(json, "users");
    assertThat(user).containsOnlyKeys(
        "name", "username", "email", "personal_identity_number", "org_affiliation", "phone_number", "rights");
    assertThat(user).containsEntry("username", "anna").containsEntry("personal_identity_number", PIN);
    assertThat(((List<?>) user.get("rights")).getFirst())
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsOnlyKeys("org_identifier", "function_id", "right");
  }

  @Test
  void exportCarriesTheUsernameOfEveryNonSuperuserAndExcludesSuperusers() {
    when(this.keycloakAdminClient.fetchAllUsers())
        .thenReturn(List.of(userInfo("anna", false), userInfo("root", true)));
    when(this.keycloakAdminClient.fetchUserRights(anyString())).thenReturn(List.of());

    final ImportExportBundle bundle = this.service.exportAll();

    assertThat(bundle.users()).hasSize(1);
    assertThat(bundle.users().getFirst().username()).isEqualTo("anna");
  }

  // ---------------------------------------------------------------------------
  // The bundle format
  // ---------------------------------------------------------------------------

  @Test
  void aSnakeCaseFileWithTaggedLanguageKeysIsAccepted() {
    final ImportPreviewReport report = this.dryRun("""
        {
          "schema_version": "1.0",
          "functions": [
            { "id": "demo", "name#sv": "Demo", "name#en": "Demo", "description#sv": "Beskrivning" }
          ],
          "organizations": [
            {
              "org_identifier": "5561234567",
              "legal_name": "Exempel Aktiebolag",
              "name#sv": "Exempel",
              "name#en": "Example",
              "attached_functions": ["demo"]
            }
          ],
          "users": [
            {
              "name": "Anna Andersson",
              "personal_identity_number": "197001011234",
              "phone_number": "+46701112233",
              "rights": [ { "org_identifier": "5561234567", "function_id": "demo", "right": "admin" } ]
            }
          ]
        }
        """);

    assertThat(statuses(report.functions())).containsExactly("new");
    assertThat(statuses(report.organizations())).containsExactly("new");
    assertThat(statuses(report.users())).containsExactly("new");
    assertThat(report.users().getFirst().key()).isEqualTo(PIN);
  }

  @Test
  void theDryRunReportUsesSnakeCaseKeys() {
    final ImportPreviewReport report = this.dryRun(minimalFile());

    assertThat(this.asJson(report))
        .containsOnlyKeys("batch_id", "functions", "organizations", "users");
    assertThat(firstOf(this.asJson(report), "users")).containsOnlyKeys("key", "status", "reason");
  }

  @Test
  void aFileWithTheOldCamelCaseKeysIsRejectedOutright() {
    assertThatThrownBy(() -> this.dryRun("""
        {
          "schemaVersion": "1.0",
          "functions": [],
          "organizations": [],
          "users": []
        }
        """))
        .isInstanceOf(ImportValidationException.class)
        .hasMessageContaining("schema_version");
  }

  @Test
  void aFileWithTheOldPerLanguageFieldsReportsErrorsRatherThanImportingBlankValues() {
    final ImportPreviewReport report = this.dryRun("""
        {
          "schema_version": "1.0",
          "functions": [ { "id": "demo", "nameSv": "Demo", "nameEn": "Demo" } ],
          "organizations": [ { "orgIdentifier": "5561234567", "legalName": "Exempel Aktiebolag" } ],
          "users": [ { "name": "Anna", "personalIdentityNumber": "197001011234", "rights": [] } ]
        }
        """);

    assertThat(report.functions().getFirst().status()).isEqualTo("error");
    assertThat(report.functions().getFirst().reason()).isEqualTo("name#sv and name#en must not be blank");
    assertThat(report.organizations().getFirst().status()).isEqualTo("error");
    assertThat(report.organizations().getFirst().reason()).isEqualTo("org_identifier must be exactly 10 digits");
    assertThat(report.users().getFirst().status()).isEqualTo("error");
    assertThat(report.users().getFirst().reason())
        .isEqualTo("at least one of personal_identity_number or org_affiliation must be given");
  }

  @Test
  void aKeyTaggedWithAnotherLanguageIsIgnored() {
    final ImportPreviewReport report = this.dryRun("""
        {
          "schema_version": "1.0",
          "functions": [
            {
              "id": "demo",
              "name#sv": "Demo",
              "name#en": "Demo",
              "name#de": "Demo auf Deutsch",
              "description#de": "Beschreibung"
            }
          ],
          "organizations": [],
          "users": []
        }
        """);

    assertThat(statuses(report.functions())).containsExactly("new");

    this.service.confirm(report.batchId(), "actor", this.session);
    verify(this.keycloakAdminClient).createFunction("demo", "Demo", "Demo", null, null);
  }

  // ---------------------------------------------------------------------------
  // The username
  // ---------------------------------------------------------------------------

  @Test
  void aUsernameIsAssignedWhenTheDeploymentAllowsAChosenUserId() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);

    final ImportPreviewReport report = this.dryRun(userFile("\"username\": \"anna\","));
    assertThat(statuses(report.users())).containsExactly("new");

    final ImportReport result = this.service.confirm(report.batchId(), "actor", this.session);

    assertThat(statuses(result.users())).containsExactly("created");
    verify(this.keycloakAdminClient)
        .createUser(eq("anna"), eq("Anna Andersson"), any(), eq(PIN), isNull(), any(), isNull());
  }

  @Test
  void anEntryWithoutAUsernameIsCreatedWithAKeycloakAssignedUuid() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);

    final ImportPreviewReport report = this.dryRun(userFile(""));
    assertThat(statuses(report.users())).containsExactly("new");

    this.service.confirm(report.batchId(), "actor", this.session);

    verify(this.keycloakAdminClient)
        .createUser(isNull(), eq("Anna Andersson"), any(), eq(PIN), isNull(), any(), isNull());
  }

  @Test
  void aTakenUsernameIsAnErrorAndNotADuplicate() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);
    when(this.keycloakAdminClient.usernameExists("anna")).thenReturn(true);

    final ImportPreviewReport report = this.dryRun(userFile("\"username\": \"anna\","));

    assertThat(report.users().getFirst().status()).isEqualTo("error");
    assertThat(report.users().getFirst().reason()).isEqualTo("username 'anna' is already taken");

    this.service.confirm(report.batchId(), "actor", this.session);
    verify(this.keycloakAdminClient, never()).createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  @Test
  void aUsernameIsAnErrorWhenTheDeploymentDoesNotAllowAChosenUserId() {
    this.properties.getUserRegistration().setAllowSelectUserId(false);

    final ImportPreviewReport report = this.dryRun(userFile("\"username\": \"anna\","));

    assertThat(report.users().getFirst().status()).isEqualTo("error");
    assertThat(report.users().getFirst().reason())
        .isEqualTo("usernames are not accepted in an import file for this deployment "
            + "(iam.admin.user-registration.allow-select-user-id is false)");

    this.service.confirm(report.batchId(), "actor", this.session);
    verify(this.keycloakAdminClient, never()).createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  @Test
  void aFileWithNoUsernameImportsWhenTheDeploymentDoesNotAllowAChosenUserId() {
    this.properties.getUserRegistration().setAllowSelectUserId(false);

    final ImportPreviewReport report = this.dryRun(userFile(""));
    assertThat(statuses(report.users())).containsExactly("new");

    final ImportReport result = this.service.confirm(report.batchId(), "actor", this.session);

    assertThat(statuses(result.users())).containsExactly("created");
    verify(this.keycloakAdminClient)
        .createUser(isNull(), eq("Anna Andersson"), any(), eq(PIN), isNull(), any(), isNull());
  }

  @Test
  void aDuplicateIsSkippedBeforeAnyUsernameRuleIsApplied() {
    this.properties.getUserRegistration().setAllowSelectUserId(false);
    when(this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(PIN)).thenReturn(Optional.of("existing"));
    when(this.keycloakAdminClient.usernameExists("anna")).thenReturn(true);

    final ImportPreviewReport report = this.dryRun(userFile("\"username\": \"anna\","));

    assertThat(report.users().getFirst().status()).isEqualTo("skipped_duplicate");
    assertThat(report.users().getFirst().reason()).isEqualTo("user already exists");
    verify(this.keycloakAdminClient, never()).usernameExists(anyString());
  }

  @Test
  void aDuplicateMatchedOnOrgAffiliationIsSkippedBeforeAnyUsernameRuleIsApplied() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);
    when(this.keycloakAdminClient.findUserIdByOrgAffiliation("anna@2021006883"))
        .thenReturn(Optional.of("existing"));
    when(this.keycloakAdminClient.usernameExists("anna")).thenReturn(true);

    final ImportPreviewReport report = this.dryRun("""
        {
          "schema_version": "1.0",
          "functions": [],
          "organizations": [],
          "users": [
            {
              "name": "Anna Andersson",
              "username": "anna",
              "org_affiliation": "anna@2021006883",
              "rights": []
            }
          ]
        }
        """);

    assertThat(report.users().getFirst().status()).isEqualTo("skipped_duplicate");
    verify(this.keycloakAdminClient, never()).usernameExists(anyString());
  }

  @Test
  void confirmReChecksTheUsernameAgainstKeycloak() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);
    final ImportPreviewReport report = this.dryRun(userFile("\"username\": \"anna\","));
    assertThat(statuses(report.users())).containsExactly("new");

    // Somebody else takes the username between the dry run and the confirm.
    when(this.keycloakAdminClient.usernameExists("anna")).thenReturn(true);

    final ImportReport result = this.service.confirm(report.batchId(), "actor", this.session);

    assertThat(result.users().getFirst().status()).isEqualTo("error");
    assertThat(result.users().getFirst().reason()).isEqualTo("username 'anna' is already taken");
    verify(this.keycloakAdminClient, never()).createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  @Test
  void confirmReChecksWhetherTheDeploymentStillAllowsAChosenUserId() {
    this.properties.getUserRegistration().setAllowSelectUserId(true);
    final ImportPreviewReport report = this.dryRun(userFile("\"username\": \"anna\","));

    this.properties.getUserRegistration().setAllowSelectUserId(false);

    final ImportReport result = this.service.confirm(report.batchId(), "actor", this.session);

    assertThat(result.users().getFirst().status()).isEqualTo("error");
    assertThat(result.users().getFirst().reason()).contains("usernames are not accepted");
    verify(this.keycloakAdminClient, never()).createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Round trip
  // ---------------------------------------------------------------------------

  @Test
  void importingAnExportBackIntoTheRealmItCameFromCreatesNothing() {
    when(this.keycloakAdminClient.fetchAllUsers()).thenReturn(List.of(userInfo("anna", false)));
    when(this.keycloakAdminClient.fetchUserRights("anna-id")).thenReturn(List.of());
    when(this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(PIN)).thenReturn(Optional.of("anna-id"));

    final String exported = this.objectMapper.writeValueAsString(this.service.exportAll());

    for (final boolean allowSelectUserId : List.of(true, false)) {
      this.properties.getUserRegistration().setAllowSelectUserId(allowSelectUserId);
      final ImportPreviewReport report = this.dryRun(exported);

      assertThat(statuses(report.functions())).containsExactly("skipped_duplicate");
      assertThat(statuses(report.organizations())).containsExactly("skipped_duplicate");
      assertThat(statuses(report.users())).containsExactly("skipped_duplicate");

      final ImportReport result = this.service.confirm(report.batchId(), "actor", this.session);
      assertThat(result.functions()).isEmpty();
      assertThat(result.organizations()).isEmpty();
      assertThat(result.users()).isEmpty();
    }
    verify(this.keycloakAdminClient, never()).createUser(any(), anyString(), any(), any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ImportPreviewReport dryRun(final String json) {
    return this.service.dryRun(json.getBytes(StandardCharsets.UTF_8), "actor", this.session);
  }

  private Map<String, Object> asJson(final Object value) {
    return this.objectMapper.readValue(this.objectMapper.writeValueAsString(value), Map.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> firstOf(final Map<String, Object> json, final String listKey) {
    return (Map<String, Object>) ((List<?>) json.get(listKey)).getFirst();
  }

  private static List<String> statuses(final List<ImportItemOutcome> outcomes) {
    return outcomes.stream().map(ImportItemOutcome::status).toList();
  }

  private static String minimalFile() {
    return userFile("");
  }

  /** A one-user file. {@code extraFields} is inserted as raw JSON before the name. */
  private static String userFile(final String extraFields) {
    return """
        {
          "schema_version": "1.0",
          "functions": [],
          "organizations": [],
          "users": [
            {
              %s
              "name": "Anna Andersson",
              "personal_identity_number": "197001011234",
              "rights": []
            }
          ]
        }
        """.formatted(extraFields);
  }

  private static FunctionInfo functionInfo() {
    final LocalizedString name = new LocalizedString();
    name.add("sv", "Walletregistrering");
    name.add("en", "Wallet registration");
    return new FunctionInfo("walletreg", name, null);
  }

  private static OrganizationInfo organizationInfo() {
    final LocalizedString displayName = new LocalizedString();
    displayName.add("sv", "Digg");
    displayName.add("en", "Digg");
    return new OrganizationInfo(ORG, "Myndigheten för Digital förvaltning", displayName,
        "group-id", List.of("walletreg"), "info@digg.se", null);
  }

  private static UserInfo userInfo(final String username, final boolean superuser) {
    return new UserInfo(username + "-id", username, "Anna", "Andersson", "anna@digg.se",
        PIN, null, "+46701112233", superuser, List.of());
  }

}
