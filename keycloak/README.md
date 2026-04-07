![Sweden Connect](../docs/images/sweden-connect.png)

# KeyCloak Plugins

## Plugins

| Plugin | Description |
| :--- | :--- |
| [org-rights-mapper](org-rights-mapper/README.md) | Adds the `org_rights` claim derived from the user's group memberships under the `orgs` group. |
| [scope-org-identifier-mapper](scope-org-identifier-mapper/README.md) | Extracts the organization identifier from the granted scope string and emits it as the `organization_identifier` claim. |
| [resource-aud-plugin](resource-aud-plugin/README.md) | Validates the OAuth2 `resource` parameter against the target client's `client_functions` attribute and sets the `aud` claim to `[client_id, function]`. |

## Build

From the repository root:

```bash
mvn -U -DskipTests clean package
```

## Install into Keycloak 26.x (Quarkus distribution)

1. Copy the desired provider JAR into Keycloak's `providers/` directory:

```bash
cp <module>/target/<module>-<version>.jar /opt/keycloak/providers/
```

2. Run a build (required when adding or changing providers):

```bash
/opt/keycloak/bin/kc.sh build
```

3. Start Keycloak (typically using `--optimized` after a build):

```bash
/opt/keycloak/bin/kc.sh start --optimized
```

See each module's README for what it does and how to configure it in the Admin Console.

> **Note:** For the project's local Docker Compose environment, use the convenience script
> `compose/config/keycloak/install-keycloak-plugins.sh` which builds these plugins, also
> downloads the external `oidc-sweden-claims-plugin` from Maven Central, and installs all
> JARs into `compose/config/keycloak/spi/` in one step. See
> [compose/README.md](../compose/README.md) for full instructions.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
