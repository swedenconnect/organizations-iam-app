![Sweden Connect](../../docs/images/sweden-connect.png)

# org-rights-mapper

A Keycloak 26.x OIDC protocol mapper that adds the `org_rights` claim to tokens. The claim
describes all rights the authenticated user holds across organizations and functions, derived
entirely from the user's Keycloak group memberships.

## What it does

The mapper reads the user's group memberships under the top-level `orgs` group and produces
a structured `org_rights` array in the token. Each element represents one organization the
user has access to, with a nested `functions` array listing the individual rights granted.

Rights are represented by the leaf groups `_admin`, `_write`, and `_read`. The mapper
recognizes two grant patterns:

- **Org-level right** — the user is a member of `orgs/{org}/{_admin|_write|_read}`. The
  resulting `functions` entry uses `"*"` as the function name, indicating the right applies
  across all functions in that organization.

- **Function-level right** — the user is a member of `orgs/{org}/{function}/{_admin|_write|_read}`.
  The resulting `functions` entry names the specific function.

**Example claim for a user with mixed memberships:**

```json
"org_rights": [
  {
    "organization_identifier": "5590026042",
    "organization_name#sv": "Exempelorganisationen",
    "organization_name#en": "Example Organization",
    "functions": [
      { "function": "walletreg", "right": "write" },
      { "function": "reporting", "right": "read" }
    ]
  }
]
```

**Superuser shortcut** — if the user holds the `superuser` realm role the mapper emits a
single-element array with just `{ "superuser": true }`, bypassing the group walk entirely:

```json
"org_rights": [
  { "superuser": true }
]
```

If the user has no relevant group memberships and is not a superuser, an empty array is emitted.

The `org_rights` claim can be added to the **ID token**, **access token**, and **UserInfo**
response. Which tokens carry the claim is controlled per mapper instance in the Admin Console.

## Group structure assumed

```
orgs/
  {org-group}/             ← attributes: organization_identifier, organization_name#sv, organization_name#en
    _admin                 ← org-level admin right
    _write                 ← org-level write right
    _read                  ← org-level read right
    {function-group}/
      _admin               ← function-level admin right
      _write               ← function-level write right
      _read                ← function-level read right
```

The organization attributes (`organization_identifier`, `organization_name#sv`,
`organization_name#en`) are read from the org group and included verbatim in each
`org_rights` entry.

## Build

```bash
mvn -U -DskipTests clean package
```

(Run from the repository root or from `keycloak/org-rights-mapper/`.)

## Install into Keycloak 26.x

```bash
cp target/org-rights-mapper-<version>.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start --optimized
```

Note: both `org-rights-mapper` and `scope-org-identifier-mapper` JARs must be present in
the providers directory. Installing one does not affect the other.

## Configure in the Admin Console

1. Open the target client in the Keycloak Admin Console.
2. Go to the **Client scopes** tab → **Dedicated scopes** → **Add mapper** → **By configuration**.
3. Select **Org Rights Mapper** from the list.
4. Set **Name** to `org-rights-mapper`.
5. Toggle **Add to ID token** and/or **Add to access token** as required.
6. Click **Save**.

This mapper should be added to every OAuth/OIDC client that requests `{org}:{function}:{right}`
scopes on behalf of users — that is, every client registered with `iam_admin_managed = true`.
It should **not** be added to passive resource servers.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).

