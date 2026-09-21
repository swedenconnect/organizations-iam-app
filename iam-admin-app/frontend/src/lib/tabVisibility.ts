/**
 * Visibility rules for the right-hand tab group: Functions, Services and Import/Export.
 *
 * Every tab in that group is a deployment-wide view that belongs to a superuser, so the group is
 * shown as a whole or not at all. A session carrying a function constraint hides it regardless of
 * superuser status, because such a session is scoped to one function and the deployment-wide views
 * do not apply to it.
 */

/**
 * Returns true when the right-hand tab group, and every tab in it, is to be rendered.
 *
 * @param isSuperuser whether the signed-in administrator is a superuser
 * @param functionConstraint the function the session is constrained to, or null when unconstrained
 */
export function showsDeploymentTabs(
  isSuperuser: boolean,
  functionConstraint: string | null
): boolean {
  return isSuperuser && functionConstraint === null;
}
