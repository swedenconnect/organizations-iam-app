import { describe, expect, it } from 'vitest';
import { UserRegistrationSettings } from '@/types';
import {
  EMPTY_IDENTITY_VALUES,
  IdentityValues,
  fillableIdentityFields,
  identityPayload,
  eidAttributeMandatory,
  requiredIdentityField,
  userIdRequired,
  validateIdentityValues,
} from '@/app/components/UserIdentityFields';

/** Returns the translation key, so a test can assert which message was chosen. */
const t = (key: string) => key;

function settings(overrides: Partial<UserRegistrationSettings> = {}): UserRegistrationSettings {
  return {
    allowSelectUserId: false,
    allowTemporaryPassword: false,
    eidAttributeRequired: true,
    personalNumberEnabled: true,
    hsaIdEnabled: false,
    orgAffiliationEnabled: false,
    efosIdEnabled: false,
    ...overrides,
  };
}

function values(overrides: Partial<IdentityValues> = {}): IdentityValues {
  return { ...EMPTY_IDENTITY_VALUES, ...overrides };
}

describe('fillableIdentityFields', () => {
  it('counts the personal identity number and the organizational affiliation', () => {
    expect(fillableIdentityFields(settings({ orgAffiliationEnabled: true })))
      .toEqual(['personalIdentityNumber', 'orgAffiliation']);
  });

  it('counts all four eID attributes, in the order they are shown', () => {
    expect(fillableIdentityFields(settings({
      orgAffiliationEnabled: true,
      hsaIdEnabled: true,
      efosIdEnabled: true,
    }))).toEqual(['personalIdentityNumber', 'orgAffiliation', 'hsaId', 'efosId']);
  });

  it('counts the HSA-ID and the EFOS-ID only when enabled', () => {
    expect(fillableIdentityFields(settings({ personalNumberEnabled: false, efosIdEnabled: true })))
      .toEqual(['efosId']);
  });

  it('is empty when no identity field is enabled', () => {
    expect(fillableIdentityFields(settings({ personalNumberEnabled: false }))).toEqual([]);
  });
});

describe('requiredIdentityField', () => {
  it('is the personal identity number when that is the only field to fill', () => {
    expect(requiredIdentityField(settings())).toBe('personalIdentityNumber');
  });

  it('is the organizational affiliation when that is the only field to fill', () => {
    expect(requiredIdentityField(settings({
      personalNumberEnabled: false,
      orgAffiliationEnabled: true,
    }))).toBe('orgAffiliation');
  });

  it('is nothing once there is more than one field, so neither is marked', () => {
    expect(requiredIdentityField(settings({ orgAffiliationEnabled: true }))).toBeNull();
  });

  it('is the HSA-ID or the EFOS-ID when that is the only field to fill', () => {
    expect(requiredIdentityField(settings({ personalNumberEnabled: false, hsaIdEnabled: true })))
      .toBe('hsaId');
    expect(requiredIdentityField(settings({ personalNumberEnabled: false, efosIdEnabled: true })))
      .toBe('efosId');
  });

  it('counts the HSA-ID and the EFOS-ID among the fields, so none is marked with several', () => {
    expect(requiredIdentityField(settings({ hsaIdEnabled: true }))).toBeNull();
    expect(requiredIdentityField(settings({ personalNumberEnabled: false, hsaIdEnabled: true, efosIdEnabled: true })))
      .toBeNull();
  });

  it('is nothing when the requirement is off and a user ID can be chosen instead', () => {
    expect(requiredIdentityField(settings({ eidAttributeRequired: false, allowSelectUserId: true })))
      .toBeNull();
    expect(requiredIdentityField(settings({
      eidAttributeRequired: false,
      allowSelectUserId: true,
      orgAffiliationEnabled: true,
    }))).toBeNull();
  });

  it('is the single field when the requirement is off but no user ID can be chosen', () => {
    expect(requiredIdentityField(settings({ eidAttributeRequired: false })))
      .toBe('personalIdentityNumber');
    expect(requiredIdentityField(settings({
      eidAttributeRequired: false,
      orgAffiliationEnabled: true,
    }))).toBeNull();
  });
});

describe('eidAttributeMandatory', () => {
  it('holds when the requirement is on, whatever else is set', () => {
    expect(eidAttributeMandatory(settings())).toBe(true);
    expect(eidAttributeMandatory(settings({ allowSelectUserId: true }))).toBe(true);
  });

  it('holds when no user ID can be chosen and there is a field to fill', () => {
    expect(eidAttributeMandatory(settings({ eidAttributeRequired: false }))).toBe(true);
  });

  it('does not hold when no user ID can be chosen and there is no field to fill', () => {
    expect(eidAttributeMandatory(settings({
      eidAttributeRequired: false,
      personalNumberEnabled: false,
    }))).toBe(false);
  });

  it('holds when no user ID can be chosen and the only field is the HSA-ID', () => {
    expect(eidAttributeMandatory(settings({
      eidAttributeRequired: false,
      personalNumberEnabled: false,
      hsaIdEnabled: true,
    }))).toBe(true);
  });

  it('does not hold when the requirement is off and a user ID can be chosen', () => {
    expect(eidAttributeMandatory(settings({ eidAttributeRequired: false, allowSelectUserId: true })))
      .toBe(false);
  });
});

describe('userIdRequired', () => {
  it('never holds when no user ID can be chosen', () => {
    expect(userIdRequired(values({ temporaryPassword: 'secret' }), settings({
      allowTemporaryPassword: true,
      eidAttributeRequired: false,
      personalNumberEnabled: false,
    }))).toBe(false);
  });

  it('holds once an initial password is entered', () => {
    const s = settings({ allowSelectUserId: true, allowTemporaryPassword: true });

    expect(userIdRequired(values(), s)).toBe(false);
    expect(userIdRequired(values({ temporaryPassword: 'secret' }), s)).toBe(true);
  });

  it('ignores a password when the password field is not shown', () => {
    expect(userIdRequired(values({ temporaryPassword: 'secret' }), settings({ allowSelectUserId: true })))
      .toBe(false);
  });

  it('holds when it is the only identity there is to give', () => {
    expect(userIdRequired(values(), settings({
      allowSelectUserId: true,
      eidAttributeRequired: false,
      personalNumberEnabled: false,
    }))).toBe(true);
  });

  it('does not hold when an EFOS-ID could be given instead', () => {
    expect(userIdRequired(values(), settings({
      allowSelectUserId: true,
      eidAttributeRequired: false,
      personalNumberEnabled: false,
      efosIdEnabled: true,
    }))).toBe(false);
  });

  it('does not hold when an eID attribute could be given instead', () => {
    expect(userIdRequired(values(), settings({ allowSelectUserId: true, eidAttributeRequired: false })))
      .toBe(false);
  });
});

describe('validateIdentityValues, a single identity field', () => {
  it('reports a missing value against the field, with the ordinary required wording', () => {
    const errors = validateIdentityValues(values(), settings(), t);

    expect(errors.personalIdentityNumber).toBe('validation.required');
    expect(errors.eid).toBeUndefined();
  });

  it('reports it against the organizational affiliation when that is the single field', () => {
    const errors = validateIdentityValues(
      values(),
      settings({ personalNumberEnabled: false, orgAffiliationEnabled: true }),
      t);

    expect(errors.orgAffiliation).toBe('validation.required');
    expect(errors.eid).toBeUndefined();
  });

  it('reports it against the HSA-ID when that is the single field', () => {
    const errors = validateIdentityValues(
      values(),
      settings({ personalNumberEnabled: false, hsaIdEnabled: true }),
      t);

    expect(errors).toEqual({ hsaId: 'validation.required' });
  });

  it('reports it against the EFOS-ID when that is the single field', () => {
    const errors = validateIdentityValues(
      values({ efosId: '   ' }),
      settings({ personalNumberEnabled: false, efosIdEnabled: true }),
      t);

    expect(errors).toEqual({ efosId: 'validation.required' });
  });

  it('reports a quotation mark in an HSA-ID or an EFOS-ID on the field', () => {
    const s = settings({ hsaIdEnabled: true, efosIdEnabled: true });

    expect(validateIdentityValues(values({ hsaId: 'SE"1' }), s, t))
      .toEqual({ hsaId: 'validation.noQuotationMark' });
    expect(validateIdentityValues(values({ personalIdentityNumber: '196911292032', efosId: ' " ' }), s, t))
      .toEqual({ efosId: 'validation.noQuotationMark' });
  });

  it('ignores a quotation mark in a field that is turned off', () => {
    expect(validateIdentityValues(
      values({ personalIdentityNumber: '196911292032', hsaId: 'SE"1', efosId: '"' }),
      settings(),
      t)).toEqual({});
  });

  it('accepts any HSA-ID or EFOS-ID that is not blank', () => {
    const s = settings({ personalNumberEnabled: false, hsaIdEnabled: true });

    expect(validateIdentityValues(values({ hsaId: 'a b/c@å' }), s, t)).toEqual({});
    expect(validateIdentityValues(
      values({ efosId: '1' }),
      settings({ personalNumberEnabled: false, efosIdEnabled: true }),
      t)).toEqual({});
  });

  it('passes once the field is filled', () => {
    const errors = validateIdentityValues(
      values({ personalIdentityNumber: '196911292032' }),
      settings(),
      t);

    expect(errors).toEqual({});
  });

  it('still reports a malformed value as a format error', () => {
    const errors = validateIdentityValues(
      values({ personalIdentityNumber: '19691129' }),
      settings(),
      t);

    expect(errors.personalIdentityNumber).toBe('validation.pin12digits');
    expect(errors.eid).toBeUndefined();
  });
});

describe('validateIdentityValues, several identity fields', () => {
  it('reports an empty set at form level, not against either field', () => {
    const errors = validateIdentityValues(values(), settings({ orgAffiliationEnabled: true }), t);

    expect(errors.eid).toBe('validation.eidAttributeRequired');
    expect(errors.personalIdentityNumber).toBeUndefined();
    expect(errors.orgAffiliation).toBeUndefined();
  });

  it('is satisfied by either one of them', () => {
    const withPin = validateIdentityValues(
      values({ personalIdentityNumber: '196911292032' }),
      settings({ orgAffiliationEnabled: true }),
      t);
    const withAffiliation = validateIdentityValues(
      values({ orgAffiliation: 'martin@2021006883' }),
      settings({ orgAffiliationEnabled: true }),
      t);

    expect(withPin).toEqual({});
    expect(withAffiliation).toEqual({});
  });

  it('reports at form level when the HSA-ID and the EFOS-ID are among them', () => {
    const errors = validateIdentityValues(values(), settings({ hsaIdEnabled: true, efosIdEnabled: true }), t);

    expect(errors).toEqual({ eid: 'validation.eidAttributeRequired' });
  });

  it('is satisfied by an HSA-ID or an EFOS-ID alone', () => {
    const s = settings({ orgAffiliationEnabled: true, hsaIdEnabled: true, efosIdEnabled: true });

    expect(validateIdentityValues(values({ hsaId: 'SE2321000016-1234' }), s, t)).toEqual({});
    expect(validateIdentityValues(values({ efosId: 'efos-4711' }), s, t)).toEqual({});
  });

  it('does not count an HSA-ID or an EFOS-ID whose setting is off', () => {
    const errors = validateIdentityValues(
      values({ hsaId: 'SE2321000016-1234', efosId: 'efos-4711' }),
      settings({ orgAffiliationEnabled: true }),
      t);

    expect(errors).toEqual({ eid: 'validation.eidAttributeRequired' });
  });

  it('still validates the format of each value that is given', () => {
    const errors = validateIdentityValues(
      values({ orgAffiliation: 'martin@202100688' }),
      settings({ orgAffiliationEnabled: true }),
      t);

    expect(errors.orgAffiliation).toBe('validation.orgAffiliationFormat');
    expect(errors.eid).toBeUndefined();
  });
});

describe('validateIdentityValues, the requirement turned off and no user ID to choose', () => {
  const off = { eidAttributeRequired: false };

  it('still requires the single field, reported against it', () => {
    const errors = validateIdentityValues(values(), settings(off), t);

    expect(errors).toEqual({ personalIdentityNumber: 'validation.required' });
  });

  it('reports at form level when there are several fields', () => {
    const errors = validateIdentityValues(values(), settings({ ...off, orgAffiliationEnabled: true }), t);

    expect(errors).toEqual({ eid: 'validation.eidAttributeRequired' });
  });

  it('passes once an eID attribute is given', () => {
    expect(validateIdentityValues(
      values({ orgAffiliation: 'martin@2021006883' }),
      settings({ ...off, orgAffiliationEnabled: true }),
      t)).toEqual({});
  });

  it('reports nothing when there is no field that can be filled in', () => {
    expect(validateIdentityValues(
      values(),
      settings({ ...off, personalNumberEnabled: false }),
      t)).toEqual({});
  });

  it('requires the HSA-ID when that is the single field', () => {
    expect(validateIdentityValues(
      values(),
      settings({ ...off, personalNumberEnabled: false, hsaIdEnabled: true }),
      t)).toEqual({ hsaId: 'validation.required' });
  });
});

describe('validateIdentityValues, the user ID with the requirement on', () => {
  const s = settings({ allowSelectUserId: true, allowTemporaryPassword: true });

  it('may be left empty when an eID attribute is given', () => {
    expect(validateIdentityValues(values({ personalIdentityNumber: '196911292032' }), s, t))
      .toEqual({});
  });

  it('is required once a password is entered', () => {
    const errors = validateIdentityValues(
      values({ personalIdentityNumber: '196911292032', temporaryPassword: 'secret' }),
      s,
      t);

    expect(errors).toEqual({ userId: 'validation.userIdRequired' });
  });

  it('counts whitespace as empty', () => {
    const errors = validateIdentityValues(
      values({ userId: '   ', personalIdentityNumber: '196911292032', temporaryPassword: 'secret' }),
      s,
      t);

    expect(errors.userId).toBe('validation.userIdRequired');
  });

  it('does not replace the eID attribute', () => {
    const errors = validateIdentityValues(values({ userId: 'kalle' }), s, t);

    expect(errors).toEqual({ personalIdentityNumber: 'validation.required' });
  });
});

describe('validateIdentityValues, the user ID with the requirement off', () => {
  const s = settings({ allowSelectUserId: true, allowTemporaryPassword: true, eidAttributeRequired: false });

  it('reports at form level when neither a user ID nor an eID attribute is given', () => {
    expect(validateIdentityValues(values(), s, t)).toEqual({ eid: 'validation.eidAttributeRequired' });
    expect(validateIdentityValues(values(), { ...s, orgAffiliationEnabled: true }, t))
      .toEqual({ eid: 'validation.eidAttributeRequired' });
  });

  it('counts a whitespace user ID as no user ID', () => {
    expect(validateIdentityValues(values({ userId: '  ' }), s, t))
      .toEqual({ eid: 'validation.eidAttributeRequired' });
  });

  it('is satisfied by a user ID alone', () => {
    expect(validateIdentityValues(values({ userId: 'kalle' }), s, t)).toEqual({});
  });

  it('is satisfied by an eID attribute alone', () => {
    expect(validateIdentityValues(values({ personalIdentityNumber: '196911292032' }), s, t)).toEqual({});
  });

  it('reports a missing user ID on the field when a password is entered', () => {
    expect(validateIdentityValues(values({ temporaryPassword: 'secret' }), s, t))
      .toEqual({ userId: 'validation.userIdRequired' });
    expect(validateIdentityValues(
      values({ temporaryPassword: 'secret', personalIdentityNumber: '196911292032' }),
      s,
      t)).toEqual({ userId: 'validation.userIdRequired' });
  });

  it('passes with a user ID and a password', () => {
    expect(validateIdentityValues(values({ userId: 'kalle', temporaryPassword: 'secret' }), s, t))
      .toEqual({});
  });

  it('requires the user ID on the field when there is no eID field to fill in', () => {
    const noEid = { ...s, personalNumberEnabled: false };

    expect(validateIdentityValues(values(), noEid, t)).toEqual({ userId: 'validation.userIdRequired' });
    expect(validateIdentityValues(values({ userId: 'kalle' }), noEid, t)).toEqual({});
  });

  it('still validates the format of a given eID attribute', () => {
    expect(validateIdentityValues(values({ userId: 'kalle', personalIdentityNumber: '1969' }), s, t))
      .toEqual({ personalIdentityNumber: 'validation.pin12digits' });
  });
});

describe('validateIdentityValues, the user ID with the requirement off and an HSA-ID field', () => {
  const s = settings({
    allowSelectUserId: true,
    eidAttributeRequired: false,
    personalNumberEnabled: false,
    hsaIdEnabled: true,
  });

  it('reports at form level when neither is given', () => {
    expect(validateIdentityValues(values(), s, t)).toEqual({ eid: 'validation.eidAttributeRequired' });
  });

  it('is satisfied by the HSA-ID alone', () => {
    expect(validateIdentityValues(values({ hsaId: 'SE2321000016-1234' }), s, t)).toEqual({});
  });
});

describe('identityPayload', () => {
  it('carries a trimmed HSA-ID and EFOS-ID when their settings are on', () => {
    const payload = identityPayload(
      values({ hsaId: ' SE2321000016-1234 ', efosId: ' efos-4711 ' }),
      settings({ hsaIdEnabled: true, efosIdEnabled: true }));

    expect(payload.hsaId).toBe('SE2321000016-1234');
    expect(payload.efosId).toBe('efos-4711');
  });

  it('leaves out an HSA-ID and an EFOS-ID whose settings are off, or that are blank', () => {
    expect(identityPayload(values({ hsaId: 'x', efosId: 'y' }), settings()))
      .toMatchObject({ hsaId: undefined, efosId: undefined });
    expect(identityPayload(values({ hsaId: '  ', efosId: '' }), settings({ hsaIdEnabled: true, efosIdEnabled: true })))
      .toMatchObject({ hsaId: undefined, efosId: undefined });
  });
});
