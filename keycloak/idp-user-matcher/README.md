![Sweden Connect](../../docs/images/sweden-connect.png)

# idp-user-matcher

A Keycloak 26.x first broker login authenticator that resolves an incoming brokered identity to an
existing, pre-provisioned local user by matching on a configured **user attribute**.

## What it does

Keycloak's stock `Detect Existing Broker User` matches an external identity to a local account on
**username or email only**. Users in this realm are pre-provisioned by the admin application with an
opaque UUID username and no email address, so nothing ever matches and the stock recipe cannot be
used. This authenticator matches on a user attribute instead — typically `personalIdentityNumber`.

Provider id: `idp-detect-existing-user-by-attribute`
Display name: **Detect Existing Broker User By Attribute**

On each first broker login it:

1. Reads the configured attribute from the `BrokeredIdentityContext`.
2. Looks up local users carrying that attribute value.
3. On exactly one match that passes every check, writes the match to the `EXISTING_USER_INFO` auth
   note — in the same form Keycloak's own `IdpCreateUserIfUniqueAuthenticator` uses.
4. Fails the flow in every other case.

It never sets the user itself, never creates users, and never writes federated identity links. The
stock `Automatically Set Existing User` execution sets the user, and Keycloak writes the link as part
of normal post first broker login handling.

## Protocol independence

The attribute value is read from the `BrokeredIdentityContext`, where the **stock IdP mappers**
(`Attribute Importer` for SAML, the claim mapper for OIDC) place it before the first broker login
flow runs. The authenticator never touches the raw SAML assertion or the OIDC claims JSON and
contains no protocol specific code, so the same component serves a SAML provider and an OIDC provider
without change.

The authenticator is stateless and entirely configuration driven. One deployed instance serves
several identity providers, each with its own execution configuration.

## Configuration properties

| Key | Type | Required | Purpose |
| :--- | :--- | :--- | :--- |
| `matchAttribute` | String | yes | The user attribute to match on, for example `personalIdentityNumber`. |
| `requiredRole` | String | no | The matched user must hold this realm role. |
| `forbiddenRole` | String | no | The matched user must not hold this realm role. |

Both roles are **realm** roles, resolved by name. A configured role name that does not exist in the
realm fails the flow — a missing role is treated as a misconfiguration, never as an absent
constraint, so a typo cannot silently disable the guard.

The role properties are what keep the two user populations apart: configure the corporate provider to
require `superuser` and the eID provider to forbid it. Without them the only thing separating the
providers is which attribute they match on.

## Failure cases

The flow fails closed on all of:

- the configured attribute is absent from the brokered context, or empty
- no `matchAttribute` is configured on the execution
- no local user matches
- more than one local user matches
- the matched user is disabled
- `requiredRole` is configured and the matched user does not hold it
- `forbiddenRole` is configured and the matched user holds it
- either configured role name does not exist in the realm

The message shown to the user is **generic and identical in every case**. It never reveals whether an
account exists and never echoes the matched attribute value. The specific reason — including which
check failed — is logged at the server at `INFO`, without the attribute value. The event error is
uniform for the same reason, so the admin event stream cannot be used to probe for accounts either.

## Where it sits in the flow

Bind a first broker login flow per identity provider, containing:

| # | Execution | Requirement |
| :--- | :--- | :--- |
| 1 | **Detect Existing Broker User By Attribute** (this plugin) | REQUIRED |
| 2 | **Automatically Set Existing User** (`idp-auto-link`, stock) | REQUIRED |

Order matters: this authenticator only records the match, and execution 2 is what actually sets the
user in the authentication context. Without it the flow completes with no user.

`REQUIRED` is the only requirement choice offered. The execution is the gate for unknown identities,
so `ALTERNATIVE` or `DISABLED` would defeat its purpose.

Disable the remaining stock executions in the flow: `Create User If Unique`, `Confirm Link Existing
Account`, `Verify Existing Account By Email` and `Verify Existing Account By Re-authentication`. The
last would otherwise fall back to prompting for a username and password.

Note that first broker login only runs when no link exists yet. For an already linked user it never
executes.

## Build

```bash
mvn -U -DskipTests clean package
```

(Run from the repository root or from `keycloak/idp-user-matcher/`.)

## Install into Keycloak 26.x

```bash
cp target/idp-user-matcher-<version>.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start --optimized
```

## Configure in the Admin Console

1. Navigate to **Authentication** and copy the built-in `first broker login` flow, or create a new
   one.
2. Delete or disable the executions listed under *Where it sits in the flow* above.
3. **Add step** → **Detect Existing Broker User By Attribute**, and set it to `REQUIRED`.
4. **Add step** → **Automatically set existing user**, set it to `REQUIRED`, and order it directly
   after step 3.
5. Open the gear icon on step 3, give the config an alias, and set **Match attribute** — plus
   **Required role** or **Forbidden role** if the provider needs them.
6. Bind the flow to the identity provider: **Identity providers** → *provider* → **Advanced
   settings** → **First login flow override**.

Repeat with a separate flow and configuration for each identity provider.

Make sure the identity provider has a mapper that writes the match attribute into the brokered
context — an **Attribute Importer** for SAML, or a **Claim to User Attribute** mapper for OIDC. The
authenticator has nothing to match on otherwise. Leave the mapper's sync mode at `import` so that the
admin application stays the source of truth for the attribute on existing users.

## A note on stability

`AbstractIdpAuthenticator`, `SerializedBrokeredIdentityContext` and `ExistingUserInfo` live in
`keycloak-services`, which is **not** a stable public API — higher upgrade risk than the protocol
mappers in this repository, which sit on `OIDCProtocolMapper`. The Keycloak version is pinned in
`keycloak/pom.xml`; re-check this authenticator against the Keycloak sources on every upgrade.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
