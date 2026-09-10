![logo](images/sweden-connect.png)

# Local environment and Demo application

---

This document sets up a local environment for working with the IAM admin application, with a
Keycloak instance of its own, starting from a fresh checkout. The environment also includes the
Demo Application and the Demo Service, which are set up along the way. The document covers the
prerequisites, the Keycloak bootstrap, the registration of every client, and how the demo is
used once the environment runs. Follow it from top to bottom and nothing else needs to be
consulted along the way.

## Table of Contents

1. [**The local environment**](#the-local-environment)

2. [**Prerequisites**](#prerequisites)

    2.1. [Hosts file](#hosts-file)

    2.2. [Building the service images](#building-the-service-images)

3. [**Setting up Keycloak**](#setting-up-keycloak)

    3.1. [Install the Keycloak provider JARs](#install-the-keycloak-provider-jars)

    3.2. [Start Keycloak](#start-keycloak)

    3.3. [Bootstrap the realm](#bootstrap-the-realm)

    3.4. [Create the initial superuser](#create-the-initial-superuser)

    3.5. [Register the IAM admin application](#register-the-iam-admin-application)

4. [**Creating the demo function**](#creating-the-demo-function)

5. [**Registering the Demo Service**](#registering-the-demo-service)

6. [**Registering the Demo Application**](#registering-the-demo-application)

7. [**Giving the Demo Application the demo function**](#giving-the-demo-application-the-demo-function)

8. [**Starting the Demo Application and the Demo Service**](#starting-the-demo-application-and-the-demo-service)

9. [**Using the demo**](#using-the-demo)

10. [**Running the demo applications from source**](#running-the-demo-applications-from-source)

---

<a name="the-local-environment"></a>
## 1. The local environment

The local environment is the IAM system running on your own machine: a Keycloak instance with
the provider plugins built from this repository, a realm configured for the organizational
rights model, a Postgres database, and the IAM admin application on top of it. It is what you
develop the IAM admin application against, and it is where a realm can be set up, changed and
inspected without touching a shared installation.

The Demo Application and the Demo Service are part of the environment too. They give the IAM
admin application something to administer, and they show how an integrating application uses
the rights granted there:

- **Demo Application** (`demo/demo-app`, port 16990), an OIDC relying party and OAuth client
  scoped to the function `demo`. It logs the user in, acts on the `org_rights` claim from the ID
  token, calls the Demo Service with org-scoped access tokens, and offers a
  **Delegate administration** button into the IAM admin application.

- **Demo Service** (`demo/demo-service`, port 16995), a pure OAuth resource server. It keeps an
  organization's contact data in memory and validates the audience and the
  `{orgId}:{function}:{right}` scopes of every access token it receives.

The patterns the two of them illustrate are described in the
[IAM Integration Guide](iam-integration-guide.md), the relying party in Section 2, the OAuth
client in Section 3, the resource server in Section 4 and delegated administration in
Section 5.

Everything runs as containers from `compose/docker-compose.yml`:

| Service | Container | URL |
| :--- | :--- | :--- |
| Keycloak | `keycloak` | https://local.dev.swedenconnect.se:17000 |
| IAM Admin Application | `iam-admin-app` | https://local.dev.swedenconnect.se:17005 |
| Demo Application | `iam-demo-app` | https://local.dev.swedenconnect.se:16990 |
| Demo Service | `iam-demo-service` | https://local.dev.swedenconnect.se:16995 |

See [compose/README.md](../compose/README.md) for the full service reference, including the
Postgres database and the configuration directory of each service.

---

<a name="prerequisites"></a>
## 2. Prerequisites

- Docker and Docker Compose.
- `curl` and `python3` on the host. The Keycloak scripts under `compose/keycloak-scripts/`
  run on the host and call the Keycloak Admin REST API, rather than running inside a
  container.
- Java and Maven, for building the service images and the Keycloak provider JARs.

<a name="hosts-file"></a>
### 2.1. Hosts file

Every service is published under `local.dev.swedenconnect.se`, which is the name the
certificates are issued for. Add a mapping from `127.0.0.1` to that name in your computer's
hosts file:

```
#
# Host Database
#
127.0.0.1       localhost
255.255.255.255 broadcasthost
::1             localhost

#
# Sweden Connect
#
127.0.0.1       local.dev.swedenconnect.se
```

<a name="building-the-service-images"></a>
### 2.2. Building the service images

Three of the services in the compose file are built from this repository rather than pulled
from a registry: `iam-admin-app`, `iam-demo-app` and `iam-demo-service`. The images are
produced by the Jib Maven plugin straight into your local Docker daemon, so there is no
Dockerfile and no compose build context.

```bash
./compose/build-services.sh
```

Run this before the first `docker compose up`, and again whenever Java or TypeScript source
changes. The script is safe to re-run.

Build the service images first, then install the provider JARs as described below, then start
the environment.

---

<a name="setting-up-keycloak"></a>
## 3. Setting up Keycloak

The steps in this section must be carried out in the order given. The admin login for the
local Keycloak instance is:

- User: `admin`
- Password: `keycloak`

The scripts under `compose/keycloak-scripts/` are wrappers over the single implementation in
`keycloak/scripts/`: they add the compose Keycloak URL and its CA certificate
(`compose/config/common/tls.crt`) and pass everything else through. Use them here for the
shorter command line, and call `keycloak/scripts/*.sh` directly against any other Keycloak.
See [compose/keycloak-scripts/README.md](../compose/keycloak-scripts/README.md) for the
prerequisites and [keycloak/scripts/README.md](../keycloak/scripts/README.md) for every option
each script takes. All of them are idempotent and safe to re-run.

<a name="install-the-keycloak-provider-jars"></a>
### 3.1. Install the Keycloak provider JARs

The Keycloak instance requires the provider JARs to be present in
`compose/config/keycloak/spi/` before it is started. Run the install script from anywhere in
the repository:

```bash
./compose/keycloak-scripts/install-keycloak-plugins.sh
```

The script builds the Keycloak plugin modules with Maven, which produces the
[distribution ZIP](../keycloak/README.md#plugin-distribution), and copies the JARs from that
ZIP into `compose/config/keycloak/spi/`. The directory therefore ends up holding exactly the
providers of the current build. The external `oidc-sweden-claims-plugin` is one of them,
resolved as a dependency of the distribution module rather than downloaded by the script. A
failed build stops the script, so the SPI directory is never filled from an older build. Re-run
the script whenever a plugin changes, and restart Keycloak afterwards to load the new JARs. See
[Provider JARs](../keycloak/README.md#provider-jars) for the full provider set.

*The local plugin directory defaults to `keycloak/` at the repository root. Override by
setting `KEY_CLOAK_PLUGIN_DIR` if your checkout layout differs.*

<a name="start-keycloak"></a>
### 3.2. Start Keycloak

Start at least the `keycloak` service. Other services may be brought up at the same time, but
they are of no use until the realm has been bootstrapped:

```bash
docker compose -f compose/docker-compose.yml up keycloak
```

or as a daemon:

```bash
docker compose -f compose/docker-compose.yml up -d keycloak
```

Wait for Keycloak to finish its startup before proceeding, in another shell if it was not
started as a daemon.

<a name="bootstrap-the-realm"></a>
### 3.3. Bootstrap the realm

Create the realm with all required base configuration:

```bash
./compose/keycloak-scripts/bootstrap-realm.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --display-name "Organizations and Users IAM"
```

This creates the realm, the top-level groups, the roles, the OIDC Sweden client scopes with
their protocol mappers, and the OIDC Sweden user profile groups and attributes. The
`oidc-sweden-claims-plugin` JAR only registers the protocol mapper types, so everything a realm
needs is put there by this script. See [docs/keycloak-setup.md](keycloak-setup.md) for what the
realm configuration means.

<a name="create-the-initial-superuser"></a>
### 3.4. Create the initial superuser

Create the first user with the `superuser` role, so that the IAM admin application can be
accessed:

```bash
./compose/keycloak-scripts/create-admin-user.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --new-username diggadmin \
    --new-password changeme
```

Any name or other attribute on this user is set by hand afterwards, in the Keycloak Admin
Console at https://local.dev.swedenconnect.se:17000 under **Users → diggadmin → Details**.

<a name="register-the-iam-admin-application"></a>
### 3.5. Register the IAM admin application

The IAM admin application is an OIDC client, a resource server for its `/iam-api` endpoints,
and a client handling every function. `add-iam-admin-app.sh` registers all three roles; use it
rather than `add-oidc-client.sh`, which gives only the first. See
[Registering a Client](registering-a-client.md#the-iam-admin-application) for what the roles
mean.

```bash
./compose/keycloak-scripts/add-iam-admin-app.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://local.dev.swedenconnect.se:17005 \
    --name "IAM Admin"
```

The redirect URI defaults to `/login/oauth2/code/*` and the root URL to the client ID, so
neither needs to be given. The JWKS URL defaults to
`https://local.dev.swedenconnect.se:17005/jwks` and is registered in Keycloak at this point.
Keycloak only fetches it when the first token request is made, so the application does not need
to be running during registration. A service account with `realm-management` roles is always
created, because the application administers the realm through the Keycloak Admin API.

Re-run the script to bring an application that was registered with `add-oidc-client.sh` alone
up to the full shape.

---

<a name="creating-the-demo-function"></a>
## 4. Creating the demo function

The function `demo` has to exist before any client can be given it, so it is created before the
demo clients are registered. Functions are created in the IAM admin application, not by a
script.

Start the IAM admin application (Keycloak must also be running):

```bash
docker compose -f compose/docker-compose.yml up -d iam-admin-app
```

or

```bash
docker compose -f compose/docker-compose.yml up keycloak iam-admin-app
```

Open https://local.dev.swedenconnect.se:17005 and log in as the superuser created in
Section 3.4 (`diggadmin`). Go to **Functions**, create a function with the identifier `demo`,
and give it the Swedish name `Demo` and the English name `Demo`.

Only the function is created at this point. Organizations, attaching the function to an
organization and granting rights come later, in Section 9, once the clients are in place.

---

<a name="registering-the-demo-service"></a>
## 5. Registering the Demo Service

The Demo Service is a resource server. It never requests tokens of its own, so it is registered
with `add-resource-server.sh` rather than `add-oidc-client.sh`:

```bash
./compose/keycloak-scripts/add-resource-server.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://local.dev.swedenconnect.se:16995 \
    --name "Demo Service" \
    --functions demo
```

This creates a Keycloak client with all flows disabled, no service account and no Authorization
Services. It exists so that access tokens can carry it as the `aud` claim through the OAuth2
`resource` parameter (RFC 8707).

`--functions demo` restricts the Demo Service to the function `demo`. The `resource-aud-plugin`
checks at token issuance time that the function in the requested scope is one the resource
server declares, and rejects the token request with `invalid_target` if it is not.

---

<a name="registering-the-demo-application"></a>
## 6. Registering the Demo Application

The Demo Application is registered as an OIDC client:

```bash
./compose/keycloak-scripts/add-oidc-client.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://local.dev.swedenconnect.se:16990 \
    --name "Demo App" \
    --redirect-uri '/login/oauth2/code/*' \
    --redirect-uri '/callback/oauth2/code/*' \
    --no-org-rights-access-token
```

Both redirect URIs are needed. The Demo Application logs the user in on
`/login/oauth2/code/*`, and obtains its access tokens for the Demo Service and for the IAM
Service API on `/callback/oauth2/code/*`. Keycloak rejects a redirect it has not been given, so
registering only the first pattern makes every API token request fail. Section 3.5 of the
[IAM Integration Guide](iam-integration-guide.md#separating-oidc-and-oauth2-callbacks) explains
why the two flows use separate callback paths.

`--no-org-rights-access-token` leaves `org_rights` out of the access token. The Demo
Application takes its UI decisions from the ID token, and requests org-scoped access tokens
separately for its calls to the Demo Service.

The script sets `iam_admin_managed=true` and `iam_admin_oidc_client=true` on the client itself,
so no further step is needed to make the IAM admin application administer it.
`set-iam-admin-managed.sh` is for clients that were registered by other means.

---

<a name="giving-the-demo-application-the-demo-function"></a>
## 7. Giving the Demo Application the demo function

`add-oidc-client.sh` does not take functions, so the Demo Application is given `demo` with
`add-function.sh`, which appends to `client_functions` rather than replacing it:

```bash
./compose/keycloak-scripts/add-function.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://local.dev.swedenconnect.se:16990 \
    --function demo
```

The function has to exist already, which it does, since it was created in Section 4.

---

<a name="starting-the-demo-application-and-the-demo-service"></a>
## 8. Starting the Demo Application and the Demo Service

Start the two demo containers:

```bash
docker compose -f compose/docker-compose.yml up -d iam-demo-app iam-demo-service
```

The whole environment can also be brought up at once with
`docker compose -f compose/docker-compose.yml up -d`, once the steps above have been carried
out.

---

<a name="using-the-demo"></a>
## 9. Using the demo

### Preparing an organization

In the IAM admin application at https://local.dev.swedenconnect.se:17005, logged in as the
superuser:

1. Go to **Organizations** and create or select an organization. An organization is identified
   by its ten-digit Swedish organizational number, for example `2021006883`.

2. On the organization's detail page, click **Attach function** and select `demo`. The IAM
   admin application creates the three Keycloak scopes `{orgId}:demo:read`, `{orgId}:demo:write`
   and `{orgId}:demo:admin` together with their Authorization Services policies, on every client
   holding the OIDC client role that handles `demo`. The Demo Application already declares
   `demo` from Section 7, so its scopes are created at this point and no reconciliation step is
   needed.

3. Go to **Users** and select or create the user who will log in to the demo. Grant that user a
   right on `demo` for the organization, for example `write`.

### Logging in to the Demo Application

Open https://local.dev.swedenconnect.se:16990 and click **Log in** to authenticate through
Keycloak. After a successful login the application displays the organization's name, the
user's right level, and a contact data card. Changes to the address, telephone number and email
address are saved to the Demo Service using an access token scoped to `{orgId}:demo:write`, so
a user holding only `read` can see the card but not save it.

### Delegating administration

Click **Delegate administration** to open the IAM admin application. If the user is already
authenticated in the same Keycloak realm, no re-authentication prompt is shown.

The Demo Application passes `func=demo` in the redirect URL, so the IAM admin application
restricts the session to the function `demo`. The administrator can manage user rights for
`demo` only, not for other functions that may be attached to the same organization, and the
function management page is not available in a function-restricted session. To use the IAM
admin application without that restriction, log in directly at
https://local.dev.swedenconnect.se:17005.

---

<a name="running-the-demo-applications-from-source"></a>
## 10. Running the demo applications from source

The containers are the way the demo is run. When working on the demo code itself, the two
applications can instead be started from source with the `local` Spring profile, which enables
TLS, sets the port, and points at the local Keycloak instance:

```bash
# Terminal 1: Demo Service (port 16995)
./demo/scripts/start-demo-service.sh

# Terminal 2: Demo Application (port 16990)
./demo/scripts/start-demo-app.sh
```

Each script runs `mvn spring-boot:run` with `-Dspring-boot.run.profiles=local` and the
truststore the local certificates are issued under. Stop the corresponding container first, as
both bind the same port:

```bash
docker compose -f compose/docker-compose.yml stop iam-demo-app iam-demo-service
```

Keycloak and the IAM admin application keep running as containers either way.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
