import { describe, expect, it } from 'vitest';
import { clientDriftStatus } from '@/app/components/ClientList';

const HEALTHY = 'https://healthy.example.se';
const BROKEN = 'https://broken.example.se';
const COMPLETE = 'https://complete.example.se';

const drift = {
  missingByClientId: { [HEALTHY]: 13 },
  uncheckedClientIds: [BROKEN],
};

describe('clientDriftStatus', () => {
  it('reports the number of missing artifacts', () => {
    expect(clientDriftStatus(HEALTHY, drift)).toEqual({ kind: 'missing', missing: 13 });
  });

  it('reports a client that could not be checked, never as correctly configured', () => {
    expect(clientDriftStatus(BROKEN, drift)).toEqual({ kind: 'unchecked' });
  });

  it('reports a client that could not be checked even if a count is also given', () => {
    expect(clientDriftStatus(BROKEN, { ...drift, missingByClientId: { [BROKEN]: 3 } }))
      .toEqual({ kind: 'unchecked' });
  });

  it('reports nothing for a client that holds what it should', () => {
    expect(clientDriftStatus(COMPLETE, drift)).toBeNull();
    expect(clientDriftStatus(COMPLETE, { missingByClientId: { [COMPLETE]: 0 }, uncheckedClientIds: [] }))
      .toBeNull();
  });

  it('reports nothing before drift has been loaded', () => {
    expect(clientDriftStatus(BROKEN, undefined)).toBeNull();
  });
});
