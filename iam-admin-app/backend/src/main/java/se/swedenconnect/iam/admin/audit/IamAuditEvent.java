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
import org.springframework.context.ApplicationEvent;
import se.swedenconnect.iam.admin.keycloak.model.RightHolder;
import se.swedenconnect.spring.audit.AuditEvent;
import se.swedenconnect.spring.audit.AuditEventBuilder;
import se.swedenconnect.spring.audit.AuditEventContext;
import se.swedenconnect.spring.audit.AuditType;
import se.swedenconnect.spring.audit.transform.SingleEventTransformer;
import se.swedenconnect.spring.audit.value.AuditValue;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An application event saying that something worth auditing has happened in the IAM admin
 * application. The event transforms itself into an audit event, so nothing has to be registered.
 *
 * <p>Events are only created through the static factory methods, one per audit event type, which is
 * what fixes the shape of each type. Failures are reported with {@link IamErrorAuditEvent}
 * instead.</p>
 *
 * <p>The principal is taken from the security context unless the event assigns one, which it does
 * for login events, the logout and everything an import produces.</p>
 *
 * @author Martin Lindström
 */
public final class IamAuditEvent extends ApplicationEvent implements SingleEventTransformer<IamAuditEvent> {

  @Serial
  private static final long serialVersionUID = 1L;

  /** The audit type. */
  private final AuditType type;

  /** The data fields. */
  private final List<AuditValue<? extends Serializable>> data;

  /** Whether the principal is assigned by the event rather than taken from the context. */
  private final boolean principalAssigned;

  /** The assigned principal. */
  private final @Nullable String principal;

  private IamAuditEvent(final @NonNull AuditType type, final @NonNull List<AuditValue<? extends Serializable>> data,
      final boolean principalAssigned, final @Nullable String principal) {
    super(type.type());
    this.type = type;
    this.data = List.copyOf(data);
    this.principalAssigned = principalAssigned;
    this.principal = principal;
  }

  private static @NonNull IamAuditEvent of(final @NonNull AuditType type, final @NonNull Data data) {
    return new IamAuditEvent(type, data.values, false, null);
  }

  /**
   * Returns a copy of this event with the given principal, replacing the one in the security context.
   *
   * @param principal the principal, or {@code null} for none at all
   * @return a new event
   */
  public @NonNull IamAuditEvent withPrincipal(final @Nullable String principal) {
    return new IamAuditEvent(this.type, this.data, true, principal);
  }

  /**
   * Returns the audit type of this event.
   *
   * @return the audit type
   */
  public @NonNull AuditType getType() {
    return this.type;
  }

  /** {@inheritDoc} */
  @Override
  public @NonNull AuditEvent transformEvent(final @NonNull IamAuditEvent event, final @NonNull AuditEventContext context) {
    final AuditEventBuilder builder = AuditEventBuilder.builder(context)
        .type(event.type)
        .timestamp(Instant.ofEpochMilli(event.getTimestamp()))
        .dataFields(new ArrayList<>(event.data));
    if (event.principalAssigned) {
      builder.principal(event.principal);
    }
    return builder.build();
  }

  /** {@inheritDoc} */
  @Override
  public @NonNull Class<IamAuditEvent> getEventType() {
    return IamAuditEvent.class;
  }

  // Login

  /**
   * {@code login_succeeded}
   *
   * @param user the user, with {@code user_id} and {@code display_name}
   * @param login the login
   * @return the event
   */
  public static @NonNull IamAuditEvent loginSucceeded(
      final AuditData.@NonNull User user, final AuditData.@NonNull Login login) {
    return of(IamAuditTypes.LOGIN_SUCCEEDED, new Data().add(user.toAuditValue()).add(login.toAuditValue()))
        .withPrincipal(user.userId());
  }

  /**
   * {@code login_rejected}
   *
   * @param user the rejected user, with {@code user_id} and {@code display_name}
   * @param login the login, carrying the reason
   * @return the event
   */
  public static @NonNull IamAuditEvent loginRejected(
      final AuditData.@NonNull User user, final AuditData.@NonNull Login login) {
    return of(IamAuditTypes.LOGIN_REJECTED, new Data().add(user.toAuditValue()).add(login.toAuditValue()))
        .withPrincipal(user.userId());
  }

  /**
   * {@code logout}
   *
   * @param user the user logging out, with {@code user_id} and {@code display_name}
   * @return the event
   */
  public static @NonNull IamAuditEvent logout(final AuditData.@NonNull User user) {
    return of(IamAuditTypes.LOGOUT, new Data().add(user.toAuditValue())).withPrincipal(user.userId());
  }

  // Users

  /**
   * {@code user_created}
   *
   * @param user the user, with every member that has a value
   * @param importData the import that created the user, or {@code null}
   * @return the event
   */
  public static @NonNull IamAuditEvent userCreated(
      final AuditData.@NonNull User user, final AuditData.@Nullable Import importData) {
    return of(IamAuditTypes.USER_CREATED, new Data().add(user.toAuditValue()).add(importData));
  }

  /**
   * {@code user_updated}
   *
   * @param user the user
   * @param change the changed fields
   * @return the event
   */
  public static @NonNull IamAuditEvent userUpdated(
      final AuditData.@NonNull User user, final AuditData.@NonNull Change change) {
    return of(IamAuditTypes.USER_UPDATED, new Data().add(user.toAuditValue()).add(change.toAuditValue()));
  }

  /**
   * {@code user_deleted}
   *
   * @param user the user, with its eID attributes
   * @param removedRights the rights the user held
   * @return the event
   */
  public static @NonNull IamAuditEvent userDeleted(
      final AuditData.@NonNull User user, final @NonNull List<RightHolder> removedRights) {
    return of(IamAuditTypes.USER_DELETED, new Data()
        .add(user.toAuditValue())
        .add(AuditData.removedRights(removedRights, false)));
  }

  /**
   * {@code user_right_assigned}
   *
   * @param user the user
   * @param organizationIdentifier the organization
   * @param functionId the function, or {@code null} for an organization-wide right
   * @param right the right granted
   * @param previousRight the right held before at the same level, or {@code null}
   * @param importData the import that granted the right, or {@code null}
   * @return the event
   */
  public static @NonNull IamAuditEvent userRightAssigned(final AuditData.@NonNull User user,
      final @NonNull String organizationIdentifier, final @Nullable String functionId, final @NonNull String right,
      final @Nullable String previousRight, final AuditData.@Nullable Import importData) {
    return of(IamAuditTypes.USER_RIGHT_ASSIGNED, new Data()
        .add(user.toAuditValue())
        .add(AuditData.ORGANIZATION_IDENTIFIER, organizationIdentifier)
        .add(AuditData.FUNCTION_ID, functionId)
        .add(AuditData.RIGHT, right)
        .add(AuditData.PREVIOUS_RIGHT, previousRight)
        .add(importData));
  }

  /**
   * {@code user_right_removed}
   *
   * @param user the user
   * @param organizationIdentifier the organization
   * @param functionId the function, or {@code null} for an organization-wide right
   * @param right the right removed
   * @return the event
   */
  public static @NonNull IamAuditEvent userRightRemoved(final AuditData.@NonNull User user,
      final @NonNull String organizationIdentifier, final @Nullable String functionId, final @NonNull String right) {
    return of(IamAuditTypes.USER_RIGHT_REMOVED, new Data()
        .add(user.toAuditValue())
        .add(AuditData.ORGANIZATION_IDENTIFIER, organizationIdentifier)
        .add(AuditData.FUNCTION_ID, functionId)
        .add(AuditData.RIGHT, right));
  }

  // Organizations

  /**
   * {@code organization_created}
   *
   * @param organizationIdentifier the organization
   * @param legalName the legal name
   * @param importData the import that created the organization, or {@code null}
   * @return the event
   */
  public static @NonNull IamAuditEvent organizationCreated(final @NonNull String organizationIdentifier,
      final @NonNull String legalName, final AuditData.@Nullable Import importData) {
    return of(IamAuditTypes.ORGANIZATION_CREATED, new Data()
        .add(AuditData.ORGANIZATION_IDENTIFIER, organizationIdentifier)
        .add(AuditData.ORGANIZATION_LEGAL_NAME, legalName)
        .add(importData));
  }

  /**
   * {@code organization_updated}
   *
   * @param organizationIdentifier the organization
   * @param change the changed fields
   * @return the event
   */
  public static @NonNull IamAuditEvent organizationUpdated(
      final @NonNull String organizationIdentifier, final AuditData.@NonNull Change change) {
    return of(IamAuditTypes.ORGANIZATION_UPDATED, new Data()
        .add(AuditData.ORGANIZATION_IDENTIFIER, organizationIdentifier)
        .add(change.toAuditValue()));
  }

  /**
   * {@code organization_deleted}
   *
   * @param organizationIdentifier the organization
   * @param legalName the legal name, or {@code null} if not known
   * @param removedRights the rights held in the organization
   * @return the event
   */
  public static @NonNull IamAuditEvent organizationDeleted(final @NonNull String organizationIdentifier,
      final @Nullable String legalName, final @NonNull List<RightHolder> removedRights) {
    return of(IamAuditTypes.ORGANIZATION_DELETED, new Data()
        .add(AuditData.ORGANIZATION_IDENTIFIER, organizationIdentifier)
        .add(AuditData.ORGANIZATION_LEGAL_NAME, legalName)
        .add(AuditData.removedRights(removedRights, true)));
  }

  // Functions

  /**
   * {@code function_created}
   *
   * @param functionId the function
   * @param importData the import that created the function, or {@code null}
   * @return the event
   */
  public static @NonNull IamAuditEvent functionCreated(
      final @NonNull String functionId, final AuditData.@Nullable Import importData) {
    return of(IamAuditTypes.FUNCTION_CREATED, new Data().add(AuditData.FUNCTION_ID, functionId).add(importData));
  }

  /**
   * {@code function_updated}
   *
   * @param functionId the function
   * @param change the changed fields
   * @return the event
   */
  public static @NonNull IamAuditEvent functionUpdated(
      final @NonNull String functionId, final AuditData.@NonNull Change change) {
    return of(IamAuditTypes.FUNCTION_UPDATED, new Data()
        .add(AuditData.FUNCTION_ID, functionId)
        .add(change.toAuditValue()));
  }

  /**
   * {@code function_deleted}
   *
   * @param functionId the function
   * @param removedRights the rights held on the function in every organization
   * @return the event
   */
  public static @NonNull IamAuditEvent functionDeleted(
      final @NonNull String functionId, final @NonNull List<RightHolder> removedRights) {
    return of(IamAuditTypes.FUNCTION_DELETED, new Data()
        .add(AuditData.FUNCTION_ID, functionId)
        .add(AuditData.removedRights(removedRights, true)));
  }

  /**
   * {@code function_attached}
   *
   * @param organizationIdentifier the organization
   * @param functionId the function
   * @param importData the import that attached the function, or {@code null}
   * @return the event
   */
  public static @NonNull IamAuditEvent functionAttached(final @NonNull String organizationIdentifier,
      final @NonNull String functionId, final AuditData.@Nullable Import importData) {
    return of(IamAuditTypes.FUNCTION_ATTACHED, new Data()
        .add(AuditData.ORGANIZATION_IDENTIFIER, organizationIdentifier)
        .add(AuditData.FUNCTION_ID, functionId)
        .add(importData));
  }

  /**
   * {@code function_detached}
   *
   * @param organizationIdentifier the organization
   * @param functionId the function
   * @param removedRights the rights held on the function in the organization
   * @return the event
   */
  public static @NonNull IamAuditEvent functionDetached(final @NonNull String organizationIdentifier,
      final @NonNull String functionId, final @NonNull List<RightHolder> removedRights) {
    return of(IamAuditTypes.FUNCTION_DETACHED, new Data()
        .add(AuditData.ORGANIZATION_IDENTIFIER, organizationIdentifier)
        .add(AuditData.FUNCTION_ID, functionId)
        .add(AuditData.removedRights(removedRights, true)));
  }

  // Clients

  /**
   * {@code client_created}
   *
   * @param client the client with its settings
   * @return the event
   */
  public static @NonNull IamAuditEvent clientCreated(final AuditData.@NonNull Client client) {
    return of(IamAuditTypes.CLIENT_CREATED, new Data().add(client.toAuditValue()));
  }

  /**
   * {@code client_updated}
   *
   * @param clientId the client_id
   * @param change the changed fields with their previous and new values
   * @return the event
   */
  public static @NonNull IamAuditEvent clientUpdated(
      final @NonNull String clientId, final AuditData.@NonNull Change change) {
    return of(IamAuditTypes.CLIENT_UPDATED, new Data()
        .add(AuditData.Client.of(clientId).toAuditValue())
        .add(change.toAuditValue()));
  }

  /**
   * {@code client_deleted}
   *
   * @param clientId the client_id
   * @return the event
   */
  public static @NonNull IamAuditEvent clientDeleted(final @NonNull String clientId) {
    return of(IamAuditTypes.CLIENT_DELETED, new Data().add(AuditData.Client.of(clientId).toAuditValue()));
  }

  /**
   * {@code clients_reconciled}
   *
   * @param reconciliation the outcome of the run
   * @param clientId the client_id on a single-client run, or {@code null}
   * @return the event
   */
  public static @NonNull IamAuditEvent clientsReconciled(
      final AuditData.@NonNull Reconciliation reconciliation, final @Nullable String clientId) {
    return of(IamAuditTypes.CLIENTS_RECONCILED, new Data()
        .add(reconciliation.toAuditValue())
        .add(clientId != null ? AuditData.Client.of(clientId).toAuditValue() : null));
  }

  // Export and import

  /**
   * {@code data_exported}
   *
   * @param export the export
   * @return the event
   */
  public static @NonNull IamAuditEvent dataExported(final AuditData.@NonNull Export export) {
    return of(IamAuditTypes.DATA_EXPORTED, new Data().add(export.toAuditValue()));
  }

  /**
   * {@code import_started}
   *
   * @param importData the import with its planned counts
   * @return the event
   */
  public static @NonNull IamAuditEvent importStarted(final AuditData.@NonNull Import importData) {
    return of(IamAuditTypes.IMPORT_STARTED, new Data().add(importData));
  }

  /**
   * {@code import_completed}
   *
   * @param importData the import with its outcome
   * @return the event
   */
  public static @NonNull IamAuditEvent importCompleted(final AuditData.@NonNull Import importData) {
    return of(IamAuditTypes.IMPORT_COMPLETED, new Data().add(importData));
  }

  /**
   * {@code import_cancelled}
   *
   * @param batchId the discarded batch
   * @return the event
   */
  public static @NonNull IamAuditEvent importCancelled(final @NonNull String batchId) {
    return of(IamAuditTypes.IMPORT_CANCELLED, new Data().add(AuditData.Import.batch(batchId)));
  }

  // Refusals

  /**
   * {@code access_denied}
   *
   * @param operation the operation and the reason
   * @param target what the operation was aimed at
   * @return the event
   */
  public static @NonNull IamAuditEvent accessDenied(
      final AuditData.@NonNull Operation operation, final AuditData.@NonNull Target target) {
    return of(IamAuditTypes.ACCESS_DENIED, new Data().add(operation.toAuditValue()).addAll(target.toAuditValues()));
  }

  /**
   * {@code operation_rejected}
   *
   * @param operation the operation and the reason
   * @param target what the operation was aimed at
   * @return the event
   */
  public static @NonNull IamAuditEvent operationRejected(
      final AuditData.@NonNull Operation operation, final AuditData.@NonNull Target target) {
    return of(IamAuditTypes.OPERATION_REJECTED,
        new Data().add(operation.toAuditValue()).addAll(target.toAuditValues()));
  }

  /** Collects data fields, leaving out the ones without a value. */
  private static final class Data {

    private final List<AuditValue<? extends Serializable>> values = new ArrayList<>();

    @NonNull Data add(final @Nullable AuditValue<? extends Serializable> value) {
      if (value != null) {
        this.values.add(value);
      }
      return this;
    }

    @NonNull Data add(final AuditData.@Nullable Import importData) {
      return importData != null ? this.add(importData.toAuditValue()) : this;
    }

    @NonNull Data add(final @NonNull String name, final @Nullable String value) {
      AuditData.addString(this.values, name, value);
      return this;
    }

    @NonNull Data addAll(final @NonNull List<AuditValue<? extends Serializable>> values) {
      this.values.addAll(values);
      return this;
    }
  }

}
