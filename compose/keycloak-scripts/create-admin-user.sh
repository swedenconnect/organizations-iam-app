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

set -euo pipefail

REALM=""
USERNAME=""
PASSWORD=""
NEW_USERNAME=""
NEW_PASSWORD=""
EMAIL=""

usage() {
  echo "Usage: $0 --realm <realm> --username <admin-username> --password <admin-password>" >&2
  echo "          --new-username <username> --new-password <password> [--email <email>]" >&2
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)
      cat <<EOF
Create a user in the specified Keycloak realm and assign the superuser role.

Usage: $0 --realm <realm> --username <admin-username> --password <admin-password>
          --new-username <username> --new-password <password> [--email <email>]

Options:
  --realm <realm>              Keycloak realm name
  --username <username>        Admin username for Keycloak master realm
  --password <password>        Admin password for Keycloak master realm
  --new-username <username>    Username for the new user
  --new-password <password>    Password for the new user
  --email <email>              Email address for the new user (optional)
  --help, -h                   Show this help message
EOF
      exit 0
      ;;
    --realm)        REALM="$2";        shift 2 ;;
    --username)     USERNAME="$2";     shift 2 ;;
    --password)     PASSWORD="$2";     shift 2 ;;
    --new-username) NEW_USERNAME="$2"; shift 2 ;;
    --new-password) NEW_PASSWORD="$2"; shift 2 ;;
    --email)        EMAIL="$2";        shift 2 ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

if [ -z "${REALM}" ] || [ -z "${USERNAME}" ] || [ -z "${PASSWORD}" ] \
    || [ -z "${NEW_USERNAME}" ] || [ -z "${NEW_PASSWORD}" ]; then
  echo "Error: --realm, --username, --password, --new-username and --new-password are required." >&2
  usage
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ARGS=("${REALM}" "${USERNAME}" "${PASSWORD}" "${NEW_USERNAME}" "${NEW_PASSWORD}")
if [ -n "${EMAIL}" ]; then
  ARGS+=("${EMAIL}")
fi

docker compose -f "${COMPOSE_DIR}/docker-compose.yml" run --rm keycloak-setup \
  /scripts/create-admin-user.sh "${ARGS[@]}"
