![Logo](../docs/images/sweden-connect.png)

# Building a Release Docker Image

![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)

-----

Every push to `main` triggers a GitHub Actions workflow that builds and publishes a **snapshot** Docker image to the
GitHub Container Registry (`ghcr.io`). This image always carries whatever version is currently set in `pom.xml`
(for example `1.0.9-SNAPSHOT`).

-----

## Doing a Release, Step by Step

Cutting a release goes through a dedicated release branch, which is merged back into `main`
once the release is tagged:

1. **Create a release branch off `main`**, named after the version being released:

   ```bash
   git checkout -b release_1_2_3
   ```

2. **Set the release version in every pom.**  run locally 
   against a real version number:

   ```bash
   mvn versions:set -DgenerateBackupPoms=false -DnewVersion=1.2.3 --no-transfer-progress
   ```

3. **Update the release notes and set the release date.** Fill in the release date and
   finalize the entry for `1.2.3` in [`docs/release-notes.md`](../docs/release-notes.md).

4. **Commit and push the pom files and release notes with the new version:**

   ```bash
   git add $(find . -name pom.xml) docs/release-notes.md
   git commit -m "Release: 1.2.3"
   git push origin release_1_2_3
   ```

5. **Tag the release commit:**

   ```bash
   git tag v1.2.3
   git push origin v1.2.3
   ```
   The tag name must start with `v` (e.g. `v1.2.0`, `v0.0.3-rc1`), tags that do not match this
   pattern will not trigger a release build. Pushing the tag triggers `release.yml`, which
   builds and publishes a Docker image per service (the admin application, the demo app, and
   the demo service) to `ghcr.io`, each tagged with `1.2.3`. This point in the flow is also
   where the library modules could be published to Maven Central, using the repository's
   `release` Maven profile; that step is manual today, not run by CI.

6. **Step forward to the next development version**, still on the release branch:

   ```bash
   mvn versions:set -DgenerateBackupPoms=false -DnewVersion=1.2.4-SNAPSHOT --no-transfer-progress
   ```

7. **Add a placeholder entry for the next release** at the top of
   [`docs/release-notes.md`](../docs/release-notes.md), an empty `### Version 1.2.4` section
   with a blank `**Date:**` line, ready for the next round of feature work to fill in.

8. **Commit and push:**

   ```bash
   git add $(find . -name pom.xml) docs/release-notes.md
   git commit -m "build: Bumped after 1.2.3 release"
   git push origin release_1_2_3
   ```

9. **Merge the release branch into `main`**, so ongoing work picks up both the bump to the
   next snapshot version and the placeholder release-notes entry.

-----

Copyright &copy; 2025-2026, [Sweden Connect](https://swedenconnect.se). Licensed under version 2.0 of the [Apache License](http://www.apache.org/licenses/LICENSE-2.0).
