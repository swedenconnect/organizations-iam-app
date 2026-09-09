![Sweden Connect](../docs/images/sweden-connect.png)

# Keycloak Plugins and Scripts

This directory holds two things: the Keycloak provider plugins built for the IAM system, and
the [administration scripts](scripts/README.md) that configure a Keycloak realm to use them.

## Plugins

The plugins built from this directory:

| Plugin | Description |
| :--- | :--- |
| [org-rights-mapper](org-rights-mapper/README.md) | Adds the `org_rights` claim derived from the user's group memberships under the `orgs` group. |
| [scope-org-identifier-mapper](scope-org-identifier-mapper/README.md) | Extracts the organization identifier from the granted scope string and emits it as the `organization_identifier` claim. |
| [resource-aud-plugin](resource-aud-plugin/README.md) | Validates the OAuth2 `resource` parameter against the target client's `client_functions` attribute and sets the `aud` claim to `[client_id, function]`. |
| idp-user-matcher | Resolves an incoming brokered identity to an existing local user by matching on a configured user attribute. Never creates users, and fails the authentication flow unless exactly one enabled and permitted user matches. |

<a name="provider-jars"></a>
## Provider JARs

The complete set of provider JARs the IAM system needs in Keycloak. The
[distribution ZIP](#plugin-distribution) below is what defines that set for a given release;
this table describes what is in the ZIP and what each provider does, rather than serving as an
installation checklist.

| Provider type | JAR | Origin |
| :--- | :--- | :--- |
| `org-rights-mapper` | `org-rights-mapper-*.jar` | Built from this repository (`keycloak/org-rights-mapper`) |
| `scope-org-identifier-mapper` | `scope-org-identifier-mapper-*.jar` | Built from this repository (`keycloak/scope-org-identifier-mapper`) |
| `resource-audience-mapper` | `resource-aud-plugin-*.jar` | Built from this repository (`keycloak/resource-aud-plugin`) |
| `idp-detect-existing-user-by-attr` | `idp-user-matcher-*.jar` | Built from this repository (`keycloak/idp-user-matcher`) |
| `oidc-sweden-claims-mapper`, `natural-person-info-mapper` | `oidc-sweden-claims-plugin-*.jar` | External artifact, fetched from Maven Central (`se.oidc.keycloak:oidc-sweden-claims-plugin`) |

`idp-user-matcher` is the exception: it is not yet part of the build here, so it is not in the
ZIP either. The distribution holds what the build produces.

The local plugins carry the project version. The external plugin is pinned to a single
version, the `oidc-sweden-claims-plugin.version` property in
[plugin-distribution/pom.xml](plugin-distribution/pom.xml). That is the one place to look for
the version number, and the one place to change it.

The first four provider types are the ones `bootstrap-realm.sh` checks for before it configures
a realm. `idp-detect-existing-user-by-attr` is used when an identity provider is brokered, and
is not part of that check.

## Build

From the repository root:

```bash
mvn -U -DskipTests clean package
```

<a name="plugin-distribution"></a>
## The distribution ZIP

`plugin-distribution` is a module whose only output is one ZIP holding every provider JAR the
release needs:

| | |
| :--- | :--- |
| Coordinates | `se.swedenconnect.iam.keycloak:keycloak-plugin-distribution:<version>:zip:plugins` |
| File name | `keycloak-plugin-distribution-<version>-plugins.zip` |
| Contents | One top level directory, `keycloak-plugins-<version>/`, holding the provider JARs |

The module declares each provider as a dependency and the assembly takes the declared
dependencies, never the contents of a target directory. That is what keeps the ZIP honest: a
JAR from an earlier build, or from a module that has since been removed or renamed, cannot end
up in it. Add a provider to the release by adding a dependency there, and nowhere else.

It is built with the rest of the project and published to Maven Central alongside the
individual plugin artifacts, which keep their own coordinates and file names.

```bash
mvn -U -DskipTests clean package -f keycloak/pom.xml
unzip -l keycloak/plugin-distribution/target/keycloak-plugin-distribution-*-plugins.zip
```

<a name="deploying-to-keycloak"></a>
## Deploying to Keycloak 26.x (Quarkus distribution)

**The local Docker Compose environment.**
[compose/keycloak-scripts/install-keycloak-plugins.sh](../compose/keycloak-scripts/install-keycloak-plugins.sh)
builds the plugin modules and unpacks the distribution ZIP the build produced into
`compose/config/keycloak/spi/`, which the Keycloak container mounts as its providers
directory. Since it installs the contents of the ZIP, the SPI directory ends up holding
exactly the providers of the current build and nothing else. A failed build stops the script
rather than leaving an older ZIP to be installed. Restart Keycloak afterwards. See
[compose/README.md](../compose/README.md) for the full local setup.

**Any other Keycloak.** Nothing in this repository can deploy providers to a server it does
not run, so this is done wherever that server is managed from. Get the JARs for the version
you are deploying, either with

```bash
./keycloak/scripts/get-keycloak-plugins.sh --version 0.9.3 --output-dir ./plugins
```

which fetches the distribution ZIP and unpacks it for you, or by taking the ZIP from Maven
Central by hand and unpacking it. Then, on the Keycloak host:

1. Copy the JARs into Keycloak's `providers/` directory:

```bash
cp ./plugins/*.jar /opt/keycloak/providers/
```

2. Run a build, which is required whenever a provider is added or changed:

```bash
/opt/keycloak/bin/kc.sh build
```

3. Restart Keycloak, typically with `--optimized` after a build:

```bash
/opt/keycloak/bin/kc.sh start --optimized
```

A provider is invisible to Keycloak until the rebuild has happened. A JAR that has been
copied but not built into the distribution behaves exactly as if it were absent: the mapper
and authenticator types it registers cannot be selected, and `bootstrap-realm.sh` reports
them as missing.

See each module's README for what it does and how to configure it in the Admin Console.

<a name="administration-scripts"></a>
## Administration scripts

[scripts/](scripts/README.md) holds the scripts that configure a Keycloak realm for the IAM
system: bootstrapping the realm, creating the initial superuser, and registering clients and
resource servers. They call the Admin REST API and work against any reachable Keycloak,
local or remote, so they serve production installations as well as the local Docker Compose
environment.

Deploy the provider JARs and rebuild Keycloak before running them. `bootstrap-realm.sh`
configures mappers that the JARs provide, and reports them as missing until they are there.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
