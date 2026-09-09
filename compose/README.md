
# Organizations and Users IAM – Local Development Environment

Docker compose scripts for starting the Sweden Connect Organizations and Users IAM services locally.

---

## Prerequisites

The following prerequisites are needed for running the scripts:

- Docker and Docker Compose.
- `curl` and `python3` on the host. The Keycloak scripts under
  `compose/keycloak-scripts/` run on the host and call the Keycloak Admin REST API, rather
  than running inside a container.
- Java and Maven, for building the service images and the Keycloak provider JARs.

### Hosts File

- Edit your computer's hosts-file to contain a mapping from `127.0.0.1` to `local.dev.swedenconnect.se`.

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

### Building the service images

Three of the services in the compose file are built from this repository rather than pulled from a registry: `iam-admin-app`, `iam-demo-app` and `iam-demo-service`. The images are produced by the Jib Maven plugin straight into your local Docker daemon, so there is no Dockerfile and no compose build context.

```bash
./compose/build-services.sh
```

Run this before the first `docker compose up`, and again whenever Java or TypeScript source changes. The script is safe to re-run.

The Keycloak provider JARs are a separate step, handled by `compose/keycloak-scripts/install-keycloak-plugins.sh`, which builds them and installs them from the [distribution ZIP](../keycloak/README.md#plugin-distribution); see [Bootstrap of Keycloak](#bootstrap-of-keycloak) below. Build the service images first, then install the provider JARs, then start the environment.

### Access to GitHub's Docker Registry

Some images that are used by the Docker Compose script are available from GitHub's Docker Registry located at `ghcr.io`. In order to access this registry you need to logon before running the docker compose commands.

It is recommended that you assign the following environment variables:

- `GITHUB_USER` - Your GitHub username.

- `GITHUB_ACCESS_TOKEN` - Your GitHub access token, see [Authenticating to the Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry#authenticating-to-the-container-registry).

You can then execute the following command to authenticate:

```
echo $GITHUB_ACCESS_TOKEN | docker login ghcr.io -u $GITHUB_USER --password-stdin
```

**Note:** As things stand no image in `compose/docker-compose.yml` is pulled from `ghcr.io`, so this login is not needed. It will be again if a service that is pulled from the registry is added to the compose file.

<a name="bootstrap-of-keycloak"></a>
### Bootstrap of Keycloak

Before starting the environment for the first time you need to install the Keycloak provider JARs and bootstrap the realm. The steps must be carried out in the order below.

The admin login for the test instance is:

- User: `admin`
- Password: `keycloak`

#### Step 1: Install Keycloak provider JARs

The Keycloak instance requires the provider JARs to be present in `compose/config/keycloak/spi/` before it is started. Run the install script from anywhere in the repository. It builds the plugin modules and unpacks the [distribution ZIP](../keycloak/README.md#plugin-distribution) the build produced, so the directory ends up holding exactly the providers of the current build:

```bash
./compose/keycloak-scripts/install-keycloak-plugins.sh
```

The script builds the Keycloak plugin modules with Maven, which produces the distribution ZIP,
and copies the JARs from that ZIP into `compose/config/keycloak/spi/`. The external
`oidc-sweden-claims-plugin` is one of them, resolved as a dependency of the distribution module
rather than downloaded by the script. A failed build stops the script, so the SPI directory is
never filled from an older build. Re-run the script whenever a plugin changes, and restart
Keycloak afterwards to load the new JARs. See
[Provider JARs](../keycloak/README.md#provider-jars) for the full provider set.

*The local plugin directory defaults to `keycloak/` at the repository root. Override by
setting `KEY_CLOAK_PLUGIN_DIR` if your checkout layout differs.*

#### Step 2: Start the Keycloak service

Start at least the `keycloak` service (other services may be brought up at the same time):

```bash
docker compose -f compose/docker-compose.yml up keycloak
```

or as a daemon:

```bash
docker compose -f compose/docker-compose.yml up -d keycloak
```

Wait for Keycloak to finish its startup before proceeding (in another shell if not started as a daemon).

#### Step 3: Bootstrap the realm

Create the realm with all required base configuration:

```bash
./compose/keycloak-scripts/bootstrap-realm.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --display-name "Organizations and Users IAM"
```

This creates the realm, top-level groups, roles, the OIDC Sweden client scopes with their
protocol mappers, and the OIDC Sweden user profile groups and attributes. The
`oidc-sweden-claims-plugin` JAR only registers the protocol mapper types, so everything a
realm needs is put there by this script. The script is idempotent and safe to re-run. See
[compose/keycloak-scripts/README.md](keycloak-scripts/README.md) for full option
reference.

#### Step 4: Create the initial admin user

Create the first user with the `superuser` role so that the IAM admin application can
be accessed:

```bash
./compose/keycloak-scripts/create-admin-user.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --new-username diggadmin \
    --new-password changeme
```

Next, log in to the Keycloak Admin Console at https://local.dev.swedenconnect.se:17000 and set any desired name and other attributes manually under
**Users → diggadmin → Details**.

#### Step 5: Register clients

Register the IAM admin application client:

```bash
./compose/keycloak-scripts/add-oidc-client.sh \
    --realm orgiam \
    --username admin \
    --password keycloak \
    --client-id https://local.dev.swedenconnect.se:17005 \
    --name "IAM Admin" \
    --redirect-uri '/login/oauth2/code/*' \
    --service-account
```

The JWKS URL defaults to `https://local.dev.swedenconnect.se:17005/jwks` and is registered in
Keycloak at this point. Keycloak only fetches it when the first token request is made,
so the application does not need to be running during registration.

*For clients registered outside of `add-oidc-client.sh`, use `set-iam-admin-managed.sh`
to mark them as IAM-admin-managed, and `set-iam-admin-resource-server.sh` to mark them as
resource servers. The two roles are independent, so a client may be given both. See
[compose/keycloak-scripts/README.md](keycloak-scripts/README.md) for details.*

The scripts under `compose/keycloak-scripts/` are wrappers over the single implementation in
`keycloak/scripts/`: they add the compose Keycloak URL and its CA certificate
(`compose/config/common/tls.crt`) and pass everything else through. Use them here for the
shorter command line, and call `keycloak/scripts/*.sh` directly against any other Keycloak.
See [compose/keycloak-scripts/README.md](keycloak-scripts/README.md) for the prerequisites
and [keycloak/scripts/README.md](../keycloak/scripts/README.md) for every option each script
takes.

## Services

<a name="base-services"></a>
### Base Services

Services under the Base Services section are services that are used by our applications such
as databases, etc. Applications may thus share these instances within the Docker compose
script.

Base services should use host ports in the range: `16900-17000`.

<a name="postgres-db"></a>
#### Postgres DB

Postgres Database.

**Ports:**

- `16905`: Postgres port

**Configuration directory:** [config/postgres](config/postgres)

## Keycloak

Sweden Connect IAM Keycloak instance.

**Port range:** `17000-17004`

**Ports:**

- `17000` - KeyCloak HTTPS port.

**URL:s**

- https://local.dev.swedenconnect.se:17000 - Admin page

  - User: `admin`
  - Password: `keycloak`

**Configuration directory:** [config/keycloak](config/keycloak)

**Note:** See [Bootstrap of Keycloak](#bootstrap-of-keycloak) for how to setup Keycloak before starting the environment.

## Organization and User Admin Application

IAM Admin Application for managing organizations, users, and group memberships.

**Port range:** `17005-17009`

**Ports:**

- `17005` - IAM Admin Application HTTPS port.

**Image:** `iam-admin-app:latest`, built locally, see [Building the service images](#building-the-service-images).

**URL:s**

- https://local.dev.swedenconnect.se:17005 - IAM Admin Application

**Configuration directory:** [config/iam-admin-app](config/iam-admin-app)

## Demo Application (OIDC client/OAuth 2.0 client)

Demo application that simulates an app that integrates against the IAM app and Keycloak.

**Ports:**

- `16990` - Application HTTPS port.

**Image:** `iam-demo-app:latest`, built locally, see [Building the service images](#building-the-service-images).

**URL:s**

- https://local.dev.swedenconnect.se:16990 - Demo Application

**Configuration directory:** [config/iam-demo-app](config/iam-demo-app)

## Demo Service (OIDC Protected Resource)

Demo service that simulates a resource server (API) that is used by the demo application.

**Ports:**

- `16995` - Application HTTPS port.

**Image:** `iam-demo-service:latest`, built locally, see [Building the service images](#building-the-service-images).

**URL:s**

- https://local.dev.swedenconnect.se:16995 - Demo Service

**Configuration directory:** [config/iam-demo-service](config/iam-demo-service)
