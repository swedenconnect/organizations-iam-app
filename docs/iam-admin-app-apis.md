![logo](images/sweden-connect.png)

# IAM Admin Application — Service API

---

## Table of Contents

1. [**Overview**](#overview)
2. [**Security Model**](#security-model)
3. [**Endpoints**](#endpoints)

    3.1. [List Organizations](#list-organizations)

    3.2. [List Rights Holders for Org/Function](#list-rights-holders)

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

**Authorization:** Per-endpoint, based on the token's `realm_access.roles` claim and/or
the `org_rights` claim.

Keycloak includes realm roles in access tokens by default. The IAM Service API extracts
the `superuser` role and maps it to the Spring Security authority `ROLE_SUPERUSER`.

Some endpoints additionally inspect the `org_rights` claim on the access token to
determine whether the caller has admin rights on a specific organization/function
combination. For these endpoints to work for non-superusers, the `org_rights` protocol
mapper must be configured to include the claim on access tokens (the default when using
`add-oidc-client.sh`).

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
    "name#sv": "Litsec AB",
    "name#en": "Litsec",
    "attached_functions": ["demo", "walletreg"],
    "contact": {
      "email": "info@litsec.se",
      "phone": null
    }
  },
  "5591617864": {
    "name#sv": "IDsec Solutions AB",
    "name#en": "IDsec Solutions",
    "attached_functions": ["demo", "swedenconnect"],
    "contact": {
      "email": null,
      "phone": null
    }
  }
}
```

| Field                | Type           | Description                                                    |
|----------------------|----------------|----------------------------------------------------------------|
| `name#sv`            | string \| null | Swedish organization name.                                     |
| `name#en`            | string \| null | English organization name.                                     |
| `attached_functions` | string[]       | Function identifiers attached to this organization.            |
| `contact`            | object         | Object with `email` and `phone`, each string or null. Always present. |
| `contact.email`      | string \| null | Contact email address.                                         |
| `contact.phone`      | string \| null | Contact phone number.                                          |

**Error responses:**

| Status | Condition                                |
|--------|------------------------------------------|
| 401    | Missing or invalid Bearer token          |
| 403    | Token is valid but caller is not superuser |

<a name="list-rights-holders"></a>
### 3.2. List Rights Holders for Org/Function

Lists all users holding a right on a specific (organization, function) combination.

**Request:**

```
GET /iam-api/v1/organizations/{orgIdentifier}/functions/{functionId}/users
Authorization: Bearer <token>
```

**Authorization:** Requires one of:

- The `superuser` realm role (`ROLE_SUPERUSER`); or
- An `org_rights` claim granting `admin` on `(orgIdentifier, functionId)` — including
  via the org-wide wildcard `(orgIdentifier, *, admin)`.

Authorization is evaluated before any Keycloak lookup; unauthorized callers cannot
distinguish a non-existent org/function from an existing-but-forbidden one.

**Response:** `200 OK`

```json
{
  "users": [
    {
      "user_id": "a3b4c5d6-1234-5678-9abc-def012345678",
      "personal_identity_number": "197001011234",
      "name": "Anna Andersson",
      "right": "admin",
      "scope": "function"
    },
    {
      "user_id": "b4c5d6e7-2345-6789-abcd-ef0123456789",
      "personal_identity_number": "198505050505",
      "name": "Bertil Bengtsson",
      "right": "write",
      "scope": "organization"
    }
  ]
}
```

| Field                      | Type           | Description                                                                                         |
|----------------------------|----------------|-----------------------------------------------------------------------------------------------------|
| `user_id`                  | string         | Keycloak user UUID                                                                                  |
| `personal_identity_number` | string \| null | The user's `personalIdentityNumber` attribute from Keycloak                                         |
| `name`                     | string \| null | `firstName + " " + lastName`, trimmed. Falls back to `username`, then `null`.                       |
| `right`                    | string         | Effective right: `admin`, `write`, or `read`                                                        |
| `scope`                    | string         | `"function"` if from the function sub-group; `"organization"` if from the org-wide right sub-group  |

**Ordering:** Sorted by `right` descending (`admin` → `write` → `read`), then by `name`
ascending within each right (nulls last).

**Right resolution:** A user may appear in multiple candidate groups. Each user is
returned exactly once with the highest right (`admin` > `write` > `read`). If the same
level is held both org-wide and function-specific, the function scope wins (more
specific).

**Error responses:**

| Status | Condition                                                            |
|--------|----------------------------------------------------------------------|
| 401    | Missing or invalid Bearer token                                      |
| 403    | Caller is neither superuser nor admin on this (org, function)        |
| 404    | Organization does not exist, or function is not attached to it       |
| 500    | Unexpected Keycloak admin API error                                  |

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
