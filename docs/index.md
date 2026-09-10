![Sweden Connect](images/sweden-connect.png)

# Organizations and Users IAM

A centralized administration system for assigning and delegating organizational
rights across service domains. Built on Keycloak with custom protocol mappers and
a Spring Boot admin application.

- [Release notes](release-notes.md)

## Documentation

### Architecture

- [Organization and Function-Based Rights Model](rights-model.md) — The conceptual
  model: organizations, functions, users, rights, the `org_rights` claim, and
  org-scoped API tokens.

### Integration and Development

- [IAM Integration Guide](iam-integration-guide.md) — How to build OIDC relying
  parties, OAuth clients, and resource servers that integrate with this system.
  Includes Spring Boot configuration and the `iam-security` library.

- [Local environment and Demo application](local-environment.md) — Setting up the
  local Docker Compose environment from a fresh checkout and running the demo
  applications that illustrate the integration patterns.

- [IAM Security Library](iam-security.md) — Reference documentation for the
  `iam-security-base` and `iam-security-spring-boot-starter` modules.

### Administration

- [IAM Admin Application — Service API](iam-admin-app-apis.md) — REST API for
  the IAM admin app's internal service endpoints.

- [IAM Admin Themes](iam-admin-themes.md) — White-label theming system for the
  admin application.

- [IAM Admin Application Configuration](iam-admin-configuration.md) — Configuration
  reference for the IAM admin application.

- [Registering a Client](registering-a-client.md) — Step-by-step registration of an
  OIDC client or resource server, through the admin application, the scripts, or the
  REST API.

### Operations

- [Keycloak Setup](keycloak-setup.md) — Step-by-step Keycloak realm configuration,
  client registration, and automation scripts.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
