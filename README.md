![Sweden Connect](docs/images/sweden-connect.png)

# Sweden Connect Organizations and Users IAM Application

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) ![Maven Central](https://img.shields.io/maven-central/v/se.swedenconnect.iam/iam-parent.svg)

A centralized administration application for assigning and delegating rights for organizations and users against various target systems. Built on Keycloak with custom protocol mappers, a Spring Boot admin application, and shared security libraries.

## Documentation

Full documentation is available at https://docs.swedenconnect.se/organizations-iam-app/index.html.

- [Release notes](https://docs.swedenconnect.se/organizations-iam-app/release-notes.html)

## Modules

| Module | Description |
| :--- | :--- |
| [iam-commons](iam-commons) | Shared value types (`OrganizationID`, `ClientID`, `OperationID`, `LocalizedString`, etc.) and validation support. Has no Spring dependency, so it can also be used by the Keycloak plugins. |
| [iam-security](iam-security) | The security library, holding the `org_rights` claims model and the Spring Security authority types (`iam-security-base`), along with the Spring Boot starter that auto-configures OIDC clients, OAuth2 clients and resource servers (`iam-security-spring-boot-starter`). |
| [keycloak](keycloak) | The Keycloak provider plugins (org-rights mapper, scope-org-identifier mapper, resource-audience plugin), the plugin distribution ZIP that collects the providers a release needs, and the scripts for administering a realm and its clients. |
| [iam-admin-app](iam-admin-app) | The administration application for organizations, users and rights. A Spring Boot backend serving a React frontend, packaged as a single artifact. |
| [demo](demo) | Example applications showing how the security library is integrated, a demo app acting as OIDC and OAuth2 client, and a demo service acting as resource server. |
| [compose](compose) | The Docker Compose local development environment for the services and Keycloak. See [compose/README.md](compose/README.md) for how to set it up. |

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
