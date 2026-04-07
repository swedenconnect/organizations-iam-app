#!/usr/bin/env bash
#
# Copyright 2026 Sweden Connect
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# bootstrap-realm.sh
#
# Wrapper script. Runs the inner bootstrap-realm.sh script inside the
# Docker Compose keycloak-setup service.
#
# Usage:
#   ./bootstrap-realm.sh --realm <realm> --username <username> --password <password> [--display-name <name>]

set -euo pipefail

REALM=""
USERNAME=""
PASSWORD=""
DISPLAY_NAME=""

usage() {
  echo "Usage: $0 --realm <realm> --username <username> --password <password> [--display-name <name>]" >&2
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)
      cat <<EOF
Bootstrap a Keycloak realm with groups, roles, client scopes, user profile
attributes, and client policies required by the IAM system.

Usage: $0 --realm <realm> --username <username> --password <password> [--display-name <name>]

Options:
  --realm <realm>              Keycloak realm name to create
  --username <username>        Admin username for Keycloak master realm
  --password <password>        Admin password for Keycloak master realm
  --display-name <name>        Realm display name (defaults to the realm name)
  --help, -h                   Show this help message
EOF
      exit 0
      ;;
    --realm)         REALM="$2";        shift 2 ;;
    --username)      USERNAME="$2";     shift 2 ;;
    --password)      PASSWORD="$2";     shift 2 ;;
    --display-name)  DISPLAY_NAME="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

if [ -z "${REALM}" ] || [ -z "${USERNAME}" ] || [ -z "${PASSWORD}" ]; then
  echo "Error: --realm, --username and --password are required." >&2
  usage
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ARGS=("${REALM}" "${USERNAME}" "${PASSWORD}")
if [ -n "${DISPLAY_NAME}" ]; then
  ARGS+=("${DISPLAY_NAME}")
fi

docker compose -f "${COMPOSE_DIR}/docker-compose.yml" run --rm keycloak-setup \
  /scripts/bootstrap-realm.sh "${ARGS[@]}"
