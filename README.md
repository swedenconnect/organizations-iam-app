![Sweden Connect](docs/images/sweden-connect.png)

# Sweden Connect Organizations and Users IAM Application

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) ![Maven Central](https://img.shields.io/maven-central/v/se.swedenconnect.iam/iam-parent.svg)

A centralized administration application for assigning and delegating rights for organizations and users against various target systems. Built on Keycloak with custom protocol mappers, a Spring Boot admin application, and shared security libraries.

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
