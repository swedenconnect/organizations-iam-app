#!/usr/bin/env bash
#
# Checks that the project may be published under a given release tag.
#
# The tag must be "v" followed by the version in the POMs, and that version must not be a snapshot.
# Every module that is actually published is checked, not only the root, so a module left behind at
# an older parent version stops the release before anything is uploaded. A module where the
# skipPublishing property is true is not published, and is not checked. The root pom.xml maps
# maven.deploy.skip, which the Maven deploy plugin reads, to that property, and the Central
# publishing plugin reads it directly. demo/pom.xml and iam-admin-app/pom.xml set it for their
# trees, and the root pom.xml sets it to false for every other module, so the check and the
# publishing cannot disagree on which modules are published.
#
# Usage:
#     internal/scripts/check-release-version.sh <tag>
#
# Exit status: 0 when the release may go ahead, 1 when it must not, 2 on a usage error.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Prints a failure so that it also shows up as an annotation when running in GitHub Actions.
fail() {
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    echo "::error::$1"
  else
    echo "$1" >&2
  fi
}

# Prints one "<pom path> <version> <skip publishing>" line per pom.xml under version control. Only
# those are read, so a POM that is not part of the build is never checked. The skip publishing
# column is the value of the skipPublishing property of the module.
list_modules() {
  local pom version skip
  while IFS= read -r pom; do
    if ! version="$(mvn -q --no-transfer-progress -f "$pom" -Dexpression=project.version -DforceStdout help:evaluate)" ||
       ! skip="$(mvn -q --no-transfer-progress -f "$pom" -Dexpression=skipPublishing -DforceStdout help:evaluate)"; then
      echo "Could not read the version of ${pom}" >&2
      exit 1
    fi
    echo "${pom} ${version} ${skip}"
  done < <(git ls-files -- pom.xml '**/pom.xml' | sort)
}

TAG="${1:-}"

if [ -z "$TAG" ]; then
  echo "Usage: $0 <tag>" >&2
  exit 2
fi

if [ "${TAG#v}" = "$TAG" ]; then
  fail "The tag ${TAG} does not start with 'v' - a release tag is 'v' followed by the version"
  exit 1
fi

EXPECTED_VERSION="${TAG#v}"

if [ -z "$EXPECTED_VERSION" ]; then
  fail "The tag ${TAG} carries no version - a release tag is 'v' followed by the version"
  exit 1
fi

cd "$REPO_ROOT"
MODULES="$(list_modules)"

echo "Checking that every published module is at version ${EXPECTED_VERSION}, as required by the tag ${TAG}."

published=0
errors=0

while read -r pom version skip; do
  [ -z "$pom" ] && continue
  if [ "$skip" = "true" ]; then
    echo "  ${pom}: ${version} - not published, not checked"
    continue
  fi
  published=$((published + 1))
  if [ "${version%-SNAPSHOT}" != "$version" ]; then
    fail "The version of ${pom} is the snapshot ${version} - set the release version in the POMs before tagging"
    errors=$((errors + 1))
  elif [ "$version" != "$EXPECTED_VERSION" ]; then
    fail "The version of ${pom} is ${version}, but the tag ${TAG} requires ${EXPECTED_VERSION}"
    errors=$((errors + 1))
  else
    echo "  ${pom}: ${version} - ok"
  fi
done <<< "$MODULES"

if [ "$published" -eq 0 ]; then
  fail "No module would be published - there is nothing to publish"
  exit 1
fi

if [ "$errors" -gt 0 ]; then
  fail "${errors} module(s) do not match the tag ${TAG} - nothing has been published"
  exit 1
fi

echo "All ${published} published module(s) are at version ${EXPECTED_VERSION}."
