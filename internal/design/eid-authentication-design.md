# Delegated Authentication Design

Status: draft. Input for continued design work, not a specification.

## 1. Problem

Today the realm authenticates users with username and password. The admin app does not set
credentials, so passwords are assigned manually through the Keycloak admin console. This was always
a temporary arrangement.

We want:

1. Usernames to stay opaque UUIDs. No personal identifier in `preferred_username`.
2. Authentication delegated to an external SAML IdP or OIDC OP.
3. Selected attributes and claims from the IdP response mapped onto Keycloak user attributes,
   primarily `personalIdentityNumber`.

Users remain pre-provisioned by the admin app. Rights live in the Keycloak group tree and are
assigned before the user ever logs in. Nobody may self-provision by authenticating.

Two user populations, authenticating differently:

| Population | Authenticates via | Matched on |
|---|---|---|
| Regular users | eID IdP (SAML proxy now, OIDC OP later) | `personalIdentityNumber` |
| Superadmins | Corporate IdP | Attribute set by the admin app (email, UPN or employee id) |

The eID IdP is a corporate proxy fronting the Sweden Connect IdPs. Registration is bilateral. The
same organisation will operate the OIDC OP, and the SAML to OIDC transition is under our control.

### The core difficulty

Keycloak's built in first broker login matches an incoming external identity to an existing local
account on **username or email only**. With UUID usernames and no email, nothing ever matches. The
stock "Detect Existing Broker User" plus "Automatically Set Existing User" recipe does not work
here.

Matching has to happen on a user attribute, and Keycloak has no built in way to do that.

## 2. Rejected: identity provider links pre-created by the admin app

The obvious alternative is for the admin app to write the federated identity link itself when it
provisions a user:

```
POST /admin/realms/orgiam/users/{id}/federated-identity/{alias}
{ "identityProvider": "...", "userId": "<pnr>", "userName": "<pnr>" }
```

Keycloak then resolves the user directly on login and first broker login never runs. No custom code.
Rejected for four reasons.

**It requires the external identifier to be predictable at provisioning time.** For SAML this can be
forced by setting `Principal type` to `ATTRIBUTE` and pointing `Principal attribute` at the personal
number. For OIDC there is no equivalent: the `OpenID Connect v1.0` provider always uses `sub`, which
is pairwise and unknowable in advance. The generic `OAuth 2.0 v1.0` provider does have a
configurable ID claim, but it reads claims from the user info endpoint and abandons ID token
validation. Given the planned move to OIDC, this alone disqualifies the approach.

**It does not survive the SAML to OIDC migration.** Links are keyed on `(alias, external user id)`.
Moving to a new provider invalidates every link, and they cannot be re-issued because the new
identifiers are not predictable. Every user would have to be re-provisioned.

**Links cannot drive provider selection.** A link is only consulted after an IdP has responded,
because the response carries the external id. At the login page there is no identity to look up, so
a per-user link cannot answer "which IdP does this person use". Routing has to be solved separately
regardless.

**It puts the personal identity number in `FEDERATED_IDENTITY.federated_user_id`.** Avoidable, so
avoid it.

## 3. Design overview

Delegate authentication to external identity providers, and resolve the incoming identity to a
pre-provisioned local user by matching on a user attribute. Nothing is created or linked
automatically.

Four parts:

**Identity providers.** One per external IdP, registered at realm level. The eID provider is SAML
today and becomes OIDC later; both can be registered in parallel during the transition. A second
provider covers superadmins.

**Attribute mapping is configuration, not code.** Stock Keycloak IdP mappers (`Attribute Importer`
for SAML, the claim mapper for OIDC) translate assertion attributes and claims into user attributes.
Mapping is per provider, editable in the admin console, no redeploy.

**Matching is one small custom authenticator.** It reads a configured attribute from the brokered
identity context, looks up the local user by that attribute, and hands off to the stock
`Automatically Set Existing User`. No match means login fails. This is the only new code.

**Provider selection is an entry point property.** The admin app exposes a separate admin login
route that passes `kc_idp_hint` for the corporate provider. Everything else falls through to the eID
provider as the realm default.

Two consequences worth stating early.

Because matching is on an attribute rather than a stored external identifier, the SAML to OIDC
cutover is self-healing. Adding the OIDC provider under a new alias means existing users have no link
for it, first broker login runs, the authenticator matches on `personalIdentityNumber`, and Keycloak
writes the new link. No migration.

Because the authenticator is protocol agnostic and takes its mapping from stock mappers, the same
component serves the eID provider and the corporate provider, before and after the cutover.

## 4. Design

### 4.1 The matching authenticator

A new Keycloak provider JAR, deployed like the existing `org-rights-mapper`.

Rather than one monolithic component, mirror the built in pair:

1. **Detect Existing Broker User By Attribute** (custom, REQUIRED). Reads the configured attribute
   from `BrokeredIdentityContext`, looks up the local user, and on a unique hit sets the
   `EXISTING_USER_INFO` auth note.
2. **Automatically Set Existing User** (`idp-auto-link`, stock, REQUIRED). Reads the note and sets
   the user in the authentication context.

Keycloak writes the federated identity link itself as part of normal post first broker login
handling. The custom surface is one lookup.

Configuration properties:

| Property | Purpose |
|---|---|
| Match attribute | Which user attribute to match on. `personalIdentityNumber` for the eID provider, something else for the corporate one. |
| Required role | Optional. The matched user must hold this realm role. |
| Forbidden role | Optional. The matched user must not hold this realm role. |

The role properties make the population separation explicit rather than emergent. Configure the
corporate provider to require `superuser` and the eID provider to forbid it. Without them the only
thing distinguishing the two providers is which attribute they match on, and the failure mode if
that ever collides is a regular user landing in a superadmin session.

Role assignment stays where it is today: granted by the admin app in Keycloak, never asserted by the
IdP.

Fail closed on all of: attribute absent from the assertion or token, more than one local user
matching, matched user disabled, attribute present but malformed.

### 4.2 Protocol independence

`BrokeredIdentityContext` is protocol neutral. Both the SAML and OIDC providers populate it, and an
authenticator in the first broker login flow does not care which produced it.

The wrinkle is that raw attributes are not normalised. SAML values sit in the assertion, OIDC values
in the claims JSON. Reading raw values would require a per protocol adapter, which is exactly the
code that would have to be rewritten at cutover.

Letting stock IdP mappers normalise first avoids this. They run before the first broker login flow
and write into the context via `setUserAttribute`. The authenticator then reads one normalised
attribute and contains no protocol specific code at all.

Sync mode is the lever for ownership. Default `import` mode populates the context without writing
back to an existing user, so the admin app stays the source of truth for `personalIdentityNumber`.
Use `force` only for attributes the IdP should own.

**To verify before implementation.** That stock IdP mappers populate the context in
`preprocessFederatedIdentity` rather than only in `importNewUser` and `updateBrokeredUser`, and that
`import` sync mode leaves an existing user's attributes untouched. The whole "mapping declarative,
matching in code" split depends on this. If it does not hold, mapping config moves into the
authenticator, which is possible via `MULTIVALUED_STRING_TYPE` config properties but uglier.

### 4.3 Flows

One first broker login flow per provider, both built on the pair above with different authenticator
config.

On both flows, DISABLED: `Create User If Unique`, `Confirm Link Existing Account`, `Verify Existing
Account By Email`, `Verify Existing Account By Re-authentication`. The last is the one that would
otherwise fall back to prompting for username and password.

Note that first broker login only runs when no link exists. For an already linked user it never
executes, so the flow is purely the gate for unknown identities.

Browser flow: remove `Username Password Form`, leaving the Identity Provider Redirector. With both
populations on external IdPs there is no reason for any credential to exist in the realm.

### 4.4 Provider selection

The eID provider is the **Default Identity Provider** on the Identity Provider Redirector. The
corporate provider is marked **Hide on login page** and reached only from the admin app's separate
admin login route, which passes `kc_idp_hint=<corporate-alias>`.

Regular users never see a second button or need to understand the distinction.

`kc_idp_hint` is selection, not authorization. Anyone can set the parameter and reach the corporate
IdP's login page. That is harmless: they cannot authenticate there, and if they somehow could, the
role guard blocks the match. The security property lives in the flow.

The hint also only has effect when authentication actually happens. A user holding an existing SSO
session who hits the admin route gets that session reused and the hint silently ignored. Force
`prompt=login` on the admin route. `PromptLoginAuthorizationRequestResolver` in the starter already
does this.

Note that this gives freshness, not proof of which IdP was used. If anything ever depends on the
authentication method, read it from the token. Keycloak stores the broker alias as a user session
note, surfaceable as a claim with a stock **User Session Note** protocol mapper. Verify the note key
before relying on it.

Open: whether the admin login route is a separate client or a second route on the existing client.
Two clients gives cleaner separation and independent scoping later. One client is less realm
configuration.

### 4.5 Bootstrap

The first superadmin has to exist before anyone can log in to create users. `create-admin-user.sh`
creates it today and sets a password via `reset-password`. That step goes away. The script sets the
`superuser` role and the match attribute the corporate IdP will assert, and nothing else. The account
is then reachable only through the corporate IdP, like every other superadmin.

## 5. Metadata and registration

### 5.1 SAML

Keycloak acts as SP. Once the identity provider is created and saved, SP metadata is served at:

```
https://{host}/realms/orgiam/broker/{alias}/endpoint/descriptor
```

Not to be confused with `/realms/orgiam/protocol/saml/descriptor`, which is Keycloak's own IdP
metadata for its SAML clients.

Chicken and egg: the descriptor is generated from the saved provider config, so create the provider
with placeholder IdP values first, fetch the descriptor, register, then fill in the real IdP side.

| Field | Value |
|---|---|
| SP entityID | Defaults to `https://{host}/realms/orgiam`. Set explicitly via **Service provider entity ID**. |
| ACS and SLO | `https://{host}/realms/orgiam/broker/{alias}/endpoint` |
| Certificates | From **Realm settings, Keys** |

Registration is bilateral with the proxy operator rather than against a federation registry, so the
generated descriptor is expected to be sufficient as it stands.

One thing to watch: the default `rsa-generated` key provider rotates, which silently invalidates
registered metadata. Add an explicit `rsa` key provider backed by a keystore we control. Also check
upstream issue #13606, where the descriptor emitted realm signing keys under both the `signing` and
`encryption` `KeyDescriptor` elements instead of using the encryption key.

### 5.2 OIDC

There is no RP metadata document. Keycloak as OIDC broker is an ordinary confidential client and is
registered manually with:

- Redirect URI: `https://{host}/realms/orgiam/broker/{alias}/endpoint`
- Client ID as agreed with the OP
- Client authentication: `client_secret_*` or `private_key_jwt`

With `private_key_jwt` the OP validates against the realm JWKS at
`https://{host}/realms/orgiam/protocol/openid-connect/certs`. Note this is the realm JWKS, shared
with everything else the realm signs. Different posture from our existing per client
`private_key_jwt` credentials, and worth a decision rather than a default.

Discovery works the other way only: Keycloak imports the OP's `.well-known/openid-configuration`.

### 5.3 Both

The alias is baked into the ACS URL and redirect URI. Pick it once, never change it.

The public hostname must be correct. Descriptor and redirect URIs are built from Keycloak's
configured hostname. If `KC_HOSTNAME` is not the externally reachable value, we register metadata
containing internal URLs.

## 6. Risks

`AbstractIdpAuthenticator`, `BrokeredIdentityContext` and the auth note constants live in
`keycloak-services`, which is not a stable public API. Higher fragility than `org-rights-mapper`,
which sits on `OIDCProtocolMapper`. Pin the Keycloak version and write integration tests against a
Keycloak testcontainer with a mock IdP, so upgrades break CI rather than production.

Requested authentication context and level of assurance passthrough is a separate question, not
covered here, and should be settled early for an eID IdP.

## 7. To verify

- Stock IdP mapper behaviour in `preprocessFederatedIdentity`, and `import` sync mode against an
  existing user (section 4.2). Blocks the mapping design.
- Whether an `Attribute Importer` can write `personalIdentityNumber` at all, given the user profile
  attribute is declared "Enabled when: Scopes are requested, naturalPersonNumber" with admin only
  edit permission. The broker flow is not an admin context and no client scope is applied. May
  silently drop. Only matters if we decide the IdP should own the attribute.
- The user session note key holding the broker alias (section 4.4).
- Whether `Deny Access` is selectable in a first broker login flow, if we want an unconditional deny
  variant.
- Keycloak 26.5.7 release notes for anything added around attribute based linking since this was
  written.

## 8. Open decisions

- Separate client or second route for admin login (section 4.4).
- Whether `iam.admin.pnr-userids` survives. It makes local development diverge from production on
  exactly the field the design depends on.
- Whether the corporate IdP is SAML or OIDC, and which attribute superadmins are matched on.
