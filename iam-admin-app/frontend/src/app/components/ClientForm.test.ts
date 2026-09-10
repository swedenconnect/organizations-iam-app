import { describe, expect, it } from 'vitest';
import { ClientFormValues, cleanRedirectUris, clientPayload } from '@/app/components/ClientForm';

function values(overrides: Partial<ClientFormValues> = {}): ClientFormValues {
  return {
    clientId: 'https://service.example.se',
    name: 'Registry',
    oidcClient: true,
    resourceServer: false,
    functions: ['demo'],
    redirectUris: ['https://service.example.se/login/oauth2/code/orgiam'],
    jwksMode: 'uri',
    jwksUri: 'https://service.example.se/jwks',
    jwksString: '',
    orgRightsIdToken: true,
    orgRightsAccessToken: true,
    ...overrides,
  };
}

describe('clientPayload, the display name', () => {
  it('is sent for an OIDC client', () => {
    expect(clientPayload(values()).name).toBe('Registry');
  });

  it('is sent for a client that is only a resource server', () => {
    const payload = clientPayload(values({ oidcClient: false, resourceServer: true }));

    expect(payload.name).toBe('Registry');
    expect(payload.resourceServer).toBe(true);
    expect(payload.oidcClient).toBe(false);
  });

  it('is sent for a client holding both roles', () => {
    expect(clientPayload(values({ resourceServer: true })).name).toBe('Registry');
  });

  it('is trimmed, whatever the roles', () => {
    expect(clientPayload(values({ name: '  Registry  ' })).name).toBe('Registry');
    expect(clientPayload(values({
      name: '  Registry  ',
      oidcClient: false,
      resourceServer: true,
    })).name).toBe('Registry');
  });

  it('is an empty string when the field was left empty, so a resource server saves without one', () => {
    const payload = clientPayload(values({
      name: '   ',
      oidcClient: false,
      resourceServer: true,
    }));

    expect(payload.name).toBe('');
  });
});

describe('clientPayload, the fields belonging to the OIDC client role', () => {
  it('carries the redirect URIs and the JWKS URI for an OIDC client', () => {
    const payload = clientPayload(values());

    expect(payload.redirectUris).toEqual(['https://service.example.se/login/oauth2/code/orgiam']);
    expect(payload.jwksUri).toBe('https://service.example.se/jwks');
    expect(payload.jwksString).toBeNull();
  });

  it('carries the inline JWK Set instead when that is the chosen mode', () => {
    const payload = clientPayload(values({ jwksMode: 'inline', jwksString: '{"keys":[]}' }));

    expect(payload.jwksString).toBe('{"keys":[]}');
    expect(payload.jwksUri).toBeNull();
  });

  it('drops them all for a client that is only a resource server', () => {
    const payload = clientPayload(values({ oidcClient: false, resourceServer: true }));

    expect(payload.redirectUris).toEqual([]);
    expect(payload.jwksUri).toBeNull();
    expect(payload.jwksString).toBeNull();
  });

  it('reports both org_rights switches as on for a resource server, which emits neither', () => {
    const payload = clientPayload(values({
      oidcClient: false,
      resourceServer: true,
      orgRightsIdToken: false,
      orgRightsAccessToken: false,
    }));

    expect(payload.orgRightsIdToken).toBe(true);
    expect(payload.orgRightsAccessToken).toBe(true);
  });

  it('keeps the org_rights switches as chosen for an OIDC client', () => {
    const payload = clientPayload(values({ orgRightsAccessToken: false }));

    expect(payload.orgRightsIdToken).toBe(true);
    expect(payload.orgRightsAccessToken).toBe(false);
  });
});

describe('clientPayload, the functions and the client ID', () => {
  it('sends the selected functions for a resource server as well', () => {
    expect(clientPayload(values({ oidcClient: false, resourceServer: true })).functions)
      .toEqual(['demo']);
  });

  it('trims the client ID', () => {
    expect(clientPayload(values({ clientId: '  https://service.example.se  ' })).clientId)
      .toBe('https://service.example.se');
  });
});

describe('cleanRedirectUris', () => {
  it('trims each entry and drops the blank ones the repeated inputs leave behind', () => {
    expect(cleanRedirectUris(['  https://a.example.se/cb  ', '', '   ', 'https://b.example.se/cb']))
      .toEqual(['https://a.example.se/cb', 'https://b.example.se/cb']);
  });

  it('is empty when nothing was filled in', () => {
    expect(cleanRedirectUris([''])).toEqual([]);
  });
});
