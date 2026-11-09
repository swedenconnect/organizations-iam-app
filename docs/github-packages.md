![Sweden Connect](images/sweden-connect.png)

# Using the Artifacts from GitHub Packages

How to use the Maven artifacts of the Organizations and Users IAM, such as `iam-security-spring-boot-starter`, `iam-commons` and the Keycloak plugins, in your own build.

-----

## Where the artifacts are

- **Versions up to 0.9.8** are on Maven Central, and stay there. No extra setup is needed for them.
- **Later versions** are published only to the GitHub Packages Maven registry of this project:

  ```
  https://maven.pkg.github.com/swedenconnect/organizations-iam-app
  ```

  Version 1.0.0 was never published as a library. If you use 0.9.8, the next version is 1.0.1.

The group IDs, artifact IDs and versions are the same as before. To move from 0.9.8 to 1.0.1 you add the repository and a token, as described below, and change the version. Nothing else.

Snapshot versions are not published.

## Every build needs a token

GitHub Packages does not allow anonymous downloads, also not for public packages. Every build that downloads these artifacts must authenticate, a build from your terminal as well as one in CI.

Each developer, and each CI setup, uses its own token. No shared token is handed out.

## Create a token

Use a **classic** personal access token with the `read:packages` scope only. The GitHub Packages Maven registry accepts only classic tokens.

1. On GitHub, go to **Settings**, **Developer settings**, **Personal access tokens**, **Tokens (classic)**.
2. Choose **Generate new token (classic)**.
3. Give it a name, such as `maven read packages`, and an expiration date.
4. Select the `read:packages` scope, and nothing else.
5. Generate the token and copy it. GitHub shows it once.

Any GitHub account can create such a token and download the packages, since the project is public.

## Add the server to settings.xml

Maven reads credentials from `~/.m2/settings.xml`. Add a server with the id `github`. The id must be the same as the id of the repository in the POM.

Either with the token in the file:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_TOKEN</password>
    </server>
  </servers>
</settings>
```

Or read from environment variables, so that the token is not stored in the file:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_USERNAME}</username>
      <password>${env.GITHUB_PACKAGES_TOKEN}</password>
    </server>
  </servers>
</settings>
```

```bash
export GITHUB_USERNAME=your-github-username
export GITHUB_PACKAGES_TOKEN=your-token
```

The names of the environment variables are up to you, as long as the file and the environment agree.

## Add the repository to the POM

Add the repository to the `pom.xml` of your project, with the same id, `github`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <name>Sweden Connect on GitHub Packages</name>
    <url>https://maven.pkg.github.com/swedenconnect/*</url>
    <snapshots>
      <enabled>false</enabled>
    </snapshots>
  </repository>
</repositories>
```

The `*` at the end of the URL makes Maven install packages from any repository owned by `swedenconnect`, so this one entry, and the one server entry in `settings.xml`, also covers other Sweden Connect projects that publish to GitHub Packages.

Maven Central is still used for everything else. The dependencies themselves are declared as before, for example:

```xml
<dependency>
  <groupId>se.swedenconnect.iam</groupId>
  <artifactId>iam-security-spring-boot-starter</artifactId>
  <version>1.0.1</version>
</dependency>
```

## In GitHub Actions

The token that GitHub gives a workflow, `GITHUB_TOKEN`, is meant for the repository the workflow runs in. Do not rely on it to download packages from this project. Use a classic personal access token with only `read:packages` instead, and store it as a secret in your repository or organization, for example as `IAM_PACKAGES_TOKEN`.

`actions/setup-java` can write the server entry for you, with the values read from the environment:

```yaml
- uses: actions/setup-java@v6
  with:
    distribution: 'temurin'
    java-version: '21'
    server-id: github
    server-username: GITHUB_PACKAGES_USERNAME
    server-password: GITHUB_PACKAGES_TOKEN

- name: Build
  env:
    GITHUB_PACKAGES_USERNAME: ${{ github.actor }}
    GITHUB_PACKAGES_TOKEN: ${{ secrets.IAM_PACKAGES_TOKEN }}
  run: mvn --no-transfer-progress clean verify
```

`server-username` and `server-password` name the environment variables to read, not the values.

## GitHub documentation

- [Working with the Apache Maven registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)
- [Publishing and installing a package with GitHub Actions](https://docs.github.com/en/packages/managing-github-packages-using-github-actions-workflows/publishing-and-installing-a-package-with-github-actions)
- [About permissions for GitHub Packages](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages)
- [Managing your personal access tokens](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)

-----

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the [Apache License](https://www.apache.org/licenses/LICENSE-2.0).
