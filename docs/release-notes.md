![Sweden Connect](images/sweden-connect.png)

# Organizations and Users IAM - Release Notes

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) ![Maven Central](https://img.shields.io/maven-central/v/se.swedenconnect.iam/iam-parent.svg)

---

### Version 0.9.2

**Date:** Not yet released

- **Organisation-level rights are now expanded per function in the `org_rights` claim.**
  Previously a right granted at the organisation level was emitted as the wildcard
  `{"function": "*", "right": "..."}`. The wildcard was documented to mean "all functions
  currently attached to the organisation", but the claim never carried the attachment set, so
  consumers could not evaluate that and treated it as "all functions". The `org-rights-mapper`
  Keycloak plugin now emits one entry per attached function instead, and records the
  organisation-level grant in a new `org_level_right` field. The wildcard is gone.

  `org_level_right` is **provenance only** — it says *how* a right was granted, never *what* the
  user may do. Effective rights come exclusively from the `functions` array. Its one legitimate
  use is deciding whether someone may administer the organisation itself, such as granting or
  revoking organisation-level rights or editing the organisation record.

  **Behaviour change:** organisation-level `admin` on an organisation with no attached functions
  previously granted access to every function; it now grants none. More generally, a
  function-scoped relying party no longer accepts an organisation-level right for a function that
  was never attached to that organisation. Rights that were correct before remain unchanged — an
  organisation-level right still covers every function actually attached.

  The OAuth scope path is unaffected: Keycloak already enforced attachment there, since the
  `{org}:{function}:{right}` client scopes are created on attach and deleted on detach.

  No Keycloak configuration changes are required — the realm, group attributes, client scopes and
  protocol-mapper instances are all unchanged. Deploy the new `org-rights-mapper` JAR as usual;
  a `start --optimized` installation needs an explicit `kc.sh build`.

- **New `OrgRightsClaim.organizations()` helper in `iam-security-base`.** Listing the
  organisations a user is associated with is a different question from deciding what the user may
  do, and it needs a different source. Granted authorities are derived per function, so an
  organisation with no attached functions produces no authority and is invisible to a consumer
  that enumerates authorities — even when the user administers it. `organizations()` enumerates
  the claim entries instead, returning the organisation identifier, localised name and the
  nullable organisation-level right. Authority construction is unchanged. For a superuser the
  claim carries no organisation entries, so the method returns an empty list and the full list
  must be fetched from `/iam-api/v1/organizations`.

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
