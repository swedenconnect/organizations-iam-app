![Sweden Connect](images/sweden-connect.png)

# Audit Events of the IAM Admin Application

---

This page documents the audit events produced by the IAM admin application. The events are built with
[spring-audit-support](https://docs.swedenconnect.se/spring-audit-support/), and the library's own events,
`system_started`, `system_shutdown` and `system_alert`, are documented on its
[Audit Events](https://docs.swedenconnect.se/spring-audit-support/audit-events.html) page. How audit logging is
configured is described in [Audit Logging](iam-admin-configuration.md#audit-logging).

The audit log covers what is done through the IAM admin application, that is, its GUI, its REST API and its
import. Changes made directly in Keycloak, through the Keycloak admin console or through the scripts under
`keycloak/scripts`, do not pass through the application and do not appear in this log.

## Table of Contents

1. [Common structure](#common-structure)
2. [Shared objects](#shared-objects)
3. [Events](#events)
4. [Operation names and reasons](#operation-names-and-reasons)
5. [What is not audited](#not-audited)

<a name="common-structure"></a>
## Common structure

All events carry the
[common audit event structure](https://docs.swedenconnect.se/spring-audit-support/usage.html#the-audit-event):

- `type` - The audit event type, see below.
- `timestamp` - The instant when the event occurred.
- `application` - The application that produced the event, holding its `name` (`spring.application.name`,
  `iam-admin` by default) and its `version`. The version is only present when it has been configured.
- `correlation_id` - Ties together the events of one session or one background run, see below.
- `trace_id` - The trace ID supplied by the library. Absent when the library supplies none.
- `principal` - Who performed the operation, see below.
- `data` - The event-specific content, described per event below.

Fields and members without a value are omitted from the serialized event. An identifier that is the only value of
its kind, such as an organization identifier, is placed directly under `data` rather than in an object of its own.

### The principal

| Situation | `principal` |
| :--- | :--- |
| A request made by a logged-in user | The Keycloak subject (`sub`) of the user. |
| A rejected login (`login_rejected`) | The subject of the user who was rejected. |
| A failed login (`login_failed`) | The subject if it was known when the login failed, otherwise absent. Never `system`. |
| Events from an import | The user who confirmed the import, although the import runs on a thread of its own. |
| A request to the IAM service API (`/iam-api/v1`) | The name of the token's authentication, which is the token subject. |
| A scheduled reconciliation, and the library's lifecycle events and system alerts | `system` |

### The correlation ID

The correlation ID ties together everything done within one login session, or within one background run.

| Situation | `correlation_id` |
| :--- | :--- |
| A standard login | A new UUID, assigned when the login starts so that rejected and failed logins have one too. Kept for the session. |
| A login through the SSO endpoint with a valid `correlation-id` query parameter | The supplied value, kept for the session. See the [IAM Integration Guide](iam-integration-guide.md#the-sso-login-entry-point). |
| A login through the SSO endpoint without a `correlation-id`, or with an invalid one | A new UUID, kept for the session. |
| Any login starting in a session that already has a correlation ID | The existing value. A supplied `correlation-id` is ignored. |
| Later requests in the session, including the logout | The session's value. |
| An import | The value of the session in which the import was confirmed. |
| A scheduled reconciliation | A new UUID for each run. |
| A request to the IAM service API (`/iam-api/v1`) | Absent. |

<a name="shared-objects"></a>
## Shared objects

The objects below appear in several events. Each event lists which objects it carries, and which of their
members.

<a name="user-object"></a>
### `user`

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `user_id` | The Keycloak user ID. Always present. | String |
| `display_name` | The user's name, first name and last name. Present whenever it is available, that is, whenever the user could be read and has a name. On the login events and `logout` it is taken from the ID token: the `name` claim, or the `given_name` and `family_name` claims. | String |
| `personal_identity_number` | The Swedish personal identity number. Only on the events stating it. | String |
| `org_affiliation` | The organizational affiliation, `userID@organization-number`. Only on the events stating it. | String |
| `hsa_id` | The HSA-ID. Only on the events stating it. | String |
| `efos_id` | The EFOS-ID. Only on the events stating it. | String |
| `username` | The Keycloak username chosen when the user was created. Absent when Keycloak assigned a random one. Only on `user_created`. | String |
| `temporary_password_set` | Whether a temporary password was set. The password itself is never written. Only on `user_created`. | Boolean |

<a name="client-object"></a>
### `client`

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `client_id` | The OAuth2 client_id. Always present. | String |
| `name` | The display name. Absent if the client has none. | String |
| `oidc_client` | Whether the client plays the OIDC client role. | Boolean |
| `resource_server` | Whether the client plays the resource server role. | Boolean |
| `functions` | The functions the client handles. | List of strings |
| `redirect_uris` | The redirect URIs. | List of strings |
| `jwks_uri` | The URI of the client's JWK Set. Absent if the keys are given inline. | String |
| `jwks_string` | The client's JWK Set given inline. Absent if a URI is used. | String |
| `org_rights_id_token` | Whether the `org_rights` claim is issued in the ID token. | Boolean |
| `org_rights_access_token` | Whether the `org_rights` claim is issued in the access token. | Boolean |

Only `client_created` carries all members. Every other event carries `client_id` only.

<a name="removed-rights"></a>
### `removed_rights`

A list of the rights that disappeared with a deletion, read before the deletion was made. This includes rights
that disappear because the group holding them is removed. Absent if no rights were held.

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `user_id` | The Keycloak user ID of the user holding the right. Left out on `user_deleted`, where `user` already says who. | String |
| `display_name` | The name of that user, whenever available. Left out on `user_deleted`. | String |
| `organization_identifier` | The organization. | String |
| `function_id` | The function. Absent for an organization-wide right. | String |
| `right` | `read`, `write` or `admin`. | String |

<a name="change-object"></a>
### `change`

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `changed_fields` | The names of the fields that changed. The possible names are listed per event. | List of strings |
| `old` | The previous values of the changed fields, by field name. Only on `client_updated`. A field that had no value is absent. | Object |
| `new` | The new values of the changed fields, by field name. Only on `client_updated`. A field that no longer has a value is absent. | Object |

A blank value counts as no value, so changing a field from empty to blank is no change. A save that changes nothing
produces no event.

<a name="login-object"></a>
### `login`

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `method` | `standard` for a login through the ordinary login page, `sso` for a login through the SSO endpoint. | String |
| `superuser` | Whether the user is a superuser. Absent on `login_failed`, where it is not known. | Boolean |
| `organization_constraint` | The organization given by the `org` parameter of an SSO login. Absent if none was given. | String |
| `function_constraint` | The function given by the `func` parameter of an SSO login. Absent if none was given. | String |
| `reason` | Why the login was rejected: `no_org_rights` or `insufficient_admin_rights`. Only on `login_rejected`. | String |

<a name="import-object"></a>
### `import`

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `batch_id` | The ID of the batch produced by the dry-run. Always present. | String |
| `job_id` | The ID of the job running the import. Absent on `import_cancelled` and on a refused request to the import endpoints. | String |
| `function_count` | The number of functions the import plans to handle. Only on `import_started`. | Integer |
| `organization_count` | The number of organizations the import plans to handle. Only on `import_started`. | Integer |
| `user_count` | The number of users the import plans to handle. Only on `import_started`. | Integer |
| `status` | `completed` if every entry was handled, `failed` otherwise. Only on `import_completed`. | String |
| `created_count` | The number of entries created. Only on `import_completed`. | Integer |
| `updated_count` | The number of existing organizations that were given functions. Only on `import_completed`. | Integer |
| `partial_count` | The number of organizations created or updated without all of their functions. Only on `import_completed`. | Integer |
| `skipped_count` | The number of entries skipped because they already existed. Only on `import_completed`. | Integer |
| `error_count` | The number of entries that failed. Only on `import_completed`. | Integer |
| `message` | Why the import failed. Only on `import_completed` with `status` `failed`. | String |

The counts on `import_completed` cover the entries handled before the import ended.

<a name="export-object"></a>
### `export`

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `function_count` | The number of functions exported. | Integer |
| `organization_count` | The number of organizations exported. | Integer |
| `user_count` | The number of users exported. Superusers are never exported. | Integer |

<a name="reconciliation-object"></a>
### `reconciliation`

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `trigger` | `manual` for a run started from the application, `scheduled` for a run started by the schedule. | String |
| `client_count` | The number of clients reconciled. | Integer |
| `created` | The number of artifacts created. | Integer |
| `removed` | The number of artifacts removed. | Integer |
| `errors` | The error messages of the run. Absent if there were none. | List of strings |

<a name="operation-object"></a>
### `operation`

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `name` | The operation. See [Operation names and reasons](#operation-names-and-reasons). | String |
| `reason` | Why the operation was refused. Present on `access_denied` and `operation_rejected`, absent on `operation_failed`. | String |

<a name="error-object"></a>
### `error`

The library's error object, described under
[Error events](https://docs.swedenconnect.se/spring-audit-support/audit-events.html#error-events).
`error.code` takes these values:

| Value | Used when |
| :--- | :--- |
| `keycloak_error` | Keycloak reported an error or could not be reached. |
| `internal_error` | Any other failure stopped an operation. |
| `authentication_failed` | A login failed without an OAuth2 error code. |
| An OAuth2 error code | A login failed with an OAuth2 error, for example `invalid_token_response`. |

<a name="target"></a>
### The target

`access_denied`, `operation_rejected` and `operation_failed` describe what the operation was aimed at, using the
same members as the other events. Each member is present when the operation concerns it:

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `organization_identifier` | The organization. | String |
| `function_id` | The function. | String |
| `right` | The right level asked for. | String |
| `user` | The [user](#user-object), with `user_id` and `display_name`. | Object |
| `client` | The [client](#client-object), with `client_id`. A request refused before the client was looked up names the client by its Keycloak UUID only, so the client is then not identified. | Object |
| `import` | The [import](#import-object), with `batch_id`, and `job_id` when the operation is part of a running import. | Object |

<a name="events"></a>
## Events

The events are listed in the order they typically occur.

<a name="login_succeeded"></a>
### Login Succeeded

**Type:** `login_succeeded`

**Description:** Created when a login has completed and the user has been let into the application. Written
before the user's session data is set up. If the session data cannot be set up, the login still succeeds and a
`system_alert` follows. If this event cannot be written, the login is stopped and the session is discarded.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `user` | The [user](#user-object), with `user_id` and `display_name`. | Object |
| `login` | The [login](#login-object), without `reason`. | Object |

<a name="login_rejected"></a>
### Login Rejected

**Type:** `login_rejected`

**Description:** Created when a user has authenticated in Keycloak, but lacks the rights to use the application.
The user is not let in. The reason is `no_org_rights` when the user's `org_rights` claim is absent or empty, and
`insufficient_admin_rights` when the user holds no admin right within the constraints of the login. A rejected
login produces only this event, not `login_failed` as well.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `user` | The rejected [user](#user-object), with `user_id` and `display_name`. | Object |
| `login` | The [login](#login-object), with `reason`. | Object |

<a name="login_failed"></a>
### Login Failed

**Type:** `login_failed`

**Description:** Created when a login fails for any reason other than a lack of rights, for example an error
response from Keycloak or a token that cannot be validated. The principal is the subject if it was known when the
login failed, and is otherwise absent.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `user` | The [user](#user-object), with `user_id` and `display_name`. Absent if the login failed before the user was known. | Object |
| `login` | The [login](#login-object), without `superuser` and `reason`. | Object |
| `error` | The [error](#error-object). | Object |

<a name="organization_created"></a>
### Organization Created

**Type:** `organization_created`

**Description:** Created when an organization has been created. Also produced by an import, for each organization
it creates.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `organization_identifier` | The organization identifier. | String |
| `organization_legal_name` | The legal name. | String |
| `import` | The [import](#import-object), with `batch_id` and `job_id`. Only when created by an import. | Object |

<a name="organization_updated"></a>
### Organization Updated

**Type:** `organization_updated`

**Description:** Created when an organization's names or contact details have been changed. Not produced when
the save changed nothing.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `organization_identifier` | The organization identifier. | String |
| `change` | The [change](#change-object). `changed_fields` holds one or more of `legal_name`, `name_sv`, `name_en`, `contact_email` and `contact_phone`. | Object |

<a name="function_created"></a>
### Function Created

**Type:** `function_created`

**Description:** Created when a function has been created. Also produced by an import, for each function it
creates. If the function could not then be added to the clients handling all functions, a `system_alert` follows.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `function_id` | The function identifier. | String |
| `import` | The [import](#import-object), with `batch_id` and `job_id`. Only when created by an import. | Object |

<a name="function_updated"></a>
### Function Updated

**Type:** `function_updated`

**Description:** Created when a function's names or descriptions have been changed. Not produced when the save
changed nothing.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `function_id` | The function identifier. | String |
| `change` | The [change](#change-object). `changed_fields` holds one or more of `name_sv`, `name_en`, `description_sv` and `description_en`. | Object |

<a name="function_attached"></a>
### Function Attached

**Type:** `function_attached`

**Description:** Created when a function has been attached to an organization, with all its Keycloak artifacts.
The reconciliation of the clients that follows is part of the attachment and produces no `clients_reconciled`.
An attachment that fails part-way produces `operation_failed` instead. Also produced by an import, for each
function it attaches.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `organization_identifier` | The organization identifier. | String |
| `function_id` | The function identifier. | String |
| `import` | The [import](#import-object), with `batch_id` and `job_id`. Only when attached by an import. | Object |

<a name="user_created"></a>
### User Created

**Type:** `user_created`

**Description:** Created when a user has been created. Also produced by an import, for each user it creates.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `user` | The [user](#user-object), with every member that has a value. | Object |
| `import` | The [import](#import-object), with `batch_id` and `job_id`. Only when created by an import. | Object |

<a name="user_updated"></a>
### User Updated

**Type:** `user_updated`

**Description:** Created when a user's profile has been changed. Not produced when the save changed nothing.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `user` | The [user](#user-object), with `user_id` and `display_name`. The name is the new one. | Object |
| `change` | The [change](#change-object). `changed_fields` holds one or more of `name`, `email` and `phone_number`. | Object |

<a name="user_right_assigned"></a>
### User Right Assigned

**Type:** `user_right_assigned`

**Description:** Created when a user has been granted a right, or the right at a level has been changed, for
example from `read` to `write`. Not produced when the user already held the right. Also produced by an import,
for each right it grants.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `user` | The [user](#user-object), with `user_id` and `display_name`. | Object |
| `organization_identifier` | The organization. | String |
| `function_id` | The function. Absent for an organization-wide right. | String |
| `right` | The right granted: `read`, `write` or `admin`. | String |
| `previous_right` | The right the user held at the same level before. Absent if none. | String |
| `import` | The [import](#import-object), with `batch_id` and `job_id`. Only when granted by an import. | Object |

<a name="user_right_removed"></a>
### User Right Removed

**Type:** `user_right_removed`

**Description:** Created when a right has been removed from a user. Not produced when the user did not hold the
right.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `user` | The [user](#user-object), with `user_id` and `display_name`. | Object |
| `organization_identifier` | The organization. | String |
| `function_id` | The function. Absent for an organization-wide right. | String |
| `right` | The right removed. | String |

<a name="client_created"></a>
### Client Created

**Type:** `client_created`

**Description:** Created when a client has been registered. The reconciliation that follows is part of the
creation and produces no `clients_reconciled`. If the reconciliation fails, the client still exists, so this
event is written and is followed by `operation_failed`.

**Audit data:** [`client`](#client-object), with all members that have a value.

<a name="client_updated"></a>
### Client Updated

**Type:** `client_updated`

**Description:** Created when a client's settings have been changed. Not produced when the save changed nothing.
The reconciliation that follows produces no `clients_reconciled`. If it fails, the client has still changed, so
this event is written and is followed by `operation_failed`.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `client` | The [client](#client-object), with `client_id`. | Object |
| `change` | The [change](#change-object), with `old` and `new`. `changed_fields` holds one or more of the client members other than `client_id`. | Object |

<a name="clients_reconciled"></a>
### Clients Reconciled

**Type:** `clients_reconciled`

**Description:** Created when a reconciliation of the managed clients has run. Every run started from the
application produces it. A scheduled run produces it only when it created or removed artifacts, or had errors. A
scheduled run that fails altogether produces a `system_alert` instead.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `reconciliation` | The [reconciliation](#reconciliation-object). | Object |
| `client` | The [client](#client-object), with `client_id`. Only on a run for a single client. | Object |

<a name="data_exported"></a>
### Data Exported

**Type:** `data_exported`

**Description:** Created when a full export has been produced. The file itself is not recorded.

**Audit data:** [`export`](#export-object).

<a name="import_started"></a>
### Import Started

**Type:** `import_started`

**Description:** Created when an import has been confirmed and its job registered, before any entry has been
handled. The dry-run before it is not audited. If this event cannot be written, the import is not started.

**Audit data:** [`import`](#import-object), with `batch_id`, `job_id` and the planned counts.

<a name="import_completed"></a>
### Import Completed

**Type:** `import_completed`

**Description:** Created when an import job has ended, however it ended. An import that could not be started, or
that stopped part-way, is reported with `status` `failed`. A failure to write any audit event of the import stops
the import, which then ends with `status` `failed`, and the entry being handled is reported in the import's report
as what was done, for example `created`. If this event cannot be written either, the import's report shows the
failure.

The changes made by the import produce their own events, carrying `import`: `function_created`,
`organization_created`, `function_attached`, `user_created` and `user_right_assigned`. An entry skipped because
it already exists produces no event. A failed entry or right produces `operation_failed`, and an
organization-wide right skipped because organization-wide rights are turned off produces `operation_rejected`
with the reason `org_rights_disabled`.

**Audit data:** [`import`](#import-object), with `batch_id`, `job_id`, `status`, the outcome counts, and
`message` if the import failed.

<a name="import_cancelled"></a>
### Import Cancelled

**Type:** `import_cancelled`

**Description:** Created when a pending batch has been discarded without anything being imported.

**Audit data:** [`import`](#import-object), with `batch_id`.

<a name="access_denied"></a>
### Access Denied

**Type:** `access_denied`

**Description:** Created when an authorization check refuses a request. This includes the reads that are not
otherwise audited. A user who exists but is outside the caller's scope is answered exactly as an unknown user, with
a 404, and the refusal is recorded here with the reason `out_of_scope`. The possible reasons are listed under
[Operation names and reasons](#operation-names-and-reasons).

**Audit data:** [`operation`](#operation-object), with `name` and `reason`, and [the target](#target).

<a name="operation_rejected"></a>
### Operation Rejected

**Type:** `operation_rejected`

**Description:** Created when a safety rule refuses an operation that the caller is otherwise allowed to perform,
for example removing the last administrator of an organization. Nothing has been changed. The possible reasons
are listed under [Operation names and reasons](#operation-names-and-reasons).

**Audit data:** [`operation`](#operation-object), with `name` and `reason`, and [the target](#target).

<a name="operation_failed"></a>
### Operation Failed

**Type:** `operation_failed`

**Description:** Created when an error stops a change, typically an error from Keycloak. Keycloak performs no
rollback, so a change may have been made in part. Where a change was completed before the failure, as when the
reconciliation after creating or changing a client fails, the event for that change is written first. In an
import, a failed entry or right produces this event and the import goes on.

**Audit data:** [`operation`](#operation-object), with `name`, [the target](#target), and
[`error`](#error-object).

<a name="function_detached"></a>
### Function Detached

**Type:** `function_detached`

**Description:** Created when a function has been detached from an organization and its Keycloak artifacts have
been removed. The rights held on the function in that organization disappear with it.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `organization_identifier` | The organization identifier. | String |
| `function_id` | The function identifier. | String |
| `removed_rights` | The [rights](#removed-rights) held on the function in the organization. | List of objects |

<a name="function_deleted"></a>
### Function Deleted

**Type:** `function_deleted`

**Description:** Created when a function has been deleted, together with its attachments to every organization.
Only possible when `iam.admin.allow-function-removal` is set.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `function_id` | The function identifier. | String |
| `removed_rights` | The [rights](#removed-rights) held on the function in every organization. | List of objects |

<a name="organization_deleted"></a>
### Organization Deleted

**Type:** `organization_deleted`

**Description:** Created when an organization has been deleted. An organization with functions attached is not
deleted, and produces `operation_rejected` instead.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `organization_identifier` | The organization identifier. | String |
| `organization_legal_name` | The legal name. | String |
| `removed_rights` | The [rights](#removed-rights) held in the organization. | List of objects |

<a name="client_deleted"></a>
### Client Deleted

**Type:** `client_deleted`

**Description:** Created when a client has been deleted. A client holding a service account is not deleted, and
produces `operation_rejected` instead.

**Audit data:** [`client`](#client-object), with `client_id`.

<a name="user_deleted"></a>
### User Deleted

**Type:** `user_deleted`

**Description:** Created when a user has been deleted. A user who is the last administrator of an organization or
function is not deleted, and produces `operation_rejected` instead.

**Audit data:**

| Parameter | Description | Type |
| :--- | :--- | :--- |
| `user` | The [user](#user-object), with `user_id`, `display_name`, `personal_identity_number`, `org_affiliation`, `hsa_id` and `efos_id`. | Object |
| `removed_rights` | The [rights](#removed-rights) the user held, without the user members. | List of objects |

<a name="logout"></a>
### Logout

**Type:** `logout`

**Description:** Created when a user has logged out. The event carries the session's correlation ID, although the
session has ended.

**Audit data:** [`user`](#user-object), with `user_id` and `display_name`.

### Events from the library

The library's `system_started` and `system_shutdown` events are produced when the application starts and stops.
The application uses the library's `system_alert` event in three cases:

- A scheduled client reconciliation failed altogether.
- The session data could not be set up after a login.
- A new function could not be added to the clients handling all functions.

See the library's [Audit Events](https://docs.swedenconnect.se/spring-audit-support/audit-events.html) page.

<a name="operation-names-and-reasons"></a>
## Operation names and reasons

### `operation.name`

| Value | Operation |
| :--- | :--- |
| `list_users` | Listing users |
| `read_user` | Reading a single user |
| `create_user` | Creating a user |
| `update_user` | Changing a user's profile |
| `delete_user` | Deleting a user |
| `assign_user_right` | Granting or changing a right |
| `remove_user_right` | Removing a right |
| `list_organizations` | Listing organizations |
| `read_organization` | Reading a single organization |
| `create_organization` | Creating an organization |
| `update_organization` | Changing an organization |
| `delete_organization` | Deleting an organization |
| `list_function_organizations` | Listing the organizations a function is attached to |
| `create_function` | Creating a function |
| `update_function` | Changing a function |
| `delete_function` | Deleting a function |
| `attach_function` | Attaching a function to an organization |
| `detach_function` | Detaching a function from an organization |
| `list_clients` | Listing clients |
| `read_client` | Reading a single client |
| `create_client` | Creating a client, including the reconciliation that follows |
| `update_client` | Changing a client, including the reconciliation that follows |
| `delete_client` | Deleting a client |
| `read_client_drift` | Reading which clients lack artifacts |
| `reconcile_client` | Reconciling a single client |
| `reconcile_clients` | Reconciling every client |
| `export_data` | Producing a full export |
| `import_dry_run` | Validating an import file |
| `confirm_import` | Confirming an import |
| `read_import_job` | Reading the progress of an import |
| `cancel_import` | Discarding a pending import batch |
| `list_iam_organizations` | Listing organizations through the IAM service API |
| `list_rights_holders` | Listing the rights holders of a function through the IAM service API |
| `iam_api_request` | Any other request to the IAM service API |

### `operation.reason` on `access_denied`

| Value | Meaning |
| :--- | :--- |
| `no_admin_session` | The caller has no admin session, for example because the session data could not be set up at login. |
| `not_superuser` | The operation is reserved to superusers. |
| `insufficient_rights` | The caller lacks the admin right the operation requires in the organization or function. |
| `out_of_scope` | The addressed user holds no right the caller may see. The response is a 404. |
| `self_modification` | The caller addressed themselves, which is not allowed for this operation. |
| `admin_assignment_restricted` | The caller may not grant, remove or downgrade the `admin` right, see `iam.admin.allow-admin-assigning-admin`. |
| `org_rights_disabled` | Organization-wide rights are turned off, see `iam.admin.allow-org-rights`. |
| `function_removal_disabled` | Function removal is turned off, see `iam.admin.allow-function-removal`. |
| `name_change_not_allowed` | Only a superuser may change an organization's names. |

### `operation.reason` on `operation_rejected`

| Value | Meaning |
| :--- | :--- |
| `last_admin` | The operation would leave an organization or function without an administrator. |
| `service_account_client` | The client holds a service account and cannot be deleted from the application. |
| `organization_has_functions` | The organization still has functions attached. |
| `org_rights_disabled` | An import skipped an organization-wide right because such rights are turned off. |

<a name="not-audited"></a>
## What is not audited

- Reads, other than the export. This includes the user list, single-user reads and the IAM service API. A refused
  read is still recorded as `access_denied`.
- Requests rejected as invalid (400), ordinary not-found responses (404) and conflicts (409) other than the safety
  rules listed under `operation_rejected`.
- The import dry-run.
- An import entry rejected because its username is taken or not allowed. It is reported in the import's report.
- A save that changes nothing.
- Changes made directly in Keycloak or through the scripts.
- The security events that Spring Boot creates by itself: `AUTHENTICATION_SUCCESS`, `AUTHENTICATION_FAILURE`,
  `AUTHENTICATION_SWITCH`, `AUTHORIZATION_FAILURE` and `LOGOUT_SUCCESS`. They are filtered out by default, since
  the events above cover what they report. See
  [Audit Logging](iam-admin-configuration.md#audit-logging) in the configuration documentation.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
