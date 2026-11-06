![Logo](../docs/images/sweden-connect.png)

# Deploying HSA-ID, EFOS-ID and the Primary Identifier Scope

![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)

What a deployment of this version changes, and what has to be done when upgrading an existing
installation. See [#143](https://github.com/swedenconnect/organizations-iam-app/issues/143).

-----

## What changes

- **HSA-ID and EFOS-ID.** A user can be registered with an HSA-ID (SITHS) and an EFOS-ID (EFOS),
  in addition to the personal identity number and the organizational affiliation. They are
  stored in the Keycloak user attributes `hsaId` and `efosId`. Any value that is not blank and
  holds no quotation mark (`"`) is accepted. Like the other two, each value is unique, and it cannot be changed after the user
  has been created. See
  [User Registration Settings](../docs/iam-admin-configuration.md#user-registration-settings).

- **A new client scope**, `https://id.swedenconnect.se/scope/primary-identifier`. A client that
  requests it gets the user's primary identifiers, whichever the user has, as these claims in
  the ID token, the access token and UserInfo:

  | Claim | Content |
  |---|---|
  | `https://id.oidc.se/claim/personalIdentityNumber` | Personal identity number |
  | `https://id.oidc.se/claim/orgAffiliation` | Organizational affiliation |
  | `http://sambi.se/attributes/1/employeeHsaId` | HSA-ID |
  | `https://id.swedenconnect.se/claim/efos-id` | EFOS-ID |

  See [Identifying the User](../docs/iam-integration-guide.md#identifying-the-user) and
  [The Primary Identifier Scope](../docs/keycloak-setup.md#the-primary-identifier-scope).

- **The new scope is a base optional scope.** Every client holding the OIDC client role is given
  it, as it already is given `naturalPersonNumber`, `naturalPersonOrgId` and `phone`.
  Reconciliation now adds any base optional scope a client lacks. See
  [Base optional scopes](../docs/keycloak-setup.md#managed-clients-and-reconciliation).

- **Export and import.** A user entry has two new keys, `hsa_id` and `efos_id`. A user entry
  needs at least one of the four identifiers, and duplicates are detected on all four. The
  schema version stays `1.0`, and files exported by earlier versions import as before. See
  [Export and Import](../docs/export-import.md#users).

- **Service API.** The entries returned by
  `GET /iam-api/v1/organizations/{orgIdentifier}/functions/{functionId}/users` have three new
  members, `org_affiliation`, `hsa_id` and `efos_id`, each `null` when the user lacks it. See
  [List Rights Holders](../docs/iam-admin-app-apis.md#list-rights-holders).

- **Audit log.** `user_created`, also when produced by an import, and `user_deleted`
  carry `hsa_id` and `efos_id` when the user has them. See [Audit Events](../docs/audit-events.md).

## Steps

Do these in order, after the new provider JARs and the new admin application are deployed.

1. **Run `bootstrap-realm.sh` against the realm**, with the same arguments as when the realm was
   set up. It creates the new scope and its mappers, and declares the `hsaId` and `efosId` user
   attributes. It changes nothing that is already there. Until it has run, HSA-ID and EFOS-ID
   values entered in the admin application are not stored, since Keycloak drops attributes the
   realm does not declare.

2. **Run reconciliation**, as a superuser with **Reconcile All** under the **Services** tab, or
   with `POST /api/clients/reconcile`. This gives every existing client holding the OIDC client
   role the new scope as an optional scope. Scheduled reconciliation is off by default, so do
   not count on it having run.

Until both steps are done, existing clients do not hold the new scope. A client that requests it
before then has the request refused with the OAuth2 error `invalid_scope`, and the user cannot
log in through that client. Clients that do not request the scope are not affected.

The full procedure is described in
[Upgrading an Existing Installation](../docs/keycloak-setup.md#upgrading-an-existing-installation).

## Visible as soon as the new version runs

`iam.admin.user-registration.hsa-id-enabled` and `iam.admin.user-registration.efos-id-enabled`
used to show fields that could not be edited. They now show fields that can. A deployment with
either setting on sees this change at once. The default values do not change: both settings are
`false`.

## What each deployment decides

- Whether to turn on `hsa-id-enabled` and `efos-id-enabled`. Do it only after step 1.
- Which clients request the new scope. The scope is optional, so a client receives nothing
  through it until it asks for it.
- Whether the consumers of the Service API may receive the additional identifiers, see below.

## Consequences to know about

- **A base scope removed by hand comes back.** Reconciliation now adds every base optional scope
  a client holding the OIDC client role lacks. A base scope someone removed from such a client in
  the Keycloak Admin Console is added again on the next reconciliation run.
- **More personal data in the Service API.** The rights holder entries now carry the
  organizational affiliation, the HSA-ID and the EFOS-ID, in addition to the personal identity
  number. Every caller allowed to list rights holders receives them.

## Checking the upgrade

- In the Keycloak Admin Console, the realm has the client scope
  `https://id.swedenconnect.se/scope/primary-identifier` with four mappers, and **Realm
  settings → User profile** lists `hsaId` and `efosId`.
- After reconciliation, the **Services** tab reports no client as needing reconciliation, and
  each client holding the OIDC client role lists the new scope among its optional client scopes.
- A user created with an HSA-ID shows that HSA-ID when the user is opened in the admin
  application. Creating another user with the same HSA-ID is refused, and the existing user is
  offered instead.
- A client requesting the new scope logs a user in and receives the claims for the identifiers
  that user has, each under its exact name from the table above.

-----

Copyright &copy; 2025-2026, [Sweden Connect](https://swedenconnect.se). Licensed under version 2.0 of the [Apache License](http://www.apache.org/licenses/LICENSE-2.0).
