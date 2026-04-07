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

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<EOF
Create a user in the specified Keycloak realm and assign the superuser role.

Usage: $0 <realm> <admin-username> <admin-password> <new-username> <new-password> [email]

Positional arguments:
  realm            Keycloak realm name
  admin-username   Admin username for Keycloak master realm
  admin-password   Admin password for Keycloak master realm
  new-username     Username for the new user
  new-password     Password for the new user
  email            Email address for the new user (optional)

Note: This is the inner script invoked by the outer wrapper. Use the
wrapper script in compose/keycloak-scripts/ which accepts named arguments.
EOF
  exit 0
fi

if [ $# -lt 5 ]; then
  echo "Usage: $0 <realm> <admin-username> <admin-password> <new-username> <new-password> [email]" >&2
  exit 1
fi

REALM="$1"
ADMIN_USERNAME="$2"
ADMIN_PASSWORD="$3"
NEW_USERNAME="$4"
NEW_PASSWORD="$5"
EMAIL="${6:-}"

KCADM="/opt/keycloak/bin/kcadm.sh"
SERVER_URL="http://keycloak:8080"

# ---------------------------------------------------------------------------
# Authenticate
# ---------------------------------------------------------------------------

echo "==> Authenticating as '${ADMIN_USERNAME}'..."
"${KCADM}" config credentials \
  --server "${SERVER_URL}" \
  --realm master \
  --user "${ADMIN_USERNAME}" \
  --password "${ADMIN_PASSWORD}"

# ---------------------------------------------------------------------------
# 1. Create user (idempotent)
# ---------------------------------------------------------------------------

echo "==> Looking up user '${NEW_USERNAME}' in realm '${REALM}'..."
USERS_TMP=$(mktemp)
( "${KCADM}" get users -r "${REALM}" -q "username=${NEW_USERNAME}" --fields id,username ) > "${USERS_TMP}" 2>&1 || true
USER_ID=$(grep -A1 "\"username\" : \"${NEW_USERNAME}\"" "${USERS_TMP}" \
  | grep '"id"' \
  | sed 's/.*: "\(.*\)".*/\1/' || true)
rm -f "${USERS_TMP}"

if [ -n "${USER_ID}" ]; then
  echo "    User '${NEW_USERNAME}' already exists (ID: ${USER_ID}) — skipping creation."
else
  echo "==> Creating user '${NEW_USERNAME}'..."
  CREATE_ARGS=(
    -s "username=${NEW_USERNAME}"
    -s "enabled=true"
  )
  if [ -n "${EMAIL}" ]; then
    CREATE_ARGS+=(-s "email=${EMAIL}")
  fi
  CREATE_OUTPUT=$("${KCADM}" create users -r "${REALM}" "${CREATE_ARGS[@]}" 2>&1 || true)
  echo "${CREATE_OUTPUT}"
  USER_ID=$(echo "${CREATE_OUTPUT}" | sed "s/.*id '\\(.*\\)'.*/\\1/" || true)

  if [ -z "${USER_ID}" ]; then
    echo "ERROR: Could not resolve ID for newly created user '${NEW_USERNAME}'." >&2
    exit 1
  fi
  echo "    User created (ID: ${USER_ID})."
fi

# ---------------------------------------------------------------------------
# 2. Set password
# ---------------------------------------------------------------------------

echo "==> Setting password for '${NEW_USERNAME}'..."
"${KCADM}" set-password -r "${REALM}" \
  --username "${NEW_USERNAME}" \
  --new-password "${NEW_PASSWORD}"
echo "    Password set."

# ---------------------------------------------------------------------------
# 3. Assign superuser realm role
# ---------------------------------------------------------------------------

echo "==> Assigning 'superuser' realm role to '${NEW_USERNAME}'..."
"${KCADM}" add-roles \
  -r "${REALM}" \
  --uusername "${NEW_USERNAME}" \
  --rolename superuser
echo "    Role 'superuser' assigned."

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

echo ""
echo "==> User '${NEW_USERNAME}' is ready."
echo ""
echo "    Summary:"
echo "      Realm    : ${REALM}"
echo "      Username : ${NEW_USERNAME}"
if [ -n "${EMAIL}" ]; then
echo "      Email    : ${EMAIL}"
fi
echo "      Role     : superuser"
echo ""
