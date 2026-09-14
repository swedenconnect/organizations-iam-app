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

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import se.swedenconnect.iam.admin.controllers.dto.BundleFunctionEntry;
import se.swedenconnect.iam.admin.controllers.dto.BundleOrganizationEntry;
import se.swedenconnect.iam.admin.controllers.dto.BundleUserEntry;
import se.swedenconnect.iam.admin.controllers.dto.BundleUserRightEntry;
import se.swedenconnect.iam.admin.controllers.dto.ImportExportBundle;
import se.swedenconnect.iam.admin.controllers.dto.ImportItemOutcome;
import se.swedenconnect.iam.admin.controllers.dto.ImportPreviewReport;
import se.swedenconnect.iam.admin.controllers.dto.ImportReport;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminClient;
import se.swedenconnect.iam.admin.keycloak.KeycloakAdminException;
import se.swedenconnect.iam.admin.keycloak.model.FunctionInfo;
import se.swedenconnect.iam.admin.keycloak.model.OrganizationInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.iam.admin.keycloak.model.UserRight;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Default {@link ImportExportService}. Reuses {@link OrganizationService} for organization
 * writes (to keep its cache coherent) and {@link KeycloakAdminClient} directly for functions,
 * users and rights — the same split {@code OrganizationController}, {@code FunctionController},
 * {@code UserController} and {@code UserRightsController} already use, so a bulk import has
 * exactly the same side effects as the equivalent sequence of manual API calls.
 *
 * @author PF Plars
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportExportServiceImpl implements ImportExportService {

  private static final String SESSION_ATTR = "pendingImportBatch";

  private static final int MAX_ENTRIES_PER_TYPE = 5000;

  private static final Pattern FUNCTION_ID_PATTERN = Pattern.compile("^[a-z0-9_-]+$");
  private static final Pattern ORG_IDENTIFIER_PATTERN = Pattern.compile("^\\d{10}$");
  private static final Pattern PIN_PATTERN = Pattern.compile("^\\d{12}$");
  private static final Pattern ORG_AFFILIATION_PATTERN = Pattern.compile("^[^@\\s]+@\\d{10}$");

  private final KeycloakAdminClient keycloakAdminClient;
  private final OrganizationService organizationService;
  private final ObjectMapper objectMapper;

  @Override
  public @NonNull ImportExportBundle exportAll() {
    final List<BundleFunctionEntry> functions = this.keycloakAdminClient.fetchAllFunctions().stream()
        .map(ImportExportServiceImpl::toBundleFunction)
        .toList();

    final List<BundleOrganizationEntry> organizations = this.keycloakAdminClient.fetchAllOrganizationGroups().stream()
        .map(ImportExportServiceImpl::toBundleOrganization)
        .toList();

    // Superuser accounts are excluded: their status cannot be reproduced through user creation
    // anyway, and there is no reason to spread their identities through an export file.
    final List<BundleUserEntry> users = this.keycloakAdminClient.fetchAllUsers().stream()
        .filter(u -> !u.superuser())
        .map(u -> toBundleUser(u, this.keycloakAdminClient.fetchUserRights(u.userId())))
        .toList();

    log.info("Export: {} functions, {} organizations, {} users (superuser accounts excluded)",
        functions.size(), organizations.size(), users.size());

    return new ImportExportBundle(
        ImportExportBundle.SCHEMA_VERSION, Instant.now().toString(), functions, organizations, users);
  }

  @Override
  public @NonNull ImportPreviewReport dryRun(
      final byte @NonNull [] fileContent,
      final @NonNull String actorUserId,
      final @NonNull HttpSession session) {

    final ImportExportBundle bundle = parseAndValidateShape(fileContent);

    // --- Functions -----------------------------------------------------------------------
    final List<ImportItemOutcome> functionOutcomes = new ArrayList<>();
    final List<BundleFunctionEntry> newFunctions = new ArrayList<>();
    final Set<String> knownFunctionIds = new HashSet<>(
        this.keycloakAdminClient.fetchAllFunctions().stream().map(FunctionInfo::id).toList());

    for (final BundleFunctionEntry f : bundle.functions()) {
      if (f.id() == null || !FUNCTION_ID_PATTERN.matcher(f.id()).matches()) {
        functionOutcomes.add(new ImportItemOutcome(String.valueOf(f.id()), "error", "id must match [a-z0-9_-]+"));
        continue;
      }
      if (blank(f.nameSv()) || blank(f.nameEn())) {
        functionOutcomes.add(new ImportItemOutcome(f.id(), "error", "nameSv and nameEn must not be blank"));
        continue;
      }
      if (knownFunctionIds.contains(f.id())) {
        functionOutcomes.add(new ImportItemOutcome(f.id(), "skipped_duplicate", "function already exists"));
        continue;
      }
      functionOutcomes.add(new ImportItemOutcome(f.id(), "new", null));
      newFunctions.add(f);
      knownFunctionIds.add(f.id());
    }

    // --- Organizations -------------------------------------------------------------------
    final List<ImportItemOutcome> organizationOutcomes = new ArrayList<>();
    final List<BundleOrganizationEntry> newOrganizations = new ArrayList<>();
    final List<OrganizationInfo> existingOrganizations = this.keycloakAdminClient.fetchAllOrganizationGroups();
    final Set<String> knownOrgIdentifiers = new HashSet<>(
        existingOrganizations.stream().map(OrganizationInfo::orgIdentifier).toList());
    // orgIdentifier -> function ids attached to it, existing or newly attached earlier in this batch.
    final Map<String, Set<String>> orgFunctionAttachments = new HashMap<>();
    for (final OrganizationInfo o : existingOrganizations) {
      orgFunctionAttachments.put(o.orgIdentifier(), new HashSet<>(o.attachedFunctions()));
    }

    for (final BundleOrganizationEntry o : bundle.organizations()) {
      if (o.orgIdentifier() == null || !ORG_IDENTIFIER_PATTERN.matcher(o.orgIdentifier()).matches()) {
        organizationOutcomes.add(new ImportItemOutcome(
            String.valueOf(o.orgIdentifier()), "error", "orgIdentifier must be exactly 10 digits"));
        continue;
      }
      if (blank(o.legalName())) {
        organizationOutcomes.add(new ImportItemOutcome(o.orgIdentifier(), "error", "legalName must not be blank"));
        continue;
      }
      if (knownOrgIdentifiers.contains(o.orgIdentifier())) {
        organizationOutcomes.add(
            new ImportItemOutcome(o.orgIdentifier(), "skipped_duplicate", "organization already exists"));
        continue;
      }
      final List<String> unknownFunctions = o.attachedFunctions().stream()
          .filter(fid -> !knownFunctionIds.contains(fid))
          .toList();
      if (!unknownFunctions.isEmpty()) {
        organizationOutcomes.add(new ImportItemOutcome(o.orgIdentifier(), "error",
            "attachedFunctions references unknown function(s): " + unknownFunctions));
        continue;
      }
      organizationOutcomes.add(new ImportItemOutcome(o.orgIdentifier(), "new", null));
      newOrganizations.add(o);
      knownOrgIdentifiers.add(o.orgIdentifier());
      orgFunctionAttachments.computeIfAbsent(o.orgIdentifier(), k -> new HashSet<>()).addAll(o.attachedFunctions());
    }

    // --- Users ---------------------------------------------------------------------------
    final List<ImportItemOutcome> userOutcomes = new ArrayList<>();
    final List<BundleUserEntry> newUsers = new ArrayList<>();
    int rowIndex = 0;
    for (final BundleUserEntry u : bundle.users()) {
      rowIndex++;
      final String pin = trimToNull(u.personalIdentityNumber());
      final String orgAffiliation = trimToNull(u.orgAffiliation());
      final String key = pin != null ? pin : (orgAffiliation != null ? orgAffiliation : "row-" + rowIndex);

      if (blank(u.name())) {
        userOutcomes.add(new ImportItemOutcome(key, "error", "name must not be blank"));
        continue;
      }
      if (pin == null && orgAffiliation == null) {
        userOutcomes.add(new ImportItemOutcome(key, "error",
            "at least one of personalIdentityNumber or orgAffiliation must be given"));
        continue;
      }
      if (pin != null && !PIN_PATTERN.matcher(pin).matches()) {
        userOutcomes.add(new ImportItemOutcome(key, "error", "personalIdentityNumber must be exactly 12 digits"));
        continue;
      }
      if (orgAffiliation != null && !ORG_AFFILIATION_PATTERN.matcher(orgAffiliation).matches()) {
        userOutcomes.add(new ImportItemOutcome(key, "error",
            "orgAffiliation must be on the format userID@organization-number"));
        continue;
      }
      final String email = trimToNull(u.email());
      if (email != null && !email.contains("@")) {
        userOutcomes.add(new ImportItemOutcome(key, "error", "email is not valid"));
        continue;
      }

      final String invalidRightReason = firstInvalidRightReason(u.rights(), knownOrgIdentifiers, orgFunctionAttachments);
      if (invalidRightReason != null) {
        userOutcomes.add(new ImportItemOutcome(key, "error", invalidRightReason));
        continue;
      }

      final boolean duplicate =
          (pin != null && this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(pin).isPresent())
              || (orgAffiliation != null
                  && this.keycloakAdminClient.findUserIdByOrgAffiliation(orgAffiliation).isPresent());
      if (duplicate) {
        userOutcomes.add(new ImportItemOutcome(key, "skipped_duplicate", "user already exists"));
        continue;
      }

      userOutcomes.add(new ImportItemOutcome(key, "new", null));
      newUsers.add(u);
    }

    final String batchId = UUID.randomUUID().toString();
    session.setAttribute(SESSION_ATTR,
        new PendingImportBatch(batchId, actorUserId, Instant.now(), newFunctions, newOrganizations, newUsers));

    log.info("Import dry-run by '{}': batchId={}, {}/{} new functions, {}/{} new organizations, {}/{} new users",
        actorUserId, batchId,
        newFunctions.size(), bundle.functions().size(),
        newOrganizations.size(), bundle.organizations().size(),
        newUsers.size(), bundle.users().size());

    return new ImportPreviewReport(batchId, functionOutcomes, organizationOutcomes, userOutcomes);
  }

  @Override
  public @NonNull ImportReport confirm(
      final @NonNull String batchId,
      final @NonNull String actorUserId,
      final @NonNull HttpSession session) {

    final PendingImportBatch batch = resolveBatch(batchId, actorUserId, session);
    session.removeAttribute(SESSION_ATTR);

    final List<ImportItemOutcome> functionOutcomes = new ArrayList<>();
    for (final BundleFunctionEntry f : batch.functions()) {
      // State may have changed since the dry-run; re-check rather than fail.
      if (this.keycloakAdminClient.functionExists(f.id())) {
        functionOutcomes.add(new ImportItemOutcome(f.id(), "skipped_duplicate", "function already exists"));
        continue;
      }
      try {
        this.keycloakAdminClient.createFunction(f.id(), f.nameSv(), f.nameEn(), f.descriptionSv(), f.descriptionEn());
        materializeOnAllFunctionsClients(f.id());
        functionOutcomes.add(new ImportItemOutcome(f.id(), "created", null));
      }
      catch (final KeycloakAdminException e) {
        log.error("Import: failed to create function '{}': {}", f.id(), e.getMessage(), e);
        functionOutcomes.add(new ImportItemOutcome(f.id(), "error", e.getMessage()));
      }
    }

    final List<ImportItemOutcome> organizationOutcomes = new ArrayList<>();
    for (final BundleOrganizationEntry o : batch.organizations()) {
      if (this.organizationService.exists(o.orgIdentifier())) {
        organizationOutcomes.add(
            new ImportItemOutcome(o.orgIdentifier(), "skipped_duplicate", "organization already exists"));
        continue;
      }
      try {
        this.organizationService.create(o.orgIdentifier(), o.legalName(), o.nameSv(), o.nameEn());
        if (o.contactEmail() != null || o.contactPhone() != null) {
          this.organizationService.update(o.orgIdentifier(), null, null, null, o.contactEmail(), o.contactPhone());
        }
        for (final String functionId : o.attachedFunctions()) {
          try {
            this.keycloakAdminClient.attachFunctionToOrg(o.orgIdentifier(), functionId);
          }
          catch (final KeycloakAdminException e) {
            log.warn("Import: organization '{}' created but function '{}' could not be attached: {}",
                o.orgIdentifier(), functionId, e.getMessage());
          }
        }
        organizationOutcomes.add(new ImportItemOutcome(o.orgIdentifier(), "created", null));
      }
      catch (final KeycloakAdminException e) {
        log.error("Import: failed to create organization '{}': {}", o.orgIdentifier(), e.getMessage(), e);
        organizationOutcomes.add(new ImportItemOutcome(o.orgIdentifier(), "error", e.getMessage()));
      }
    }

    final List<ImportItemOutcome> userOutcomes = new ArrayList<>();
    for (final BundleUserEntry u : batch.users()) {
      final String pin = trimToNull(u.personalIdentityNumber());
      final String orgAffiliation = trimToNull(u.orgAffiliation());
      final String key = pin != null ? pin : orgAffiliation;

      final boolean duplicate =
          (pin != null && this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(pin).isPresent())
              || (orgAffiliation != null
                  && this.keycloakAdminClient.findUserIdByOrgAffiliation(orgAffiliation).isPresent());
      if (duplicate) {
        userOutcomes.add(new ImportItemOutcome(key, "skipped_duplicate", "user already exists"));
        continue;
      }

      try {
        final String userId = this.keycloakAdminClient.createUser(
            null, u.name(), u.email(), pin, orgAffiliation, u.phoneNumber(), null);
        for (final BundleUserRightEntry r : u.rights()) {
          try {
            if (r.functionId() == null) {
              this.keycloakAdminClient.addUserToOrgRight(r.orgIdentifier(), userId, r.right());
            }
            else {
              this.keycloakAdminClient.addUserToFunctionRight(r.orgIdentifier(), r.functionId(), userId, r.right());
            }
          }
          catch (final KeycloakAdminException e) {
            log.warn("Import: user '{}' created but right {}/{}/{} could not be granted: {}",
                key, r.orgIdentifier(), r.functionId(), r.right(), e.getMessage());
          }
        }
        userOutcomes.add(new ImportItemOutcome(key, "created", null));
      }
      catch (final KeycloakAdminException e) {
        log.error("Import: failed to create user '{}': {}", key, e.getMessage(), e);
        userOutcomes.add(new ImportItemOutcome(key, "error", e.getMessage()));
      }
    }

    log.info("Import confirmed by '{}': batchId={}, functions created={}/{}, organizations created={}/{}, "
            + "users created={}/{}",
        actorUserId, batchId,
        countCreated(functionOutcomes), functionOutcomes.size(),
        countCreated(organizationOutcomes), organizationOutcomes.size(),
        countCreated(userOutcomes), userOutcomes.size());

    return new ImportReport(functionOutcomes, organizationOutcomes, userOutcomes);
  }

  @Override
  public boolean cancel(final @NonNull String batchId, final @NonNull HttpSession session) {
    final Object attr = session.getAttribute(SESSION_ATTR);
    if (attr instanceof final PendingImportBatch batch && batch.batchId().equals(batchId)) {
      session.removeAttribute(SESSION_ATTR);
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private @NonNull ImportExportBundle parseAndValidateShape(final byte @NonNull [] fileContent) {
    final ImportExportBundle bundle;
    try {
      bundle = this.objectMapper.readValue(fileContent, ImportExportBundle.class);
    }
    catch (final JacksonException e) {
      throw new ImportValidationException(
          "File is not valid JSON or does not match the expected shape: " + e.getMessage(), e);
    }
    if (bundle == null) {
      throw new ImportValidationException("File is empty");
    }
    if (!ImportExportBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())) {
      throw new ImportValidationException(
          "Unsupported schemaVersion '" + bundle.schemaVersion() + "', expected '"
              + ImportExportBundle.SCHEMA_VERSION + "'");
    }
    final List<BundleFunctionEntry> functions = bundle.functions() != null ? bundle.functions() : List.of();
    final List<BundleOrganizationEntry> organizations =
        bundle.organizations() != null ? bundle.organizations() : List.of();
    final List<BundleUserEntry> users = bundle.users() != null ? bundle.users() : List.of();
    if (functions.size() > MAX_ENTRIES_PER_TYPE
        || organizations.size() > MAX_ENTRIES_PER_TYPE
        || users.size() > MAX_ENTRIES_PER_TYPE) {
      throw new ImportValidationException(
          "Too many entries in one or more of functions/organizations/users; the limit is "
              + MAX_ENTRIES_PER_TYPE + " each");
    }
    return new ImportExportBundle(bundle.schemaVersion(), bundle.exportedAt(), functions, organizations, users);
  }

  private @NonNull PendingImportBatch resolveBatch(
      final @NonNull String batchId, final @NonNull String actorUserId, final @NonNull HttpSession session) {
    final Object attr = session.getAttribute(SESSION_ATTR);
    if (!(attr instanceof final PendingImportBatch batch)
        || !batch.batchId().equals(batchId)
        || !batch.ownerUserId().equals(actorUserId)) {
      throw new ImportBatchNotFoundException("No pending import batch '" + batchId + "' found for this session");
    }
    if (batch.isExpired()) {
      session.removeAttribute(SESSION_ATTR);
      throw new ImportBatchNotFoundException("Import batch '" + batchId + "' has expired; run the dry-run again");
    }
    return batch;
  }

  /**
   * Returns a validation-failure reason for the first right in {@code rights} that has an
   * invalid right level, or references an organization or org/function pair unknown in Keycloak
   * and unknown earlier in this same import file. {@code null} if every right is valid.
   */
  private static @Nullable String firstInvalidRightReason(
      final @NonNull List<BundleUserRightEntry> rights,
      final @NonNull Set<String> knownOrgIdentifiers,
      final @NonNull Map<String, Set<String>> orgFunctionAttachments) {
    for (final BundleUserRightEntry r : rights) {
      if (r.right() == null || !KeycloakAdminClient.RIGHT_LEVELS.contains(r.right())) {
        return "right must be one of: read, write, admin (got '" + r.right() + "')";
      }
      if (r.orgIdentifier() == null || !knownOrgIdentifiers.contains(r.orgIdentifier())) {
        return "rights reference unknown organization '" + r.orgIdentifier() + "'";
      }
      if (r.functionId() != null) {
        final Set<String> attached = orgFunctionAttachments.getOrDefault(r.orgIdentifier(), Set.of());
        if (!attached.contains(r.functionId())) {
          return "rights reference function '" + r.functionId() + "' not attached to organization '"
              + r.orgIdentifier() + "'";
        }
      }
    }
    return null;
  }

  private void materializeOnAllFunctionsClients(final @NonNull String functionId) {
    try {
      final List<String> updated = this.keycloakAdminClient.materializeAllFunctions(functionId);
      if (!updated.isEmpty()) {
        log.info("Import: function '{}' added to client_functions of all-functions clients: {}",
            functionId, updated);
      }
    }
    catch (final KeycloakAdminException e) {
      log.warn("Import: function '{}' was created but could not be added to the client_functions of the"
              + " all-functions clients: {}. Run add-function.sh for those clients.",
          functionId, e.getMessage());
    }
  }

  private static int countCreated(final @NonNull List<ImportItemOutcome> outcomes) {
    return (int) outcomes.stream().filter(o -> "created".equals(o.status())).count();
  }

  private static @NonNull BundleFunctionEntry toBundleFunction(final @NonNull FunctionInfo f) {
    return new BundleFunctionEntry(
        f.id(),
        f.name().get("sv"),
        f.name().get("en"),
        f.description() != null ? f.description().get("sv") : null,
        f.description() != null ? f.description().get("en") : null);
  }

  private static @NonNull BundleOrganizationEntry toBundleOrganization(final @NonNull OrganizationInfo o) {
    return new BundleOrganizationEntry(
        o.orgIdentifier(),
        o.legalName(),
        o.displayName("sv"),
        o.displayName("en"),
        o.contactEmail(),
        o.contactPhone(),
        o.attachedFunctions());
  }

  private static @NonNull BundleUserEntry toBundleUser(
      final @NonNull UserInfo u, final @NonNull List<UserRight> rights) {
    final String firstName = u.firstName() != null ? u.firstName() : "";
    final String lastName = u.lastName() != null ? u.lastName() : "";
    final String fullName = (firstName + " " + lastName).trim();
    final String name = fullName.isBlank() ? (u.username() != null ? u.username() : u.userId()) : fullName;
    return new BundleUserEntry(
        name,
        u.email(),
        u.personalIdentityNumber(),
        u.orgAffiliation(),
        u.phoneNumber(),
        rights.stream().map(r -> new BundleUserRightEntry(r.orgIdentifier(), r.functionId(), r.right())).toList());
  }

  private static boolean blank(final @Nullable String value) {
    return value == null || value.isBlank();
  }

  private static @Nullable String trimToNull(final @Nullable String value) {
    if (value == null) {
      return null;
    }
    final String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

}
