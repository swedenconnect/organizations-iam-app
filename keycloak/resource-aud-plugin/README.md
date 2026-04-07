![Sweden Connect](../../docs/images/sweden-connect.png)

# resource-aud-plugin

A Keycloak 26.x plugin that validates the OAuth2 `resource` parameter (RFC 8707) against the target client's `client_functions` attribute and sets the `aud` claim in access tokens to a multi-valued array.

## What it does

The plugin contains two Keycloak SPI components:

### 1. Resource Function Executor (Client Policy Executor)

Intercepts authorization and token requests. When a request carries a `resource` parameter, the executor:

1. Extracts the function identifier from the requested scope (pattern: `{org}:{function}:{right}`).
2. Looks up the Keycloak client indicated by the `resource` value.
3. Checks that the client's `client_functions` attribute contains the requested function.
4. Rejects the request with an `invalid_target` error if the function is not supported.
5. For authorization requests, stores the validated `resource` value in an auth session note so the mapper can read it at token generation time.

### 2. Resource Audience Mapper (Protocol Mapper)

Runs during access token generation. Only fires when the `resource` parameter is present (from the token request form parameters or from the auth session note stored by the executor during the authorization request phase).

When the `resource` parameter is present, the mapper looks up the resource server client and checks for the `client_functions` attribute:

- **Client has `client_functions`** (single-function mode): extracts the function from the
  first matching org-scoped scope and sets `aud` = `[resource_server_client_id, function]`.
- **Client has no `client_functions`** (multi-function mode): extracts all distinct
  functions from the granted scopes and sets
  `aud` = `[resource_server_client_id, func1, func2, ...]`.
- **`resource` absent**: the `aud` claim is **not modified** — Keycloak's default audience
  is preserved.

**Example — single-function mode** (resource server has `client_functions=walletreg`):

```json
"aud": ["https://my-service.example.com", "walletreg"]
```

**Example — multi-function mode** (resource server has no `client_functions`, scope contains two org-scoped entries):

```json
"aud": ["https://my-service.example.com", "demo", "walletreg"]
```

## The `client_functions` attribute

Resource server clients can declare the functions they support via the `client_functions` attribute. The value is a comma-separated list of function identifiers (e.g., `walletreg` or `demo,walletreg`).

If `client_functions` is absent or blank, the resource server is treated as function-universal and accepts all functions.

## Build

```bash
mvn -U -DskipTests clean package
```

(Run from the repository root or from `keycloak/resource-aud-plugin/`.)

## Install into Keycloak 26.x

```bash
cp target/resource-aud-plugin-<version>.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start --optimized
```

## Configure in the Admin Console

### Step 1 — Add the Resource Audience Mapper to OAuth clients

Add the mapper to every OAuth client that requests org-scoped tokens against resource servers (e.g., `https://dev-wallet.pts.se:17005`, `https://dev-wallet.pts.se:17010`):

1. Open the target client in the Keycloak Admin Console.
2. Go to the **Client scopes** tab → **Dedicated scopes** → **Add mapper** → **By configuration**.
3. Select **Resource Audience Mapper** from the list.
4. Set **Name** to `resource-audience-mapper`.
5. Ensure **Add to access token** is `ON`. Set **Add to ID token** and **Add to userinfo** to `OFF`.
6. Click **Save**.

Do **not** add this mapper to passive resource server clients.

### Step 2 — Create a Client Policy using the Resource Function Executor

1. Navigate to **Realm Settings → Client Policies**.
2. Click **Create policy**.
3. Give it a name (e.g., `resource-function-validation`) and a description.
4. Under **Conditions**, add a condition that applies to the relevant clients (e.g., clients with `iam_admin_managed=true`, or list specific clients).
5. Under **Executors**, click **Add executor**, select **Resource Function Executor**, and click **Add**.
6. Click **Save**.

The policy will now intercept token and authorization requests from the matched clients and validate the `resource` parameter.

## Error responses

When the executor rejects a request, Keycloak returns a standard OAuth error response:

```json
{
  "error": "invalid_target",
  "error_description": "Resource server does not support the requested function: <function>"
}
```

The `invalid_target` error code is defined by RFC 8707 for cases where the resource indicator is not valid for the requested scope.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
