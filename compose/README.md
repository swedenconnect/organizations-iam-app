
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

Before the environment can be used, the Keycloak provider JARs have to be installed, the realm
bootstrapped, the first superuser created, and the IAM admin application and the demo clients
registered.

[Local environment and Demo application](../docs/local-environment.md) is the step-by-step
description of all of that, taking a fresh checkout to a running demo, and it repeats the
prerequisites and the image build above so it can be followed on its own.

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
