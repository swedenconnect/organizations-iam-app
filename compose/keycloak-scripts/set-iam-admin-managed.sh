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
# set-iam-admin-managed.sh
#
# Wrapper script. Runs the inner set-iam-admin-managed.sh script inside the
# Docker Compose keycloak-setup service.
#
# Usage:
#   ./set-iam-admin-managed.sh --realm <realm> --client-id <clientId> --username <username> --password <password>

set -euo pipefail

REALM=""
CLIENT_ID=""
USERNAME=""
PASSWORD=""

usage() {
  echo "Usage: $0 --realm <realm> --client-id <clientId> --username <username> --password <password>" >&2
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)
      cat <<EOF
Mark a Keycloak client as managed by the IAM admin application by setting
the iam_admin_managed=true client attribute.

Usage: $0 --realm <realm> --client-id <clientId> --username <username> --password <password>

Options:
  --realm <realm>              Keycloak realm name
  --client-id <clientId>       Client ID of the target client
  --username <username>        Admin username for Keycloak master realm
  --password <password>        Admin password for Keycloak master realm
  --help, -h                   Show this help message
EOF
      exit 0
      ;;
    --realm)      REALM="$2";     shift 2 ;;
    --client-id)  CLIENT_ID="$2"; shift 2 ;;
    --username)   USERNAME="$2";  shift 2 ;;
    --password)   PASSWORD="$2";  shift 2 ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

if [ -z "${REALM}" ] || [ -z "${CLIENT_ID}" ] || [ -z "${USERNAME}" ] || [ -z "${PASSWORD}" ]; then
  echo "Error: --realm, --client-id, --username and --password are required." >&2
  usage
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

docker compose -f "${COMPOSE_DIR}/docker-compose.yml" run --rm keycloak-setup \
  /scripts/set-iam-admin-managed.sh "${REALM}" "${CLIENT_ID}" "${USERNAME}" "${PASSWORD}"
