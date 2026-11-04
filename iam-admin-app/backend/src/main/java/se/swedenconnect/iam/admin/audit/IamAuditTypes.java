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

import se.swedenconnect.spring.audit.AuditType;

import java.util.List;

/**
 * The audit event types produced by the IAM admin application. Each type is documented in
 * {@code docs/audit-events.md}.
 *
 * <p>The library's own types, {@code system_started}, {@code system_shutdown} and
 * {@code system_alert}, are not listed here.</p>
 *
 * @author Martin Lindström
 */
public final class IamAuditTypes {

  /** A login completed. */
  public static final AuditType LOGIN_SUCCEEDED = AuditType.of("login_succeeded");

  /** An authenticated user lacked the required rights. */
  public static final AuditType LOGIN_REJECTED = AuditType.of("login_rejected");

  /** A login failed for any other reason. */
  public static final AuditType LOGIN_FAILED = AuditType.of("login_failed");

  /** A user logged out. */
  public static final AuditType LOGOUT = AuditType.of("logout");

  /** A user was created. */
  public static final AuditType USER_CREATED = AuditType.of("user_created");

  /** A user's profile was changed. */
  public static final AuditType USER_UPDATED = AuditType.of("user_updated");

  /** A user was deleted. */
  public static final AuditType USER_DELETED = AuditType.of("user_deleted");

  /** A right was granted or changed. */
  public static final AuditType USER_RIGHT_ASSIGNED = AuditType.of("user_right_assigned");

  /** A right was removed. */
  public static final AuditType USER_RIGHT_REMOVED = AuditType.of("user_right_removed");

  /** An organization was created. */
  public static final AuditType ORGANIZATION_CREATED = AuditType.of("organization_created");

  /** An organization was changed. */
  public static final AuditType ORGANIZATION_UPDATED = AuditType.of("organization_updated");

  /** An organization was deleted. */
  public static final AuditType ORGANIZATION_DELETED = AuditType.of("organization_deleted");

  /** A function was created. */
  public static final AuditType FUNCTION_CREATED = AuditType.of("function_created");

  /** A function was changed. */
  public static final AuditType FUNCTION_UPDATED = AuditType.of("function_updated");

  /** A function was deleted. */
  public static final AuditType FUNCTION_DELETED = AuditType.of("function_deleted");

  /** A function was attached to an organization. */
  public static final AuditType FUNCTION_ATTACHED = AuditType.of("function_attached");

  /** A function was detached from an organization. */
  public static final AuditType FUNCTION_DETACHED = AuditType.of("function_detached");

  /** A client was created. */
  public static final AuditType CLIENT_CREATED = AuditType.of("client_created");

  /** A client was changed. */
  public static final AuditType CLIENT_UPDATED = AuditType.of("client_updated");

  /** A client was deleted. */
  public static final AuditType CLIENT_DELETED = AuditType.of("client_deleted");

  /** A reconciliation of the managed clients was run. */
  public static final AuditType CLIENTS_RECONCILED = AuditType.of("clients_reconciled");

  /** A full export was produced. */
  public static final AuditType DATA_EXPORTED = AuditType.of("data_exported");

  /** An import was confirmed. */
  public static final AuditType IMPORT_STARTED = AuditType.of("import_started");

  /** An import job ended. */
  public static final AuditType IMPORT_COMPLETED = AuditType.of("import_completed");

  /** A pending import batch was discarded. */
  public static final AuditType IMPORT_CANCELLED = AuditType.of("import_cancelled");

  /** An authorization check refused a request. */
  public static final AuditType ACCESS_DENIED = AuditType.of("access_denied");

  /** A safety rule refused an operation. */
  public static final AuditType OPERATION_REJECTED = AuditType.of("operation_rejected");

  /** A Keycloak error stopped a change. */
  public static final AuditType OPERATION_FAILED = AuditType.of("operation_failed");

  /** Every audit event type the application produces itself. */
  public static final List<AuditType> ALL = List.of(
      LOGIN_SUCCEEDED, LOGIN_REJECTED, LOGIN_FAILED, LOGOUT,
      USER_CREATED, USER_UPDATED, USER_DELETED, USER_RIGHT_ASSIGNED, USER_RIGHT_REMOVED,
      ORGANIZATION_CREATED, ORGANIZATION_UPDATED, ORGANIZATION_DELETED,
      FUNCTION_CREATED, FUNCTION_UPDATED, FUNCTION_DELETED, FUNCTION_ATTACHED, FUNCTION_DETACHED,
      CLIENT_CREATED, CLIENT_UPDATED, CLIENT_DELETED, CLIENTS_RECONCILED,
      DATA_EXPORTED, IMPORT_STARTED, IMPORT_COMPLETED, IMPORT_CANCELLED,
      ACCESS_DENIED, OPERATION_REJECTED, OPERATION_FAILED);

  private IamAuditTypes() {
  }

}
