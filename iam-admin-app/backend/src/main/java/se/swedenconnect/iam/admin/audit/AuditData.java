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
package se.swedenconnect.iam.admin.audit;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.oidc.StandardClaimAccessor;
import se.swedenconnect.iam.admin.keycloak.model.ManagedClientInfo;
import se.swedenconnect.iam.admin.keycloak.model.RightHolder;
import se.swedenconnect.iam.admin.keycloak.model.UserInfo;
import se.swedenconnect.spring.audit.value.AuditValue;
import se.swedenconnect.spring.audit.value.ListAuditValue;
import se.swedenconnect.spring.audit.value.MapAuditValue;
import se.swedenconnect.spring.audit.value.StringAuditValue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The objects that make up the {@code data} part of the application's audit events. Each record
 * turns itself into an audit value with a fixed set of members, and leaves out every member that
 * has no value.
 *
 * <p>The members are documented in {@code docs/audit-events.md}.</p>
 *
 * @author Martin Lindström
 */
public final class AuditData {

  /** Data member holding an organization identifier. */
  public static final String ORGANIZATION_IDENTIFIER = "organization_identifier";

  /** Data member holding an organization's legal name. */
  public static final String ORGANIZATION_LEGAL_NAME = "organization_legal_name";

  /** Data member holding a function identifier. */
  public static final String FUNCTION_ID = "function_id";

  /** Data member holding a right level. */
  public static final String RIGHT = "right";

  /** Data member holding the right level held before a change. */
  public static final String PREVIOUS_RIGHT = "previous_right";

  /** Data member holding the rights that disappeared with a deletion. */
  public static final String REMOVED_RIGHTS = "removed_rights";

  /**
   * The {@code user} object.
   *
   * @param userId the Keycloak user ID
   * @param displayName the user's name, or {@code null} if not available
   * @param personalIdentityNumber the personal identity number, or {@code null}
   * @param orgAffiliation the organizational affiliation, or {@code null}
   * @param hsaId the HSA-ID, or {@code null}
   * @param efosId the EFOS-ID, or {@code null}
   * @param username the Keycloak username chosen at creation, or {@code null}
   * @param temporaryPasswordSet whether a temporary password was set, or {@code null} when not
   *     relevant to the event
   */
  public record User(
      @NonNull String userId,
      @Nullable String displayName,
      @Nullable String personalIdentityNumber,
      @Nullable String orgAffiliation,
      @Nullable String hsaId,
      @Nullable String efosId,
      @Nullable String username,
      @Nullable Boolean temporaryPasswordSet) {

    /**
     * Identifies a user by ID and name only.
     *
     * @param userId the Keycloak user ID
     * @param displayName the user's name, or {@code null}
     * @return the user object
     */
    public static @NonNull User of(final @NonNull String userId, final @Nullable String displayName) {
      return new User(userId, displayName, null, null, null, null, null, null);
    }

    /**
     * Identifies a user by ID and name only.
     *
     * @param user the user
     * @return the user object
     */
    public static @NonNull User of(final @NonNull UserInfo user) {
      return of(user.userId(), displayName(user));
    }

    /**
     * Identifies a user by ID and name from the claims of an OIDC login. The ID is the subject, which
     * is the Keycloak user ID, and the name is the {@code name} claim, or the given name and family
     * name if there is no {@code name} claim.
     *
     * @param claims the claims of the ID token or the logged-in user
     * @return the user object
     */
    public static @NonNull User of(final @NonNull StandardClaimAccessor claims) {
      String name = claims.getFullName();
      if (name == null || name.isBlank()) {
        final String given = claims.getGivenName() != null ? claims.getGivenName() : "";
        final String family = claims.getFamilyName() != null ? claims.getFamilyName() : "";
        name = (given + " " + family).trim();
      }
      return of(claims.getSubject(), name.isBlank() ? null : name);
    }

    /**
     * Identifies a user by ID, name and eID attributes, which is what a deletion records.
     *
     * @param user the user
     * @return the user object
     */
    public static @NonNull User withIdentity(final @NonNull UserInfo user) {
      return new User(user.userId(), displayName(user), user.personalIdentityNumber(), user.orgAffiliation(),
          user.hsaId(), user.efosId(), null, null);
    }

    /**
     * Returns the name of a user, built from the first and last name, or {@code null} if the user has
     * neither.
     *
     * @param user the user
     * @return the name, or {@code null}
     */
    public static @Nullable String displayName(final @NonNull UserInfo user) {
      final String first = user.firstName() != null ? user.firstName() : "";
      final String last = user.lastName() != null ? user.lastName() : "";
      final String name = (first + " " + last).trim();
      return name.isEmpty() ? null : name;
    }

    /**
     * Returns the {@code user} audit value.
     *
     * @return the audit value
     */
    public @NonNull MapAuditValue toAuditValue() {
      return new Members()
          .put("user_id", this.userId)
          .put("display_name", this.displayName)
          .put("personal_identity_number", this.personalIdentityNumber)
          .put("org_affiliation", this.orgAffiliation)
          .put("hsa_id", this.hsaId)
          .put("efos_id", this.efosId)
          .put("username", this.username)
          .put("temporary_password_set", this.temporaryPasswordSet)
          .toAuditValue("user");
    }
  }

  /**
   * The {@code client} object.
   *
   * @param clientId the OAuth2 client_id
   * @param settings the client's settings by member name, empty when only the client is identified
   */
  public record Client(@NonNull String clientId, @NonNull Map<String, Serializable> settings) {

    /**
     * Identifies a client by its client_id only.
     *
     * @param clientId the client_id
     * @return the client object
     */
    public static @NonNull Client of(final @NonNull String clientId) {
      return new Client(clientId, Map.of());
    }

    /**
     * Describes a client with all of its settings.
     *
     * @param client the client
     * @return the client object
     */
    public static @NonNull Client withSettings(final @NonNull ManagedClientInfo client) {
      return new Client(client.clientId(), settingsOf(client));
    }

    /**
     * Returns the settings of a client that the audit log records, by member name. A setting without a
     * value is left out.
     *
     * @param client the client
     * @return the settings
     */
    public static @NonNull Map<String, Serializable> settingsOf(final @NonNull ManagedClientInfo client) {
      final Members members = new Members()
          .put("name", client.name())
          .put("oidc_client", client.oidcClient())
          .put("resource_server", client.resourceServer())
          .put("functions", new ArrayList<>(client.functions()))
          .put("redirect_uris", new ArrayList<>(client.redirectUris()))
          .put("jwks_uri", client.jwksUri())
          .put("jwks_string", client.jwksString())
          .put("org_rights_id_token", client.orgRightsIdToken())
          .put("org_rights_access_token", client.orgRightsAccessToken());
      return members.values;
    }

    /**
     * Returns the {@code client} audit value.
     *
     * @return the audit value
     */
    public @NonNull MapAuditValue toAuditValue() {
      final Members members = new Members().put("client_id", this.clientId);
      this.settings.forEach(members::put);
      return members.toAuditValue("client");
    }
  }

  /**
   * The {@code change} object.
   *
   * @param changedFields the names of the fields that changed
   * @param oldValues the previous values of the changed fields, or {@code null} if not recorded
   * @param newValues the new values of the changed fields, or {@code null} if not recorded
   */
  public record Change(
      @NonNull List<String> changedFields,
      @Nullable Map<String, Serializable> oldValues,
      @Nullable Map<String, Serializable> newValues) {

    /**
     * Compares two sets of field values and records which fields differ. A blank string is treated
     * as no value.
     *
     * @param before the values before the change, by field name
     * @param after the values after the change, by field name
     * @param recordValues whether the previous and new values are recorded as well
     * @return the change, or {@code null} if nothing changed
     */
    public static @Nullable Change between(
        final @NonNull Map<String, ? extends @Nullable Serializable> before,
        final @NonNull Map<String, ? extends @Nullable Serializable> after,
        final boolean recordValues) {

      final List<String> fields = new ArrayList<>(before.keySet());
      after.keySet().stream().filter(k -> !fields.contains(k)).forEach(fields::add);

      final List<String> changed = new ArrayList<>();
      final Members oldValues = new Members();
      final Members newValues = new Members();
      for (final String field : fields) {
        final Serializable oldValue = normalize(before.get(field));
        final Serializable newValue = normalize(after.get(field));
        if (!Objects.equals(oldValue, newValue)) {
          changed.add(field);
          oldValues.put(field, oldValue);
          newValues.put(field, newValue);
        }
      }
      if (changed.isEmpty()) {
        return null;
      }
      return recordValues
          ? new Change(List.copyOf(changed), oldValues.values, newValues.values)
          : new Change(List.copyOf(changed), null, null);
    }

    private static @Nullable Serializable normalize(final @Nullable Serializable value) {
      return value instanceof final String s && s.isBlank() ? null : value;
    }

    /**
     * Returns the {@code change} audit value.
     *
     * @return the audit value
     */
    public @NonNull MapAuditValue toAuditValue() {
      final Members members = new Members().put("changed_fields", new ArrayList<>(this.changedFields));
      if (this.oldValues != null) {
        members.put("old", new LinkedHashMap<>(this.oldValues));
      }
      if (this.newValues != null) {
        members.put("new", new LinkedHashMap<>(this.newValues));
      }
      return members.toAuditValue("change");
    }
  }

  /**
   * The {@code login} object.
   *
   * @param method {@code standard} or {@code sso}
   * @param superuser whether the user is a superuser, or {@code null} if not known
   * @param organizationConstraint the organization the login was constrained to, or {@code null}
   * @param functionConstraint the function the login was constrained to, or {@code null}
   * @param reason the reason for a rejected login, or {@code null}
   */
  public record Login(
      @NonNull String method,
      @Nullable Boolean superuser,
      @Nullable String organizationConstraint,
      @Nullable String functionConstraint,
      @Nullable String reason) {

    /** A login through the ordinary login page. */
    public static final String STANDARD = "standard";

    /** A login through the SSO endpoint. */
    public static final String SSO = "sso";

    /** Rejection reason: the user has no {@code org_rights} claim. */
    public static final String NO_ORG_RIGHTS = "no_org_rights";

    /** Rejection reason: the user holds no admin right within the login's constraints. */
    public static final String INSUFFICIENT_ADMIN_RIGHTS = "insufficient_admin_rights";

    /**
     * Returns the {@code login} audit value.
     *
     * @return the audit value
     */
    public @NonNull MapAuditValue toAuditValue() {
      return new Members()
          .put("method", this.method)
          .put("superuser", this.superuser)
          .put("organization_constraint", this.organizationConstraint)
          .put("function_constraint", this.functionConstraint)
          .put("reason", this.reason)
          .toAuditValue("login");
    }
  }

  /**
   * The {@code import} object.
   *
   * @param batchId the batch ID
   * @param jobId the job ID, or {@code null}
   * @param functionCount the number of functions in the batch, or {@code null}
   * @param organizationCount the number of organizations in the batch, or {@code null}
   * @param userCount the number of users in the batch, or {@code null}
   * @param status {@code completed} or {@code failed}, or {@code null}
   * @param createdCount the number of entries created, or {@code null}
   * @param updatedCount the number of entries updated, or {@code null}
   * @param partialCount the number of entries only partly handled, or {@code null}
   * @param skippedCount the number of entries skipped as duplicates, or {@code null}
   * @param errorCount the number of entries that failed, or {@code null}
   * @param message why the import failed, or {@code null}
   */
  public record Import(
      @NonNull String batchId,
      @Nullable String jobId,
      @Nullable Integer functionCount,
      @Nullable Integer organizationCount,
      @Nullable Integer userCount,
      @Nullable String status,
      @Nullable Integer createdCount,
      @Nullable Integer updatedCount,
      @Nullable Integer partialCount,
      @Nullable Integer skippedCount,
      @Nullable Integer errorCount,
      @Nullable String message) {

    /**
     * Identifies a pending batch.
     *
     * @param batchId the batch ID
     * @return the import object
     */
    public static @NonNull Import batch(final @NonNull String batchId) {
      return new Import(batchId, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Identifies a running import, which is what every change made by the import carries.
     *
     * @param batchId the batch ID
     * @param jobId the job ID
     * @return the import object
     */
    public static @NonNull Import job(final @NonNull String batchId, final @NonNull String jobId) {
      return new Import(batchId, jobId, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Describes a confirmed import with the number of entries it plans to handle.
     *
     * @param batchId the batch ID
     * @param jobId the job ID
     * @param functionCount the number of functions
     * @param organizationCount the number of organizations
     * @param userCount the number of users
     * @return the import object
     */
    public static @NonNull Import planned(final @NonNull String batchId, final @NonNull String jobId,
        final int functionCount, final int organizationCount, final int userCount) {
      return new Import(batchId, jobId, functionCount, organizationCount, userCount,
          null, null, null, null, null, null, null);
    }

    /**
     * Describes an import that has ended.
     *
     * @param batchId the batch ID
     * @param jobId the job ID
     * @param status {@code completed} or {@code failed}
     * @param created the number of entries created
     * @param updated the number of entries updated
     * @param partial the number of entries only partly handled
     * @param skipped the number of entries skipped as duplicates
     * @param errors the number of entries that failed
     * @param message why the import failed, or {@code null}
     * @return the import object
     */
    public static @NonNull Import ended(final @NonNull String batchId, final @NonNull String jobId,
        final @NonNull String status, final int created, final int updated, final int partial,
        final int skipped, final int errors, final @Nullable String message) {
      return new Import(batchId, jobId, null, null, null, status, created, updated, partial, skipped, errors,
          message);
    }

    /**
     * Returns the {@code import} audit value.
     *
     * @return the audit value
     */
    public @NonNull MapAuditValue toAuditValue() {
      return new Members()
          .put("batch_id", this.batchId)
          .put("job_id", this.jobId)
          .put("function_count", this.functionCount)
          .put("organization_count", this.organizationCount)
          .put("user_count", this.userCount)
          .put("status", this.status)
          .put("created_count", this.createdCount)
          .put("updated_count", this.updatedCount)
          .put("partial_count", this.partialCount)
          .put("skipped_count", this.skippedCount)
          .put("error_count", this.errorCount)
          .put("message", this.message)
          .toAuditValue("import");
    }
  }

  /**
   * The {@code export} object.
   *
   * @param functionCount the number of functions exported
   * @param organizationCount the number of organizations exported
   * @param userCount the number of users exported
   */
  public record Export(int functionCount, int organizationCount, int userCount) {

    /**
     * Returns the {@code export} audit value.
     *
     * @return the audit value
     */
    public @NonNull MapAuditValue toAuditValue() {
      return new Members()
          .put("function_count", this.functionCount)
          .put("organization_count", this.organizationCount)
          .put("user_count", this.userCount)
          .toAuditValue("export");
    }
  }

  /**
   * The {@code reconciliation} object.
   *
   * @param trigger {@code manual} or {@code scheduled}
   * @param clientCount the number of clients reconciled
   * @param created the number of artifacts created
   * @param removed the number of artifacts removed
   * @param errors the errors of the run
   */
  public record Reconciliation(
      @NonNull String trigger, int clientCount, int created, int removed, @NonNull List<String> errors) {

    /** A run started from the application. */
    public static final String MANUAL = "manual";

    /** A run started by the schedule. */
    public static final String SCHEDULED = "scheduled";

    /**
     * Returns the {@code reconciliation} audit value.
     *
     * @return the audit value
     */
    public @NonNull MapAuditValue toAuditValue() {
      return new Members()
          .put("trigger", this.trigger)
          .put("client_count", this.clientCount)
          .put("created", this.created)
          .put("removed", this.removed)
          .put("errors", this.errors.isEmpty() ? null : new ArrayList<>(this.errors))
          .toAuditValue("reconciliation");
    }
  }

  /**
   * The {@code operation} object.
   *
   * @param name the operation, see {@link AuditOperations}
   * @param reason why the operation was refused, or {@code null}
   */
  public record Operation(@NonNull String name, @Nullable String reason) {

    /**
     * Returns the {@code operation} audit value.
     *
     * @return the audit value
     */
    public @NonNull MapAuditValue toAuditValue() {
      return new Members()
          .put("name", this.name)
          .put("reason", this.reason)
          .toAuditValue("operation");
    }
  }

  /**
   * What a refused or failed operation was aimed at. Every part is optional.
   *
   * @param organizationIdentifier the organization, or {@code null}
   * @param functionId the function, or {@code null}
   * @param right the right level, or {@code null}
   * @param user the user, or {@code null}
   * @param client the client, or {@code null}
   * @param importData the import, or {@code null}
   */
  public record Target(
      @Nullable String organizationIdentifier,
      @Nullable String functionId,
      @Nullable String right,
      @Nullable User user,
      @Nullable Client client,
      @Nullable Import importData) {

    /**
     * A target with nothing identified.
     *
     * @return the target
     */
    public static @NonNull Target none() {
      return new Target(null, null, null, null, null, null);
    }

    /**
     * Returns a copy aimed at the given organization.
     *
     * @param organizationIdentifier the organization
     * @return the target
     */
    public @NonNull Target organization(final @Nullable String organizationIdentifier) {
      return new Target(organizationIdentifier, this.functionId, this.right, this.user, this.client, this.importData);
    }

    /**
     * Returns a copy aimed at the given function.
     *
     * @param functionId the function
     * @return the target
     */
    public @NonNull Target function(final @Nullable String functionId) {
      return new Target(this.organizationIdentifier, functionId, this.right, this.user, this.client, this.importData);
    }

    /**
     * Returns a copy aimed at the given right level.
     *
     * @param right the right level
     * @return the target
     */
    public @NonNull Target right(final @Nullable String right) {
      return new Target(this.organizationIdentifier, this.functionId, right, this.user, this.client, this.importData);
    }

    /**
     * Returns a copy aimed at the given user.
     *
     * @param user the user
     * @return the target
     */
    public @NonNull Target user(final @Nullable User user) {
      return new Target(this.organizationIdentifier, this.functionId, this.right, user, this.client, this.importData);
    }

    /**
     * Returns a copy aimed at the given client.
     *
     * @param client the client
     * @return the target
     */
    public @NonNull Target client(final @Nullable Client client) {
      return new Target(this.organizationIdentifier, this.functionId, this.right, this.user, client, this.importData);
    }

    /**
     * Returns a copy belonging to the given import.
     *
     * @param importData the import
     * @return the target
     */
    public @NonNull Target importData(final @Nullable Import importData) {
      return new Target(this.organizationIdentifier, this.functionId, this.right, this.user, this.client, importData);
    }

    /**
     * Returns the audit values describing the target.
     *
     * @return the audit values
     */
    public @NonNull List<AuditValue<? extends Serializable>> toAuditValues() {
      final List<AuditValue<? extends Serializable>> values = new ArrayList<>();
      addString(values, ORGANIZATION_IDENTIFIER, this.organizationIdentifier);
      addString(values, FUNCTION_ID, this.functionId);
      addString(values, RIGHT, this.right);
      if (this.user != null) {
        values.add(this.user.toAuditValue());
      }
      if (this.client != null) {
        values.add(this.client.toAuditValue());
      }
      if (this.importData != null) {
        values.add(this.importData.toAuditValue());
      }
      return values;
    }
  }

  /**
   * Returns the {@code removed_rights} audit value, or {@code null} if no rights were removed.
   *
   * @param rights the rights held before the deletion
   * @param includeUser whether each entry names the user holding the right
   * @return the audit value, or {@code null}
   */
  public static @Nullable ListAuditValue removedRights(
      final @NonNull List<RightHolder> rights, final boolean includeUser) {
    if (rights.isEmpty()) {
      return null;
    }
    final List<LinkedHashMap<String, Serializable>> entries = new ArrayList<>();
    for (final RightHolder r : rights) {
      final Members members = new Members();
      if (includeUser) {
        members.put("user_id", r.userId()).put("display_name", r.displayName());
      }
      members.put(ORGANIZATION_IDENTIFIER, r.orgIdentifier())
          .put(FUNCTION_ID, r.functionId())
          .put(RIGHT, r.right());
      entries.add(members.values);
    }
    return new ListAuditValue(REMOVED_RIGHTS, entries);
  }

  /**
   * Adds a string audit value to the list, unless the value is {@code null}.
   *
   * @param values the list
   * @param name the name of the value
   * @param value the value, or {@code null}
   */
  static void addString(final @NonNull List<AuditValue<? extends Serializable>> values,
      final @NonNull String name, final @Nullable String value) {
    if (value != null) {
      values.add(new StringAuditValue(name, value));
    }
  }

  /** Collects the members of an object, leaving out every member without a value. */
  private static final class Members {

    private final LinkedHashMap<String, Serializable> values = new LinkedHashMap<>();

    @NonNull Members put(final @NonNull String name, final @Nullable Serializable value) {
      if (value != null) {
        this.values.put(name, value);
      }
      return this;
    }

    @NonNull MapAuditValue toAuditValue(final @NonNull String name) {
      return new MapAuditValue(name, this.values);
    }
  }

  private AuditData() {
  }

}
