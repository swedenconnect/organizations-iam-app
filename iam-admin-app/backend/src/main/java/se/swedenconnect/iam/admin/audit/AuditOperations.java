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

/**
 * The values of {@code operation.name} and {@code operation.reason} in the {@code access_denied},
 * {@code operation_rejected} and {@code operation_failed} audit events. The values are listed in
 * {@code docs/audit-events.md}.
 *
 * @author Martin Lindström
 */
public final class AuditOperations {

  // Operation names

  /** Listing users. */
  public static final String LIST_USERS = "list_users";

  /** Reading a single user. */
  public static final String READ_USER = "read_user";

  /** Creating a user. */
  public static final String CREATE_USER = "create_user";

  /** Changing a user's profile. */
  public static final String UPDATE_USER = "update_user";

  /** Deleting a user. */
  public static final String DELETE_USER = "delete_user";

  /** Granting or changing a right. */
  public static final String ASSIGN_USER_RIGHT = "assign_user_right";

  /** Removing a right. */
  public static final String REMOVE_USER_RIGHT = "remove_user_right";

  /** Listing organizations. */
  public static final String LIST_ORGANIZATIONS = "list_organizations";

  /** Reading a single organization. */
  public static final String READ_ORGANIZATION = "read_organization";

  /** Creating an organization. */
  public static final String CREATE_ORGANIZATION = "create_organization";

  /** Changing an organization. */
  public static final String UPDATE_ORGANIZATION = "update_organization";

  /** Deleting an organization. */
  public static final String DELETE_ORGANIZATION = "delete_organization";

  /** Listing the organizations a function is attached to. */
  public static final String LIST_FUNCTION_ORGANIZATIONS = "list_function_organizations";

  /** Creating a function. */
  public static final String CREATE_FUNCTION = "create_function";

  /** Changing a function. */
  public static final String UPDATE_FUNCTION = "update_function";

  /** Deleting a function. */
  public static final String DELETE_FUNCTION = "delete_function";

  /** Attaching a function to an organization. */
  public static final String ATTACH_FUNCTION = "attach_function";

  /** Detaching a function from an organization. */
  public static final String DETACH_FUNCTION = "detach_function";

  /** Listing clients. */
  public static final String LIST_CLIENTS = "list_clients";

  /** Reading a single client. */
  public static final String READ_CLIENT = "read_client";

  /** Creating a client. */
  public static final String CREATE_CLIENT = "create_client";

  /** Changing a client. */
  public static final String UPDATE_CLIENT = "update_client";

  /** Deleting a client. */
  public static final String DELETE_CLIENT = "delete_client";

  /** Reading which clients lack artifacts. */
  public static final String READ_CLIENT_DRIFT = "read_client_drift";

  /** Reconciling a single client. */
  public static final String RECONCILE_CLIENT = "reconcile_client";

  /** Reconciling every client. */
  public static final String RECONCILE_CLIENTS = "reconcile_clients";

  /** Producing a full export. */
  public static final String EXPORT_DATA = "export_data";

  /** Validating an import file. */
  public static final String IMPORT_DRY_RUN = "import_dry_run";

  /** Confirming an import. */
  public static final String CONFIRM_IMPORT = "confirm_import";

  /** Reading the progress of an import. */
  public static final String READ_IMPORT_JOB = "read_import_job";

  /** Discarding a pending import batch. */
  public static final String CANCEL_IMPORT = "cancel_import";

  /** Listing organizations through the IAM service API. */
  public static final String LIST_IAM_ORGANIZATIONS = "list_iam_organizations";

  /** Listing the rights holders of a function through the IAM service API. */
  public static final String LIST_RIGHTS_HOLDERS = "list_rights_holders";

  /** Any other request to the IAM service API. */
  public static final String IAM_API_REQUEST = "iam_api_request";

  // access_denied reasons

  /** The caller has no admin session. */
  public static final String NO_ADMIN_SESSION = "no_admin_session";

  /** The operation is reserved to superusers. */
  public static final String NOT_SUPERUSER = "not_superuser";

  /** The caller lacks the admin right the operation requires. */
  public static final String INSUFFICIENT_RIGHTS = "insufficient_rights";

  /** The addressed user is outside the caller's scope. */
  public static final String OUT_OF_SCOPE = "out_of_scope";

  /** The caller addressed themselves. */
  public static final String SELF_MODIFICATION = "self_modification";

  /** The caller may not grant, remove or downgrade the admin right. */
  public static final String ADMIN_ASSIGNMENT_RESTRICTED = "admin_assignment_restricted";

  /** Organization-wide rights are turned off. Also an {@code operation_rejected} reason. */
  public static final String ORG_RIGHTS_DISABLED = "org_rights_disabled";

  /** Function removal is turned off. */
  public static final String FUNCTION_REMOVAL_DISABLED = "function_removal_disabled";

  /** Only a superuser may change an organization's names. */
  public static final String NAME_CHANGE_NOT_ALLOWED = "name_change_not_allowed";

  // operation_rejected reasons

  /** The operation would leave an organization or function without an administrator. */
  public static final String LAST_ADMIN = "last_admin";

  /** The client holds a service account. */
  public static final String SERVICE_ACCOUNT_CLIENT = "service_account_client";

  /** The organization still has functions attached. */
  public static final String ORGANIZATION_HAS_FUNCTIONS = "organization_has_functions";

  private AuditOperations() {
  }

}
