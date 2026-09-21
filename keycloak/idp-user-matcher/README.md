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
(`Attribute Importer`, for both SAML and OIDC) place it before the first broker login
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

Disable the remaining stock executions in the flow: `Review Profile`, `Create User If Unique`,
`Confirm Link Existing Account`, `Verify Existing Account By Email` and `Verify Existing Account By
Re-authentication`. The last would otherwise fall back to prompting for a username and password.
`Review Profile` would show an *Update Account Information* form whenever the identity provider does
not release `email`, `firstName` and `lastName`, which is the normal case here. Users must never be
asked to fill in profile data themselves.

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
context. The authenticator has nothing to match on otherwise. Both SAML and OIDC providers use a
mapper named **Attribute Importer** (SAML: `saml-user-attribute-idp-mapper`, OIDC:
`oidc-user-attribute-idp-mapper`). Leave the mapper's sync mode at `import` so that the admin
application stays the source of truth for the attribute on existing users.

### Example: OIDC provider releasing the Swedish eID personal identity number

The OIDC provider releases the personal identity number in the ID token as the claim
`https://id.oidc.se/claim/personalIdentityNumber`, provided the scope
`https://id.oidc.se/scope/naturalPersonNumber` is requested. The value is 12 digits without a hyphen,
for example `190001011234`.

Under **Identity providers** → *provider* → **Mappers** → **Add mapper**:

| Field | Value |
| :--- | :--- |
| Name | `personalIdentityNumber` |
| Sync mode override | `import` |
| Mapper type | `Attribute Importer` |
| Claim | `https://id\.oidc\.se/claim/personalIdentityNumber` |
| User Attribute Name | `personalIdentityNumber` |

Then set **Match attribute** to `personalIdentityNumber` on the execution of this plugin.

Three details decide whether this works:

- **Escape every dot in the claim name.** Keycloak reads an unescaped `.` as a step into a nested
  object, so `https://id.oidc.se/claim/personalIdentityNumber` is searched for as `https`,
  `//id`, `oidc`, and so on, and is never found. Write each dot as `\.`. The slashes need no escaping.
- **The claim must not end with a space (or start with one).** Keycloak does not trim the value
  before looking it up, so `https://id\.oidc\.se/claim/personalIdentityNumber␣` (trailing space)
  searches for a claim that does not exist. This is easy to introduce by copy and paste, and nothing
  in the admin console shows it. Retype the field by hand if the attribute is missing. The same
  applies to **User Attribute Name**.
- **The names are case sensitive and must agree.** *User Attribute Name* in the mapper, **Match
  attribute** on the execution and the attribute stored on the pre-provisioned user must all be
  `personalIdentityNumber`, and the stored value must have the same format as the claim (here 12
  digits, no hyphen).

### Realm user profile

Pre-provisioned users have an opaque UUID username and no e-mail address, first name or last name.
Since Keycloak 24 the realm has a declarative user profile in which `email`, `firstName` and
`lastName` are required by default. A user who lacks a required attribute is sent to the required
action **Verify Profile** right after a successful login, no matter how the login was made. The user
then sees an *Update Account Information* form and cannot continue without filling it in. The URL of
that page contains `execution=VERIFY_PROFILE`.

This happens even when the first broker login flow is correct: the plugin has matched and linked the
user, and the profile check is a separate step that follows. To prevent it:

1. Go to **Realm settings** → **User profile**.
2. Open `email`, `firstName` and `lastName` one at a time and turn off **Required field**. Check
   *Required for* as well, since a field can be required for the `user` role, the `admin` role, or both.
3. Make sure `personalIdentityNumber` is defined in the user profile, or that **Unmanaged attributes**
   is enabled for the realm. Otherwise Keycloak may refuse to store or may drop the attribute, and
   the match stops working.

As a last resort, **Authentication** → **Required actions** → **Verify Profile** can be switched off,
but that disables the profile check for the whole realm.

### Troubleshooting

Enable debug logging for the plugin and the Keycloak broker code:

```
se.swedenconnect.iam.keycloak:DEBUG
org.keycloak.broker:DEBUG
```

| Log output | Meaning |
| :--- | :--- |
| `BrokerAtt:personalIdentityNumber: [...]` | The mapper works. If the login is still rejected, look at the matching against the local user. |
| `No user attributes found for broker`, or no `BrokerAtt:personalIdentityNumber` line | The mapper did not find the claim. Check dots, spaces and spelling as described above. |
| `Going to process JsonNode path <claim>  on data null` | Keycloak searched the access token and the ID token without finding the claim and then fell back to the user info response, which was empty. Two spaces before `on` means the configured claim ends with a space. |
| `rejected: attribute 'personalIdentityNumber' is absent ...` | The plugin received nothing from the mapper. Same causes as above. |
| `rejected: no local user has a matching 'personalIdentityNumber' attribute` | The claim arrived, but no user stores that exact value. Compare formats. |
| An *Update Account Information* form is shown after login, URL contains `/login-actions/first-broker-login` | The `Review Profile` execution is still active in the first broker login flow. Disable it. |
| An *Update Account Information* form is shown after login, URL contains `execution=VERIFY_PROFILE` | The realm user profile requires attributes the user lacks. See *Realm user profile* above. |

## A note on stability

`AbstractIdpAuthenticator`, `SerializedBrokeredIdentityContext` and `ExistingUserInfo` live in
`keycloak-services`, which is **not** a stable public API — higher upgrade risk than the protocol
mappers in this repository, which sit on `OIDCProtocolMapper`. The Keycloak version is pinned in
`keycloak/pom.xml`; re-check this authenticator against the Keycloak sources on every upgrade.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
