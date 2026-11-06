![Sweden Connect](../../docs/images/sweden-connect.png)

# idp-user-matcher

A Keycloak 26.x first broker login authenticator that resolves an incoming brokered identity to an
existing, pre-provisioned local user by trying a configured list of **user attributes**, in order.

## What it does

Keycloak's stock `Detect Existing Broker User` matches an external identity to a local account on
**username or email only**. Users in this realm are pre-provisioned by the admin application with an
opaque UUID username and no email address, so nothing ever matches and the stock recipe cannot be
used. This authenticator matches on user attributes instead, for example `personalIdentityNumber`
and `efosId`. One identity provider, such as a proxy in front of several eIDs, can then serve people
who are identified by different attributes.

Provider id: `idp-detect-existing-user-by-attr`
Display name: **Detect Existing Broker User By Attribute**

Note that the provider id abbreviates the last word while the display name spells it out.
`idp-detect-existing-user-by-attr` is the exact value the Admin REST API expects as
`provider`, and `idp-detect-existing-user-by-attribute` matches nothing.

On each first broker login it:

1. Reads the configured list of attributes, see [The match list](#the-match-list).
2. Takes the attributes in order, reads each one's value from the `BrokeredIdentityContext`, and
   looks up the local users carrying that value, until an attribute finds a user.
3. If that attribute finds exactly one user that passes every check, writes the match to the
   `EXISTING_USER_INFO` auth note, carrying the attribute that matched, in the same form Keycloak's
   own `IdpCreateUserIfUniqueAuthenticator` uses.
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

<a name="the-match-list"></a>
## The match list

The authenticator has one setting, `matchAttribute`. It is a list, edited in the Admin Console as
one field per entry. Keycloak stores it as one string with the entries joined by `##`, so a
configuration written when the setting held a single attribute name is read as a list of one and
keeps working unchanged.

Each entry is a user attribute name, optionally followed by roles:

```
personalIdentityNumber;forbiddenRole=superuser
efosId;requiredRole=superuser
```

| Part | Meaning |
| :--- | :--- |
| The attribute name | The user attribute to match on. Required, and first in the entry. |
| `requiredRole=<role>` | Optional. A user found through this attribute must hold this realm role. |
| `forbiddenRole=<role>` | Optional. A user found through this attribute must not hold this realm role. |

An entry may have one `requiredRole`, one `forbiddenRole`, both or neither, in either order. The
parts are separated by `;` and a role by `=`, with no spaces around either separator. Roles are
**realm** roles, resolved by name.

An empty entry, or one holding only spaces, is ignored. This is what a row left empty in the Admin
Console gives. A list with only empty entries counts as having no entries.

### The order, and when the next attribute is tried

The attributes are tried in the order of the list.

- An attribute that is **absent or empty** in the brokered identity context moves on to the next
  attribute.
- An attribute whose value **no local user has** moves on to the next attribute.
- The **first attribute that finds a user decides** the outcome. No later attribute is tried,
  whether the login is accepted or refused.
- If **no attribute finds a user**, the login is refused.

### When a login is refused

The attribute that found a user refuses the login when:

- it finds **more than one** user
- the user is **disabled**
- the user fails that attribute's **role check**: lacks its `requiredRole` or holds its
  `forbiddenRole`
- the user **contradicts an earlier attribute**: an attribute earlier in the list had a value in the
  brokered identity context but found nobody, and the user has a different value stored for it

The last rule stops one identifier from reaching an account that another identifier in the same
login says belongs to someone else. A user with **no** stored value for the earlier attribute is
accepted. Take the list `personalIdentityNumber`, `efosId`, and a login carrying both
`personalIdentityNumber=197001011234` and `efosId=E123`. No user has that personal identity number,
so `efosId` is tried and finds a user. If that user has the personal identity number
`198001011234` stored, the login is refused. If the user has none stored, the login is accepted.

A refusal by the deciding attribute is final. A user refused by the role check on
`personalIdentityNumber` cannot log in through `efosId` in the same login, even if `efosId` would
have found the same user without that restriction.

## How a mapper and a list entry work together

The authenticator reads values from the brokered identity context and nothing else. It never touches
the SAML assertion or the OIDC claims. Each identity provider **mapper** is what copies a value from
the assertion or the token into that context, under a user attribute name. The list entry names the
same user attribute. **Every attribute in the list needs a mapper**, or it is always absent and the
next attribute is tried.

For example, an upstream that releases the EFOS-ID in the SAML attribute
`urn:oid:1.2.3.4.5.6.7` (a made-up name):

| Mapper field | Value |
| :--- | :--- |
| Mapper type | `Attribute Importer` (`saml-user-attribute-idp-mapper`) |
| Attribute Name | `urn:oid:1.2.3.4.5.6.7` |
| Name Format | `ATTRIBUTE_FORMAT_URI` |
| User Attribute Name | `efosId` |

and the list entry

```
efosId;requiredRole=superuser
```

The mapper puts the value under `efosId`. The entry looks for local users with that `efosId`.

A mapper whose attribute is not in the list still copies its value into the context, where the
matcher ignores it.

## Roles per attribute

With a proxy identity provider there is one provider, one first broker login flow and therefore one
matcher for everybody, regular users and superusers alike. A single role pair for the whole matcher
could only forbid or require `superuser` for everybody who logs in. Setting roles per attribute keeps
the two groups apart within one matcher:

```
personalIdentityNumber;forbiddenRole=superuser
efosId;requiredRole=superuser
```

Regular users log in with an eID that releases a personal identity number. Superusers log in with
EFOS. A consequence: a superuser who also has a `personalIdentityNumber` stored **cannot log in with
it**. That attribute finds them first, the forbidden role refuses the login, and `efosId` is never
tried. Such a superuser logs in with EFOS, through a login that carries no personal identity number.

An attribute with **no roles** lets anybody it finds log in, superusers included. Nothing stops a
superuser from logging in with that attribute.

A configured role that does not exist in the realm is a misconfiguration, never an absent
restriction, and refuses every login. A typo cannot silently disable the separation.

<a name="links-and-the-role-check"></a>
## Links and the role check

The matcher only runs at **first broker login**, that is, when Keycloak has no link yet between the
identity provider and the external user ID in the login. After a successful first login, Keycloak
stores that link on the user. Every later login through the same provider with the same external
user ID goes straight to the linked user. Neither the list nor the role checks run again.

This has been observed against Keycloak 26.8.1: a regular user who was given the `superuser` role
after the first login kept logging in through the attribute with `forbiddenRole=superuser`, and a
superuser whose role was removed after the first login kept logging in through the attribute with
`requiredRole=superuser`.

**A link lets a person past the role check.** The roles keep the two groups apart at the first login
only. When a user's roles change, or a user should no longer be able to log in through a provider,
remove the link under **Users** → *user* → **Identity provider links**, so that the next login runs
the matcher again. Disabling the user stops every login, including logins through a link.

<a name="removed-role-settings"></a>
## The removed role settings

Earlier versions had two matcher-level settings, `requiredRole` and `forbiddenRole`, applied to every
user the matcher found. They are gone. A configuration that still carries either of them refuses
**every** login, and logs why at `ERROR`, until it is rewritten. Silently ignoring them would drop a
role restriction without anyone noticing. The Admin Console no longer shows them, and the REST API
returns their value masked as `**********`.

To rewrite a configuration, move the roles onto the entries they should apply to, and delete the old
settings. Before:

```json
{
  "matchAttribute": "personalIdentityNumber",
  "forbiddenRole": "superuser"
}
```

After:

```json
{
  "matchAttribute": "personalIdentityNumber;forbiddenRole=superuser"
}
```

With several attributes, the roles that used to apply to everybody now go on each entry that needs
them, for example `personalIdentityNumber;forbiddenRole=superuser##efosId;requiredRole=superuser` as
stored. Re-running `add-identity-provider.sh` writes the configuration from its `--match` options and
removes the old settings.

## Failure cases

The flow fails closed on all of:

- the execution has no configuration, or `matchAttribute` is missing or has no entries other than
  empty ones
- an entry is malformed: no attribute name, an unknown key, a key given twice, an empty role, or
  spaces in the attribute name or around a separator. The error gives the entry's position in the
  list, counting empty entries, so it matches the row in the Admin Console.
- the same attribute appears in more than one entry
- a configured role does not exist in the realm
- the removed `requiredRole` or `forbiddenRole` setting is present, even with an empty value
- no attribute finds a local user, including when none of them is present in the brokered context
- the deciding attribute finds more than one user, a disabled user, a user failing its role check,
  or a user contradicting an earlier attribute

The configuration errors refuse every login, whatever the login carries.

The message shown to the user is **generic and identical in every case**. It never reveals whether an
account exists and never echoes an attribute value. The server log says which attribute and which
check decided the outcome, without any attribute value: at `INFO` for a refused login, at `ERROR`
for a configuration error. The event error is uniform for the same reason, so the admin event stream
cannot be used to probe for accounts either.

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
executes, see [Links and the role check](#links-and-the-role-check).

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
5. Open the gear icon on step 3, give the config an alias, and add one **Match attributes** entry
   per attribute, in the order they should be tried, with any roles in the entry itself.
6. Bind the flow to the identity provider: **Identity providers** → *provider* → **Advanced
   settings** → **First login flow override**.

Repeat with a separate flow and configuration for each identity provider.

Make sure the identity provider has a mapper that writes each listed attribute into the brokered
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

Then add `personalIdentityNumber` as an entry under **Match attributes** on the execution of this
plugin.

Three details decide whether this works:

- **Escape every dot in the claim name.** Keycloak reads an unescaped `.` as a step into a nested
  object, so `https://id.oidc.se/claim/personalIdentityNumber` is searched for as `https`,
  `//id`, `oidc`, and so on, and is never found. Write each dot as `\.`. The slashes need no escaping.
- **The claim must not end with a space (or start with one).** Keycloak does not trim the value
  before looking it up, so `https://id\.oidc\.se/claim/personalIdentityNumber␣` (trailing space)
  searches for a claim that does not exist. This is easy to introduce by copy and paste, and nothing
  in the admin console shows it. Retype the field by hand if the attribute is missing. The same
  applies to **User Attribute Name**.
- **The names are case sensitive and must agree.** *User Attribute Name* in the mapper, the entry
  under **Match attributes** on the execution and the attribute stored on the pre-provisioned user must all be
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
| `Attribute 'personalIdentityNumber' is absent from the brokered identity context or empty, trying the next attribute` (`DEBUG`) | The plugin received nothing from the mapper. Same causes as above. |
| `No local user has a matching 'personalIdentityNumber' attribute, trying the next attribute` (`DEBUG`) | The claim arrived, but no user stores that exact value. Compare formats. |
| `rejected: none of the attributes [...] found a local user` | No attribute in the list found a user. The two lines above say why for each attribute. |
| `the matcher configuration is invalid: ...` (`ERROR`) | Every login is refused until the configuration is corrected. The message names the problem. |
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
