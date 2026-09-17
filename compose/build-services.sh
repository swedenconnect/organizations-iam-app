#!/usr/bin/env bash
#
# Build the Docker images for the services consumed by compose/docker-compose.yml.
#
# Compose references local image tags (iam-admin-app, iam-demo-app and
# iam-demo-service) that are not pulled from any registry, so they must exist in
# the local Docker daemon before `docker compose up`. This script produces them
# via the Jib Maven plugin, straight into the daemon. There is no Dockerfile and
# no compose build context.
#
# The whole reactor is compiled and installed up front with a single
# `mvn -DskipTests clean install` at the repository root, *then* Jib builds each
# image. Building everything first is what guarantees Jib packages current
# artifacts: the two backends unpack a sibling frontend module's `dist` zip into
# target/classes/static during process-resources, and that zip has to be present
# in the local Maven repository before the backend is built. A per-module
# `-pl <module> -am` build would leave a stale or missing frontend in the image.
#
# Idempotent: re-running rebuilds the same tags; Jib is content addressable, so
# unchanged layers are reused.
#
# To add a new service, append its Maven module path to SERVICES below and add
# the module -> image-name line to the case block near the end. The root build
# already compiles every module, so a new service needs no extra build wiring.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Maven modules (relative to the repository root) that ship a local Docker image.
SERVICES=(
  "iam-admin-app/backend"
  "demo/demo-app/backend"
  "demo/demo-service"
)

# Build for the host architecture rather than the amd64 default, which would
# produce an emulated image on Apple Silicon.
case "$(uname -m)" in
  arm64 | aarch64) ARCH="arm64" ;;
  *)               ARCH="amd64" ;;
esac

echo "Repo root:    ${REPO_ROOT}"
echo "Architecture: ${ARCH}"

# Compile and install the entire reactor first. Output goes to a log to keep the
# console readable; on failure the log is printed so nothing is swallowed. Tests
# are skipped, this is a packaging step and not a test run.
echo
echo "Building all modules (mvn -DskipTests clean install) ..."
build_log="$(mktemp -t build-services.XXXXXX.log)"
if ! (cd "${REPO_ROOT}" && mvn -DskipTests clean install) > "${build_log}" 2>&1; then
  echo "  Maven build FAILED - full output follows:" >&2
  cat "${build_log}" >&2
  rm -f "${build_log}"
  exit 1
fi
rm -f "${build_log}"

for module in "${SERVICES[@]}"; do
  echo
  echo "==> Building image for ${module}"
  # Run the Jib local execution from the module directory so the goal targets
  # only this project. The reactor contains modules with no main class that
  # would fail under jib:dockerBuild.
  (cd "${REPO_ROOT}/${module}" && mvn "-Djib.local.architecture=${ARCH}" jib:dockerBuild@local)
done

echo
echo "Done. Listing produced images:"
docker images --format 'table {{.Repository}}:{{.Tag}}\t{{.ID}}\t{{.Size}}' | head -n 1
for module in "${SERVICES[@]}"; do
  case "${module}" in
    iam-admin-app/backend) image="iam-admin-app" ;;
    demo/demo-app/backend) image="iam-demo-app" ;;
    demo/demo-service)     image="iam-demo-service" ;;
    *)                     image="${module}" ;;
  esac
  docker images --format 'table {{.Repository}}:{{.Tag}}\t{{.ID}}\t{{.Size}}' \
    | grep "^${image}:" || true
done
