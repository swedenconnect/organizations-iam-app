![Sweden Connect](../../docs/images/sweden-connect.png)

# scope-org-identifier-mapper

A Keycloak 26.x OIDC protocol mapper that extracts the organization identifier from the granted scope string and emits it as the `organization_identifier` claim in the access token.

## What it does

The mapper inspects the space-separated granted scope string in the access token, finds the first scope matching the `{org}:{function}:{right}` pattern (where `{org}` is a ten-digit Swedish organizational number), and emits the organizational number as the `organization_identifier` claim.

The claim is added to the **access token only** — not the ID token or UserInfo response.

**Example:** a token granted the scope `5590026042:walletreg:write` will contain:

```json
"organization_identifier": "5590026042"
```

Resource servers can read `organization_identifier` directly from the token without parsing the scope string, and can use it to enforce that an operation targets the correct organization (see `docs/keycloak-setup.md` section 3.3).

If no scope matching the pattern is present (e.g. a pure OIDC session with no org-scoped scopes), the claim is simply absent from the token.

## Build

```bash
mvn -U -DskipTests clean package
```

(Run from the repository root or from `keycloak/scope-org-identifier-mapper/`.)

## Install into Keycloak 26.x

```bash
cp target/scope-org-identifier-mapper-<version>.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start --optimized
```

Note: both `org-rights-mapper` and `scope-org-identifier-mapper` JARs must be present in the providers directory. Installing one does not affect the other.

## Configure in the Admin Console

1. Open the target client in the Keycloak Admin Console.
2. Go to the **Client scopes** tab → **Dedicated scopes** → **Add mapper** → **By configuration**.
3. Select **Scope Org Identifier Mapper** from the list.
4. Set **Name** to `scope-org-identifier-mapper`.
5. Ensure **Add to access token** is `ON`. **Add to ID token** and **Add to userinfo** are not applicable to this mapper type and will have no effect.
6. Click **Save**.

This mapper should be added to every OAuth/OIDC client that requests `{org}:{function}:{right}` scopes on behalf of users — that is, every client registered with `iam_admin_managed = true`. It should **not** be added to passive resource servers.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).

