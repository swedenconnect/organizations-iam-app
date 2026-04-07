![logo](images/sweden-connect.png)

# IAM Admin Application — Service API

---

## Table of Contents

1. [**Overview**](#overview)
2. [**Security Model**](#security-model)
3. [**Endpoints**](#endpoints)

    3.1. [List Organizations](#list-organizations)

---

<a name="overview"></a>
## 1. Overview

The IAM admin application exposes a set of REST endpoints under `/iam-api/v1/` for use by
external applications that need to query IAM data. These endpoints are separate from the
session-based `/api/` endpoints used by the IAM admin application's own frontend.

The primary use case is enabling applications to resolve the full list of organizations
for superusers, whose `org_rights` claim contains no organization details.

The IAM Service API retrieves data from Keycloak using the iam-admin-app's existing
service account (client_credentials grant with `private_key_jwt` authentication). Callers
of the API do not need direct Keycloak access.

<a name="security-model"></a>
## 2. Security Model

**Authentication:** OAuth2 Bearer token (JWT).

The token is validated against Keycloak's JWKS endpoint. The following checks are
performed:

- **Signature and expiry** — standard JWT validation via the configured `issuer-uri`.
- **Audience** — the `aud` claim must contain the iam-admin-app's client ID
  (e.g. `https://local.dev.swedenconnect.se:17005`).

**Authorization:** Per-endpoint, based on the token's `realm_access.roles` claim.

Keycloak includes realm roles in access tokens by default. The IAM Service API extracts
the `superuser` role and maps it to the Spring Security authority `ROLE_SUPERUSER`. Each
endpoint declares its required authority.

**Token acquisition by the caller:** The calling application obtains a user-delegated
access token (authorization code grant) with the `resource` parameter set to the
iam-admin-app's client ID. This sets the `aud` claim correctly. No special scope is
required — the token carries the user's realm roles automatically.

<a name="endpoints"></a>
## 3. Endpoints

<a name="list-organizations"></a>
### 3.1. List Organizations

Lists all organizations registered in the IAM system with their full attributes.

**Request:**

```
GET /iam-api/v1/organizations
Authorization: Bearer <token>
```

**Authorization:** Requires the `superuser` realm role (`ROLE_SUPERUSER`).

**Response:** `200 OK`

A JSON object keyed by organization identifier. Each value contains all organization
attributes:

```json
{
  "5590026042": {
    "nameSv": "Litsec AB",
    "nameEn": "Litsec",
    "attachedFunctions": ["demo", "walletreg"],
    "contactEmail": "info@litsec.se",
    "contactPhone": null
  },
  "5591617864": {
    "nameSv": "IDsec Solutions AB",
    "nameEn": "IDsec Solutions",
    "attachedFunctions": ["demo", "swedenconnect"],
    "contactEmail": null,
    "contactPhone": null
  }
}
```

| Field               | Type           | Description                                        |
|---------------------|----------------|----------------------------------------------------|
| `nameSv`            | string \| null | Swedish organization name                          |
| `nameEn`            | string \| null | English organization name                          |
| `attachedFunctions` | string[]       | Function identifiers attached to this organization |
| `contactEmail`      | string \| null | Contact email address                              |
| `contactPhone`      | string \| null | Contact phone number                               |

**Error responses:**

| Status | Condition                                |
|--------|------------------------------------------|
| 401    | Missing or invalid Bearer token          |
| 403    | Token is valid but caller is not superuser |

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
