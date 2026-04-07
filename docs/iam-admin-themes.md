![Logo](images/sweden-connect.png)

# IAM Admin Themes

This document is aimed at **integrators** who have a running deployment of the IAM Admin
application and want to apply a custom look-and-feel without touching Java or TypeScript source
code.

---

## 1. Overview

A *theme* controls:

| Concern | Mechanism |
|---|---|
| Colours and typography | CSS custom properties in `theme.css` |
| Header logo | `logo.png` served from the theme directory |
| Footer logo (optional) | `footer-logo.png` served from the theme directory |
| Footer content (org name, links) | `footer.json` |
| Browser tab icon (optional) | `favicon.ico` |

The application supports two deployment modes for the theme directory:

- **Classpath** — theme files are bundled inside the JAR under
  `static/theme/<name>/`. Requires a rebuild when you change a theme.
- **External directory** — theme files live in a filesystem path that is mounted at
  runtime. No rebuild required; just place the files and restart the application.

---

## 2. Theme directory layout

Every theme directory must contain the following files:

```
theme.css          (required)  CSS custom-property overrides
footer.json        (required)  Footer content: org name, contact, links, logo heights
logo.png           (required)  Header logo image
footer-logo.png    (optional)  Footer logo image — omit to show no footer logo
favicon.ico        (optional)  Browser tab icon
```

If `footer-logo.png` is absent the footer logo element is automatically hidden; no
configuration is needed.

---

## 3. `theme.css` reference

The file only needs to declare the variables that differ from the built-in defaults (which
use the Sweden Connect default colour palette).

### Application-specific tokens (required)

These variables control the header and footer chrome. The application will not display
correctly without them.

| Variable | Purpose | Default value | DIGG value |
|---|---|---|---|
| `--header-bg` | Header background colour | `#FFFFFF` | `#54684F` |
| `--header-fg` | Header text / icon colour | `#1a1a1a` | `#FFFFFF` |
| `--footer-bg` | Footer background colour | `#2E1A47` | `#54684F` |
| `--footer-fg` | Footer text colour | `#FFFFFF` | `#FFFFFF` |
| `--footer-tagline-bg` | Tagline bar background colour | `#4a5568` | `#CE7869` |

### Tailwind design tokens (optional)

Override these to restyle buttons, cards, and other UI components. If absent the
Tailwind defaults apply.

| Variable | Purpose | DIGG example |
|---|---|---|
| `--primary` | Primary button / accent colour | `#54684F` |
| `--primary-foreground` | Text on primary colour | `#FFFFFF` |
| `--secondary` | Secondary surface colour | `#f0f0f8` |
| `--secondary-foreground` | Text on secondary colour | `#54684F` |
| `--background` | Page background colour | `#F5F5F5` |
| `--foreground` | Default text colour | `#1B1B43` |
| `--card` | Card background | `#FFFFFF` |
| `--card-foreground` | Card text colour | `#1B1B43` |
| `--muted` | Muted surface colour | `#f0f0f8` |
| `--muted-foreground` | Muted text colour | `#5a5a7a` |
| `--accent` | Accent / hover colour | `#54684F` |
| `--accent-foreground` | Text on accent colour | `#FFFFFF` |
| `--destructive` | Destructive action colour | `#D32F2F` |
| `--destructive-foreground` | Text on destructive colour | `#FFFFFF` |
| `--border` | Border colour | `rgba(0,0,0,0.1)` |
| `--ring` | Focus ring colour | `#54684F` |

---

## 4. `footer.json` reference

`orgName` and each link's `label` are **localized string maps** — an object whose keys are
BCP 47 language tags and whose values are the translated strings. The application resolves
them at request time based on the `lang` query parameter sent by the browser. If the
requested language is not present, the first entry in the map is used as a fallback.

```json
{
  "orgName": {
    "sv": "Organisationens namn",
    "en": "Name of the organization"
  },
  "contactEmail":      "contact@example.se",
  "contactPhone":      "08-000 00 00",
  "logoHeight":        "h-12",
  "footerLogoHeight":  "h-8",
  "links": [
    {
      "label": { "sv": "Länktext", "en": "Link text" },
      "url": "https://example.se/page"
    }
  ]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `orgName` | `{ lang: string }` | yes | Localized organization name shown in the footer |
| `contactEmail` | string | yes | Contact e-mail address (not localized) |
| `contactPhone` | string | yes | Contact phone number (not localized) |
| `logoHeight` | string | yes | Tailwind height class for the **header** logo, e.g. `h-8`, `h-12` |
| `footerLogoHeight` | string | no | Tailwind height class for the **footer** logo. Defaults to `h-8` if absent |
| `links` | array | yes | Navigation links rendered in the footer (may be empty) |
| `links[].label` | `{ lang: string }` | yes | Localized visible link text |
| `links[].url` | string | yes | Link destination (not localized) |

If `footer.json` is missing or cannot be parsed the application logs a warning and uses
empty strings / an empty links list as a safe fallback.

---

## 5. Classpath deployment (requires rebuild)

Place all theme files under `static/theme/<name>/` on the application classpath — typically
inside the Maven module at:

```
iam-admin-app/backend/src/main/resources/static/theme/<name>/
```

Set the active theme in `application.yml` (or the environment-specific override):

```yaml
iam:
  admin:
    theme: <name>
```

Rebuild and redeploy the JAR.

---

## 6. External directory deployment (no rebuild required)

1. Create a directory for the theme, for example:
   ```
   /opt/iam-admin/themes/mytheme/
   ```

2. Place the required files in that directory:
   ```
   /opt/iam-admin/themes/mytheme/theme.css
   /opt/iam-admin/themes/mytheme/footer.json
   /opt/iam-admin/themes/mytheme/logo.png
   /opt/iam-admin/themes/mytheme/footer-logo.png   # optional
   /opt/iam-admin/themes/mytheme/favicon.ico        # optional
   ```

3. Configure the application to use the external directory:
   ```yaml
   iam:
     admin:
       theme: mytheme
       theme-dir: /opt/iam-admin/themes/mytheme
   ```
   When `theme-dir` is set it takes precedence over the classpath location.

4. Restart the application.

---

## 7. Worked example — a minimal blue theme

This example creates a theme named `example` with a blue colour scheme.

### `theme.css`

```css
:root {
  --primary:           #1565C0;
  --primary-foreground: #FFFFFF;
  --header-bg:         #1565C0;
  --header-fg:         #FFFFFF;
  --footer-bg:         #0D47A1;
  --footer-fg:         #FFFFFF;
  --footer-tagline-bg: #1976D2;
}
```

### `footer.json`

```json
{
  "orgName": {
    "sv": "Exempelmyndigheten",
    "en": "Example Authority"
  },
  "contactEmail": "info@example.se",
  "contactPhone": "08-000 00 00",
  "logoHeight":   "h-12",
  "links": [
    {
      "label": { "sv": "Om oss",  "en": "About us" },
      "url": "https://www.example.se/about"
    },
    {
      "label": { "sv": "Kontakt", "en": "Contact" },
      "url": "https://www.example.se/contact"
    }
  ]
}
```

### Configuration

```yaml
iam:
  admin:
    theme: example
    theme-dir: /opt/iam-admin/themes/example
```

Place `logo.png` (and optionally `footer-logo.png` and `favicon.ico`) in the directory,
then restart the application.

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
