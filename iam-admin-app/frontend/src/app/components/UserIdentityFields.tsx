import { useState } from 'react';
import { CreateUserInput, UserRegistrationSettings } from '@/types';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { Label } from '@/app/components/ui/label';
import { useLanguage } from '@/app/contexts/LanguageContext';

/** The identity values collected when a user is created. */
export interface IdentityValues {
  userId: string;
  temporaryPassword: string;
  personalIdentityNumber: string;
  orgAffiliation: string;
  hsaId: string;
  efosId: string;
}

export const EMPTY_IDENTITY_VALUES: IdentityValues = {
  userId: '',
  temporaryPassword: '',
  personalIdentityNumber: '',
  orgAffiliation: '',
  hsaId: '',
  efosId: '',
};

/** Error keys used for the identity fields. `eid` is reported at form level. */
export type IdentityErrors = Partial<Record<'userId' | 'personalIdentityNumber' | 'orgAffiliation' | 'eid', string>>;

const ORG_AFFILIATION_PATTERN = /^[^@\s]+@\d{10}$/;

/** An identity field the administrator can actually type into. */
export type FillableIdentityField = 'personalIdentityNumber' | 'orgAffiliation';

/**
 * The identity fields that are both enabled and possible to fill. HSA-ID and EFOS-ID are
 * rendered disabled while they are unimplemented, so they are not counted.
 */
export function fillableIdentityFields(settings: UserRegistrationSettings): FillableIdentityField[] {
  const fields: FillableIdentityField[] = [];
  if (settings.personalNumberEnabled) {
    fields.push('personalIdentityNumber');
  }
  if (settings.orgAffiliationEnabled) {
    fields.push('orgAffiliation');
  }
  return fields;
}

/**
 * The identity field that is required in its own right, or null when none is.
 *
 * With the requirement on and exactly one field to fill, that field is mandatory and is marked
 * and reported like any other required field. With more than one there is a choice to make, so
 * no single field is required and a missing value is reported at form level instead.
 */
export function requiredIdentityField(
  settings: UserRegistrationSettings
): FillableIdentityField | null {
  if (!settings.eidAttributeRequired) {
    return null;
  }
  const fields = fillableIdentityFields(settings);
  return fields.length === 1 ? fields[0] : null;
}

/**
 * Validates the identity values against the settings. Returns an empty object when everything
 * is in order. A missing identity is reported against the field when only one can be filled,
 * and at form level under the key `eid` when there are several to choose between.
 */
export function validateIdentityValues(
  values: IdentityValues,
  settings: UserRegistrationSettings,
  t: (key: string) => string
): IdentityErrors {
  const errors: IdentityErrors = {};

  if (settings.allowSelectUserId && !values.userId.trim()) {
    errors.userId = t('validation.userIdRequired');
  }

  const pin = settings.personalNumberEnabled ? values.personalIdentityNumber.replace(/-/g, '').trim() : '';
  if (pin && !/^\d{12}$/.test(pin)) {
    errors.personalIdentityNumber = t('validation.pin12digits');
  }

  const orgAffiliation = settings.orgAffiliationEnabled ? values.orgAffiliation.trim() : '';
  if (orgAffiliation && !ORG_AFFILIATION_PATTERN.test(orgAffiliation)) {
    errors.orgAffiliation = t('validation.orgAffiliationFormat');
  }

  // Only the personal identity number and the organizational affiliation are implemented, so
  // only they can satisfy the requirement for now.
  if (settings.eidAttributeRequired && !pin && !orgAffiliation) {
    const required = requiredIdentityField(settings);
    if (required === null) {
      errors.eid = t('validation.eidAttributeRequired');
    }
    else {
      errors[required] = t('validation.required');
    }
  }

  return errors;
}

/**
 * Turns the collected identity values into the fields of a create-user request. Values for
 * settings that are turned off are left out.
 */
export function identityPayload(
  values: IdentityValues,
  settings: UserRegistrationSettings
): Pick<CreateUserInput, 'userId' | 'personalIdentityNumber' | 'orgAffiliation' | 'temporaryPassword'> {
  const pin = values.personalIdentityNumber.replace(/-/g, '').trim();
  return {
    userId: settings.allowSelectUserId ? values.userId.trim() || undefined : undefined,
    temporaryPassword: settings.allowSelectUserId && settings.allowTemporaryPassword
      ? values.temporaryPassword || undefined
      : undefined,
    personalIdentityNumber: settings.personalNumberEnabled ? pin || undefined : undefined,
    orgAffiliation: settings.orgAffiliationEnabled ? values.orgAffiliation.trim() || undefined : undefined,
  };
}

interface UserIdentityFieldsProps {
  settings: UserRegistrationSettings;
  values: IdentityValues;
  errors: IdentityErrors;
  /** Prefixes the element ids so several forms can live on the same page. */
  idPrefix: string;
  onChange: (values: IdentityValues) => void;
}

/**
 * The identity part of a create-user form: user ID, initial password and the eID attributes
 * that the deployment has enabled.
 */
export function UserIdentityFields({ settings, values, errors, idPrefix, onChange }: UserIdentityFieldsProps) {
  const { t } = useLanguage();
  const [showOtherIdentities, setShowOtherIdentities] = useState(false);

  // Marked with an asterisk like every other required field on the form, but only when it is
  // the one identity field there is.
  const required = requiredIdentityField(settings);

  const set = (field: keyof IdentityValues, value: string) => {
    onChange({ ...values, [field]: value });
  };

  // HSA-ID and EFOS-ID are rendered when enabled, but cannot be edited yet.
  const otherIdentitiesEnabled =
    settings.orgAffiliationEnabled || settings.hsaIdEnabled || settings.efosIdEnabled;
  const otherIdentitiesVisible = !settings.personalNumberEnabled || showOtherIdentities;

  return (
    <>
      {settings.allowSelectUserId && (
        <div className="space-y-1">
          <Label htmlFor={`${idPrefix}-userId`}>{t('users.userId')} *</Label>
          <Input
            id={`${idPrefix}-userId`}
            value={values.userId}
            onChange={(e) => set('userId', e.target.value)}
            placeholder=""
          />
          <p className="text-xs text-gray-500">{t('users.userIdHelp')}</p>
          {errors.userId && <p className="text-xs text-red-500">{errors.userId}</p>}
        </div>
      )}

      {settings.allowSelectUserId && settings.allowTemporaryPassword && (
        <div className="space-y-1">
          <Label htmlFor={`${idPrefix}-temporaryPassword`}>{t('users.temporaryPassword')}</Label>
          <Input
            id={`${idPrefix}-temporaryPassword`}
            type="password"
            value={values.temporaryPassword}
            onChange={(e) => set('temporaryPassword', e.target.value)}
            placeholder=""
          />
          <p className="text-xs text-gray-500">{t('users.temporaryPasswordHelp')}</p>
        </div>
      )}

      {settings.personalNumberEnabled && (
        <div className="space-y-1">
          <Label htmlFor={`${idPrefix}-personalIdentityNumber`}>
            {t('users.uniqueIdentity')}{required === 'personalIdentityNumber' ? ' *' : ''}
          </Label>
          <Input
            id={`${idPrefix}-personalIdentityNumber`}
            value={values.personalIdentityNumber}
            onChange={(e) => set('personalIdentityNumber', e.target.value)}
            placeholder={t('validation.pin12digitsPlaceholder')}
          />
          {errors.personalIdentityNumber && (
            <p className="text-xs text-red-500">{errors.personalIdentityNumber}</p>
          )}
        </div>
      )}

      {otherIdentitiesEnabled && settings.personalNumberEnabled && !showOtherIdentities && (
        <Button type="button" variant="outline" size="sm" onClick={() => setShowOtherIdentities(true)}>
          {t('users.otherIdentities')}
        </Button>
      )}

      {otherIdentitiesEnabled && otherIdentitiesVisible && (
        <>
          {settings.orgAffiliationEnabled && (
            <div className="space-y-1">
              <Label htmlFor={`${idPrefix}-orgAffiliation`}>
                {t('users.orgAffiliation')}{required === 'orgAffiliation' ? ' *' : ''}
              </Label>
              <Input
                id={`${idPrefix}-orgAffiliation`}
                value={values.orgAffiliation}
                onChange={(e) => set('orgAffiliation', e.target.value)}
                placeholder={t('users.orgAffiliationPlaceholder')}
              />
              <p className="text-xs text-gray-500">{t('users.orgAffiliationHelp')}</p>
              {errors.orgAffiliation && <p className="text-xs text-red-500">{errors.orgAffiliation}</p>}
            </div>
          )}

          {settings.hsaIdEnabled && (
            <div className="space-y-1">
              <Label htmlFor={`${idPrefix}-hsaId`}>{t('users.hsaId')}</Label>
              <Input id={`${idPrefix}-hsaId`} value="" disabled placeholder="" />
              <p className="text-xs text-gray-500">{t('users.identityNotSupported')}</p>
            </div>
          )}

          {settings.efosIdEnabled && (
            <div className="space-y-1">
              <Label htmlFor={`${idPrefix}-efosId`}>{t('users.efosId')}</Label>
              <Input id={`${idPrefix}-efosId`} value="" disabled placeholder="" />
              <p className="text-xs text-gray-500">{t('users.identityNotSupported')}</p>
            </div>
          )}
        </>
      )}

      {errors.eid && <p className="text-xs text-red-500">{errors.eid}</p>}
    </>
  );
}
