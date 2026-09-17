import { useState, useEffect } from 'react';
import { User, Organization, FunctionType, UserRightData } from '@/types';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { Label } from '@/app/components/ui/label';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/app/components/ui/dialog';
import { Badge } from '@/app/components/ui/badge';
import { useLanguage } from '@/app/contexts/LanguageContext';
import { Building2, Boxes, X } from 'lucide-react';
import { formatPersonalIdentityNumber, resolveOrgName } from '@/utils';
import { UserIdTakenError } from '@/services/userService';
import { useUserRegistration } from '@/app/contexts/UserRegistrationContext';
import {
  EMPTY_IDENTITY_VALUES,
  IdentityErrors,
  IdentityValues,
  UserIdentityFields,
  identityPayload,
  validateIdentityValues,
} from '@/app/components/UserIdentityFields';

/** What the form hands over on save. The identity values are only set when creating a user. */
export type UserFormValues = Omit<User, 'id'> & {
  id?: string;
  userId?: string;
  temporaryPassword?: string;
};

type PendingOp =
  | { kind: 'remove'; orgIdentifier: string; functionId: string | null; right: 'read' | 'write' | 'admin' }
  | { kind: 'change'; orgIdentifier: string; functionId: string | null; newRight: 'read' | 'write' | 'admin'; oldRight: 'read' | 'write' | 'admin' };

interface UserFormProps {
  user: User | null;
  organizations: Organization[];
  functions: FunctionType[];
  isOpen: boolean;
  currentUserId: string;
  /** When false, the admin right is read-only: it cannot be granted, changed or removed here. */
  canAssignAdmin: boolean;
  onClose: () => void;
  /** Rejects with a UserIdTakenError when the chosen user ID is already in use. */
  onSave: (user: UserFormValues) => Promise<void> | void;
  onRemoveRight: (
    userId: string,
    orgIdentifier: string,
    functionId: string | null,
    right: 'read' | 'write' | 'admin'
  ) => void;
  onChangeRight: (
    userId: string,
    orgIdentifier: string,
    functionId: string | null,
    newRight: 'read' | 'write' | 'admin',
    oldRight: 'read' | 'write' | 'admin'
  ) => void;
}

export function UserForm({ user, organizations, functions, isOpen, currentUserId, canAssignAdmin, onClose, onSave, onRemoveRight, onChangeRight }: UserFormProps) {
  const { t, language } = useLanguage();
  const settings = useUserRegistration();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [identity, setIdentity] = useState<IdentityValues>(EMPTY_IDENTITY_VALUES);
  const [identityErrors, setIdentityErrors] = useState<IdentityErrors>({});
  const [phoneNumber, setPhoneNumber] = useState('');
  const [emailError, setEmailError] = useState('');
  const [phoneError, setPhoneError] = useState('');

  const [stagedRights, setStagedRights] = useState<UserRightData[]>([]);
  const [pendingOps, setPendingOps] = useState<PendingOp[]>([]);

  const [editingRight, setEditingRight] = useState<{
    orgIdentifier: string;
    functionId: string | null;
    currentRight: 'read' | 'write' | 'admin';
  } | null>(null);

  useEffect(() => {
    if (user) {
      setName(user.name);
      setEmail(user.email);
      setPhoneNumber(user.phoneNumber || '');
    } else {
      setName('');
      setEmail('');
      setPhoneNumber('');
    }
    setIdentity(EMPTY_IDENTITY_VALUES);
    setIdentityErrors({});
    setEmailError('');
    setPhoneError('');
    setStagedRights(user?.rights ?? []);
    setPendingOps([]);
    setEditingRight(null);
  }, [user, isOpen]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // Validate the identity values (create mode only)
    if (!user) {
      const errors = validateIdentityValues(identity, settings, t);
      setIdentityErrors(errors);
      if (Object.keys(errors).length > 0) {
        return;
      }
    }

    // Validate email
    const atIdx = email.indexOf('@');
    if (atIdx < 0 || !email.slice(atIdx + 1).includes('.')) {
      setEmailError('Please enter a valid email address');
      return;
    }
    setEmailError('');

    // Normalise and validate phone number
    const normalisedPhone = phoneNumber.replace(/[\s\-]/g, '');
    let resolvedPhone: string | undefined;
    if (normalisedPhone === '') {
      resolvedPhone = undefined;
    } else if (/^\+?\d+$/.test(normalisedPhone)) {
      resolvedPhone = normalisedPhone;
    } else {
      setPhoneError('Phone number may only contain digits, spaces, hyphens, and an optional leading +');
      return;
    }
    setPhoneError('');

    if (user) {
      // Edit mode: do not include personalIdentityNumber
      onSave({
        id: user.id,
        name,
        email,
        personalIdentityNumber: user.personalIdentityNumber,
        phoneNumber: resolvedPhone,
      });
      for (const op of pendingOps) {
        if (op.kind === 'remove') {
          onRemoveRight(user.id, op.orgIdentifier, op.functionId, op.right);
        } else {
          onChangeRight(user.id, op.orgIdentifier, op.functionId, op.newRight, op.oldRight);
        }
      }
    } else {
      // Create mode: include the identity values the settings allow
      const payload = identityPayload(identity, settings);
      try {
        await onSave({
          name,
          email,
          personalIdentityNumber: payload.personalIdentityNumber ?? '',
          orgAffiliation: payload.orgAffiliation,
          userId: payload.userId,
          temporaryPassword: payload.temporaryPassword,
          phoneNumber: resolvedPhone,
        });
      } catch (err) {
        if (err instanceof UserIdTakenError) {
          setIdentityErrors({ userId: t('validation.userIdTaken') });
        }
      }
    }
  };

  const stageChange = (orgIdentifier: string, functionId: string | null, newRight: 'read' | 'write' | 'admin', oldRight: 'read' | 'write' | 'admin') => {
    setStagedRights(prev => prev.map(r =>
      r.orgIdentifier === orgIdentifier && r.functionId === functionId
        ? { ...r, right: newRight }
        : r
    ));
    setPendingOps(prev => {
      const filtered = prev.filter(op => !(op.orgIdentifier === orgIdentifier && op.functionId === functionId));
      return [...filtered, { kind: 'change', orgIdentifier, functionId, newRight, oldRight }];
    });
    setEditingRight(null);
  };

  const stageRemove = (orgIdentifier: string, functionId: string | null, right: 'read' | 'write' | 'admin') => {
    setStagedRights(prev => prev.filter(r => !(r.orgIdentifier === orgIdentifier && r.functionId === functionId && r.right === right)));
    setPendingOps(prev => {
      const filtered = prev.filter(op => !(op.kind === 'change' && op.orgIdentifier === orgIdentifier && op.functionId === functionId));
      return [...filtered, { kind: 'remove', orgIdentifier, functionId, right }];
    });
  };

  const getRoleBadgeVariant = (role: string): 'default' | 'secondary' | 'outline' => {
    if (role === 'admin') return 'default';
    if (role === 'write') return 'secondary';
    return 'outline';
  };

  const orgRights = stagedRights.filter(r => r.functionId === null);
  const funcRights = stagedRights.filter(r => r.functionId !== null);

  const getOrgName = (org: Organization) => resolveOrgName(org, language);

  const isSelf = user !== null && user.id === currentUserId;

  return (
    <Dialog open={isOpen} onOpenChange={onClose}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {user ? t('users.edit') : t('users.create')}
          </DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Name */}
          <div className="space-y-2">
            <Label htmlFor="name">{t('users.name')} *</Label>
            <Input
              id="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder=""
              required
            />
          </div>

          {/* Identities — read-only in edit mode, collected in create mode */}
          {user ? (
            <>
              {user.personalIdentityNumber && (
                <div className="space-y-2">
                  <Label>{t('users.uniqueIdentity')}</Label>
                  <div className="px-3 py-2 text-sm bg-gray-50 border rounded-md text-gray-700">
                    {formatPersonalIdentityNumber(user.personalIdentityNumber)}
                  </div>
                </div>
              )}
              {user.orgAffiliation && (
                <div className="space-y-2">
                  <Label>{t('users.orgAffiliation')}</Label>
                  <div className="px-3 py-2 text-sm bg-gray-50 border rounded-md text-gray-700">
                    {user.orgAffiliation}
                  </div>
                </div>
              )}
            </>
          ) : (
            <UserIdentityFields
              settings={settings}
              values={identity}
              errors={identityErrors}
              idPrefix="userForm"
              onChange={(values) => { setIdentity(values); setIdentityErrors({}); }}
            />
          )}

          {/* Email */}
          <div className="space-y-2">
            <Label htmlFor="email">{t('users.email')} *</Label>
            <Input
              id="email"
              type="text"
              value={email}
              onChange={(e) => { setEmail(e.target.value); setEmailError(''); }}
              placeholder=""
              required
            />
            {emailError && <p className="text-sm text-red-500">{emailError}</p>}
          </div>

          {/* Phone number */}
          <div className="space-y-2">
            <Label htmlFor="phoneNumber">{t('users.phoneNumber')}</Label>
            <Input
              id="phoneNumber"
              type="tel"
              value={phoneNumber}
              onChange={(e) => { setPhoneNumber(e.target.value); setPhoneError(''); }}
              placeholder=""
            />
            {phoneError && <p className="text-sm text-red-500">{phoneError}</p>}
          </div>

          {/* Rights panel (edit mode only) */}
          <div className="space-y-2">
            {user && (orgRights.length > 0 || funcRights.length > 0) && (
              <div className="space-y-3">
                <Label>{t('users.assignedRights')}</Label>

                {/* Organization-level rights */}
                {orgRights.length > 0 && (
                  <div>
                    <div className="flex items-center gap-1.5 mb-2">
                      <Building2 className="w-3.5 h-3.5 text-gray-500" />
                      <p className="text-xs font-medium text-gray-500 uppercase">{t('users.orgLevelRights')}</p>
                    </div>
                    <div className="space-y-1 p-3 bg-gray-50 rounded border">
                      {orgRights.map((r, i) => {
                        const org = organizations.find((o) => o.id === r.orgIdentifier);
                        const orgName = org ? getOrgName(org) : r.orgIdentifier;
                        const isEditingThis =
                          editingRight !== null &&
                          editingRight.orgIdentifier === r.orgIdentifier &&
                          editingRight.functionId === null;
                        return (
                          <div key={i} className="flex items-center justify-between bg-white p-2 rounded border">
                            <span className="text-sm">{orgName}</span>
                            {isSelf || (!canAssignAdmin && r.right === 'admin') ? (
                              <Badge variant={getRoleBadgeVariant(r.right)}>{t(`role.${r.right}`)}</Badge>
                            ) : (
                              <div className="flex items-center gap-1">
                                {isEditingThis ? (
                                  <select
                                    className="text-sm border rounded px-1 py-0.5"
                                    defaultValue={r.right}
                                    autoFocus
                                    onChange={(e) => {
                                      stageChange(r.orgIdentifier, null, e.target.value as 'read' | 'write' | 'admin', r.right);
                                    }}
                                    onBlur={() => setEditingRight(null)}
                                  >
                                    <option value="read">{t('role.read')}</option>
                                    <option value="write">{t('role.write')}</option>
                                    {canAssignAdmin && <option value="admin">{t('role.admin')}</option>}
                                  </select>
                                ) : (
                                  <Badge
                                    variant={getRoleBadgeVariant(r.right)}
                                    className="cursor-pointer hover:opacity-75"
                                    onClick={() => setEditingRight({ orgIdentifier: r.orgIdentifier, functionId: null, currentRight: r.right as 'read' | 'write' | 'admin' })}
                                  >
                                    {t(`role.${r.right}`)}
                                  </Badge>
                                )}
                                <Button
                                  type="button"
                                  variant="ghost"
                                  size="sm"
                                  className="h-7 w-7 p-0 hover:bg-red-50"
                                  onClick={() => stageRemove(r.orgIdentifier, null, r.right as 'read' | 'write' | 'admin')}
                                >
                                  <X className="w-4 h-4 text-red-500" />
                                </Button>
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  </div>
                )}

                {/* Function-level rights */}
                {funcRights.length > 0 && (
                  <div>
                    <div className="flex items-center gap-1.5 mb-2">
                      <Boxes className="w-3.5 h-3.5 text-gray-500" />
                      <p className="text-xs font-medium text-gray-500 uppercase">{t('users.functionLevelRights')}</p>
                    </div>
                    <div className="space-y-1 p-3 bg-gray-50 rounded border">
                      {funcRights.map((r, i) => {
                        const org = organizations.find((o) => o.id === r.orgIdentifier);
                        const func = functions.find((f) => f.id === r.functionId);
                        const orgName = org ? getOrgName(org) : r.orgIdentifier;
                        const isEditingThis =
                          editingRight !== null &&
                          editingRight.orgIdentifier === r.orgIdentifier &&
                          editingRight.functionId === r.functionId;
                        return (
                          <div key={i} className="flex items-center justify-between bg-white p-2 rounded border">
                            <div>
                              <p className="text-sm font-medium">{orgName}</p>
                              <p className="text-xs text-gray-500">{func ? (language === 'sv' ? func.nameSv : func.nameEn) || (language === 'sv' ? func.nameEn : func.nameSv) || func.name : r.functionId}</p>
                            </div>
                            {isSelf || (!canAssignAdmin && r.right === 'admin') ? (
                              <Badge variant={getRoleBadgeVariant(r.right)}>{t(`role.${r.right}`)}</Badge>
                            ) : (
                              <div className="flex items-center gap-1">
                                {isEditingThis ? (
                                  <select
                                    className="text-sm border rounded px-1 py-0.5"
                                    defaultValue={r.right}
                                    autoFocus
                                    onChange={(e) => {
                                      stageChange(r.orgIdentifier, r.functionId, e.target.value as 'read' | 'write' | 'admin', r.right);
                                    }}
                                    onBlur={() => setEditingRight(null)}
                                  >
                                    <option value="read">{t('role.read')}</option>
                                    <option value="write">{t('role.write')}</option>
                                    {canAssignAdmin && <option value="admin">{t('role.admin')}</option>}
                                  </select>
                                ) : (
                                  <Badge
                                    variant={getRoleBadgeVariant(r.right)}
                                    className="cursor-pointer hover:opacity-75"
                                    onClick={() => setEditingRight({ orgIdentifier: r.orgIdentifier, functionId: r.functionId, currentRight: r.right as 'read' | 'write' | 'admin' })}
                                  >
                                    {t(`role.${r.right}`)}
                                  </Badge>
                                )}
                                <Button
                                  type="button"
                                  variant="ghost"
                                  size="sm"
                                  className="h-7 w-7 p-0 hover:bg-red-50"
                                  onClick={() => stageRemove(r.orgIdentifier, r.functionId, r.right as 'read' | 'write' | 'admin')}
                                >
                                  <X className="w-4 h-4 text-red-500" />
                                </Button>
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit">
              {user ? 'Update' : 'Create'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
