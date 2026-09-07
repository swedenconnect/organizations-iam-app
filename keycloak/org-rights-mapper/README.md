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

- **Org-level right** — the user is a member of `orgs/{org}/{_admin|_write|_read}`. The right is
  **expanded** into one `functions` entry per function currently attached to that organization,
  and additionally recorded in the `org_level_right` field for provenance. No wildcard is
  emitted, so consumers never have to resolve the attachment set themselves.

- **Function-level right** — the user is a member of `orgs/{org}/{function}/{_admin|_write|_read}`.
  The resulting `functions` entry names the specific function.

Where both apply to the same function, the **highest** right wins (`admin` > `write` > `read`),
and exactly one entry per function is emitted.

The attached functions of an organization are its sub-groups other than the three reserved right
groups `_admin`, `_write` and `_read`. Matching those three names exactly — rather than
filtering on a leading underscore — keeps a legal function identifier such as `_foo` from being
dropped.

**Example claim** for a user with org-level `write` on `5590026042` (which has `walletreg` and
`reporting` attached) and an explicit function-level `admin` on `walletreg`:

```json
"org_rights": [
  {
    "organization_identifier": "5590026042",
    "organization_legal_name": "Exempelorganisationen Aktiebolag",
    "organization_name": "Exempelorganisationen Aktiebolag",
    "organization_name#sv": "Exempelorganisationen",
    "organization_name#en": "Example Organization",
    "org_level_right": "write",
    "functions": [
      { "function": "walletreg", "right": "admin" },
      { "function": "reporting", "right": "write" }
    ]
  }
]
```

**Names.** `organization_legal_name` carries the organization's legal name, as registered at
Bolagsverket. It is always emitted and is the member to read when the registered name is wanted. The
untagged `organization_name` repeats the same value; it exists only so that a consumer resolving a
name across the `organization_name*` members still finds something when no display name is set, and
must not be relied on. `organization_name#sv` and `organization_name#en` are optional display names
and are emitted only when the corresponding group attribute is set.

**`org_level_right` confers no access.** It records only that the right was granted org-wide.
Effective rights come exclusively from `functions`; a consumer that treats `org_level_right` as
a grant would give access to functions that are not attached to the organization. Its one
legitimate use is deciding whether the user may administer the organization itself.

An org-level right on an organization with **no attached functions** yields an entry with
`org_level_right` set and `"functions": []`. The entry is still emitted so the organization
remains enumerable by relying parties; the empty array conveys that no function-level access
follows.

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
  {org-group}/             ← attributes: organization_identifier, organization_name,
                           ←             organization_name#sv, organization_name#en
    _admin                 ← org-level admin right
    _write                 ← org-level write right
    _read                  ← org-level read right
    {function-group}/        ← an attached function (attribute: function_ref)
      _admin               ← function-level admin right
      _write               ← function-level write right
      _read                ← function-level read right
```

The organization attributes are read from the org group and included in each `org_rights` entry.
`organization_identifier` and the untagged `organization_name`, which holds the legal name, are
always present. `organization_name#sv` and `organization_name#en` are optional display names and
appear in the entry only when the group carries them.

An org group created before the legal name existed has no untagged `organization_name`. The mapper
then derives one from the Swedish display name, falling back to the English one, and logs that the
organization's legal name has not been entered and needs to be updated. The derived value is not
written back to the group: a human has to supply the real registered name. A group carrying no name
at all is malformed, and the organization identifier is used so that nothing downstream breaks.

The function sub-groups are also the attachment set an org-level right is expanded onto. They
are identified by name (anything that is not `_admin`, `_write` or `_read`) rather than by their
`function_ref` attribute: reading the attribute would cost one extra load per sub-group, and a
hand-provisioned realm that omitted it would silently lose the user's rights.

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

