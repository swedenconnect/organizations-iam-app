![Sweden Connect](docs/images/sweden-connect.png)

# Sweden Connect Organizations and Users IAM Application

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) ![Maven Central](https://img.shields.io/maven-central/v/se.swedenconnect.iam/iam-parent.svg)

A centralized administration application for assigning and delegating rights for organizations and users against various target systems. Built on Keycloak with custom protocol mappers, a Spring Boot admin application, and shared security libraries.

## NOTE: Action required after pulling the 2026-08-18 change

This change alters the `org_rights` claim format. The claim is written by the `org-rights-mapper`
Keycloak plugin and read by the `iam-security` libraries, so both sides must be updated. If you
only rebuild the applications, they will parse a claim in the old format and users will silently
lose their rights.

If you already have a working environment, reinstall the plugin JARs and restart Keycloak:

```bash
./compose/keycloak-scripts/install-keycloak-plugins.sh
docker compose -f compose/docker-compose.yml restart keycloak
```

That is all. No realm configuration, group attributes, client scopes or protocol-mapper instances
need to change.

A Keycloak instance started with `start --optimized` rather than `start-dev` additionally needs an
explicit `kc.sh build` before restarting.

To confirm it worked, log in and decode an ID token for a user holding an organisation-level right:
`"function": "*"` should be gone, replaced by one entry per attached function plus an
`org_level_right` field. See the [release notes](docs/release-notes.md) for the full description.

## Documentation

Full documentation is available at https://docs.swedenconnect.se/organizations-iam-app/index.html.

- [Release notes](https://docs.swedenconnect.se/organizations-iam-app/release-notes.html)

## Modules

| Module | Description |
| :---| :--- |
| iam-commons | Shared base types (`LocalizedString`, `OrganizationID`, etc.) |
| [iam-security](docs/iam-security.md) | Security library: `org_rights` parsing, authorities, auto-configuration |
| [keycloak](keycloak) | Keycloak SPI plugins (org-rights mapper, scope-org-identifier mapper, resource-audience plugin) |
| iam-admin-app | Admin application (Spring Boot backend + React frontend) |
| `demo` | Demo application and resource server for integration testing |

## Local Development

### Prerequisites

- Java 21
- Maven
- Docker and Docker Compose
- A hosts file entry mapping `127.0.0.1` to `local.dev.swedenconnect.se`

### Setting up Keycloak

1. Install the Keycloak provider JARs:
   ```bash
   ./compose/keycloak-scripts/install-keycloak-plugins.sh
   ```

2. Start the Keycloak service:
   ```bash
   docker compose -f compose/docker-compose.yml up -d keycloak
   ```

3. Bootstrap the realm:
   ```bash
   ./compose/keycloak-scripts/bootstrap-realm.sh \
       --realm orgiam \
       --username admin \
       --password keycloak \
       --display-name "Organizations and Users IAM"
   ```

4. Create the initial admin user:
   ```bash
   ./compose/keycloak-scripts/create-admin-user.sh \
       --realm orgiam \
       --username admin \
       --password keycloak \
       --new-username diggadmin \
       --new-password changeme
   ```

See [compose/README.md](compose/README.md) for the full local environment setup
including all services and client registration.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
