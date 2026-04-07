import { useState, useEffect } from 'react';
import { User, UserOrganizationRole, Organization } from '@/types';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { Label } from '@/app/components/ui/label';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/app/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/app/components/ui/select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/app/components/ui/tabs';
import { Search, User as UserIcon, Loader2, Info } from 'lucide-react';
import { useLanguage } from '@/app/contexts/LanguageContext';
import { createUser, DuplicatePinError } from '@/services/userService';

interface AddUserToOrgDialogProps {
  open: boolean;
  onClose: () => void;
  organization: Organization | null;
  users: User[];
  userRoles: UserOrganizationRole[];
  currentUserId: string;
  onAddUserToOrg: (organizationId: string, userId: string, role: string) => void;
  onRemoveUserFromOrg?: (organizationId: string, userId: string) => void;
  onUserCreated?: (user: User) => void;
}

export function AddUserToOrgDialog({
  open,
  onClose,
  organization,
  users,
  userRoles,
  currentUserId,
  onAddUserToOrg,
  onUserCreated,
}: AddUserToOrgDialogProps) {
  const { t, language } = useLanguage();

  // Shared
  const [activeTab, setActiveTab] = useState<string>('select');
  const [selectedRole, setSelectedRole] = useState<string>('write');

  // Select-tab state
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedUserId, setSelectedUserId] = useState<string>('');

  // Create-tab state
  const [newName, setNewName] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [newPin, setNewPin] = useState('');
  const [newPhone, setNewPhone] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [duplicateUser, setDuplicateUser] = useState<User | null>(null);
  const [externalDuplicateUserId, setExternalDuplicateUserId] = useState<string | null>(null);

  const getOrgName = (org: Organization) =>
    language === 'sv' ? org.nameSv : org.nameEn;

  // Reset all state when dialog closes
  useEffect(() => {
    if (!open) {
      setActiveTab('select');
      setSelectedRole('write');
      setSearchTerm('');
      setSelectedUserId('');
      setNewName('');
      setNewEmail('');
      setNewPin('');
      setNewPhone('');
      setFieldErrors({});
      setIsSubmitting(false);
      setDuplicateUser(null);
      setExternalDuplicateUserId(null);
    }
  }, [open]);

  // When the dialog opens, jump straight to "create" if there is nobody to select
  useEffect(() => {
    if (open && availableUsers.length === 0) {
      setActiveTab('create');
    }
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Select-tab helpers ──────────────────────────────────────────────────────

  const existingUserIds = organization
    ? userRoles.filter((r) => r.organizationId === organization.id).map((r) => r.userId)
    : [];

  const availableUsers = organization
    ? users.filter((u) => !existingUserIds.includes(u.id) && u.id !== currentUserId)
    : [];

  const filteredUsers = availableUsers.filter((u) => {
    const q = searchTerm.toLowerCase();
    return (
      u.name.toLowerCase().includes(q) ||
      u.personalIdentityNumber.toLowerCase().includes(q) ||
      u.email.toLowerCase().includes(q)
    );
  });

  const handleSelectAndAdd = () => {
    if (selectedUserId && organization) {
      onAddUserToOrg(organization.id, selectedUserId, selectedRole);
      onClose();
    }
  };

  // ── Create-tab helpers ──────────────────────────────────────────────────────

  const handleCreateAndAdd = async () => {
    const pinStripped = newPin.replace(/-/g, '');
    const errors: Record<string, string> = {};
    if (!newName.trim()) errors.name = t('validation.required');
    if (!newEmail.trim() || !newEmail.includes('@')) errors.email = t('validation.emailRequired');
    if (!/^\d{12}$/.test(pinStripped)) errors.pin = t('validation.pin12digits');
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      return;
    }
    setFieldErrors({});
    setIsSubmitting(true);
    try {
      const created = await createUser({
        name: newName.trim(),
        email: newEmail.trim(),
        personalIdentityNumber: pinStripped,
        phoneNumber: newPhone.trim() || undefined,
        superuser: false,
        rights: [],
      });
      onAddUserToOrg(organization!.id, created.id, selectedRole);
      onClose();
    } catch (err) {
      if (err instanceof DuplicatePinError) {
        const existing = err.existingUserId
          ? users.find((u) => u.id === err.existingUserId) ?? null
          : null;
        if (existing) {
          setDuplicateUser(existing);
        } else if (err.existingUserId) {
          setExternalDuplicateUserId(err.existingUserId);
        } else {
          // No ID provided — fall back to a generic error
          setFieldErrors({ general: t('org.userExistsNotVisible') });
        }
      } else {
        setFieldErrors({ general: t('validation.createUserFailed') });
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  if (!organization) return null;

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{t('org.addUserToOrg')}</DialogTitle>
          <DialogDescription>{getOrgName(organization)}</DialogDescription>
        </DialogHeader>

        <Tabs value={activeTab} onValueChange={setActiveTab} className="mt-2">
          <TabsList className="w-full">
            <TabsTrigger value="select" className="flex-1">
              {t('org.selectExistingUser')}
            </TabsTrigger>
            <TabsTrigger value="create" className="flex-1">
              {t('org.createNewUser')}
            </TabsTrigger>
          </TabsList>

          {/* ── Select existing user ── */}
          <TabsContent value="select" className="space-y-4 pt-2">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
              <Input
                placeholder={t('search.users')}
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="pl-10"
              />
            </div>

            <div className="border rounded-lg max-h-52 overflow-y-auto">
              {filteredUsers.length === 0 ? (
                <p className="p-4 text-center text-sm text-gray-500">
                  {availableUsers.length === 0
                    ? t('org.userAlreadyAdded')
                    : t('common.noResults')}
                </p>
              ) : (
                <div className="divide-y">
                  {filteredUsers.map((user) => (
                    <div
                      key={user.id}
                      className={`p-3 cursor-pointer hover:bg-gray-50 transition-colors ${
                        selectedUserId === user.id ? 'bg-primary/10' : ''
                      }`}
                      onClick={() => setSelectedUserId(user.id)}
                    >
                      <div className="flex items-center gap-3">
                        <UserIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
                        <div className="flex-1 min-w-0">
                          <p className="text-sm font-medium truncate">{user.name}</p>
                          <p className="text-xs text-gray-500 truncate">{user.email}</p>
                          <p className="text-xs text-gray-400">{user.personalIdentityNumber}</p>
                        </div>
                        {selectedUserId === user.id && (
                          <div className="w-2 h-2 rounded-full bg-primary flex-shrink-0" />
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {selectedUserId && (
              <div className="space-y-1">
                <Label>{t('org.selectRole')}</Label>
                <Select value={selectedRole} onValueChange={setSelectedRole}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="read">{t('role.read')}</SelectItem>
                    <SelectItem value="write">{t('role.write')}</SelectItem>
                    <SelectItem value="admin">{t('role.admin')}</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            )}

            <DialogFooter>
              <Button variant="outline" onClick={onClose}>
                {t('common.cancel')}
              </Button>
              <Button
                onClick={handleSelectAndAdd}
                disabled={!selectedUserId}
                className="bg-primary hover:bg-primary/90"
              >
                {t('org.addUser')}
              </Button>
            </DialogFooter>
          </TabsContent>

          {/* ── Create new user ── */}
          <TabsContent value="create" className="space-y-3 pt-2">
            {duplicateUser ? (
              /* Case A: existing user is visible to this admin */
              <div className="space-y-4">
                <div className="flex items-start gap-3 p-3 bg-blue-50 border border-blue-200 rounded-lg">
                  <Info className="w-5 h-5 text-blue-500 flex-shrink-0 mt-0.5" />
                  <div>
                    <p className="text-sm font-medium text-blue-800">{t('org.userAlreadyExists')}</p>
                    <p className="text-xs text-blue-700 mt-1">{t('org.userAlreadyExistsInfo')}</p>
                  </div>
                </div>
                <div className="border rounded-lg p-3 bg-gray-50 space-y-1">
                  <p className="text-sm font-medium">{duplicateUser.name}</p>
                  <p className="text-xs text-gray-500">{duplicateUser.email}</p>
                  <p className="text-xs text-gray-400">{duplicateUser.personalIdentityNumber}</p>
                </div>
                <div className="space-y-1">
                  <Label>{t('org.selectRole')}</Label>
                  <Select value={selectedRole} onValueChange={setSelectedRole}>
                    <SelectTrigger><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="read">{t('role.read')}</SelectItem>
                      <SelectItem value="write">{t('role.write')}</SelectItem>
                      <SelectItem value="admin">{t('role.admin')}</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <DialogFooter>
                  <Button variant="outline" onClick={() => setDuplicateUser(null)}>{t('common.cancel')}</Button>
                  <Button
                    onClick={() => { onAddUserToOrg(organization!.id, duplicateUser.id, selectedRole); onClose(); }}
                    className="bg-primary hover:bg-primary/90"
                  >
                    {t('org.addUser')}
                  </Button>
                </DialogFooter>
              </div>
            ) : externalDuplicateUserId ? (
              /* Case B: user exists in another org — offer to import */
              <div className="space-y-4">
                <div className="flex items-start gap-3 p-3 bg-blue-50 border border-blue-200 rounded-lg">
                  <Info className="w-5 h-5 text-blue-500 flex-shrink-0 mt-0.5" />
                  <p className="text-sm text-blue-800">{t('org.userExistsNotVisible')}</p>
                </div>
                <div className="space-y-1">
                  <Label>{t('org.selectRole')}</Label>
                  <Select value={selectedRole} onValueChange={setSelectedRole}>
                    <SelectTrigger><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="read">{t('role.read')}</SelectItem>
                      <SelectItem value="write">{t('role.write')}</SelectItem>
                      <SelectItem value="admin">{t('role.admin')}</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <DialogFooter>
                  <Button variant="outline" onClick={() => setExternalDuplicateUserId(null)}>
                    {t('common.cancel')}
                  </Button>
                  <Button
                    onClick={() => {
                      onAddUserToOrg(organization!.id, externalDuplicateUserId, selectedRole);
                      onClose();
                    }}
                    className="bg-primary hover:bg-primary/90"
                  >
                    {t('org.assignExistingUser')}
                  </Button>
                </DialogFooter>
              </div>
            ) : (
              /* Normal create form */
              <>
                <div className="space-y-1">
                  <Label>{t('users.name')} *</Label>
                  <Input
                    value={newName}
                    onChange={(e) => setNewName(e.target.value)}
                    placeholder=""
                  />
                  {fieldErrors.name && (
                    <p className="text-xs text-red-500">{fieldErrors.name}</p>
                  )}
                </div>

                <div className="space-y-1">
                  <Label>{t('users.email')} *</Label>
                  <Input
                    type="email"
                    value={newEmail}
                    onChange={(e) => setNewEmail(e.target.value)}
                    placeholder=""
                  />
                  {fieldErrors.email && (
                    <p className="text-xs text-red-500">{fieldErrors.email}</p>
                  )}
                </div>

                <div className="space-y-1">
                  <Label>{t('users.uniqueIdentity')} *</Label>
                  <Input
                    value={newPin}
                    onChange={(e) => setNewPin(e.target.value)}
                    placeholder={t('validation.pin12digitsPlaceholder')}
                  />
                  {fieldErrors.pin && (
                    <p className="text-xs text-red-500">{fieldErrors.pin}</p>
                  )}
                </div>

                <div className="space-y-1">
                  <Label>{t('users.phoneNumber')}</Label>
                  <Input
                    type="tel"
                    value={newPhone}
                    onChange={(e) => setNewPhone(e.target.value)}
                    placeholder=""
                  />
                </div>

                <div className="space-y-1">
                  <Label>{t('org.selectRole')}</Label>
                  <Select value={selectedRole} onValueChange={setSelectedRole}>
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="read">{t('role.read')}</SelectItem>
                      <SelectItem value="write">{t('role.write')}</SelectItem>
                      <SelectItem value="admin">{t('role.admin')}</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                {fieldErrors.general && (
                  <p className="text-xs text-red-500">{fieldErrors.general}</p>
                )}

                <DialogFooter>
                  <Button variant="outline" onClick={onClose}>
                    {t('common.cancel')}
                  </Button>
                  <Button
                    onClick={handleCreateAndAdd}
                    disabled={isSubmitting}
                    className="bg-primary hover:bg-primary/90"
                  >
                    {isSubmitting && <Loader2 className="w-4 h-4 mr-2 animate-spin" />}
                    {t('org.createAndAddUser')}
                  </Button>
                </DialogFooter>
              </>
            )}
          </TabsContent>
        </Tabs>
      </DialogContent>
    </Dialog>
  );
}
