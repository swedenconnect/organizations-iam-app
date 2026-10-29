![Sweden Connect](images/sweden-connect.png)

# Configuring eID Login for a Realm

---

This document describes how to let users authenticate to a realm with a Swedish eID,
while the organizational rights model stays in charge of who exists and what they may do.
It is written for whoever administers a realm: the mechanism is generic, and the endpoints,
entity identifiers and certificates are per deployment.

The short version: an identity provider is registered, an attribute importer carries the
identifying attribute into the brokered context, and a first broker login flow resolves
that attribute to a user the admin application has already provisioned. **Users are
matched, never created.**

## Table of Contents

1. [**Why matching, and not account creation**](#why-matching)
2. [**The four artefacts**](#the-four-artefacts)
3. [**Direct or through a proxy**](#direct-or-proxy)
4. [**Registering the provider**](#registering-the-provider)
5. [**One identity family per flow**](#one-family-per-flow)
6. [**Things that silently do not work**](#things-that-silently-do-not-work)
7. [**Verifying it**](#verifying-it)

---

<a name="why-matching"></a>
## 1. Why matching, and not account creation

In this system a user's rights are group memberships, created by the admin application
(see [the rights model](rights-model.md)). A user who arrives through an identity provider
and gets an account created on the spot would have an account and no rights, which is
indistinguishable from a misconfiguration and impossible to audit.

So the realm is configured to **refuse an eID login that does not resolve to an existing
user**. The admin application provisions the person first, including the attribute the eID
will present; the eID then only proves they are who they claim.

Keycloak's built-in *Detect existing broker user* cannot do this job here. It matches on
username or email, and users in this system are provisioned with an opaque UUID username
and often no email address, so nothing ever matches. The `idp-user-matcher` provider
exists for exactly this gap: it matches on a **user attribute** instead. See
[its README](https://github.com/swedenconnect/organizations-iam-app/tree/main/keycloak/idp-user-matcher)
for the full reference.

---

<a name="the-four-artefacts"></a>
## 2. The four artefacts

A working eID login needs four things in the realm. Missing any one of them produces a
login that fails, usually without saying why.

| # | Artefact | Purpose |
|---|---|---|
| 1 | **Identity provider** | the SAML or OIDC provider itself, pointing at the upstream IdP |
| 2 | **Attribute importer** | a *stock* mapper that writes the identifying attribute into the brokered context |
| 3 | **First broker login flow** | two executions: the attribute matcher, then Keycloak's *automatically set existing user* |
| 4 | **Flow bound to the provider** | the flow set as the provider's first broker login flow |

Artefact 2 is the one most easily missed, and the reason it is needed is worth stating.
The matcher reads the attribute from the `BrokeredIdentityContext`. Something has to put it
there, and that something is a stock `Attribute Importer`
(`saml-user-attribute-idp-mapper`, or `oidc-user-attribute-idp-mapper` for OIDC). A
provider-specific mapper may or may not populate the context depending on how it is
configured; the stock importer always does, and it is independent of whatever else is on
the provider. **Without it, the matcher finds nothing and every login is refused.**

Artefact 3 is exactly two executions, both `REQUIRED`, in this order:

| Order | Execution | Note |
|---|---|---|
| 1 | *Detect Existing Broker User By Attribute* (`idp-detect-existing-user-by-attr`) | records the match; the only requirement choice offered is `REQUIRED` |
| 2 | *Automatically set existing user* (`idp-auto-link`, stock) | sets the user in the authentication context |

The order matters: the matcher records the match but does not set the user. Without the
second execution the flow completes with no user. Note also that `idp-auto-link` is created
`DISABLED` and has to be switched to `REQUIRED` explicitly.

Everything else in a copied *first broker login* flow should be removed or disabled:
*Review Profile*, *Create User If Unique*, *Confirm Link Existing Account*, *Verify
Existing Account by Email* and *Verify Existing Account by Re-authentication*. The last of
those would otherwise fall back to prompting for a username and password, which defeats
the whole arrangement.

### The role guards

The matcher takes an optional `requiredRole` and `forbiddenRole`, both realm roles
resolved by name. They are what keeps two user populations apart on one realm: give the
eID provider `forbiddenRole = superuser` and an eID login can never land on a superuser
account, even if one somehow carried a matching attribute. A configured role name that
does not exist in the realm fails the flow rather than being ignored, so a typo cannot
silently disable the guard.

---

<a name="direct-or-proxy"></a>
## 3. Direct or through a proxy

There are two topologies, and the choice changes how much work the realm has to do.

**Through an identity provider proxy.** The realm brokers to a proxy, and the proxy owns
the relationship with the eID federation: the federation registration, the entity
categories, the SP metadata extensions and the signing keypair the federation trusts. The
realm's provider is then an ordinary SAML broker pointing at the proxy, and needs none of
that. Which eID the user picks is decided upstream, usually by a discovery service the
proxy fronts, so the realm sees **one** identity provider.

**Direct to the federation.** The realm is itself the service provider in the eID
federation. It must then publish SP metadata carrying the federation's required
extensions, hold a keypair the federation trusts, be registered there, and declare one
provider per eID method, so the method choice moves onto the Keycloak login page.

The proxy topology is markedly less work and is the sensible starting point. It is worth
being clear about what it does and does not remove: it removes federation registration
**for the realm**, but the proxy still has its own registration with the federation. If
that has never been exercised in the environment you are working in, the dependency has
not gone away, it has moved one hop up.

A proxy typically needs three things told to it for each realm that brokers to it:

1. the realm's **SP metadata**, so it will talk to it at all
2. an entry in its **metadata provider list** referencing that file
3. the realm's SP entity identifier in the **discovery service's allowed relying parties**,
   so the eID chooser recognises it and can show the right name and cancel URL

The first two are usually a single configuration change; the third is easy to forget
because the symptom is cosmetic or intermittent rather than a hard failure.

---

<a name="registering-the-provider"></a>
## 4. Registering the provider

Use [`add-identity-provider.sh`](https://github.com/swedenconnect/organizations-iam-app/tree/main/keycloak/scripts),
which creates all four artefacts in one idempotent run and refuses to start if the matcher
provider is not deployed. See
[the scripts README](https://github.com/swedenconnect/organizations-iam-app/blob/main/keycloak/scripts/README.md)
for every option.

```bash
./keycloak/scripts/add-identity-provider.sh \
    --url https://keycloak.example.com --base-path /auth \
    --realm myrealm \
    --username admin --password secret \
    --alias eid \
    --display-name "Log in with an eID" \
    --idp-entity-id https://proxy.example.org/sp \
    --sso-url https://proxy.example.org/idp/profile/SAML2/POST/SSO \
    --sp-entity-id https://keycloak.example.com/auth/realms/myrealm \
    --signing-certificate ./proxy-signing.crt \
    --forbidden-role superuser
```

Where an identity provider for the same upstream already exists in another realm, its
upstream parameters can be copied rather than retyped, which also avoids handling the
certificate by hand:

```bash
./keycloak/scripts/add-identity-provider.sh \
    --url https://keycloak.example.com --base-path /auth \
    --realm myrealm \
    --username admin --password secret \
    --template-realm otherrealm --template-alias eid \
    --sp-entity-id https://keycloak.example.com/auth/realms/myrealm \
    --forbidden-role superuser
```

The script sets strict SAML defaults: signature validation on, signed authentication
requests, encrypted assertions, `forceAuthn`, exact authentication context comparison, a
persistent name ID format and `syncMode=IMPORT`. Encrypted assertions require the realm to
hold an **RSA encryption key**; a realm with only a signing key cannot receive them, and
an upstream cannot encrypt to a service provider that publishes no encryption key.

`syncMode=IMPORT` means user data from the assertion is written on first link only, so the
admin application stays the source of truth afterwards.

### Afterwards, by hand

Two steps are deliberately left to a person, because both depend on things outside the
realm:

1. **Register the realm's SP metadata with the upstream.** Fetch it from
   `<url>/realms/<realm>/broker/<alias>/endpoint/descriptor`.
2. **Relax the realm's user profile.** See section 6.

---

<a name="one-family-per-flow"></a>
## 5. One identity family per flow

Different eID methods identify a person with different attributes. A Swedish personal eID
presents a personal identity number; a foreign eID presents an eIDAS person identifier or
a provisional identifier; an organisational eID presents an organisational identity. The
matcher's `matchAttribute` is **one value per execution**.

So offering several eID families is not one provider with one flow. It is, per family, its
own attribute importer and its own first broker login flow with its own `matchAttribute`.
Plan for that from the start, because retrofitting it means re-cutting flows that are
already bound and in use.

Two things can reduce the number of flows:

- Where the upstream can map a foreign identity onto a Swedish personal identity number
  and releases that mapped value, a foreign user with such a number can be matched on the
  same attribute as a domestic one.
- Where the upstream releases which IdP actually authenticated the user, a single
  conditional flow can branch on that instead of one flow per family.

Both depend on what the upstream actually releases, so confirm before designing around
them.

---

<a name="things-that-silently-do-not-work"></a>
## 6. Things that silently do not work

Every item here produces a login that fails without a useful message. They are listed in
the order they tend to bite.

### The attribute name format

Keycloak's attribute importer defaults to `ATTRIBUTE_FORMAT_BASIC`. The Swedish eID
attribute profile uses **URI** format. Left at the default, the attribute is never found,
and because the matcher fails closed the user sees the same generic error as an unknown
account. Set the format explicitly to `ATTRIBUTE_FORMAT_URI` unless you know the upstream
differs. `add-identity-provider.sh` defaults to URI for this reason.

For an OIDC provider the equivalent trap is the claim name. Keycloak reads an unescaped
`.` as a step into a nested object, so every dot in a claim name has to be written `\.`;
and the field must have no leading or trailing whitespace, which is easy to introduce by
copy and paste and invisible in the admin console.

### The metadata host

Keycloak derives the endpoint URLs it publishes **and** the assertion consumer service URL
it puts in the authentication request from the **request host**, not from the configured
entity identifier. Fetch the metadata descriptor through an administrative or internal
hostname and you get metadata naming that host, which the upstream will reject with a
message along the lines of *"Indicated response URL has not been registered"*.

Three ways out, in increasing order of tidiness:

- Declare both hostnames' endpoints in the registered metadata. Works, slightly untidy.
- Give the realm **one** entity identifier per hostname, that is a separate provider for
  each host the realm is reached on. This is the cleanest arrangement where a realm
  genuinely is reached two ways.
- Pin the realm's front end URL so Keycloak always generates one host. Note that this also
  changes the realm's OIDC issuer, so anything configured with the old issuer, including
  the admin application, has to be changed at the same time.

Whichever you choose, fetch the descriptor through the host the upstream will use, and
read the `AssertionConsumerService` locations before registering it.

### The realm user profile

Since Keycloak 24 a realm has a declarative user profile in which `email`, `firstName` and
`lastName` are required by default. Provisioned users typically have none of them, so a
correctly matched user is sent straight to the **Verify Profile** required action and an
*Update Account Information* form. It looks like a broken login and is not.

Turn off **Required field** for those three under **Realm settings → User profile**,
checking *Required for* as well as the flag. Make sure the matching attribute is either
declared in the user profile or that unmanaged attributes are enabled, otherwise Keycloak
may refuse to store it and the match stops working.

### Transient users and matching are mutually exclusive

A provider configured for transient, or lightweight, users does not persist anybody, so
there is nothing to match against and no group memberships to carry rights. Matching
requires persisted users. Some provider implementations also only copy the incoming
attributes onto the brokered context when transient users are enabled, which makes the
combination fail in both directions at once.

### Role names that do not exist

A `requiredRole` or `forbiddenRole` naming a role the realm does not have fails the flow.
That is deliberate, so a typo cannot quietly remove a guard, but it means the roles have
to exist before the provider is registered.

---

<a name="verifying-it"></a>
## 7. Verifying it

The matcher is deliberately uninformative to the user: absent attribute, no match, several
matches, disabled account and a role rejection all produce the same message and the same
event, so that the login page cannot be used to probe for accounts. **The reason is only in
the server log.** Enable debug logging before testing:

```
se.swedenconnect.iam.keycloak: DEBUG
org.keycloak.broker: DEBUG
```

At `DEBUG` the matcher logs every attribute it received from the provider, which is what
settles an attribute name, friendly name or format mismatch in one look. The specific
reason a match was rejected is logged at `INFO`, without the attribute value.

Then, in order:

1. Provision a user whose stored attribute equals exactly what the upstream will assert,
   in the same format. For a personal identity number that is twelve digits, no hyphen.
2. Check the published SP metadata is what the upstream has registered, in particular the
   assertion consumer service host.
3. Log in. A successful first login creates the federated identity link; after that the
   first broker login flow does not run again for that user, so to retest the matching
   path the link has to be removed.

If the provider renders an eID chooser but every method fails, the failure is upstream of
the realm and the realm is working. Read the proxy's or federation's logs rather than
Keycloak's.

---

For the rights model these users plug into, see
[Organization and Function-Based Rights Model](rights-model.md).
For realm configuration generally, see [Keycloak Setup](keycloak-setup.md).

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
