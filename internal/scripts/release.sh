#!/usr/bin/env bash
#
# Runs a release, on a branch, up to and including the release tag.
#
# The script suggests the version from the root pom.xml, the snapshot version without -SNAPSHOT, lets
# you confirm it or enter another one, sets that version in every pom.xml, builds the project,
# commits, pushes the branch, tags the release commit and pushes the tag. The build uses the release
# profile with signing skipped, so the Javadoc and source files that are published are built and the
# tests are run here, before anything is committed, pushed or tagged. The GitHub Packages workflow
# does not run the tests again. Merging the branch into main is left to you, and the next snapshot
# version is opened afterwards with internal/scripts/post-release-bump.sh, run on main.
#
# Run from main, the release is made on a new release/X_Y_Z branch. Run from any other branch, the
# release is made on that branch.
#
# Pushing the tag is what starts publishing, so the script asks before it does that. Publishing to
# GitHub Packages and of the Docker images is done by GitHub workflows when the tag is pushed, see
# internal/release.md.
set -euo pipefail

REMOTE="origin"
MAIN_BRANCH="main"
WORKFLOW_URL="https://github.com/swedenconnect/organizations-iam-app/actions/workflows/maven-github-deploy.yml"

# Prints the version that follows the given tag or version: the last number raised by one.
suggest_next_version() {
  local version="${1#v}" major minor patch
  major="$(echo "$version" | cut -d. -f1)"
  minor="$(echo "$version" | cut -d. -f2)"
  patch="$(echo "$version" | cut -d. -f3)"
  echo "${major}.${minor}.$((patch + 1))"
}

# Succeeds if the given version is three numbers separated by dots.
is_valid_version() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

# Succeeds if the given version is three numbers separated by dots, with or without -SNAPSHOT.
is_valid_pom_version() {
  is_valid_version "${1%-SNAPSHOT}"
}

# Prints the version that the release script suggests for the given POM version: the version
# without -SNAPSHOT. A release version is suggested as it is.
suggest_release_version() {
  echo "${1%-SNAPSHOT}"
}

# Prints the version in the root pom.xml.
pom_version() {
  mvn -q --no-transfer-progress -Dexpression=project.version -DforceStdout help:evaluate
}

# Prints the message of the commit that holds the given release version.
release_commit_message() {
  echo "build: $1 release"
}

# Prints the branch the release is made on. From main that is a new release/X_Y_Z branch, from any
# other branch it is that branch, which is then used as it is.
release_branch_for() {
  local current_branch="$1" version="$2"
  if [ "$current_branch" = "$MAIN_BRANCH" ]; then
    echo "release/${version//./_}"
  else
    echo "$current_branch"
  fi
}

# Succeeds if no branch of that name exists, neither here nor on the remote.
branch_is_free() {
  ! git show-ref --verify --quiet "refs/heads/$1" &&
    ! git ls-remote --exit-code --heads "$REMOTE" "$1" >/dev/null 2>&1
}

# Succeeds if no tag of that name exists, neither here nor on the remote.
tag_is_free() {
  ! git show-ref --verify --quiet "refs/tags/$1" &&
    ! git ls-remote --exit-code --tags "$REMOTE" "$1" >/dev/null 2>&1
}

main() {
  local repo_root
  repo_root="$(git rev-parse --show-toplevel)"
  cd "$repo_root"

  echo "== Release =="

  # From here down to the branch is created, nothing is changed. Every check that can stop the
  # release runs first, so a release that cannot go through leaves the repository as it was.

  if [ -n "$(git status --porcelain)" ]; then
    echo "The working tree has changed or untracked files. Commit, stash or remove them first." >&2
    git status --short >&2
    exit 1
  fi

  local current_branch
  current_branch="$(git branch --show-current)"
  if [ -z "$current_branch" ]; then
    echo "No branch is checked out. Check out $MAIN_BRANCH, or the branch you want to release from." >&2
    exit 1
  fi

  local pom_version_value
  if ! pom_version_value="$(pom_version)" || [ -z "$pom_version_value" ]; then
    echo "Could not read the version from pom.xml." >&2
    exit 1
  fi

  if ! is_valid_pom_version "$pom_version_value"; then
    echo "The version in the POMs, '$pom_version_value', is not of the form X.Y.Z or X.Y.Z-SNAPSHOT." >&2
    exit 1
  fi

  echo "Fetching tags from $REMOTE ..."
  git fetch --tags --quiet "$REMOTE" || echo "Could not fetch tags from $REMOTE, using the local tags."

  local suggested_version
  suggested_version="$(suggest_release_version "$pom_version_value")"
  echo "Version in the POMs: $pom_version_value"
  if [ "$suggested_version" = "$pom_version_value" ]; then
    echo "The POMs already hold a release version, so that version is suggested."
  fi

  echo "Suggested version: $suggested_version"
  read -r -p "Use this version? [Y/n/type another version]: " answer

  local version
  case "$answer" in
    ""|y|Y|yes|Yes|YES)
      version="$suggested_version"
      ;;
    n|N|no|No|NO)
      read -r -p "Enter the version (X.Y.Z): " version
      ;;
    *)
      version="$answer"
      ;;
  esac

  if ! is_valid_version "$version"; then
    echo "'$version' is not a version of the form X.Y.Z." >&2
    exit 1
  fi

  local branch tag
  branch="$(release_branch_for "$current_branch" "$version")"
  tag="v$version"

  if [ "$branch" != "$current_branch" ] && ! branch_is_free "$branch"; then
    echo "The branch '$branch' already exists here or on $REMOTE. Remove it, or pick another version." >&2
    exit 1
  fi

  if ! tag_is_free "$tag"; then
    echo "The tag '$tag' already exists here or on $REMOTE. Version $version has been released." >&2
    exit 1
  fi

  # The checks are done. From here on the repository is changed.

  if [ "$branch" != "$current_branch" ]; then
    echo "Creating the branch '$branch' from '$current_branch' ..."
    git checkout -b "$branch"
  else
    echo "Releasing on the current branch '$branch'."
  fi

  echo "Setting the version to $version in every pom.xml ..."
  mvn versions:set -DnewVersion="$version" -DprocessAllModules=true -DgenerateBackupPoms=false

  # The same files as the deploy, Javadoc and source files included, but nothing is signed or
  # uploaded. A fault stops the script here, before anything is committed, pushed or tagged.
  echo "Building the project, with the Javadoc and source files ..."
  mvn -Prelease -Dgpg.skip=true clean install

  echo
  echo "== Release notes =="
  echo "Update docs/release-notes.md with what is in version $version now, before you continue."
  read -r -p "Press Enter once the release notes are updated (or Ctrl+C to stop here) ..." _

  git add -- '**/pom.xml' pom.xml docs/release-notes.md
  # Started again on a release branch where the version and the release notes are already
  # committed, there is nothing new to commit, and the current commit is the release commit.
  if git diff --cached --quiet; then
    echo "Nothing to commit. The current commit on '$branch' is the release commit."
  else
    git commit -m "$(release_commit_message "$version")"
  fi

  echo "Pushing '$branch' to $REMOTE ..."
  git push -u "$REMOTE" "$branch"

  echo
  echo "== Tagging =="
  echo "Pushing the tag $tag starts publishing. The artifacts go to GitHub Packages and the three"
  echo "Docker images are pushed. A version that has been published must never be replaced."
  read -r -p "Create the tag $tag and push it? [y/N]: " tag_answer

  case "$tag_answer" in
    y|Y|yes|Yes|YES)
      ;;
    *)
      echo
      echo "Stopped before tagging."
      echo "The branch '$branch' is pushed to $REMOTE and holds version $version."
      echo "There is no tag and nothing has been published."
      echo "To tag later, from that commit:"
      echo
      echo "    git tag -a $tag -m \"Version $version\""
      echo "    git push $REMOTE $tag"
      echo
      echo "Then merge '$branch' into $MAIN_BRANCH and run internal/scripts/post-release-bump.sh on $MAIN_BRANCH."
      exit 0
      ;;
  esac

  echo "Tagging $tag and pushing it ..."
  git tag -a "$tag" -m "Version $version"
  git push "$REMOTE" "$tag"

  echo
  echo "== Publishing =="
  echo "The tag started the GitHub Packages workflow, the Docker release workflow and the GitHub"
  echo "release workflow. Follow the GitHub Packages run here:"
  echo
  echo "    $WORKFLOW_URL"
  echo
  echo "It publishes nothing unless every published module is at version $version."

  echo
  echo "Done. Version $version is tagged as $tag on '$branch'."
  echo
  echo "== What is left =="
  echo "1. Check that the GitHub Packages workflow for $tag succeeded."
  echo
  echo "2. Open a pull request from '$branch' into $MAIN_BRANCH and merge it with \"Create a merge"
  echo "   commit\". The other two buttons, \"Squash and merge\" and \"Rebase and merge\", write new"
  echo "   commits onto $MAIN_BRANCH. The commit that $tag points at would then not be part of the"
  echo "   history of $MAIN_BRANCH, and GitHub would not show $tag on $MAIN_BRANCH."
  echo
  echo "3. Once it is merged, open the next snapshot version from an up to date $MAIN_BRANCH:"
  echo
  echo "       git checkout $MAIN_BRANCH && git pull"
  echo "       internal/scripts/post-release-bump.sh"
  echo
  echo "   It makes the bump on a new branch, which you then merge into $MAIN_BRANCH."
}

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  main "$@"
fi
