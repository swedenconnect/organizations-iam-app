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

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import se.swedenconnect.iam.admin.config.IamAdminProperties;
import se.swedenconnect.iam.admin.controllers.dto.impexp.BundleFunctionEntry;
import se.swedenconnect.iam.admin.controllers.dto.impexp.BundleOrganizationEntry;
import se.swedenconnect.iam.admin.controllers.dto.impexp.BundleUserEntry;
import se.swedenconnect.iam.admin.controllers.dto.impexp.BundleUserRightEntry;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportExportBundle;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportItemOutcome;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportJobStatus;
import se.swedenconnect.iam.admin.controllers.dto.impexp.ImportPreviewReport;
import se.swedenconnect.iam.admin.keycloak.FunctionAttachContext;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
@Slf4j
public class ImportExportServiceImpl implements ImportExportService {

  private static final String SESSION_ATTR = "pendingImportBatch";

  private static final int MAX_ENTRIES_PER_TYPE = 5000;

  /** How many organizations an import handles between two progress lines in the log. */
  private static final int PROGRESS_LOG_INTERVAL = 25;

  private static final Pattern FUNCTION_ID_PATTERN = Pattern.compile("^[a-z0-9_-]+$");
  private static final Pattern ORG_IDENTIFIER_PATTERN = Pattern.compile("^\\d{10}$");
  private static final Pattern PIN_PATTERN = Pattern.compile("^\\d{12}$");
  private static final Pattern ORG_AFFILIATION_PATTERN = Pattern.compile("^[^@\\s]+@\\d{10}$");

  private final KeycloakAdminClient keycloakAdminClient;
  private final OrganizationService organizationService;
  private final ObjectMapper objectMapper;
  private final IamAdminProperties properties;

  /** Runs the imports, one at a time, off the request thread. */
  private final Executor importExecutor;

  /** Started imports by job id, kept for a while after they finish so that their report can be read. */
  private final Map<String, ImportJob> jobs = new ConcurrentHashMap<>();

  @Autowired
  public ImportExportServiceImpl(
      final KeycloakAdminClient keycloakAdminClient,
      final OrganizationService organizationService,
      final ObjectMapper objectMapper,
      final IamAdminProperties properties) {
    this(keycloakAdminClient, organizationService, objectMapper, properties,
        Executors.newSingleThreadExecutor(runnable -> {
          final Thread thread = new Thread(runnable, "iam-import");
          thread.setDaemon(true);
          return thread;
        }));
  }

  ImportExportServiceImpl(
      final KeycloakAdminClient keycloakAdminClient,
      final OrganizationService organizationService,
      final ObjectMapper objectMapper,
      final IamAdminProperties properties,
      final Executor importExecutor) {
    this.keycloakAdminClient = keycloakAdminClient;
    this.organizationService = organizationService;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.importExecutor = importExecutor;
  }

  @PreDestroy
  void shutdownImportExecutor() {
    if (this.importExecutor instanceof final ExecutorService executorService) {
      executorService.shutdownNow();
    }
  }

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
        functionOutcomes.add(new ImportItemOutcome(f.id(), "error", "name#sv and name#en must not be blank"));
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
            String.valueOf(o.orgIdentifier()), "error", "org_identifier must be exactly 10 digits"));
        continue;
      }
      if (blank(o.legalName())) {
        organizationOutcomes.add(new ImportItemOutcome(o.orgIdentifier(), "error", "legal_name must not be blank"));
        continue;
      }
      if (knownOrgIdentifiers.contains(o.orgIdentifier())) {
        // An existing organization is not created again, but gets the functions from the file that
        // it lacks. This also mends an organization left without its functions by an earlier
        // import that stopped half-way.
        final Set<String> attached = orgFunctionAttachments.getOrDefault(o.orgIdentifier(), Set.of());
        final List<String> missing = o.attachedFunctions().stream()
            .filter(fid -> !attached.contains(fid))
            .distinct()
            .toList();
        if (missing.isEmpty()) {
          organizationOutcomes.add(
              new ImportItemOutcome(o.orgIdentifier(), "skipped_duplicate", "organization already exists"));
          continue;
        }
        final List<String> unknownMissing = missing.stream()
            .filter(fid -> !knownFunctionIds.contains(fid))
            .toList();
        if (!unknownMissing.isEmpty()) {
          organizationOutcomes.add(new ImportItemOutcome(o.orgIdentifier(), "error",
              "attached_functions references unknown function(s): " + unknownMissing));
          continue;
        }
        organizationOutcomes.add(new ImportItemOutcome(o.orgIdentifier(), "update",
            "organization already exists; function(s) to attach: " + missing));
        newOrganizations.add(o);
        orgFunctionAttachments.computeIfAbsent(o.orgIdentifier(), k -> new HashSet<>()).addAll(missing);
        continue;
      }
      final List<String> unknownFunctions = o.attachedFunctions().stream()
          .filter(fid -> !knownFunctionIds.contains(fid))
          .toList();
      if (!unknownFunctions.isEmpty()) {
        organizationOutcomes.add(new ImportItemOutcome(o.orgIdentifier(), "error",
            "attached_functions references unknown function(s): " + unknownFunctions));
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
    final boolean allowSelectUserId = this.properties.getUserRegistration().isAllowSelectUserId();
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
            "at least one of personal_identity_number or org_affiliation must be given"));
        continue;
      }
      if (pin != null && !PIN_PATTERN.matcher(pin).matches()) {
        userOutcomes.add(new ImportItemOutcome(key, "error", "personal_identity_number must be exactly 12 digits"));
        continue;
      }
      if (orgAffiliation != null && !ORG_AFFILIATION_PATTERN.matcher(orgAffiliation).matches()) {
        userOutcomes.add(new ImportItemOutcome(key, "error",
            "org_affiliation must be on the format userID@organization-number"));
        continue;
      }
      final String email = trimToNull(u.email());
      if (email != null && !email.contains("@")) {
        userOutcomes.add(new ImportItemOutcome(key, "error", "email is not valid"));
        continue;
      }

      final String invalidRightReason =
          firstInvalidRightReason(u.rights(), knownOrgIdentifiers, orgFunctionAttachments, this.properties.isAllowOrgRights());
      if (invalidRightReason != null) {
        userOutcomes.add(new ImportItemOutcome(key, "error", invalidRightReason));
        continue;
      }

      final boolean duplicate =
          (pin != null && this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(pin).isPresent())
              || (orgAffiliation != null
                  && this.keycloakAdminClient.findUserIdByOrgAffiliation(orgAffiliation).isPresent());
      if (duplicate) {
        // A duplicate is skipped before the username is looked at, so re-importing an export into
        // the realm it came from is quiet whatever the deployment allows and whatever usernames
        // the file carries.
        userOutcomes.add(new ImportItemOutcome(key, "skipped_duplicate", "user already exists"));
        continue;
      }

      final String usernameReason = usernameRejectionReason(trimToNull(u.username()), allowSelectUserId);
      if (usernameReason != null) {
        userOutcomes.add(new ImportItemOutcome(key, "error", usernameReason));
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
  public @NonNull ImportJobStatus startImport(
      final @NonNull String batchId,
      final @NonNull String actorUserId,
      final @NonNull HttpSession session) {

    final ImportJob job;
    final PendingImportBatch batch;
    synchronized (this.jobs) {
      this.jobs.values().removeIf(ImportJob::isExpired);
      if (this.jobs.values().stream().anyMatch(ImportJob::isRunning)) {
        throw new ImportAlreadyRunningException("Another import is still running; wait for it to finish");
      }
      batch = this.resolveBatch(batchId, actorUserId, session);
      session.removeAttribute(SESSION_ATTR);
      job = new ImportJob(UUID.randomUUID().toString(), actorUserId,
          batch.functions().size() + batch.organizations().size() + batch.users().size());
      this.jobs.put(job.jobId(), job);
    }

    log.info("Import confirmed by '{}': batchId={}, jobId={}, {} functions, {} organizations, {} users",
        actorUserId, batchId, job.jobId(),
        batch.functions().size(), batch.organizations().size(), batch.users().size());
    try {
      this.importExecutor.execute(() -> this.runImport(batch, job));
    }
    catch (final RejectedExecutionException e) {
      log.error("Import job {} could not be started: {}", job.jobId(), e.getMessage(), e);
      job.fail("The import could not be started");
    }
    return job.snapshot();
  }

  @Override
  public @NonNull Optional<ImportJobStatus> getImportJob(
      final @NonNull String jobId, final @NonNull String actorUserId) {
    final ImportJob job = this.jobs.get(jobId);
    if (job == null || job.isExpired() || !job.ownerUserId().equals(actorUserId)) {
      return Optional.empty();
    }
    return Optional.of(job.snapshot());
  }

  /**
   * Creates every entry in {@code batch}, recording the outcome of each in {@code job}. Runs on the
   * import thread. A failure on one entry is recorded as an error for that entry; only a failure
   * outside the handling of a single entry stops the import.
   */
  void runImport(final @NonNull PendingImportBatch batch, final @NonNull ImportJob job) {
    try {
      for (final BundleFunctionEntry f : batch.functions()) {
        job.addFunction(this.importFunction(f));
      }

      if (!batch.organizations().isEmpty()) {
        // Read once for the whole batch: the organization and its attached functions decide what
        // an entry does, and the group ids spare every attachment a lookup.
        final FunctionAttachContext context = new FunctionAttachContext();
        final Map<String, Set<String>> orgFunctions = new HashMap<>();
        for (final OrganizationInfo o : this.keycloakAdminClient.fetchAllOrganizationGroups()) {
          orgFunctions.put(o.orgIdentifier(), new HashSet<>(o.attachedFunctions()));
          context.putOrgGroupId(o.orgIdentifier(), o.groupId());
        }
        for (final BundleOrganizationEntry o : batch.organizations()) {
          job.addOrganization(this.importOrganization(o, orgFunctions, context));
          if (job.organizations().size() % PROGRESS_LOG_INTERVAL == 0) {
            log.info("Import job {}: {}/{} organizations handled",
                job.jobId(), job.organizations().size(), batch.organizations().size());
          }
        }
      }

      final boolean allowSelectUserId = this.properties.getUserRegistration().isAllowSelectUserId();
      for (final BundleUserEntry u : batch.users()) {
        job.addUser(this.importUser(u, allowSelectUserId));
      }

      job.complete();
      log.info("Import job {} finished: functions created={}/{}, organizations created={} updated={}/{}, "
              + "users created={}/{}",
          job.jobId(),
          countStatus(job.functions(), "created"), batch.functions().size(),
          countStatus(job.organizations(), "created"), countStatus(job.organizations(), "updated"),
          batch.organizations().size(),
          countStatus(job.users(), "created"), batch.users().size());
    }
    catch (final Throwable e) {
      final ImportJobStatus progress = job.snapshot();
      log.error("Import job {} stopped after {} of {} entries: {}",
          job.jobId(), progress.processed(), progress.total(), e.getMessage(), e);
      job.fail("The import stopped before every entry was handled: " + e.getMessage());
    }
  }

  private @NonNull ImportItemOutcome importFunction(final @NonNull BundleFunctionEntry f) {
    try {
      // State may have changed since the dry-run; re-check rather than fail.
      if (this.keycloakAdminClient.functionExists(f.id())) {
        return new ImportItemOutcome(f.id(), "skipped_duplicate", "function already exists");
      }
      this.keycloakAdminClient.createFunction(f.id(), f.nameSv(), f.nameEn(), f.descriptionSv(), f.descriptionEn());
      this.materializeOnAllFunctionsClients(f.id());
      return new ImportItemOutcome(f.id(), "created", null);
    }
    catch (final RuntimeException e) {
      log.error("Import: failed to create function '{}': {}", f.id(), e.getMessage(), e);
      return new ImportItemOutcome(f.id(), "error", e.getMessage());
    }
  }

  /**
   * Creates an organization with its functions, or, when it already exists, attaches the functions
   * from the file that it lacks.
   *
   * @param o the entry from the batch
   * @param orgFunctions organization identifier to attached functions, for every organization in
   *     Keycloak; updated with what this entry creates
   * @param context the lookups shared by every attachment in the batch
   * @return the outcome of the entry
   */
  private @NonNull ImportItemOutcome importOrganization(
      final @NonNull BundleOrganizationEntry o,
      final @NonNull Map<String, Set<String>> orgFunctions,
      final @NonNull FunctionAttachContext context) {

    final String orgIdentifier = o.orgIdentifier();
    try {
      final Set<String> existing = orgFunctions.get(orgIdentifier);
      if (existing != null) {
        final List<String> missing = o.attachedFunctions().stream()
            .filter(fid -> !existing.contains(fid))
            .distinct()
            .toList();
        if (missing.isEmpty()) {
          return new ImportItemOutcome(orgIdentifier, "skipped_duplicate", "organization already exists");
        }
        final List<String> failed = this.attachFunctions(orgIdentifier, missing, existing, context);
        this.refreshOrganization(orgIdentifier);
        return failed.isEmpty()
            ? new ImportItemOutcome(orgIdentifier, "updated", "function(s) attached: " + missing)
            : new ImportItemOutcome(orgIdentifier, "partial",
                "organization already exists; function(s) could not be attached: " + failed);
      }

      final OrganizationInfo created = this.organizationService.create(
          orgIdentifier, o.legalName(), o.nameSv(), o.nameEn(), o.contactEmail(), o.contactPhone());
      final Set<String> attached = new HashSet<>();
      orgFunctions.put(orgIdentifier, attached);
      context.putOrgGroupId(orgIdentifier, created.groupId());

      final List<String> functionIds = o.attachedFunctions().stream().distinct().toList();
      if (functionIds.isEmpty()) {
        return new ImportItemOutcome(orgIdentifier, "created", null);
      }
      final List<String> failed = this.attachFunctions(orgIdentifier, functionIds, attached, context);
      this.refreshOrganization(orgIdentifier);
      return failed.isEmpty()
          ? new ImportItemOutcome(orgIdentifier, "created", null)
          : new ImportItemOutcome(orgIdentifier, "partial",
              "organization created, but function(s) could not be attached: " + failed);
    }
    catch (final RuntimeException e) {
      log.error("Import: failed to create organization '{}': {}", orgIdentifier, e.getMessage(), e);
      return new ImportItemOutcome(orgIdentifier, "error", e.getMessage());
    }
  }

  /**
   * Attaches each of {@code functionIds} to the organization, adding each one attached to
   * {@code attached}.
   *
   * @return the functions that could not be attached
   */
  private @NonNull List<String> attachFunctions(
      final @NonNull String orgIdentifier,
      final @NonNull List<String> functionIds,
      final @NonNull Set<String> attached,
      final @NonNull FunctionAttachContext context) {

    final List<String> failed = new ArrayList<>();
    for (final String functionId : functionIds) {
      try {
        this.keycloakAdminClient.attachFunctionToOrg(orgIdentifier, functionId, context);
        attached.add(functionId);
      }
      catch (final RuntimeException e) {
        log.warn("Import: function '{}' could not be attached to organization '{}': {}",
            functionId, orgIdentifier, e.getMessage());
        failed.add(functionId);
      }
    }
    return failed;
  }

  /** Attaching bypasses OrganizationService, so its cached attached function list is stale. */
  private void refreshOrganization(final @NonNull String orgIdentifier) {
    try {
      this.organizationService.refresh(orgIdentifier);
    }
    catch (final RuntimeException e) {
      log.warn("Import: cache entry for organization '{}' could not be refreshed: {}",
          orgIdentifier, e.getMessage());
    }
  }

  private @NonNull ImportItemOutcome importUser(final @NonNull BundleUserEntry u, final boolean allowSelectUserId) {
    final String pin = trimToNull(u.personalIdentityNumber());
    final String orgAffiliation = trimToNull(u.orgAffiliation());
    final String key = pin != null ? pin : orgAffiliation;

    try {
      final boolean duplicate =
          (pin != null && this.keycloakAdminClient.findUserIdByPersonalIdentityNumber(pin).isPresent())
              || (orgAffiliation != null
                  && this.keycloakAdminClient.findUserIdByOrgAffiliation(orgAffiliation).isPresent());
      if (duplicate) {
        return new ImportItemOutcome(key, "skipped_duplicate", "user already exists");
      }

      // The username is re-checked the same way the duplicate keys are: it may have been taken
      // since the dry-run, and allow-select-user-id is deployment config that may have changed.
      final String username = trimToNull(u.username());
      final String usernameReason = usernameRejectionReason(username, allowSelectUserId);
      if (usernameReason != null) {
        return new ImportItemOutcome(key, "error", usernameReason);
      }

      final String userId = this.keycloakAdminClient.createUser(
          username, u.name(), u.email(), pin, orgAffiliation, u.phoneNumber(), null);
      for (final BundleUserRightEntry r : u.rights()) {
        // Re-check rather than trust the dry-run outcome: allow-org-rights is deployment
        // config and may have changed since, and this call must never be reachable through a
        // path the equivalent manual endpoint (UserRightsController) would refuse.
        if (r.functionId() == null && !this.properties.isAllowOrgRights()) {
          log.warn("Import: user '{}' created but organization-wide right {}/{} could not be granted: "
                  + "allow-org-rights is false",
              key, r.orgIdentifier(), r.right());
          continue;
        }
        try {
          if (r.functionId() == null) {
            this.keycloakAdminClient.addUserToOrgRight(r.orgIdentifier(), userId, r.right());
          }
          else {
            this.keycloakAdminClient.addUserToFunctionRight(r.orgIdentifier(), r.functionId(), userId, r.right());
          }
        }
        catch (final RuntimeException e) {
          log.warn("Import: user '{}' created but right {}/{}/{} could not be granted: {}",
              key, r.orgIdentifier(), r.functionId(), r.right(), e.getMessage());
        }
      }
      return new ImportItemOutcome(key, "created", null);
    }
    catch (final RuntimeException e) {
      log.error("Import: failed to create user '{}': {}", key, e.getMessage(), e);
      return new ImportItemOutcome(key, "error", e.getMessage());
    }
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
          "Unsupported schema_version '" + bundle.schemaVersion() + "', expected '"
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
   * invalid right level, references an organization or org/function pair unknown in Keycloak
   * and unknown earlier in this same import file, or is an organization-wide right while
   * {@code allowOrgRights} is {@code false}. {@code null} if every right is valid.
   */
  private static @Nullable String firstInvalidRightReason(
      final @NonNull List<BundleUserRightEntry> rights,
      final @NonNull Set<String> knownOrgIdentifiers,
      final @NonNull Map<String, Set<String>> orgFunctionAttachments,
      final boolean allowOrgRights) {
    for (final BundleUserRightEntry r : rights) {
      if (r.right() == null || !KeycloakAdminClient.RIGHT_LEVELS.contains(r.right())) {
        return "right must be one of: read, write, admin (got '" + r.right() + "')";
      }
      if (r.orgIdentifier() == null || !knownOrgIdentifiers.contains(r.orgIdentifier())) {
        return "rights reference unknown organization '" + r.orgIdentifier() + "'";
      }
      if (r.functionId() == null && !allowOrgRights) {
        return "organization-wide rights are disabled (iam.admin.allow-org-rights=false); "
            + "give this right a function_id instead";
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

  /**
   * Returns a validation-failure reason for the username of a user entry that is not a duplicate,
   * or {@code null} when the username may be assigned. A username is honoured only when the
   * deployment lets an administrator choose the user ID, and never when it is already taken by
   * someone else. An entry without a username is always fine: Keycloak assigns a random UUID.
   *
   * @param username the username from the file, trimmed, or {@code null} when the entry has none
   * @param allowSelectUserId the value of {@code iam.admin.user-registration.allow-select-user-id}
   * @return the reason, or {@code null} when the entry passes
   */
  private @Nullable String usernameRejectionReason(
      final @Nullable String username, final boolean allowSelectUserId) {
    if (username == null) {
      return null;
    }
    if (!allowSelectUserId) {
      return "usernames are not accepted in an import file for this deployment "
          + "(iam.admin.user-registration.allow-select-user-id is false)";
    }
    if (this.keycloakAdminClient.usernameExists(username)) {
      return "username '" + username + "' is already taken";
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

  private static int countStatus(final @NonNull List<ImportItemOutcome> outcomes, final @NonNull String status) {
    synchronized (outcomes) {
      return (int) outcomes.stream().filter(o -> status.equals(o.status())).count();
    }
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
        u.username(),
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
