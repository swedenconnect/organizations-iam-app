import { describe, expect, it } from 'vitest';
import { showsDeploymentTabs } from './tabVisibility';

describe('showsDeploymentTabs', () => {
  it('shows the group to an unconstrained superuser', () => {
    expect(showsDeploymentTabs(true, null)).toBe(true);
  });

  it('hides the group from a non-superuser', () => {
    expect(showsDeploymentTabs(false, null)).toBe(false);
  });

  it('hides the group from a session carrying a function constraint', () => {
    expect(showsDeploymentTabs(true, 'demo')).toBe(false);
    expect(showsDeploymentTabs(false, 'demo')).toBe(false);
  });

  it('treats an empty function constraint as a constraint', () => {
    expect(showsDeploymentTabs(true, '')).toBe(false);
  });
});
