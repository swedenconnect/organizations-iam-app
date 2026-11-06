import { describe, expect, it } from 'vitest';
import { parseClientDrift } from '@/services/clientService';

describe('parseClientDrift', () => {
  it('reads the counts and the clients that could not be checked', () => {
    expect(parseClientDrift({
      missingByClientId: { 'https://a.example.se': 2 },
      uncheckedClientIds: ['https://b.example.se'],
    })).toEqual({
      missingByClientId: { 'https://a.example.se': 2 },
      uncheckedClientIds: ['https://b.example.se'],
    });
  });

  it('takes a missing field as empty', () => {
    expect(parseClientDrift({ missingByClientId: { 'https://a.example.se': 2 } }))
      .toEqual({ missingByClientId: { 'https://a.example.se': 2 }, uncheckedClientIds: [] });
    expect(parseClientDrift(null)).toEqual({ missingByClientId: {}, uncheckedClientIds: [] });
  });
});
