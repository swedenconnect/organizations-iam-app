![Sweden Connect](images/sweden-connect.png)

# Organizations and Users IAM - Release Notes

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) ![Maven Central](https://img.shields.io/maven-central/v/se.swedenconnect.iam/iam-parent.svg)

---

### Version 0.9.1

**Date:** 2026-06-12

- The admin application can now be deployed under a custom context path
  (e.g. `/iam-admin/`) in addition to the root path, without requiring a
  separate build.
- New API endpoint for listing the rights holders of a given function within
  an organisation. See the API documentation for details.
- When a user's session expires while using the application, API calls now
  detect the expired session and redirect to the login page with a clear
  "session expired" message, instead of silently failing or showing an
  unexpected error.
- Fixed a bug where removing a user's right at the organisation level failed
  with an unexpected error. The delete operation was silently dropping the
  required `right` query parameter before sending the request to the backend.
- Implemented pagination for organizations, system can handle more that 10 organizations.
  Loading of users and organizations is now done in smaller sections. There is a backend implemented
  cache that handles search on organization names. When a write is made it is invalidated, and reloaded. 
- Added debug statements to org-rights mapper Keycloak plugin. Fixed some ordering issues. Replace N calls to 
  getSubGroupsStream() with a single lookup map (one Keycloak query regardless of how many orgs the user belongs to)

---

### Version 0.9.0

**Date:** 2026-04-09

- First release

---

Copyright &copy; 2026, [Myndigheten för digital förvaltning - Swedish Agency for
Digital Government (DIGG)](https://www.digg.se). Licensed under version 2.0 of the
[Apache License](https://www.apache.org/licenses/LICENSE-2.0).
