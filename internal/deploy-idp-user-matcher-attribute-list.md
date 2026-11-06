![Logo](../docs/images/sweden-connect.png)

# Deploying the IdP User Matcher with Several Attributes

![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)

What a deployment of this version changes for the eID login matcher
(`idp-detect-existing-user-by-attr`), and what has to be done when upgrading.

-----

## What changes

- **The matcher tries a list of attributes, in order.** Its setting `matchAttribute` is now a list.
  An attribute that is absent from the login, or that no user has, moves on to the next one. The
  first attribute that finds a user decides. One identity provider can then serve people who log in
  with different eIDs and are identified by different attributes, such as `personalIdentityNumber`
  and `efosId`. A configuration with a single attribute is read as a list of one and keeps working
  unchanged. See [The match list](../keycloak/idp-user-matcher/README.md#the-match-list).

- **Roles are set per attribute.** Each entry in the list may carry a required and a forbidden
  role:

  ```
  personalIdentityNumber;forbiddenRole=superuser
  efosId;requiredRole=superuser
  ```

  See [Roles per attribute](../keycloak/idp-user-matcher/README.md#roles-per-attribute).

- **The matcher settings `requiredRole` and `forbiddenRole` are removed.** A matcher whose
  configuration still has either of them **refuses every login** through its identity provider,
  even if the value is empty, until the configuration is rewritten. The server log names the cause
  at `ERROR`. See
  [The removed role settings](../keycloak/idp-user-matcher/README.md#removed-role-settings).

- **`add-identity-provider.sh` has a new option, `--match`**, which replaces `--match-attribute`,
  `--saml-attribute`, `--saml-attribute-format`, `--required-role` and `--forbidden-role`. Each
  `--match` gives one attribute, the SAML attribute carrying it, its format, and optional roles.
  It may be repeated, and the order of the options is the order the attributes are tried. Giving a
  removed option is an error. Without `--match` the script matches on `personalIdentityNumber` from
  `urn:oid:1.2.752.29.4.13`, with no roles, as before. See
  [add-identity-provider.sh](../keycloak/scripts/README.md#add-identity-provider).

## Steps

1. **Deploy the new `idp-user-matcher` provider JAR**, together with the other provider JARs of
   this version, and rebuild and restart Keycloak.

2. **Rewrite every matcher configuration that uses `requiredRole` or `forbiddenRole`.** Logins
   through those identity providers are refused from the moment the new JAR runs until this is done,
   so do it right after step 1, or prepare the new configuration first and apply both in one
   maintenance window. Either:

    - re-run `add-identity-provider.sh` for the provider with one `--match` per attribute, carrying
      the roles. The script writes the list and removes the old settings, or
    - in the Admin Console, open the first broker login flow, the gear icon on **Detect Existing
      Broker User By Attribute**, move each role into the entry it applies to, and remove the old
      settings. Before and after examples are in
      [The removed role settings](../keycloak/idp-user-matcher/README.md#removed-role-settings).

   A configuration without roles needs no change.

3. **Add any new attributes** an identity provider should match on: an Attribute Importer mapper on
   the provider for each one, and an entry in the matcher's list. `add-identity-provider.sh` does
   both for every `--match`.

## What each deployment decides

- Which attributes each identity provider matches on, and in which order.
- Which roles go on which attribute. An attribute without roles lets superusers log in with it too.
  A superuser who has a value stored for an attribute that forbids `superuser` cannot log in with
  that attribute.

## Consequences to know about

- **The role checks run at the first login only.** After the first login, Keycloak links the
  external identity to the user, and later logins through the same provider go straight to that
  user without running the matcher. A user who is given or loses `superuser` after the first login
  keeps logging in as before. To make the role checks apply again, remove the link under
  **Users** → *user* → **Identity provider links**. See
  [Links and the role check](../keycloak/idp-user-matcher/README.md#links-and-the-role-check).
- **A login can be refused for contradicting identifiers.** If a later attribute finds a user who
  has a different stored value for an earlier attribute present in the same login, the login is
  refused.
- **Mappers are not removed.** Re-running `add-identity-provider.sh` with fewer attributes leaves the
  mappers for the others in place and reports them. Delete them by hand if they are not wanted.

## Checking the upgrade

- In the Admin Console, the configuration of **Detect Existing Broker User By Attribute** in each
  first broker login flow shows only **Match attributes**, with the expected entries in the expected
  order, and no required or forbidden role field.
- The Keycloak server log has no line containing `the matcher configuration is invalid`.
- A user with no link yet logs in through each listed attribute and reaches the right account.
- A user who should be refused by a role on an attribute, for example a superuser logging in with
  an attribute that forbids `superuser`, gets the generic login error, and the server log says which
  attribute and role refused it.

-----

Copyright &copy; 2025-2026, [Sweden Connect](https://swedenconnect.se). Licensed under version 2.0 of the [Apache License](http://www.apache.org/licenses/LICENSE-2.0).
