import { describe, expect, it } from 'vitest';
import { UserRegistrationSettings } from '@/types';
import {
  EMPTY_IDENTITY_VALUES,
  IdentityValues,
  fillableIdentityFields,
  requiredIdentityField,
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

  it('leaves out HSA-ID and EFOS-ID, which are rendered disabled', () => {
    expect(fillableIdentityFields(settings({ hsaIdEnabled: true, efosIdEnabled: true })))
      .toEqual(['personalIdentityNumber']);
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

  it('ignores the unimplemented fields when counting', () => {
    expect(requiredIdentityField(settings({ hsaIdEnabled: true, efosIdEnabled: true })))
      .toBe('personalIdentityNumber');
  });

  it('is nothing when the requirement is off, whatever is enabled', () => {
    expect(requiredIdentityField(settings({ eidAttributeRequired: false }))).toBeNull();
    expect(requiredIdentityField(settings({
      eidAttributeRequired: false,
      orgAffiliationEnabled: true,
    }))).toBeNull();
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

  it('keeps reporting against the field when only unimplemented fields are added', () => {
    const errors = validateIdentityValues(
      values(),
      settings({ hsaIdEnabled: true, efosIdEnabled: true }),
      t);

    expect(errors.personalIdentityNumber).toBe('validation.required');
    expect(errors.eid).toBeUndefined();
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

  it('still validates the format of each value that is given', () => {
    const errors = validateIdentityValues(
      values({ orgAffiliation: 'martin@202100688' }),
      settings({ orgAffiliationEnabled: true }),
      t);

    expect(errors.orgAffiliation).toBe('validation.orgAffiliationFormat');
    expect(errors.eid).toBeUndefined();
  });
});

describe('validateIdentityValues, the requirement turned off', () => {
  it('reports nothing for an empty set, with one field or with several', () => {
    expect(validateIdentityValues(values(), settings({ eidAttributeRequired: false }), t))
      .toEqual({});
    expect(validateIdentityValues(
      values(),
      settings({ eidAttributeRequired: false, orgAffiliationEnabled: true }),
      t)).toEqual({});
  });
});

describe('validateIdentityValues, the user ID', () => {
  it('is required on its own account, independently of the identity fields', () => {
    const errors = validateIdentityValues(
      values({ personalIdentityNumber: '196911292032' }),
      settings({ allowSelectUserId: true }),
      t);

    expect(errors.userId).toBe('validation.userIdRequired');
  });
});
