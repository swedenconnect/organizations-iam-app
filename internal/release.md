![Logo](../docs/images/sweden-connect.png)

# Releasing

![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)

How to make a release of the Organizations and Users IAM application.

This is for maintainers. Only maintainers can create release tags.

-----

## How a release works

A release is made in three steps:

1. [`internal/scripts/release.sh`](scripts/release.sh) sets the release version on a release
   branch, builds it, commits it, pushes the branch, and puts the tag `vX.Y.Z` on the release
   commit. Pushing that tag is what starts publishing.
2. You merge the release branch into `main`, through a pull request.
3. [`internal/scripts/post-release-bump.sh`](scripts/post-release-bump.sh), run on an up to date
   `main`, opens the next snapshot version on a new branch, which you then merge into `main` too.

The tag is created before the branch is merged, so the tag does not sit on a commit on `main` at
the time it is made. That is fine. The tag keeps the released commit available whatever happens to
the branch.

## Before you start

- The working tree has no changes and no untracked files.
- You can push to `origin`.
- Maven works on your machine.

## Run the release script

From the root of the repository:

```bash
./internal/scripts/release.sh
```

It does the release up to and including the tag, in one run:

1. Checks that the working tree is clean and that a branch is checked out.
2. Reads the version in the root `pom.xml`. If it cannot be read, or is not of the form X.Y.Z or
   X.Y.Z-SNAPSHOT, the script stops.
3. Fetches the tags from `origin` and suggests the version from the POM: the snapshot version
   without `-SNAPSHOT`, so `1.0.0-SNAPSHOT` suggests `1.0.0`. If the POMs already hold a release
   version, for example when the script is started again on a release branch after a failed build,
   that version is suggested. You confirm it, or type another version.
4. Works out which branch to use. See [Which branch the release is made on](#which-branch-the-release-is-made-on).
5. Checks that the version is of the form X.Y.Z, that the branch does not exist yet, here or on
   `origin`, and that the tag `vX.Y.Z` does not exist either. Everything that can stop the release
   is checked at this point, before anything is changed. If a check fails the script stops and the
   repository is exactly as it was.
6. Creates the release branch, if it is making one.
7. Sets the version in every `pom.xml`, with `mvn versions:set -DprocessAllModules=true`.
8. Builds and tests with `mvn -Prelease -Dgpg.skip=true clean install`. This uses the `release`
   profile, so it also builds the Javadoc and source files that are published, but it signs nothing
   and uploads nothing. If the build, the tests or the Javadoc fail, the script stops here, before
   anything is committed, pushed or tagged. These are the only tests run in a release, since the
   GitHub Packages workflow does not run them again.
9. Stops and asks you to write the release notes for this version in
   [`docs/release-notes.md`](../docs/release-notes.md), including the date. Do that now, then press
   Enter.
10. Commits the version and the release notes as `build: X.Y.Z release`, and pushes the branch to
    `origin`. If there is nothing to commit, because the version and the release notes were already
    committed on the branch, the current commit is used as the release commit.
11. Asks whether to create the tag and push it, and says that this starts publishing and that a
    published version must never be replaced. If you say no the script stops here. The
    branch is pushed, there is no tag, and nothing is published. It prints the two commands you
    need to tag later.
12. Creates the annotated tag `vX.Y.Z` on the release commit and pushes that one tag. This starts
    the GitHub Packages workflow
    ([`maven-github-deploy.yml`](../.github/workflows/maven-github-deploy.yml)), the Docker
    release workflow ([`docker-release.yml`](../.github/workflows/docker-release.yml)) and the
    GitHub release workflow ([`github-release.yml`](../.github/workflows/github-release.yml)). It
    prints where to follow the GitHub Packages run.
13. Tells you to open a pull request from the branch into `main`, and to run
    `internal/scripts/post-release-bump.sh` on `main` once it is merged.

Only three points wait for you: the version, the release notes, and the question before tagging.

Only the new tag is pushed, with `git push origin vX.Y.Z`. Nothing pushes all tags, so a tag you
happen to have locally cannot start a release workflow by accident.

## Run the post-release bump script

Once the release branch is merged into `main`, from the root of the repository:

```bash
git checkout main
git pull
./internal/scripts/post-release-bump.sh
```

1. Checks that the working tree is clean and that a branch is checked out. On `main` it also checks
   that `main` is not behind `origin/main`, so that the merged release is there.
2. Checks that the version in the POMs is a release version of the form X.Y.Z and not a snapshot,
   that `LibraryVersion.java` declares the `MAJOR`, `MINOR` and `PATCH` constants, and that
   `docs/release-notes.md` has a `### Version` section.
3. Suggests the next snapshot version, the released version with the last number raised by one
   and `-SNAPSHOT` added. You confirm it, or type another version as X.Y.Z, and `-SNAPSHOT` is
   added.
4. Works out which branch to use. From `main` that is a new branch named `bump/X_Y_Z` after the
   coming version, so version 1.0.1 is opened on `bump/1_0_1`. It checks that the branch does not
   exist yet, here or on `origin`. If any check fails the script stops and the repository is
   exactly as it was.
5. Creates the bump branch, if it is making one.
6. Sets the version in every `pom.xml`, and the `MAJOR`, `MINOR` and `PATCH` constants in
   `LibraryVersion.java` in `iam-commons`.
7. Adds a section for the coming version to `docs/release-notes.md`, directly above the newest
   version section, with the date `_not yet released_` and a `---` separator below it. If the file
   already has a section for that version it is left as it is.
8. Commits the changes as `build: bump version after X.Y.Z` and pushes the branch.

Then open a pull request from the bump branch into `main` and merge it. The bump branch holds no
tagged commit, so any of the merge buttons will do.

This is the recommended way. The script can also be run on the release branch before it is
merged, and then makes the bump on that branch, so one pull request carries both the release and
the bump. That pull request must then be merged with "Create a merge commit".

The POMs and `LibraryVersion.java` are the only files that hold the project version. The compose
environment uses the locally built images by the tag `latest`, and the scripts take the version
as an argument, so neither needs to change in a release.

## Which branch the release is made on

- **From `main`**, the script makes a new branch named `release/X_Y_Z`, with underscores. Version
  1.0.0 is released on `release/1_0_0`.
- **From any other branch**, the release is made on that branch. No new branch is made.

## Merge the pull request with "Create a merge commit"

When you merge the release branch into `main`, use the "Create a merge commit" button.

The other two buttons write new commits. "Squash and merge" replaces the branch with one new
commit, and "Rebase and merge" copies the commits onto `main` as new ones. Either way the commit
the tag points at is not part of the history of `main`, so GitHub shows the tag as not being on
`main`. The code on `main` is the same in all three cases, and the tag keeps the released commit
available, so this is a recommendation and nothing enforces it.

## Rules for tags

1. **Start with `v`.** Version `1.0.0` is tagged `v1.0.0`.

2. **Use an annotated tag**, never a lightweight one, so the tag records who made it, when and why:

   ```bash
   git tag -a v1.0.0 -m "Version 1.0.0"
   ```

   The script does this for you.

3. **The version in the POMs must match the tag.** The GitHub Packages workflow and the Docker
   release workflow both check this, and both stop if the tag does not match the version, or if the
   version is still a snapshot.

4. **Never move or delete a tag that has been pushed.** Artifacts have been built from it. Being
   able to find the exact source of a release matters more than a tidy history. If a tag is wrong,
   release a new version.

5. **Never release the same version twice.** GitHub Packages refuses to publish a version that is
   already there, and versions up to 0.9.8 are on Maven Central, which never lets a published
   version be changed or taken down. Two different builds published under the same version can
   never be told apart again.

## What a tag starts

Three workflows react to a pushed `v*` tag. They run next to each other and do not depend on each
other. There is no approval step, publishing starts as soon as the tag is pushed.

- [`maven-github-deploy.yml`](../.github/workflows/maven-github-deploy.yml) publishes every
  module except the demo and admin application modules to GitHub Packages. See
  [Publishing to GitHub Packages](#publishing-to-github-packages).
- [`docker-release.yml`](../.github/workflows/docker-release.yml) checks that the tag matches the
  Maven version and that the version is not a snapshot, installs the modules, and builds and pushes
  three images with Jib, for `linux/amd64` and `linux/arm64`, each tagged with the version and with
  `latest`:
  - `ghcr.io/swedenconnect/iam-admin-app`, the admin application
  - `ghcr.io/swedenconnect/iam-demo-app`, the demo application
  - `ghcr.io/swedenconnect/iam-demo-service`, the demo service

  It logs in to ghcr.io with the token of the workflow.
- [`github-release.yml`](../.github/workflows/github-release.yml) creates a GitHub release from the
  tag, titled with the version and marked as the latest, that points at `docs/release-notes.md` at
  that tag. Nothing is built or attached.

All builds use Java 25 from Temurin. The code is still compiled for the Java release set in the
POM.

Snapshot images are published separately. Every push to `main` runs
[`docker-snapshot.yml`](../.github/workflows/docker-snapshot.yml), which pushes the same three
images tagged with the snapshot version, for example `1.0.1-SNAPSHOT`. When `main` holds a release
version nothing is pushed, so a release image is never overwritten by later commits.

## Publishing to GitHub Packages

Pushing the tag starts the GitHub Packages workflow, which builds the tagged commit and publishes the
files to the GitHub Packages Maven registry of this repository:

```
https://maven.pkg.github.com/swedenconnect/organizations-iam-app
```

Versions up to 0.9.8 were published to Maven Central and stay there. Version 1.0.0 was never
published as a library, so 1.0.1 is the first version in GitHub Packages. Downloading from GitHub
Packages always needs a token, also for public packages. How relying parties set that up is
described in [Using the Artifacts from GitHub Packages](../docs/github-packages.md).

### What the workflow does

1. Checks out the tag and sets up Java 25 from Temurin.
2. Checks the version, before anything is built, so that a mismatch publishes nothing. Every module
   that is published must be at the version the tag names, and that version must not be a snapshot.
3. Builds the whole project with `mvn -Prelease clean deploy -DskipTests`. The tests are compiled
   and the test source files are attached, but the tests are not run. They were run by the release
   script, with the same profile, before the tag was pushed. A tag pushed without running the
   release script is therefore published without any tests being run, unless you built it yourself
   first. A failing build or Javadoc stops the release.
4. Signs every file and deploys them with the Maven deploy plugin to the repository with the id
   `github`.

The workflow publishes with the token GitHub gives every workflow, `GITHUB_TOKEN`. The deploy job
has the permissions `contents: read` and `packages: write`, which lets that token publish packages
for this repository. No other secret than the GPG key is used.

The workflow can also be started by hand from the Actions page, with a tag such as `v1.0.1` as
input. It then checks out that tag and does exactly what a pushed tag does. Use this to run a
failed publish again once the cause is fixed.

The version check is [`internal/scripts/check-release-version.sh`](scripts/check-release-version.sh).
You can run it yourself before tagging:

```bash
./internal/scripts/check-release-version.sh v1.0.1
```

It reads the version of every `pom.xml` under version control, skips a module where the
`skipPublishing` property is `true`, and lists every module that does not match instead of
stopping at the first one. Skipped modules are listed as not checked.

### What is published

Every module of the build, except the demo and admin application modules:

- `iam-parent`, the parent POM
- `iam-commons`
- `iam-security`, the parent POM of the security library, with `iam-security-base` and
  `iam-security-spring-boot-starter`
- `keycloak-plugins`, the parent POM of the Keycloak modules, with `org-rights-mapper`,
  `scope-org-identifier-mapper`, `resource-aud-plugin`, `idp-user-matcher` and `login-theme`
- `keycloak-plugin-distribution`, the ZIP of the Keycloak providers of a release
- `keycloak-scripts-distribution`, the ZIP of the realm administration scripts

Each one is published with its POM, its source and javadoc files, and a signature for every file.

**Not published:**

- the `demo` tree, that is `demo`, `demo-app`, `demo-app-fe`, `demo-app-be` and `demo-service`
- the admin application tree, that is `iam-admin-app`, `admin-app-frontend` and
  `admin-app-backend`

They are still built by every build, the `release` profile included, and their Docker images are
published as described above. They hold large application JARs that nobody fetches as Maven
artifacts. No published module depends on them. Releases 0.9.0 to 0.9.8 did publish them, and those
versions stay on Maven Central.

No list of published modules is kept anywhere, so a module added to the build later is published,
and checked, without anyone having to remember it. The root `pom.xml` sets the `skipPublishing`
property to `false`, and [`demo/pom.xml`](../demo/pom.xml) and
[`iam-admin-app/pom.xml`](../iam-admin-app/pom.xml) set it to `true` for their trees. A module tree
that must not be published does the same in its own POM.

- The root `pom.xml` sets `maven.deploy.skip` to the value of `skipPublishing`, so the Maven deploy
  plugin skips those modules, with or without the `release` profile.
- The Central publishing plugin of the `maven-central-release` profile reads `skipPublishing` for
  each module and leaves the files of a skipped module out of the bundle it uploads.
- The version check reads `skipPublishing` too, so the check and the publishing cannot disagree
  on which modules are published.

### What the `release` profile does

The `release` profile in the parent POM adds the source and javadoc files, signs everything with
GPG, and points the deploy at GitHub Packages, as the repository with the id `github`. It gives no
snapshot repository, so a snapshot version cannot be deployed with it.

The admin application frontend holds no Java code, so its `release` profile adds an empty source
file and an empty javadoc file instead. The module is not published, so these are built but not
uploaded. The login theme holds no Java code either. Its source file holds the theme resources, and
its `release` profile adds an empty javadoc file.

### Signing

The workflow signs with the Bouncy Castle signer of the Maven GPG plugin, chosen on the command
line with `-Dgpg.signer=bc`. It reads the key and its password from the environment, so the machine
running the workflow needs no `gpg` program and no imported key. The profile itself is not changed
by this, so signing from your own machine still works with `gpg`.

### Secrets

These are organisation secrets and they already exist:

| Secret | What it holds | Used by |
|--------|---------------|---------|
| `BOT_GPG_PRIVATE_KEY` | the private key, in ASCII armour | both Maven workflows |
| `BOT_GPG_PASSWORD` | the password for that key | both Maven workflows |
| `MAVEN_CENTRAL_USERNAME` | the user name half of a Central portal token, not the bot's login name | the Maven Central workflow only |
| `MAVEN_CENTRAL_TOKEN_PASSWORD` | the password half of the same token | the Maven Central workflow only |

The public half of the key is already on a key server, so the workflows do not need it. The
credentials reach Maven as environment references in a generated `settings.xml`, so no secret is
written to a file or shown in the log. The Docker workflows use no secret, only the token GitHub
gives every workflow.

### Trying it out first

A published version must never be replaced, so everything is built before the tag is pushed. The
release script does this for you. To do the same by hand:

```bash
mvn -Prelease -Dgpg.skip=true clean install
```

This builds every file the release would upload, source and javadoc files included, and runs the
tests, but signs nothing and uploads nothing.

To see what a release would deploy without uploading anything, deploy to a directory instead of
GitHub Packages. This needs a release version in the POMs, so do it on a copy of the repository with
its own local Maven repository, so that no release version reaches `~/.m2`:

```bash
mvn versions:set -DnewVersion=X.Y.Z -DprocessAllModules=true -DgenerateBackupPoms=false
mvn -Dmaven.repo.local=/path/to/scratch-repo -Prelease -Dgpg.skip=true -DskipTests \
    -DaltDeploymentRepository=local::file:///path/to/scratch-deploy clean deploy
find /path/to/scratch-deploy -type f
```

### Publishing by hand

Only if the workflow cannot be used, for example when it cannot be started on the tag. You need:

- A classic personal access token with the `write:packages` scope, from a GitHub account that can
  write to this repository. Fine-grained tokens are not accepted by the Maven registry.
- A server with the id `github` in your `~/.m2/settings.xml`, with your GitHub user name and that
  token:

  ```xml
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>${env.GITHUB_PACKAGES_TOKEN}</password>
  </server>
  ```

- A GPG key that `gpg` can find on your machine.

Run it from the tagged commit, so that what is published is built from exactly the source the tag
points at:

```bash
git checkout v1.0.1
./internal/scripts/check-release-version.sh v1.0.1
mvn -Prelease clean deploy
```

### Publishing to Maven Central again

Publishing to Maven Central is kept possible, in case it is needed again. It is not part of a
normal release.

- [`maven-central-deploy.yml`](../.github/workflows/maven-central-deploy.yml) does not start on
  tags. It can only be started by hand from the Actions page, with a tag as input. It checks out
  that tag, runs the same version check, and deploys with
  `mvn -Prelease,maven-central-release clean deploy -DskipTests`, as the `swedenconnect-bot`
  Central user.
- The `maven-central-release` profile in the parent POM holds the Sonatype Central publishing
  plugin. Selected together with `release`, it replaces the deploy to GitHub Packages with an upload
  to Central, as the server with the id `central` in `settings.xml`. `autoPublish` is on, so an
  upload that passes Central's checks goes live without anyone pressing a button in the Central
  portal.

Maven Central does not let a published version be removed or replaced. Publishing a version to
both GitHub Packages and Maven Central is fine, since it is the same build of the same tag.

## Version numbers

- Tags are `vX.Y.Z`, for example `v1.0.0`.
- Only the root `pom.xml` states a version of its own. Every module takes it from its parent and
  states only the parent version, written out in full. The only supported way to change the version
  is `mvn versions:set`, which changes the root and every parent reference at once.
- While work is going on, the version is the coming release version with `-SNAPSHOT` added. The
  release script suggests that version without `-SNAPSHOT`.
- After a release, the post-release bump script raises the last number. If the coming release
  calls for a bigger step, answer the version question in either script with the version you want
  instead of taking the suggestion.

## If something goes wrong

### A script stopped before it changed anything

- **"The working tree has changed or untracked files"**: commit, stash or remove them first.
- **"No branch is checked out"**: you are on a detached HEAD. Check out `main`, the branch you want
  to release from, or the release branch.
- **"Could not read the version from pom.xml"**: Maven could not evaluate the root POM. Run
  `mvn help:evaluate -Dexpression=project.version` to see why.
- **"The version in the POMs, ..., is not of the form X.Y.Z or X.Y.Z-SNAPSHOT"**: the POMs hold a
  version the release script does not understand. Set the version by hand, as below.
- **"... is not a version of the form X.Y.Z"**: the version must be three numbers separated by
  dots, such as `1.0.0`. No `v`, and for `release.sh` no `-SNAPSHOT`.
- **"The branch ... already exists"**: a branch of that name is already here or on `origin`. Remove
  it, or release a different version.
- **"The tag ... already exists"**: that version has been released. Release the next one.
- **"main is behind origin/main"**: run `git pull` on `main` first, so the merged release branch is
  there.
- **"The version in the POMs is already the snapshot ..."**: `post-release-bump.sh` has already
  been run, the release branch is not merged yet, or the release was never made. There is nothing
  to bump.
- **"The version in the POMs, ..., is not a released version of the form X.Y.Z"**: the POMs hold
  something other than a release version. Set the version by hand, as below.
- **"docs/release-notes.md is missing, or has no "### Version" section"**: the bump script adds the
  new section above the newest one, and found none. Restore the file.

In all of these the repository is exactly as it was.

### The build failed

Nothing has been committed, pushed or tagged. Fix the problem on the release branch and commit it,
together with the POMs at the release version. Then start the script again on that branch. It
suggests the release version that the POMs hold, and uses the branch as it is.

A failure here can be in the tests or in the Javadoc. To build by hand the same way as the script:

```bash
mvn versions:set -DnewVersion=X.Y.Z -DprocessAllModules=true -DgenerateBackupPoms=false
mvn -Prelease -Dgpg.skip=true clean install
```

### The release script stopped after the branch was pushed but before the tag

Either you answered no to the tag question, or you stopped the script. The branch is on `origin`
and holds the release version. There is no tag and nothing is published. The release commit was
built and tested by the script, so it is safe to tag it by hand. To finish, from the release
commit:

```bash
git tag -a vX.Y.Z -m "Version X.Y.Z"
git push origin vX.Y.Z
```

Then merge the branch into `main` and run `internal/scripts/post-release-bump.sh` on `main`.

### The post-release bump script failed

If it stopped before it changed anything, see above. If it stopped later, for example because the
push failed, look at what is left with `git status` and `git log`. Either push the bump commit
yourself with `git push -u origin <branch>`, or start over: from `main`, check out `main` again and
delete the bump branch with `git branch -D bump/X_Y_Z`, then run the script again.

### A workflow failed

- **The Docker release workflow failed on the version check.** The tag does not match the version
  in the POMs, or the version is still a snapshot. Nothing was published. Fix the version and
  release a new one rather than moving the tag.
- **The Docker release workflow failed later.** The images are not on ghcr.io, or only some of
  them. It is independent of the Maven artifacts, so the workflow can be run again on the same tag
  from the Actions page once the cause is fixed.
- **The GitHub release workflow failed.** Run it again, or create the release by hand from the tag
  on GitHub.
- **The GitHub Packages workflow failed.** What to do depends on how far the run got. In every
  case, never move the tag:
  - **The version check failed.** Nothing was built and nothing was uploaded. The tag does not
    match the version in the POMs, or the version is still a snapshot. Fix the version and release a
    new version.
  - **The build failed.** Nothing was uploaded. The workflow does not run the tests, and the
    release script has already built the Javadoc and source files, so this points to something that
    differs between your machine and the runner, such as the Java version. Fix the problem on `main`
    and release a new version.
  - **The deploy failed part of the way.** The modules are deployed one at a time, so some may be
    published and some not. If the cause is outside the build, such as a network error, start the
    workflow again by hand with the same tag once it is fixed. A module that is already published
    is refused by GitHub Packages, so in that case delete the published versions of that release
    on the package pages of the repository first, or release a new version.
  - **The files are published but are wrong.** Release a new version.

-----

Copyright &copy; 2025-2026, [Sweden Connect](https://swedenconnect.se). Licensed under version 2.0 of the [Apache License](http://www.apache.org/licenses/LICENSE-2.0).
