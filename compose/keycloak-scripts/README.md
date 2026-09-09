# Keycloak Scripts for the Compose Environment

Convenience wrappers for the local Docker Compose environment.

Every script here is a thin wrapper over its counterpart in
[`keycloak/scripts/`](../../keycloak/scripts/README.md), which is the single implementation
of each script. The wrapper supplies the two things the standalone script would otherwise
need on the command line, the URL the compose Keycloak is published on and the certificate
it serves:

```
--url https://local.dev.swedenconnect.se:17000
--cacert compose/config/common/tls.crt
```

Everything else is passed through unchanged, so each wrapper takes exactly the options its
counterpart takes. `--help` on a wrapper shows its own command line; run the standalone
script with `--help` for the full option reference, or read
[`keycloak/scripts/README.md`](../../keycloak/scripts/README.md), which documents what each
script does and what it leaves behind in Keycloak.

Use the wrappers when working in the local environment, and call the standalone scripts
directly against any other Keycloak.

`install-keycloak-plugins.sh` is not a wrapper. It builds the plugin modules and unpacks the
[distribution ZIP](../../keycloak/README.md#plugin-distribution) the build produced into
`compose/config/keycloak/spi/`, the directory the Keycloak container mounts as its providers
directory. The SPI directory therefore ends up holding exactly the providers of the current
build, and a JAR left behind by an earlier build is not installed. A failed build stops the
script instead of leaving an older ZIP to be installed as if it were current. Restart Keycloak
afterwards, which the script reminds you to do.

For a Keycloak outside compose, the counterpart is
[`keycloak/scripts/get-keycloak-plugins.sh`](../../keycloak/scripts/README.md#get-keycloak-plugins),
which fetches the same ZIP by version. See
[Provider JARs](../../keycloak/README.md#provider-jars) for what the providers are.

## Prerequisites

| Requirement | Notes |
|---|---|
| `curl` | The scripts call the Keycloak Admin REST API from the host |
| `python3` | Used for JSON construction and parsing |
| A hosts file entry | `127.0.0.1  local.dev.swedenconnect.se`, the name the certificate is issued for |
| The `keycloak` service running | `docker compose -f compose/docker-compose.yml up -d keycloak` |
| Provider JARs installed | Run `./compose/keycloak-scripts/install-keycloak-plugins.sh`, then start or restart Keycloak. It builds the plugins and installs them from the distribution ZIP. See [Provider JARs](../../keycloak/README.md#provider-jars) |

The scripts run on the host rather than inside a container, so `curl` and `python3` have to
be available there. Nothing needs to be installed in the Keycloak container.

The scripts can be run from any directory. They resolve the repository root themselves.

## The wrappers

| Wrapper | What it leaves behind in Keycloak |
|---|---|
| `bootstrap-realm.sh` | The realm with its top-level groups, the `superuser` role, the OIDC Sweden client scopes and their mappers, the OIDC Sweden user profile groups and attributes, and the resource function client policy |
| `create-admin-user.sh` | A user with the `superuser` realm role and a password |
| `add-oidc-client.sh` | An OIDC or OAuth client with `private_key_jwt` authentication, Authorization Services, the IAM protocol mappers and the optional client scopes |
| `add-resource-server.sh` | A public client with all flows disabled, marked `iam_admin_resource_server`, optionally carrying `client_functions` |
| `set-client-functions.sh` | The `client_functions` attribute on an existing client |
| `set-iam-admin-managed.sh` | The `iam_admin_managed=true` attribute on an existing client |

Each one is idempotent and safe to re-run. What a re-run changes, and which options are
required, is documented per script in
[`keycloak/scripts/README.md`](../../keycloak/scripts/README.md).

## Typical setup sequence

```bash
# 1. Install the provider JARs, then start Keycloak
./compose/keycloak-scripts/install-keycloak-plugins.sh
docker compose -f compose/docker-compose.yml up -d keycloak

# 2. Bootstrap the realm
./compose/keycloak-scripts/bootstrap-realm.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --display-name "Organizations and Users IAM"

# 3. Create the initial superuser
./compose/keycloak-scripts/create-admin-user.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --new-username diggadmin \
    --new-password changeme

# 4. Register the IAM admin application client
./compose/keycloak-scripts/add-oidc-client.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://local.dev.swedenconnect.se:17005 \
    --name "IAM Admin Application" \
    --redirect-uri '/login/oauth2/code/*' \
    --service-account

# 5. Register a resource server, and the client that calls it
./compose/keycloak-scripts/add-resource-server.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://local.dev.swedenconnect.se:16995 \
    --name "Demo Service" \
    --functions demo

./compose/keycloak-scripts/add-oidc-client.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://local.dev.swedenconnect.se:16990 \
    --name "Demo App" \
    --redirect-uri '/login/oauth2/code/*'
```

`{org}:{function}:{right}` scopes and their Authorization Services policies are not created
by any of these scripts. The IAM admin application creates them when a function is attached
to an organization.

See [compose/README.md](../README.md) for the full local environment setup, and
[docs/keycloak-setup.md](../../docs/keycloak-setup.md) for what the realm configuration
means.

---

Copyright &copy; 2026, [Myndigheten f&ouml;r digital f&ouml;rvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
