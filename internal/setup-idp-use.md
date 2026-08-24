# Manual Keycloak setup for IdP authentication

Step by step configuration of the realm for external IdP authentication, done through the admin
console. For testing against the SAML IdP in the compose environment.

Design: `internal/design/eid-authentication-design.md`.

Admin console labels are from Keycloak 26.5.

## 1. Install the plugin

The `idp-user-matcher` JAR must be in place before the authenticator can be selected in a flow.

```bash
cd compose/keycloak-scripts
./install-keycloak-plugins.sh
docker compose restart keycloak
```

Verify: **Authentication** → **Flows**, open any flow, **Add execution**. `Detect Existing Broker
User By Attribute` must appear in the list. If it does not, the JAR is missing or `kc.sh build` did
not run.

## 2. Create the identity provider

**Identity providers** → **SAML v2.0**. The add page shows basic fields only. Advanced settings
appear once the provider is saved.

| Field | Value |
| :--- | :--- |
| Alias | `eid` (ends up in the ACS URL and cannot be changed later) |
| Display name | What the login page button says |
| Use entity descriptor | On |
| SAML entity descriptor | `https://local.dev.swedenconnect.se:16910/idp/saml2/metadata` |

The entity descriptor field takes a URL only. Keycloak fetches it server side, so the URL must be
reachable from the Keycloak container, not just from the browser. The compose Keycloak service has
`local.dev.swedenconnect.se` mapped to `host-gateway`, so the published port `16910` works. The
internal address `https://swedish-eid-idp:8443/idp/saml2/metadata` also works and does not depend on
that mapping. If the fetch fails the console only says "Unknown error"; the real cause is in
`docker compose logs keycloak`.

Save. The SAML fields are now filled in from the metadata, including **Service provider entity ID**
and **Identity provider entity ID**, the latter becoming `http://local.dev.swedenconnect.se/idp`.
That value is used to validate the Issuer on incoming assertions.

Check **Single Sign-On service URL**. It must be the external `local.dev.swedenconnect.se:16910`
address, since the browser performs that redirect. If it shows an internal container address, login
will fail.

Set:

| Field | Value |
| :--- | :--- |
| Want Assertions Signed | On |
| Want Assertions Encrypted | On |

Both must be on before the SP descriptor is downloaded in step 3. Keycloak only puts an encryption
`KeyDescriptor` in the descriptor when **Want Assertions Encrypted** is on, and without it the IdP
fails the login with `Missing key descriptor for encryption`.

Under **Requested AuthnContext Constraints**, leave **Comparison** at `Exact` and add both of these
as AuthnContext ClassRefs:

```
http://id.elegnamnden.se/loa/1.0/loa3
http://id.swedenconnect.se/loa/1.0/uncertified-loa3
```

Under **Advanced**:

| Field | Value |
| :--- | :--- |
| Allowed clock skew | `30` |
| Hide on login page | Off |
| Trust email | Off |
| Sync mode | `Import` |

Leave **First login flow override** as it is. It cannot be set empty, and the flow we want does not
exist yet. Step 6 changes it.

## 3. Register as SP at the IdP

The SP metadata is generated from the saved provider configuration and served at:

```
https://{host}/realms/orgiam/broker/eid/endpoint/descriptor
```

It can also be downloaded from **Identity providers** → eID → Settings → Endpoints. 

> Not `/realms/orgiam/protocol/saml/descriptor`. That is Keycloak's own IdP metadata and is not what the counterparty needs.

### 3.1. Register it at the IdP (or rather the federation): 

Save the metadata to an XML-file and place it under `compose/config/md-aggregator/pyff/metadata/keycloak-sp.xml`.

Open the file in an editor, and:

Before the `md:SPSSODescriptor` element, add the following:

```xml
<md:Extensions xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata">
  <mdattr:EntityAttributes xmlns:mdattr="urn:oasis:names:tc:SAML:metadata:attribute">
    <saml2:Attribute xmlns:saml2="urn:oasis:names:tc:SAML:2.0:assertion" 
                     Name="http://macedir.org/entity-category" 
                     NameFormat="urn:oasis:names:tc:SAML:2.0:attrname-format:uri">
      <saml2:AttributeValue xmlns:xsd="http://www.w3.org/2001/XMLSchema" 
                            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                            xsi:type="xsd:string">
        http://id.elegnamnden.se/ec/1.0/loa3-pnr
      </saml2:AttributeValue>
      <saml2:AttributeValue xmlns:xsd="http://www.w3.org/2001/XMLSchema" 
                            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
                            xsi:type="xsd:string">
        http://id.swedenconnect.se/ec/sc/uncertified-loa3-pnr
      </saml2:AttributeValue>
      <saml2:AttributeValue xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                            xsi:type="xsd:string">
        http://id.swedenconnect.se/general-ec/1.0/secure-authenticator-binding
      </saml2:AttributeValue>
      <saml2:AttributeValue xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                            xsi:type="xsd:string">
        http://id.swedenconnect.se/general-ec/1.0/accepts-coordination-number
      </saml2:AttributeValue>
    </saml2:Attribute>
  </mdattr:EntityAttributes>
</md:Extensions>
```

As the first child element to `<md:SPSSODescriptor>`, add:

```xml
<md:Extensions>
  <mdui:UIInfo xmlns:mdui="urn:oasis:names:tc:SAML:metadata:ui">
    <mdui:DisplayName xml:lang="sv">Organisationsadmin</mdui:DisplayName>
    <mdui:DisplayName xml:lang="en">Org Admin</mdui:DisplayName>
    <mdui:Description xml:lang="sv">Applikation för att administrera användare</mdui:Description>
    <mdui:Description xml:lang="en">Application for administering users</mdui:Description>
    <mdui:Logo height="256" width="256" xmlns:mdui="urn:oasis:names:tc:SAML:metadata:ui">
      https://local.dev.swedenconnect.se:16910/idp/images/logo-notext.svg
    </mdui:Logo>
    <mdui:Logo height="56" width="280" xmlns:mdui="urn:oasis:names:tc:SAML:metadata:ui">
      https://local.dev.swedenconnect.se:16910/idp/images/logo.svg
    </mdui:Logo>    
  </mdui:UIInfo>
</md:Extensions>
```

As the last children of `<md:SPSSODescriptor>`, add:

```xml
<md:Organization>
  <md:OrganizationName xml:lang="sv">Myndigheten för digital förvaltning</md:OrganizationName>
  <md:OrganizationName xml:lang="en">Authority for digital government</md:OrganizationName>
  <md:OrganizationDisplayName xml:lang="sv">Digg</md:OrganizationDisplayName>
  <md:OrganizationDisplayName xml:lang="en">Digg</md:OrganizationDisplayName>
  <md:OrganizationURL xml:lang="sv">https://www.digg.se</md:OrganizationURL>
  <md:OrganizationURL xml:lang="en">https://www.digg.se/en</md:OrganizationURL>
</md:Organization>
<md:ContactPerson contactType="support">
  <md:Company>Digg</md:Company>
  <md:EmailAddress>operations@swedenconnect.se</md:EmailAddress>
</md:ContactPerson>
<md:ContactPerson contactType="technical">
  <md:Company>Digg</md:Company>
  <md:EmailAddress>operations@swedenconnect.se</md:EmailAddress>
</md:ContactPerson>
```

Re-start the Docker services so that the metadata aggregator re-reads its metadata.

## 4. Map the attribute

**Identity providers**, `eid`, **Mappers**, **Add mapper**.

| Field | Value |
| :--- | :--- |
| Name | `personal-identity-number` |
| Sync mode override | `Inherit` |
| Mapper type | `Attribute Importer` |
| Attribute Name | `urn:oid:1.2.752.29.4.13` |
| Name Format | `ATTRIBUTE_FORMAT_BASIC` |
| User Attribute Name | `personalIdentityNumber` |

Sync mode stays `Import` so the mapper does not write back to existing users. The admin app remains
the source of truth for this attribute.

This mapper is what makes the authenticator protocol independent. It normalises the SAML attribute
into the brokered context, and the authenticator reads only the normalised value. When the OIDC OP
replaces the SAML IdP, only this mapper changes: a claim to user attribute mapper reading
`https://id.oidc.se/claim/personalIdentityNumber` into the same `personalIdentityNumber` user
attribute. The authenticator and the flow are untouched.

## 5. Create the first broker login flow

**Authentication** → **Flows** → `first broker login`, **Duplicate** (click the dots to the right). 
Name it `eid first broker login`.

The copy is nested. The three sub-flows are renamed with the new flow name as a prefix, so they
appear as `eid first broker login User creation or linking`, `eid first broker login Handle Existing
Account` and `eid first broker login Account verification options`.

Set these to **Disabled**:

- `Review Profile`, at the top level. Left enabled it prompts for profile details and can rewrite the
  username.
- `Create User If Unique`, inside `User creation or linking`.
- `eid first broker login Handle Existing Account`, inside `eid first broker login User creation or linking`. Disabling this
  sub-flow takes out everything below it, including `Verify Existing Account By Re-authentication`,
  which is the one that would otherwise fall back to prompting for username and password.

On the `eid first broker login User creation or linking` row, click **+** → **Add execution**, and add
these two, in this order, both set to **Required**:

1. `Detect Existing Broker User By Attribute` (page 2)
2. `Automatically Set Existing User`

Order matters: the first writes the matched user to an auth note, the second reads it.

Now configure the `Detect Existing Broker User By Attribute` execution you just added. Click the gear
icon on its row and fill in the dialog:

| Field | Value |
| :--- | :--- |
| Alias | `eid-match-on-pnr` |
| Authenticator Reference | empty |
| Authenticator Reference Max Age | empty |
| Match attribute | `personalIdentityNumber` |
| Required role | empty |
| Forbidden role | empty |

Save.

`Alias` is just a name for this configuration and is required by Keycloak. `Authenticator Reference`
and `Authenticator Reference Max Age` are generic Keycloak fields, not used here. The two role fields
are set in step 10, once the `superuser` role exists.

## 6. Bind the flow

**Identity providers** → `eid` → **Advanced settings** → **First login flow override** = `eid first
broker login`. Save.

## 7. Prepare a test user

The user must already exist. Nothing in this configuration creates users.

Either create one through the admin app, or in **Users** create one manually with:

- Username: a UUID
- No email
- No credentials
- Attribute `personalIdentityNumber` set to the personal number the IdP will assert
- Enabled

**Users**, the user, **Identity provider links** should be empty. That is what makes the first
broker login flow run.

## 8. Test

Log in through the admin app. The eID provider button appears on the login page. Authenticate at the
IdP.

On success, check **Users**, the user, **Identity provider links**. A link for `eid` must now exist.
Log out and in again. The second login must skip the first broker login flow entirely, since the
link now resolves the user directly.

Failure cases to try, all of which must produce the same generic error:

- A personal number with no matching local user
- A matching user that is disabled
- Removing the `personalIdentityNumber` attribute from the user

## 9. Route all logins through the IdP

Optional for testing. Do this once step 8 passes.

**Authentication**, **Flows**, `browser`, then:

- `Identity Provider Redirector`, configure, **Default Identity Provider** = `eid`
- Set `Username Password Form` to **Disabled**

With both user populations on external IdPs there is no reason for any credential to exist in the
realm. Note that this also removes the local login path for anyone whose IdP link is broken, so keep
a separate master realm admin account.

## 10. The corporate IdP for superadmins

Not needed for the initial SAML test. When it is added:

1. Register it as a second identity provider under its own alias.
2. Set **Hide on login page** to On. It is reached only from the admin app's admin login route,
   which passes `kc_idp_hint=<alias>`.
3. Give it its own first broker login flow, same two steps, with **Match attribute** set to whatever
   the corporate IdP asserts and **Required role** = `superuser`.
4. Set **Forbidden role** = `superuser` on the eID flow at the same time.

## Things to check while testing

These are open in the design and testing should settle them.

- Whether the `Attribute Importer` populates the brokered context before the first broker login flow
  runs. If the authenticator cannot see the attribute, this is why, and the mapping has to move into
  the authenticator instead.
- Whether `Import` sync mode really leaves an existing user's `personalIdentityNumber` untouched.
- Whether the `Attribute Importer` can write `personalIdentityNumber` at all, given the user profile
  declares it as admin editable and enabled only when the `naturalPersonNumber` scope is requested.
  Only matters if we later decide the IdP should own the attribute.
- Which user session note holds the broker alias, if we want to surface the authentication method as
  a claim.
