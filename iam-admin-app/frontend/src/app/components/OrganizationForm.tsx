import { useState, useEffect } from 'react';
import { Organization } from '@/types';
import { formatOrgNumber } from '@/utils';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { Label } from '@/app/components/ui/label';
import { ArrowLeft } from 'lucide-react';
import { useLanguage } from '@/app/contexts/LanguageContext';
import { Header } from '@/app/components/Header';
import { Footer } from '@/app/components/Footer';

interface OrganizationFormProps {
  organization: Organization | null;
  isOpen: boolean;
  isSuperuser: boolean;
  currentUserOrgAdminIds: string[];
  onClose: () => void;
  onSave: (org: Omit<Organization, 'id'> & { id?: string }) => void;
}

export function OrganizationForm({ organization, isOpen, isSuperuser, onClose, onSave }: OrganizationFormProps) {
  const { t } = useLanguage();
  const [nameSv, setNameSv] = useState('');
  const [nameEn, setNameEn] = useState('');
  const [organizationNumber, setOrganizationNumber] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [contactPhone, setContactPhone] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (isOpen) {
      setErrors({});
      if (organization) {
        setNameSv(organization.nameSv);
        setNameEn(organization.nameEn);
        setOrganizationNumber(formatOrgNumber(organization.organizationNumber));
        setContactEmail(organization.contactEmail || '');
        setContactPhone(organization.additionalData?.contactPhone || '');
      } else {
        setNameSv('');
        setNameEn('');
        setOrganizationNumber('');
        setContactEmail('');
        setContactPhone('');
      }
    }
  }, [organization, isOpen]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    const newErrors: Record<string, string> = {};
    const normalizedOrgNumber = organizationNumber.replace(/-/g, '');
    if (!organization && !/^\d{10}$/.test(normalizedOrgNumber)) {
      newErrors.organizationNumber = t('organizations.invalidOrgNumber');
    }

    // Name validation only for superusers (non-superusers don't see name fields in edit mode)
    if (!organization || isSuperuser) {
      if (!nameSv.trim()) {
        newErrors.nameSv = 'Required';
      }
      if (!nameEn.trim()) {
        newErrors.nameEn = 'Required';
      }
    }

    // Contact email validation
    const trimmedEmail = contactEmail.trim();
    if (trimmedEmail) {
      const atIdx = trimmedEmail.indexOf('@');
      if (atIdx < 0 || !trimmedEmail.slice(atIdx + 1).includes('.')) {
        newErrors.contactEmail = 'Please enter a valid email address';
      }
    }

    // Contact phone normalisation and validation (at submit time)
    const normalisedPhone = contactPhone.replace(/[\s\-]/g, '');
    if (normalisedPhone && !/^\+?\d+$/.test(normalisedPhone)) {
      newErrors.contactPhone = 'Phone number may only contain digits, spaces, hyphens, and an optional leading +';
    }

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      return;
    }

    const resolvedPhone = normalisedPhone || undefined;
    const resolvedEmail = trimmedEmail || undefined;

    if (organization) {
      // Edit mode
      const additionalData: Record<string, string> = {};
      if (resolvedPhone) {
        additionalData.contactPhone = resolvedPhone;
      }
      onSave({
        id: organization.id,
        nameSv: isSuperuser ? nameSv : undefined,
        nameEn: isSuperuser ? nameEn : undefined,
        organizationNumber: organizationNumber,
        contactEmail: resolvedEmail,
        additionalData: Object.keys(additionalData).length > 0 ? additionalData : undefined,
      });
    } else {
      // Create mode
      const additionalData: Record<string, string> = {};
      if (resolvedPhone) {
        additionalData.contactPhone = resolvedPhone;
      }
      onSave({
        nameSv,
        nameEn,
        organizationNumber: normalizedOrgNumber,
        contactEmail: resolvedEmail,
        additionalData: Object.keys(additionalData).length > 0 ? additionalData : undefined,
      });
    }
  };

  if (!isOpen) {
    return null;
  }

  return (
    <div className="fixed inset-0 bg-background z-50 overflow-y-auto flex flex-col">
      <Header />

      {/* Page Header */}
      <div className="bg-white border-b">
        <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
          <div className="flex items-center gap-4">
            <Button
              variant="ghost"
              size="sm"
              onClick={onClose}
              className="hover:bg-gray-100"
            >
              <ArrowLeft className="w-5 h-5" />
            </Button>
            <div>
              <h1 className="text-2xl font-semibold text-gray-900">
                {organization ? t('organizations.edit') : t('organizations.create')}
              </h1>
              <p className="text-sm text-gray-500 mt-1">
                {organization
                  ? t('organizations.editDescription')
                  : t('organizations.createDescription')
                }
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* Form Content */}
      <div className="flex-1 max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-8 w-full">
        <div className="bg-white rounded-lg shadow-sm border p-8">
          <form onSubmit={handleSubmit} className="space-y-6">
            {/* Organization Number - First field in both modes */}
            <div className="space-y-2">
              <Label htmlFor="orgNumber" className="text-base font-medium">
                {t('organizations.number')} *
              </Label>
              {organization ? (
                <div className="px-3 py-2 text-sm bg-gray-50 border rounded-md text-gray-700">
                  {organizationNumber}
                </div>
              ) : (
                <Input
                  id="orgNumber"
                  value={organizationNumber}
                  onChange={(e) => setOrganizationNumber(e.target.value)}
                  className="text-base"
                />
              )}
              {errors.organizationNumber && (
                <p className="text-sm text-red-500 mt-1">{errors.organizationNumber}</p>
              )}
            </div>

            {/* Organization Names */}
            <div className="space-y-2">
              <Label htmlFor="nameSv" className="text-base font-medium">
                {t('organizations.nameSv')} {(!organization || isSuperuser) && '*'}
              </Label>
              {organization && !isSuperuser ? (
                <div className="px-3 py-2 text-sm bg-gray-50 border rounded-md text-gray-700">
                  {nameSv}
                </div>
              ) : (
                <Input
                  id="nameSv"
                  value={nameSv}
                  onChange={(e) => setNameSv(e.target.value)}
                  className="text-base"
                />
              )}
              {errors.nameSv && (
                <p className="text-sm text-red-500 mt-1">{errors.nameSv}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="nameEn" className="text-base font-medium">
                {t('organizations.nameEn')} {(!organization || isSuperuser) && '*'}
              </Label>
              {organization && !isSuperuser ? (
                <div className="px-3 py-2 text-sm bg-gray-50 border rounded-md text-gray-700">
                  {nameEn}
                </div>
              ) : (
                <Input
                  id="nameEn"
                  value={nameEn}
                  onChange={(e) => setNameEn(e.target.value)}
                  className="text-base"
                />
              )}
              {errors.nameEn && (
                <p className="text-sm text-red-500 mt-1">{errors.nameEn}</p>
              )}
            </div>

            {/* Edit Mode Only: Contact Email and Phone */}
            {organization && (
              <>
                <div className="space-y-2">
                  <Label htmlFor="contactEmail" className="text-base font-medium">
                    {t('organizations.contactEmail')}
                  </Label>
                  <Input
                    id="contactEmail"
                    type="text"
                    value={contactEmail}
                    onChange={(e) => { setContactEmail(e.target.value); setErrors(prev => ({ ...prev, contactEmail: '' })); }}
                    className="text-base"
                  />
                  {errors.contactEmail && (
                    <p className="text-sm text-red-500 mt-1">{errors.contactEmail}</p>
                  )}
                </div>

                <div className="space-y-2">
                  <Label htmlFor="contactPhone" className="text-base font-medium">
                    {t('organizations.contactPhone')}
                  </Label>
                  <Input
                    id="contactPhone"
                    type="tel"
                    value={contactPhone}
                    onChange={(e) => { setContactPhone(e.target.value); setErrors(prev => ({ ...prev, contactPhone: '' })); }}
                    className="text-base"
                  />
                  {errors.contactPhone && (
                    <p className="text-sm text-red-500 mt-1">{errors.contactPhone}</p>
                  )}
                </div>
              </>
            )}

            {/* Form Actions */}
            <div className="flex items-center justify-end gap-3 pt-6 border-t">
              <Button type="button" variant="outline" onClick={onClose}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" className="bg-primary hover:bg-primary/90">
                {organization ? t('common.save') : t('organizations.create')}
              </Button>
            </div>
          </form>
        </div>
      </div>

      <Footer />
    </div>
  );
}
