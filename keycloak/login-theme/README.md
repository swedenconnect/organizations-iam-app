![Sweden Connect](../../docs/images/sweden-connect.png)

# login-theme

A Keycloak 26.x login theme, `IamOrgAdmin-Swedenconnect`, packaged as a provider JAR. It gives the
login page of the `orgiam` realm the look of <https://sandbox.swedenconnect.se/home/>, and puts the
identity providers first, with the username and password form behind a last button.

## What it does

The theme extends the stock `keycloak.v2` login theme and overrides only what differs.

The login page (`login.ftl`) lists the identity providers first, as buttons. The last button, labelled
**Username/Password**, unfolds the username and password form. The form is open from the start when
the realm has no identity provider, and after a failed login, so that the error message is never
hidden behind a closed button. The button is a native HTML `details` element, so it works without
JavaScript and can be operated from the keyboard.

Every other page, such as password reset and OTP, comes from `keycloak.v2` and takes its colours and
typeface from the stylesheet of this theme.

## The look

Logo, colours, typeface and button shapes are taken from <https://sandbox.swedenconnect.se/home/>:

- The Sweden Connect logo and the favicon.
- Ubuntu 400 and 700, served from the theme.
- The colour tokens of the site, declared under the same names in `swedenconnect.css`, so that a later
  comparison against the site stays easy.
- A filled button for the identity providers and an outlined one for the username and password toggle,
  with the dotted focus outline that is the most recognisable detail of the site.

Dark mode is turned off, since the site has no dark variant.

## Contents

Everything is under `src/main/resources/`.

| File | Purpose |
| :--- | :--- |
| `META-INF/keycloak-themes.json` | Tells Keycloak that the JAR holds the theme `IamOrgAdmin-Swedenconnect`, of type `login`. |
| `theme/IamOrgAdmin-Swedenconnect/login/theme.properties` | Sets the parent theme `keycloak.v2`, turns off dark mode and adds the stylesheet. |
| `.../login/login.ftl` | The login page: identity providers first, the password form behind the last button. |
| `.../login/resources/css/swedenconnect.css` | Colours, typeface, logo and button shapes. |
| `.../login/resources/img/` | The Sweden Connect logo and the favicon. |
| `.../login/resources/fonts/` | Ubuntu 400 and 700, latin and latin-ext. |
| `.../login/messages/messages_en.properties`, `messages_sv.properties` | The label of the toggle button, `scUsernamePassword`. |

The theme name has no space, because Keycloak uses it as a directory name.

## Build

```bash
mvn -U -DskipTests clean package
```

(Run from the repository root or from `keycloak/login-theme/`.)

The JAR holds resources only. There is no Java code, and nothing is filtered, so the fonts and the logo
reach the JAR byte for byte.

The module is part of the plugin distribution ZIP that `keycloak/plugin-distribution` assembles, so
`compose/keycloak-scripts/install-keycloak-plugins.sh` builds it and installs it next to the other
plugin JARs.

## Install into Keycloak 26.x

```bash
cp target/login-theme-<version>.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start --optimized
```

Restart Keycloak after the JAR has been added. A Keycloak that runs with `start --optimized` needs
`kc.sh build` first, as for any provider.

## Configure in the Admin Console

1. Go to **Realm settings** → **Themes**.
2. Set **Login theme** to `IamOrgAdmin-Swedenconnect`.
3. Save.

To give a single client another look than the rest of the realm, set the login theme on the client
instead, under **Clients** → *client* → **Advanced**.

Check the Swedish text by adding `?kc_locale=sv` to the login URL. It requires that the language is
enabled for the realm, under **Realm settings** → **Localization**. Otherwise the English text is shown.

## Changing the look

- **Colours, typeface and logo:** edit the design tokens at the top of `swedenconnect.css`.
- **The label of the toggle button:** edit the `scUsernamePassword` key in the two `messages` files.
- **The layout of the login page:** edit `login.ftl`.

Any change means a rebuild. For quick iteration on the CSS, a copy of the theme directory can be
mounted at `/opt/keycloak/themes/IamOrgAdmin-Swedenconnect/login`. The Docker Compose file already
mounts `compose/config/keycloak/themes` there, and starts Keycloak with theme caching turned off, so a
reload shows the change. Remove that copy again before using the JAR, since two themes with the same
name conflict.

## A note on stability

`login.ftl` is a copy of the `login.ftl` of `keycloak.v2`, with the changes described above. It relies
on the macros and variables of that theme (`template.ftl`, `field.ftl`, `buttons.ftl` and
`passkeys.ftl`), which Keycloak does not treat as a stable API. The theme was written against the
`keycloak.v2` login theme of Keycloak 26.7.3, the version the Compose file runs. On every Keycloak
upgrade, compare `login.ftl` with the `login.ftl` of the new `keycloak.v2` theme and bring over what
has changed.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
