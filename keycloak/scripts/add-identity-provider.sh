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
# add-identity-provider.sh
#
# Register a SAML identity provider for eID login, together with everything
# needed to resolve the incoming identity to a pre-provisioned local user:
#
#   1. the identity provider itself
#   2. one stock Attribute Importer mapper per identifying attribute, carrying
#      it into the brokered context
#   3. a first broker login flow of exactly two executions, the attribute
#      matcher followed by Keycloak's "automatically set existing user"
#   4. that flow bound to the provider
#
# Users are matched, never created. A subject that does not resolve to exactly
# one permitted local user is refused.
#
# Uses the Keycloak Admin REST API (curl + python3)
# Safe to re-run: idempotent for the provider, the mapper, the flow and its
# executions.
#
# Usage: see --help

set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------

KC_URL=""
KC_BASE_PATH=""
KC_USER="admin"
KC_PASS=""
AUTH_CLIENT=""
AUTH_SECRET="${KC_AUTH_SECRET:-}"
AUTH_REALM=""
REALM=""

ALIAS="digg-proxy"
DISPLAY_NAME=""
IDP_ENTITY_ID=""
SSO_URL=""
SP_ENTITY_ID=""
SIGNING_CERT_FILE=""
ACRS=()
ACS_INDEX="0"
PRINCIPAL_ATTRIBUTE="uid"
TRANSIENT="false"
ENCRYPT_ASSERTIONS="true"
SIGNED_ASSERTIONS=""

TEMPLATE_REALM=""
TEMPLATE_ALIAS=""

FLOW_NAME="eID Broker"
MATCHES=()
DEFAULT_MATCH="personalIdentityNumber;samlAttribute=urn:oid:1.2.752.29.4.13;samlFormat=URI"

CACERT=""
INSECURE="false"

MATCHER_PROVIDER="idp-detect-existing-user-by-attr"
AUTOLINK_PROVIDER="idp-auto-link"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------

usage() {
  cat <<EOF
Register a SAML identity provider for eID login, with an attribute importer, a
first broker login flow that matches an existing user on that attribute, and
the flow bound to the provider. Users are matched, never created.
Uses the Keycloak Admin REST API.

Usage: $0 [OPTIONS]

Connection:
  --url <url>                   Keycloak base URL (e.g. https://keycloak.example.com)
  --base-path <path>            URL path prefix (e.g. /auth). Default: empty
  --realm <realm>               Target realm
  --username <username>         Admin username (default: admin)
  --password <password>         Admin password
  --auth-client <id>            Authenticate as this client with client_credentials
                                instead of a username and password
  --auth-secret <secret>        Client secret (also read from KC_AUTH_SECRET)
  --auth-realm <realm>          Realm to authenticate against. Defaults to the
                                target realm when --auth-client is given,
                                otherwise to master
  --cacert <file>               CA certificate file for TLS verification
  --insecure                    Skip TLS certificate verification (dev only)

Identity provider:
  --alias <alias>               Provider alias (default: ${ALIAS})
  --display-name <name>         Name shown on the login page
  --idp-entity-id <entityId>    Upstream IdP entityID
  --sso-url <url>               Upstream single sign-on service URL (HTTP-POST)
  --sp-entity-id <entityId>     Our SP entityID, as registered upstream
  --signing-certificate <file>  Upstream signing certificate (PEM or bare base64)
  --acr <uri>                   Requested authn context class ref (repeatable)
  --acs-index <n>               attributeConsumingServiceIndex (default: ${ACS_INDEX})
  --principal-attribute <name>  SAML friendly name used as the brokered
                                username (default: ${PRINCIPAL_ATTRIBUTE})
  --transient                   Set doNotStoreUsers, i.e. lightweight users.
                                Not compatible with matching, see below
  --no-encrypt-assertions       Do not request encrypted assertions
  --signed-assertions <bool>    Request signed assertions (true|false).
                                Default: left as the template or Keycloak has it

Copy the upstream parameters from an existing provider instead of giving them:
  --template-realm <realm>      Realm holding the provider to copy from
  --template-alias <alias>      Provider to copy from. Copies the upstream
                                entityID, SSO URL, signing certificate, ACRs
                                and the SAML hardening flags

Matching:
  --flow-name <name>            First broker login flow name (default: ${FLOW_NAME})
  --match <entry>               A local user attribute to match on, the SAML
                                attribute carrying it, and optional roles
                                (repeatable). The attributes are tried in the
                                order the options are given. An entry is
                                  <attribute>;samlAttribute=<name>
                                    [;samlFormat=URI|BASIC|UNSPECIFIED]
                                    [;requiredRole=<role>][;forbiddenRole=<role>]
                                with no spaces around ; and =. samlFormat
                                defaults to URI. Without --match:
                                  ${DEFAULT_MATCH}
  --help, -h                    Show this help message

  Example, regular users by personal identity number and superusers by EFOS-ID:

    --match "personalIdentityNumber;samlAttribute=urn:oid:1.2.752.29.4.13;forbiddenRole=superuser"
    --match "efosId;samlAttribute=urn:oid:1.2.3.4;requiredRole=superuser"

  --match-attribute, --saml-attribute, --saml-attribute-format, --required-role
  and --forbidden-role were removed. Use --match.

Notes:

  The SP entityID and the endpoint URLs Keycloak publishes are derived from the
  request host, so they are only consistent if the realm is reached on the host
  the upstream has registered. Give --sp-entity-id explicitly and check the
  published metadata at
  <url>/realms/<realm>/broker/<alias>/endpoint/descriptor before registering it.

  --transient and attribute matching are mutually exclusive: the Sweden Connect
  provider only copies SAML attributes onto the brokered context when
  doNotStoreUsers is set, while matching needs a persisted user to match
  against. With --transient the flow is not created and no mapper is added.

  The attribute format matters. Keycloak defaults to ATTRIBUTE_FORMAT_BASIC
  while Sweden Connect uses URI format, and a mismatch means the attribute is
  never found and every login is refused with a generic error.

  An Attribute Importer mapper for an attribute that is no longer given with
  --match is left in place and reported. It keeps copying its value into the
  brokered context; delete it in the Admin Console if that is not wanted.
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)                 usage; exit 0 ;;
    --url)                     KC_URL="$2";                shift 2 ;;
    --base-path)               KC_BASE_PATH="$2";          shift 2 ;;
    --realm)                   REALM="$2";                 shift 2 ;;
    --username)                KC_USER="$2";               shift 2 ;;
    --password)                KC_PASS="$2";               shift 2 ;;
    --auth-client)             AUTH_CLIENT="$2";           shift 2 ;;
    --auth-secret)             AUTH_SECRET="$2";           shift 2 ;;
    --auth-realm)              AUTH_REALM="$2";            shift 2 ;;
    --alias)                   ALIAS="$2";                 shift 2 ;;
    --display-name)            DISPLAY_NAME="$2";          shift 2 ;;
    --idp-entity-id)           IDP_ENTITY_ID="$2";         shift 2 ;;
    --sso-url)                 SSO_URL="$2";               shift 2 ;;
    --sp-entity-id)            SP_ENTITY_ID="$2";          shift 2 ;;
    --signing-certificate)     SIGNING_CERT_FILE="$2";     shift 2 ;;
    --acr)                     ACRS+=("$2");               shift 2 ;;
    --acs-index)               ACS_INDEX="$2";             shift 2 ;;
    --principal-attribute)     PRINCIPAL_ATTRIBUTE="$2";   shift 2 ;;
    --transient)               TRANSIENT="true";           shift ;;
    --no-encrypt-assertions)   ENCRYPT_ASSERTIONS="false"; shift ;;
    --signed-assertions)       SIGNED_ASSERTIONS="$2";     shift 2 ;;
    --template-realm)          TEMPLATE_REALM="$2";        shift 2 ;;
    --template-alias)          TEMPLATE_ALIAS="$2";        shift 2 ;;
    --flow-name)               FLOW_NAME="$2";             shift 2 ;;
    --match)                   MATCHES+=("$2");            shift 2 ;;
    --match-attribute|--saml-attribute|--saml-attribute-format|--required-role|--forbidden-role)
      echo "ERROR: $1 was removed. Give the attribute, the SAML attribute, its format and" >&2
      echo "       any roles together with --match, see --help." >&2
      exit 1 ;;
    --cacert)                  CACERT="$2";                shift 2 ;;
    --insecure)                INSECURE="true";            shift ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Interactive prompts for missing required values
# ---------------------------------------------------------------------------

if [ -z "${KC_URL}" ]; then
  read -r -p "Keycloak URL: " KC_URL
fi
if [ -z "${REALM}" ]; then
  read -r -p "Realm: " REALM
fi

if [ -n "${AUTH_CLIENT}" ]; then
  if [ -z "${AUTH_SECRET}" ]; then
    read -r -s -p "Client secret for '${AUTH_CLIENT}': " AUTH_SECRET
    echo ""
  fi
  if [ -z "${AUTH_REALM}" ]; then
    AUTH_REALM="${REALM}"
  fi
else
  if [ -z "${KC_USER}" ]; then
    read -r -p "Admin username: " KC_USER
  fi
  if [ -z "${KC_PASS}" ]; then
    read -r -s -p "Admin password: " KC_PASS
    echo ""
  fi
  if [ -z "${AUTH_REALM}" ]; then
    AUTH_REALM="master"
  fi
fi

if [ -z "${TEMPLATE_ALIAS}" ]; then
  if [ -z "${IDP_ENTITY_ID}" ]; then
    read -r -p "Upstream IdP entityID: " IDP_ENTITY_ID
  fi
  if [ -z "${SSO_URL}" ]; then
    read -r -p "Upstream single sign-on URL: " SSO_URL
  fi
fi
if [ -z "${SP_ENTITY_ID}" ]; then
  read -r -p "Our SP entityID: " SP_ENTITY_ID
fi
if [ -n "${TEMPLATE_ALIAS}" ] && [ -z "${TEMPLATE_REALM}" ]; then
  TEMPLATE_REALM="${REALM}"
fi

# ---------------------------------------------------------------------------
# Validate the --match entries before anything is changed
# ---------------------------------------------------------------------------

if [ ${#MATCHES[@]} -eq 0 ]; then
  MATCHES=("${DEFAULT_MATCH}")
fi

# One JSON object per entry, in order: attribute, samlAttribute, samlFormat, requiredRole, forbiddenRole
MATCH_JSON=$(python3 -c "
import json, sys

FORMATS = {'URI': 'ATTRIBUTE_FORMAT_URI', 'BASIC': 'ATTRIBUTE_FORMAT_BASIC',
           'UNSPECIFIED': 'ATTRIBUTE_FORMAT_UNSPECIFIED'}
KEYS = ('samlAttribute', 'samlFormat', 'requiredRole', 'forbiddenRole')

def fail(entry, why):
    sys.exit('ERROR: --match \\'%s\\': %s' % (entry, why))

entries, seen = [], set()
for entry in sys.argv[1:]:
    parts = entry.split(';')
    attribute = parts[0]
    if not attribute:
        fail(entry, 'no attribute name')
    if any(c.isspace() for c in attribute):
        fail(entry, 'spaces in or around the attribute name')
    if attribute in seen:
        fail(entry, 'attribute %s is given more than once' % attribute)
    seen.add(attribute)
    e = {'attribute': attribute}
    for part in parts[1:]:
        key, eq, value = part.partition('=')
        if not eq:
            fail(entry, '%s is not on the form key=value' % part)
        if any(c.isspace() for c in key) or (value and value != value.strip()):
            fail(entry, 'spaces around a separator')
        if key not in KEYS:
            fail(entry, 'unknown key %s' % key)
        if key in e:
            fail(entry, '%s given twice' % key)
        if not value:
            fail(entry, 'empty value for %s' % key)
        e[key] = value
    if 'samlAttribute' not in e:
        fail(entry, 'samlAttribute is missing')
    fmt = e.get('samlFormat', 'URI')
    if fmt not in FORMATS:
        fail(entry, 'samlFormat must be URI, BASIC or UNSPECIFIED')
    e['samlFormat'] = FORMATS[fmt]
    entries.append(e)
print(json.dumps(entries))
" "${MATCHES[@]}") || exit 1

if [ "${TRANSIENT}" = "true" ]; then
  echo ""
  echo "NOTE: --transient given, so no attribute importer and no first broker"
  echo "      login flow will be created. Lightweight users cannot be matched."
fi

# ---------------------------------------------------------------------------
# curl setup
# ---------------------------------------------------------------------------

CURL_OPTS=(-s)
if [ -n "${CACERT}" ]; then
  CURL_OPTS+=(--cacert "${CACERT}")
fi
if [ "${INSECURE}" = "true" ]; then
  CURL_OPTS+=(-k)
fi

ADMIN_BASE="${KC_URL}${KC_BASE_PATH}/admin/realms"
TOKEN_URL="${KC_URL}${KC_BASE_PATH}/realms/${AUTH_REALM}/protocol/openid-connect/token"

# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------

get_token() {
  if [ -n "${AUTH_CLIENT}" ]; then
    curl "${CURL_OPTS[@]}" -X POST "${TOKEN_URL}" \
      --data-urlencode "grant_type=client_credentials" \
      --data-urlencode "client_id=${AUTH_CLIENT}" \
      --data-urlencode "client_secret=${AUTH_SECRET}" \
      | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null
  else
    curl "${CURL_OPTS[@]}" -X POST "${TOKEN_URL}" \
      --data-urlencode "grant_type=password" \
      --data-urlencode "client_id=admin-cli" \
      --data-urlencode "username=${KC_USER}" \
      --data-urlencode "password=${KC_PASS}" \
      | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null
  fi
}

api_get() {
  curl "${CURL_OPTS[@]}" \
    -H "Authorization: Bearer ${TOKEN}" \
    "${ADMIN_BASE}${1}"
}

api_post() {
  local path="$1"
  local body="$2"
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -X POST \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-raw "${body}" \
    "${ADMIN_BASE}${path}"
}

api_put() {
  local path="$1"
  local body="$2"
  curl "${CURL_OPTS[@]}" -o /dev/null -w "%{http_code}" \
    -X PUT \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-raw "${body}" \
    "${ADMIN_BASE}${path}"
}

urlencode() {
  python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$1"
}

# ---------------------------------------------------------------------------
# Authenticate
# ---------------------------------------------------------------------------

echo ""
if [ -n "${AUTH_CLIENT}" ]; then
  echo "==> Authenticating as client '${AUTH_CLIENT}' in realm '${AUTH_REALM}'..."
else
  echo "==> Authenticating as '${KC_USER}' in realm '${AUTH_REALM}'..."
fi
TOKEN=$(get_token)
if [ -z "${TOKEN}" ]; then
  echo "ERROR: Failed to obtain an access token." >&2
  exit 1
fi
echo "    Token obtained."

# ---------------------------------------------------------------------------
# Preflight: the matcher must actually be deployed
# ---------------------------------------------------------------------------

if [ "${TRANSIENT}" = "false" ]; then
  echo "==> Checking that '${MATCHER_PROVIDER}' is available..."
  HAVE_MATCHER=$(api_get "/${REALM}/authentication/authenticator-providers" | python3 -c "
import sys, json
try:
    providers = json.load(sys.stdin)
except Exception:
    providers = []
print('yes' if any(p.get('id') == '${MATCHER_PROVIDER}' for p in providers) else 'no')
" 2>/dev/null || echo "no")
  if [ "${HAVE_MATCHER}" != "yes" ]; then
    echo "ERROR: authenticator '${MATCHER_PROVIDER}' is not registered in this Keycloak." >&2
    echo "       Deploy the idp-user-matcher provider JAR and rebuild Keycloak." >&2
    exit 1
  fi
  echo "    Available."
fi

# ---------------------------------------------------------------------------
# Optional: take the upstream parameters from an existing provider
# ---------------------------------------------------------------------------

TEMPLATE_JSON="{}"
if [ -n "${TEMPLATE_ALIAS}" ]; then
  echo "==> Copying upstream parameters from '${TEMPLATE_REALM}/${TEMPLATE_ALIAS}'..."
  TEMPLATE_JSON=$(api_get "/${TEMPLATE_REALM}/identity-provider/instances/$(urlencode "${TEMPLATE_ALIAS}")")
  if ! printf '%s' "${TEMPLATE_JSON}" | python3 -c "
import sys, json
d = json.load(sys.stdin)
sys.exit(0 if d.get('config') else 1)
" 2>/dev/null; then
    echo "ERROR: could not read provider '${TEMPLATE_ALIAS}' in realm '${TEMPLATE_REALM}'." >&2
    exit 1
  fi
  echo "    Copied."
fi

# ---------------------------------------------------------------------------
# Step 1: Resolve or create the identity provider
# ---------------------------------------------------------------------------

ALIAS_ENC=$(urlencode "${ALIAS}")
FLOW_ENC=$(urlencode "${FLOW_NAME}")

echo "==> Resolving identity provider '${ALIAS}'..."
EXISTING=$(api_get "/${REALM}/identity-provider/instances/${ALIAS_ENC}" 2>/dev/null || echo "{}")
EXISTS=$(printf '%s' "${EXISTING}" | python3 -c "
import sys, json
try:
    print('yes' if json.load(sys.stdin).get('alias') else 'no')
except Exception:
    print('no')
" 2>/dev/null || echo "no")

if [ "${EXISTS}" = "yes" ]; then
  echo "    Already exists, will be updated in place."
else
  echo "    Does not exist, will be created."
  EXISTING="{}"
fi

SIGNING_CERT=""
if [ -n "${SIGNING_CERT_FILE}" ]; then
  if [ ! -r "${SIGNING_CERT_FILE}" ]; then
    echo "ERROR: cannot read certificate file '${SIGNING_CERT_FILE}'." >&2
    exit 1
  fi
  # Accept a PEM file or a bare base64 body; Keycloak wants the body only
  SIGNING_CERT=$(python3 -c "
import re, sys
data = open(sys.argv[1]).read()
body = re.sub(r'-----(BEGIN|END) CERTIFICATE-----', '', data)
print(''.join(body.split()))
" "${SIGNING_CERT_FILE}")
fi

FLOW_EXISTS_AT_START=$(api_get "/${REALM}/authentication/flows" | _FLOW="${FLOW_NAME}" python3 -c "
import os, sys, json
try:
    flows = json.load(sys.stdin)
except Exception:
    flows = []
print('yes' if any(f.get('alias') == os.environ['_FLOW'] for f in flows) else 'no')
" 2>/dev/null || echo "no")

ACRS_JSON=$(python3 -c "import json,sys; print(json.dumps(sys.argv[1:]))" "${ACRS[@]+"${ACRS[@]}"}")

IDP_BODY=$(
  _EXISTING="${EXISTING}" \
  _TEMPLATE="${TEMPLATE_JSON}" \
  _ALIAS="${ALIAS}" \
  _DISPLAY_NAME="${DISPLAY_NAME}" \
  _IDP_ENTITY_ID="${IDP_ENTITY_ID}" \
  _SSO_URL="${SSO_URL}" \
  _SP_ENTITY_ID="${SP_ENTITY_ID}" \
  _SIGNING_CERT="${SIGNING_CERT}" \
  _ACRS="${ACRS_JSON}" \
  _ACS_INDEX="${ACS_INDEX}" \
  _PRINCIPAL_ATTRIBUTE="${PRINCIPAL_ATTRIBUTE}" \
  _TRANSIENT="${TRANSIENT}" \
  _ENCRYPT="${ENCRYPT_ASSERTIONS}" \
  _SIGNED_ASSERTIONS="${SIGNED_ASSERTIONS}" \
  _FLOW_NAME="${FLOW_NAME}" \
  _FLOW_EXISTS="${FLOW_EXISTS_AT_START}" \
  python3 -c "
import os, json

idp = json.loads(os.environ['_EXISTING']) or {}
template = json.loads(os.environ['_TEMPLATE']) or {}
tcfg = template.get('config', {})
cfg = dict(idp.get('config') or {})

def pick(explicit, template_key, default=None):
    '''An explicit value wins, then the template, then whatever is already set.'''
    if explicit:
        return explicit
    if tcfg.get(template_key):
        return tcfg[template_key]
    return cfg.get(template_key, default)

idp['alias'] = os.environ['_ALIAS']
idp['providerId'] = 'saml'
idp['enabled'] = True
idp['storeToken'] = False
idp['addReadTokenRoleOnCreate'] = False
idp['authenticateByDefault'] = False
idp['linkOnly'] = False
idp['hideOnLogin'] = False
idp['trustEmail'] = False

display_name = os.environ['_DISPLAY_NAME']
if display_name:
    idp['displayName'] = display_name

transient = os.environ['_TRANSIENT'] == 'true'
if transient:
    idp.pop('firstBrokerLoginFlowAlias', None)
elif os.environ['_FLOW_EXISTS'] == 'yes':
    idp['firstBrokerLoginFlowAlias'] = os.environ['_FLOW_NAME']
else:
    # Keycloak refuses a provider naming a flow that does not exist. The flow is created in step 3
    # and bound to the provider in step 5.
    idp.pop('firstBrokerLoginFlowAlias', None)

cfg['idpEntityId'] = pick(os.environ['_IDP_ENTITY_ID'], 'idpEntityId')
cfg['singleSignOnServiceUrl'] = pick(os.environ['_SSO_URL'], 'singleSignOnServiceUrl')
cfg['entityId'] = os.environ['_SP_ENTITY_ID']

signing_cert = pick(os.environ['_SIGNING_CERT'], 'signingCertificate')
if signing_cert:
    cfg['signingCertificate'] = signing_cert

acrs = json.loads(os.environ['_ACRS'])
if acrs:
    cfg['authnContextClassRefs'] = json.dumps(acrs)
elif tcfg.get('authnContextClassRefs'):
    cfg['authnContextClassRefs'] = tcfg['authnContextClassRefs']

cfg['attributeConsumingServiceIndex'] = os.environ['_ACS_INDEX']
cfg['principalType'] = 'FRIENDLY_ATTRIBUTE'
cfg['principalAttribute'] = os.environ['_PRINCIPAL_ATTRIBUTE']
cfg['doNotStoreUsers'] = 'true' if transient else 'false'

# Hardening. Defaults are deliberately strict; a template may not be.
cfg['validateSignature'] = 'true'
cfg['wantAuthnRequestsSigned'] = 'true'
cfg['wantAssertionsEncrypted'] = os.environ['_ENCRYPT']
signed = os.environ['_SIGNED_ASSERTIONS']
if signed in ('true', 'false'):
    cfg['wantAssertionsSigned'] = signed
elif tcfg.get('wantAssertionsSigned'):
    cfg['wantAssertionsSigned'] = tcfg['wantAssertionsSigned']
cfg['forceAuthn'] = 'true'
cfg['authnContextComparisonType'] = 'exact'
cfg['nameIDPolicyFormat'] = 'urn:oasis:names:tc:SAML:2.0:nameid-format:persistent'
cfg['postBindingAuthnRequest'] = 'true'
cfg['postBindingResponse'] = 'true'
cfg['signatureAlgorithm'] = cfg.get('signatureAlgorithm', 'RSA_SHA256')
cfg['encryptionAlgorithm'] = cfg.get('encryptionAlgorithm', 'RSA-OAEP')
# Only on first link, so the admin application stays the source of truth
cfg['syncMode'] = 'IMPORT'

missing = [k for k in ('idpEntityId', 'singleSignOnServiceUrl', 'entityId') if not cfg.get(k)]
if missing:
    raise SystemExit('ERROR: missing required provider settings: ' + ', '.join(missing))

idp['config'] = cfg
print(json.dumps(idp))
")

if [ "${EXISTS}" = "yes" ]; then
  echo "==> Updating provider..."
  STATUS=$(api_put "/${REALM}/identity-provider/instances/${ALIAS_ENC}" "${IDP_BODY}")
  EXPECT="204"
else
  echo "==> Creating provider..."
  STATUS=$(api_post "/${REALM}/identity-provider/instances" "${IDP_BODY}")
  EXPECT="201"
fi
if [ "${STATUS}" = "${EXPECT}" ]; then
  echo "    Done."
else
  echo "ERROR: unexpected HTTP status ${STATUS} for the provider." >&2
  exit 1
fi

if [ "${TRANSIENT}" = "true" ]; then
  echo ""
  echo "==> Provider '${ALIAS}' registered in realm '${REALM}' (transient users)."
  echo "    No mapper and no first broker login flow were created."
  exit 0
fi

# ---------------------------------------------------------------------------
# Step 2: The attribute importers, which are what the matcher reads
# ---------------------------------------------------------------------------

MATCH_COUNT=$(printf '%s' "${MATCH_JSON}" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")

for (( IDX=0; IDX<MATCH_COUNT; IDX++ )); do
  ENTRY=$(printf '%s' "${MATCH_JSON}" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)[${IDX}]))")
  MATCH_ATTRIBUTE=$(printf '%s' "${ENTRY}" | python3 -c "import sys,json; print(json.load(sys.stdin)['attribute'])")

  echo "==> Ensuring the attribute importer for '${MATCH_ATTRIBUTE}'..."
  MAPPER_ID=$(api_get "/${REALM}/identity-provider/instances/${ALIAS_ENC}/mappers" | _NAME="${MATCH_ATTRIBUTE}" python3 -c "
import os, sys, json
try:
    mappers = json.load(sys.stdin)
except Exception:
    mappers = []
for m in mappers:
    if m.get('name') == os.environ['_NAME']:
        print(m.get('id', ''))
        break
" 2>/dev/null || echo "")

  MAPPER_BODY=$(
    _ALIAS="${ALIAS}" \
    _ENTRY="${ENTRY}" \
    _ID="${MAPPER_ID}" \
    python3 -c "
import os, json
entry = json.loads(os.environ['_ENTRY'])
body = {
  'name': entry['attribute'],
  'identityProviderAlias': os.environ['_ALIAS'],
  'identityProviderMapper': 'saml-user-attribute-idp-mapper',
  'config': {
    'syncMode': 'IMPORT',
    'attribute.name': entry['samlAttribute'],
    'attribute.name.format': entry['samlFormat'],
    'user.attribute': entry['attribute'],
  },
}
if os.environ['_ID']:
    body['id'] = os.environ['_ID']
print(json.dumps(body))
")

  if [ -n "${MAPPER_ID}" ]; then
    STATUS=$(api_put "/${REALM}/identity-provider/instances/${ALIAS_ENC}/mappers/${MAPPER_ID}" "${MAPPER_BODY}")
    [ "${STATUS}" = "204" ] && echo "    Updated." || echo "    WARNING: unexpected HTTP status ${STATUS}" >&2
  else
    STATUS=$(api_post "/${REALM}/identity-provider/instances/${ALIAS_ENC}/mappers" "${MAPPER_BODY}")
    [ "${STATUS}" = "201" ] && echo "    Created." || echo "    WARNING: unexpected HTTP status ${STATUS}" >&2
  fi
done

# Attribute importers for attributes that are no longer listed are left alone, but reported,
# since they keep copying values into the brokered context.
LEFTOVER_MAPPERS=$(api_get "/${REALM}/identity-provider/instances/${ALIAS_ENC}/mappers" | _MATCH="${MATCH_JSON}" python3 -c "
import os, sys, json
try:
    mappers = json.load(sys.stdin)
except Exception:
    mappers = []
listed = {e['attribute'] for e in json.loads(os.environ['_MATCH'])}
for m in mappers:
    if m.get('identityProviderMapper') != 'saml-user-attribute-idp-mapper':
        continue
    attribute = (m.get('config') or {}).get('user.attribute', '')
    if attribute not in listed:
        print('%s (user attribute %s)' % (m.get('name', ''), attribute or 'none'))
" 2>/dev/null || echo "")
if [ -n "${LEFTOVER_MAPPERS}" ]; then
  echo "==> Attribute importers for attributes not given with --match, left in place:"
  printf '%s\n' "${LEFTOVER_MAPPERS}" | while read -r M; do echo "    ${M}"; done
  echo "    They keep copying values into the brokered context. Delete them in the"
  echo "    Admin Console if that is not wanted."
fi

# ---------------------------------------------------------------------------
# Step 3: The first broker login flow
# ---------------------------------------------------------------------------

echo "==> Ensuring first broker login flow '${FLOW_NAME}'..."
FLOW_EXISTS=$(api_get "/${REALM}/authentication/flows" | _FLOW="${FLOW_NAME}" python3 -c "
import os, sys, json
try:
    flows = json.load(sys.stdin)
except Exception:
    flows = []
print('yes' if any(f.get('alias') == os.environ['_FLOW'] for f in flows) else 'no')
" 2>/dev/null || echo "no")

if [ "${FLOW_EXISTS}" = "yes" ]; then
  echo "    Flow already exists."
else
  FLOW_BODY=$(_FLOW="${FLOW_NAME}" python3 -c "
import os, json
print(json.dumps({
  'alias': os.environ['_FLOW'],
  'description': 'First broker login for eID: match a pre-provisioned user on an attribute, never create.',
  'providerId': 'basic-flow',
  'topLevel': True,
  'builtIn': False,
}))
")
  STATUS=$(api_post "/${REALM}/authentication/flows" "${FLOW_BODY}")
  if [ "${STATUS}" != "201" ]; then
    echo "ERROR: could not create flow '${FLOW_NAME}' (HTTP ${STATUS})." >&2
    exit 1
  fi
  echo "    Flow created."
fi

list_executions() {
  api_get "/${REALM}/authentication/flows/${FLOW_ENC}/executions"
}

has_execution() {
  local provider="$1"
  _P="${provider}" python3 -c "
import os, sys, json
try:
    execs = json.load(sys.stdin)
except Exception:
    execs = []
print('yes' if any(e.get('providerId') == os.environ['_P'] for e in execs) else 'no')
" 2>/dev/null || echo "no"
}

for PROVIDER in "${MATCHER_PROVIDER}" "${AUTOLINK_PROVIDER}"; do
  PRESENT=$(list_executions | has_execution "${PROVIDER}")
  if [ "${PRESENT}" = "yes" ]; then
    echo "    Execution '${PROVIDER}' already present."
  else
    BODY=$(_P="${PROVIDER}" python3 -c "
import os, json
print(json.dumps({'provider': os.environ['_P']}))
")
    STATUS=$(api_post "/${REALM}/authentication/flows/${FLOW_ENC}/executions/execution" "${BODY}")
    if [ "${STATUS}" != "201" ]; then
      echo "ERROR: could not add execution '${PROVIDER}' (HTTP ${STATUS})." >&2
      exit 1
    fi
    echo "    Execution '${PROVIDER}' added."
  fi
done

# Both executions must be REQUIRED. idp-auto-link is created DISABLED.
echo "==> Setting both executions to REQUIRED..."
EXECS=$(list_executions)
for PROVIDER in "${MATCHER_PROVIDER}" "${AUTOLINK_PROVIDER}"; do
  EXEC_JSON=$(printf '%s' "${EXECS}" | _P="${PROVIDER}" python3 -c "
import os, sys, json
execs = json.load(sys.stdin)
for e in execs:
    if e.get('providerId') == os.environ['_P']:
        if e.get('requirement') != 'REQUIRED':
            e['requirement'] = 'REQUIRED'
            print(json.dumps(e))
        break
")
  if [ -n "${EXEC_JSON}" ]; then
    STATUS=$(api_put "/${REALM}/authentication/flows/${FLOW_ENC}/executions" "${EXEC_JSON}")
    [ "${STATUS}" = "204" ] && echo "    ${PROVIDER}: set REQUIRED." \
      || echo "    WARNING: ${PROVIDER}: unexpected HTTP status ${STATUS}" >&2
  else
    echo "    ${PROVIDER}: already REQUIRED."
  fi
done

# ---------------------------------------------------------------------------
# Step 4: The matcher's own configuration
# ---------------------------------------------------------------------------

echo "==> Configuring the matcher..."
MATCHER_EXEC_ID=$(list_executions | _P="${MATCHER_PROVIDER}" python3 -c "
import os, sys, json
execs = json.load(sys.stdin)
for e in execs:
    if e.get('providerId') == os.environ['_P']:
        print(e.get('id', ''))
        break
" 2>/dev/null || echo "")
MATCHER_CFG_ID=$(list_executions | _P="${MATCHER_PROVIDER}" python3 -c "
import os, sys, json
execs = json.load(sys.stdin)
for e in execs:
    if e.get('providerId') == os.environ['_P']:
        print(e.get('authenticationConfig', ''))
        break
" 2>/dev/null || echo "")

EXISTING_CFG="{}"
if [ -n "${MATCHER_CFG_ID}" ]; then
  EXISTING_CFG=$(api_get "/${REALM}/authentication/config/${MATCHER_CFG_ID}")
fi

# The list is written as one value with the entries joined by ##, which is how Keycloak stores a
# multivalued setting. Writing the whole configuration replaces it, so the removed requiredRole and
# forbiddenRole settings are dropped. An existing configuration keeps its alias, so the alias does
# not change with the list; a new one is named after the flow.
CFG_BODY=$(
  _MATCH="${MATCH_JSON}" \
  _EXISTING="${EXISTING_CFG}" \
  _FLOW="${FLOW_NAME}" \
  python3 -c "
import os, re, json
entries = json.loads(os.environ['_MATCH'])
existing = json.loads(os.environ['_EXISTING'] or '{}')
values = []
for e in entries:
    value = e['attribute']
    for key in ('requiredRole', 'forbiddenRole'):
        if key in e:
            value += ';%s=%s' % (key, e[key])
    values.append(value)
alias = existing.get('alias') or re.sub('[^a-z0-9]+', '-', os.environ['_FLOW'].lower()).strip('-') + '-match'
print(json.dumps({'alias': alias, 'config': {'matchAttribute': '##'.join(values)}}))
")

REMOVED_SETTINGS=$(printf '%s' "${EXISTING_CFG}" | python3 -c "
import sys, json
try:
    config = json.load(sys.stdin).get('config') or {}
except Exception:
    config = {}
print(' '.join(k for k in ('requiredRole', 'forbiddenRole') if k in config))
" 2>/dev/null || echo "")

if [ -n "${MATCHER_CFG_ID}" ]; then
  STATUS=$(api_put "/${REALM}/authentication/config/${MATCHER_CFG_ID}" "${CFG_BODY}")
  [ "${STATUS}" = "204" ] && echo "    Updated." || echo "    WARNING: unexpected HTTP status ${STATUS}" >&2
else
  STATUS=$(api_post "/${REALM}/authentication/executions/${MATCHER_EXEC_ID}/config" "${CFG_BODY}")
  [ "${STATUS}" = "201" ] && echo "    Created." || echo "    WARNING: unexpected HTTP status ${STATUS}" >&2
fi
if [ -n "${REMOVED_SETTINGS}" ]; then
  echo "    Removed the matcher-level setting(s) no longer supported: ${REMOVED_SETTINGS}."
  echo "    Roles are now given per attribute with --match."
fi

# ---------------------------------------------------------------------------
# Step 5: Bind the flow to the provider
# ---------------------------------------------------------------------------

echo "==> Binding flow '${FLOW_NAME}' to provider '${ALIAS}'..."
BOUND_IDP=$(api_get "/${REALM}/identity-provider/instances/${ALIAS_ENC}" | _FLOW="${FLOW_NAME}" python3 -c "
import os, sys, json
idp = json.load(sys.stdin)
if idp.get('firstBrokerLoginFlowAlias') != os.environ['_FLOW']:
    idp['firstBrokerLoginFlowAlias'] = os.environ['_FLOW']
    print(json.dumps(idp))
")
if [ -n "${BOUND_IDP}" ]; then
  STATUS=$(api_put "/${REALM}/identity-provider/instances/${ALIAS_ENC}" "${BOUND_IDP}")
  if [ "${STATUS}" = "204" ]; then
    echo "    Bound."
  else
    echo "ERROR: could not bind the flow to the provider (HTTP ${STATUS})." >&2
    exit 1
  fi
else
  echo "    Already bound."
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
echo "==> Identity provider '${ALIAS}' is registered in realm '${REALM}'."
echo ""
echo "    Upstream        : ${IDP_ENTITY_ID:-(from template)}"
echo "    Our SP entityID : ${SP_ENTITY_ID}"
echo "    Flow            : ${FLOW_NAME} (${MATCHER_PROVIDER} then ${AUTOLINK_PROVIDER})"
echo "    Matching, tried in this order:"
printf '%s' "${MATCH_JSON}" | python3 -c "
import sys, json
for n, e in enumerate(json.load(sys.stdin), 1):
    roles = []
    if 'requiredRole' in e:
        roles.append('required role ' + e['requiredRole'])
    if 'forbiddenRole' in e:
        roles.append('forbidden role ' + e['forbiddenRole'])
    print('      %d. %s -> %s (%s)%s' % (n, e['samlAttribute'], e['attribute'],
          e['samlFormat'].replace('ATTRIBUTE_FORMAT_', ''), ', ' + ', '.join(roles) if roles else ', no roles'))
"
echo ""
echo "    Two things are not done by this script:"
echo ""
echo "    1. Register our SP metadata with the upstream identity provider. Fetch it from"
echo "         ${KC_URL}${KC_BASE_PATH}/realms/${REALM}/broker/${ALIAS}/endpoint/descriptor"
echo "       and check that the endpoint URLs name the host the upstream expects. Keycloak"
echo "       derives them from the request, so fetching through the wrong host yields the"
echo "       wrong metadata."
echo "    2. Make sure the realm's user profile does not mark email, firstName or lastName"
echo "       required, or a matched user is sent to Verify Profile straight after login."
echo ""
