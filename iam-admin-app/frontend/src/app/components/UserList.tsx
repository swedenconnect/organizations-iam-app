import { User, Organization, FunctionType, UserRightData } from '@/types';
import { Button } from '@/app/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/app/components/ui/card';
import { Badge } from '@/app/components/ui/badge';
import { Input } from '@/app/components/ui/input';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/app/components/ui/alert-dialog';
import { Pencil, Trash2, Users, Mail, Phone, Search, Building2, Boxes, ChevronDown, ChevronRight, ShieldCheck, X } from 'lucide-react';
import { useLanguage } from '@/app/contexts/LanguageContext';
import { useState } from 'react';
import { formatPersonalIdentityNumber } from '@/utils';

function groupRightsByOrg(rights: UserRightData[]) {
  const map = new Map<string, { orgRights: UserRightData[]; funcRights: UserRightData[] }>();
  for (const r of rights) {
    if (!map.has(r.orgIdentifier)) {
      map.set(r.orgIdentifier, { orgRights: [], funcRights: [] });
    }
    const entry = map.get(r.orgIdentifier)!;
    if (r.functionId === null) {
      entry.orgRights.push(r);
    } else {
      entry.funcRights.push(r);
    }
  }
  return Array.from(map.entries()).map(([orgIdentifier, { orgRights, funcRights }]) => ({
    orgIdentifier,
    orgRights,
    funcRights,
  }));
}

function checkDeleteSafety(
  targetUser: User,
  allUsers: User[],
  organizations: Organization[],
  functions: FunctionType[],
  language: string
): string[] {
  const reasons: string[] = [];
  const rights = targetUser.rights ?? [];
  for (const right of rights) {
    if (right.right !== 'admin') continue;
    let otherAdminExists: boolean;
    if (right.functionId === null) {
      otherAdminExists = allUsers.some(
        (u) =>
          u.id !== targetUser.id &&
          (u.rights ?? []).some(
            (r) => r.orgIdentifier === right.orgIdentifier && r.functionId === null && r.right === 'admin'
          )
      );
      if (!otherAdminExists) {
        const org = organizations.find((o) => o.id === right.orgIdentifier);
        const name = org ? (language === 'sv' ? org.nameSv : org.nameEn) : right.orgIdentifier;
        if (!reasons.includes(name)) reasons.push(name);
      }
    } else {
      otherAdminExists = allUsers.some(
        (u) =>
          u.id !== targetUser.id &&
          (u.rights ?? []).some(
            (r) =>
              r.orgIdentifier === right.orgIdentifier &&
              r.right === 'admin' &&
              (r.functionId === right.functionId || r.functionId === null)
          )
      );
      if (!otherAdminExists) {
        const func = functions.find((f) => f.id === right.functionId);
        const name = func
          ? (language === 'sv' ? func.nameSv : func.nameEn) ||
            (language === 'sv' ? func.nameEn : func.nameSv) ||
            func.name
          : right.functionId ?? right.orgIdentifier;
        if (!reasons.includes(name)) reasons.push(name);
      }
    }
  }
  return reasons;
}

interface UserListProps {
  users: User[];
  organizations: Organization[];
  functions: FunctionType[];
  currentUserId: string;
  isSuperuser: boolean;
  onEdit: (user: User) => void;
  onDeleteUser: (userId: string) => void;
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

export function UserList({ users, organizations, functions, currentUserId, isSuperuser, onEdit, onDeleteUser, onRemoveRight, onChangeRight }: UserListProps) {
  const { t, language } = useLanguage();
  const [searchTerm, setSearchTerm] = useState('');
  const [expandedUsers, setExpandedUsers] = useState<Set<string>>(new Set());

  const [confirmRemove, setConfirmRemove] = useState<{
    userId: string;
    orgIdentifier: string;
    functionId: string | null;
    right: 'read' | 'write' | 'admin';
  } | null>(null);

  const [editingRight, setEditingRight] = useState<{
    userId: string;
    orgIdentifier: string;
    functionId: string | null;
    currentRight: 'read' | 'write' | 'admin';
  } | null>(null);

  const [confirmDeleteUser, setConfirmDeleteUser] = useState<User | null>(null);
  const [blockedDeleteUser, setBlockedDeleteUser] = useState<{
    user: User;
    reasons: string[];
  } | null>(null);

  const getOrgName = (org: Organization) => {
    return language === 'sv' ? org.nameSv : org.nameEn;
  };

  const getRoleBadgeVariant = (role: string): 'default' | 'secondary' | 'outline' => {
    if (role === 'admin') return 'default';
    if (role === 'write') return 'secondary';
    return 'outline';
  };

  const filteredUsers = users.filter((user) => {
    const searchLower = searchTerm.toLowerCase();
    return (
      user.name.toLowerCase().includes(searchLower) ||
      user.personalIdentityNumber.toLowerCase().includes(searchLower) ||
      formatPersonalIdentityNumber(user.personalIdentityNumber).toLowerCase().includes(searchLower) ||
      user.email.toLowerCase().includes(searchLower)
    );
  });

  const toggleExpanded = (userId: string) => {
    const newExpanded = new Set(expandedUsers);
    if (newExpanded.has(userId)) {
      newExpanded.delete(userId);
    } else {
      newExpanded.add(userId);
    }
    setExpandedUsers(newExpanded);
  };

  return (
    <div className="space-y-4">
      {/* Search */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400" />
        <Input
          type="text"
          placeholder={t('search.users')}
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          className="pl-10"
        />
      </div>

      {filteredUsers.length === 0 ? (
        <Card>
          <CardContent className="pt-6">
            <div className="text-center text-gray-500">
              <Users className="w-12 h-12 mx-auto mb-2 opacity-20" />
              <p>{searchTerm ? t('common.noResults') : 'No users yet. Create your first one!'}</p>
            </div>
          </CardContent>
        </Card>
      ) : (
        filteredUsers.map((user) => {
          const isExpanded = expandedUsers.has(user.id);
          const isSelf = user.id === currentUserId;

          return (
            <Card key={user.id} className="overflow-hidden">
              <CardHeader
                className="cursor-pointer hover:bg-gray-50 transition-colors"
                onClick={() => toggleExpanded(user.id)}
              >
                <div className="flex items-start justify-between">
                  <div className="flex-1 flex items-start gap-3">
                    {isExpanded ? (
                      <ChevronDown className="w-5 h-5 mt-0.5 text-gray-500 flex-shrink-0" />
                    ) : (
                      <ChevronRight className="w-5 h-5 mt-0.5 text-gray-500 flex-shrink-0" />
                    )}
                    <div className="flex-1">
                      <div className="flex items-center gap-2">
                        <Users className="w-5 h-5" />
                        <CardTitle>{user.name}</CardTitle>
                        {user.personalIdentityNumber && (
                          <span className="text-sm text-gray-500">
                            • {formatPersonalIdentityNumber(user.personalIdentityNumber)}
                          </span>
                        )}
                        {user.superuser && (
                          <Badge variant="default" className="flex items-center gap-1">
                            <ShieldCheck className="w-3 h-3" />
                            Superuser
                          </Badge>
                        )}
                      </div>
                      {!isExpanded && user.rights && user.rights.length > 0 && (
                        <div className="flex gap-3 mt-2">
                          {user.rights.filter((r) => r.functionId === null).length > 0 && (
                            <p className="text-xs text-gray-400">
                              {user.rights.filter((r) => r.functionId === null).length}{' '}
                              {user.rights.filter((r) => r.functionId === null).length === 1 ? t('users.orgRight') : t('users.orgRights')}
                            </p>
                          )}
                          {user.rights.filter((r) => r.functionId !== null).length > 0 && (
                            <p className="text-xs text-gray-400">
                              {user.rights.filter((r) => r.functionId !== null).length}{' '}
                              {user.rights.filter((r) => r.functionId !== null).length === 1 ? t('users.functionRight') : t('users.functionRights')}
                            </p>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                  {!isSelf && (
                    <div className="flex gap-2" onClick={(e) => e.stopPropagation()}>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => onEdit(user)}
                      >
                        <Pencil className="w-4 h-4" />
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => {
                          if (isSuperuser) {
                            const reasons = checkDeleteSafety(user, users, organizations, functions, language);
                            if (reasons.length > 0) {
                              setBlockedDeleteUser({ user, reasons });
                            } else {
                              setConfirmDeleteUser(user);
                            }
                          } else {
                            setConfirmDeleteUser(user);
                          }
                        }}
                      >
                        <Trash2 className="w-4 h-4 text-red-500" />
                      </Button>
                    </div>
                  )}
                </div>
              </CardHeader>

              {isExpanded && (
                <CardContent className="border-t bg-gray-50 space-y-4">
                  {/* Contact information */}
                  {(user.personalIdentityNumber || user.email || user.phoneNumber || user.superuser) && (
                    <div>
                      <h4 className="text-sm font-medium text-gray-600 mb-2">{t('users.details')}</h4>
                      <div className="space-y-1">
                        {user.personalIdentityNumber && (
                          <p className="text-sm text-gray-700">
                            <span className="font-medium">{t('users.uniqueIdentity')}:</span>{' '}
                            {formatPersonalIdentityNumber(user.personalIdentityNumber)}
                          </p>
                        )}
                        {user.email && (
                          <p className="text-sm text-gray-500 flex items-center gap-2">
                            <Mail className="w-4 h-4" />
                            {user.email}
                          </p>
                        )}
                        {user.phoneNumber && (
                          <p className="text-sm text-gray-500 flex items-center gap-2">
                            <Phone className="w-4 h-4" />
                            {user.phoneNumber}
                          </p>
                        )}
                        {user.superuser && (
                          <p className="text-sm text-gray-500">{t('users.superuser')}</p>
                        )}
                      </div>
                    </div>
                  )}

                  {/* Rights */}
                  {user.rights && user.rights.length > 0 && (
                    <div>
                      <h4 className="text-sm font-medium text-gray-600 mb-3">{t('users.assignedRights')}</h4>
                      {groupRightsByOrg(user.rights).map(({ orgIdentifier, orgRights, funcRights }) => {
                        const org = organizations.find((o) => o.id === orgIdentifier);
                        const orgName = org ? getOrgName(org) : orgIdentifier;
                        return (
                          <div key={orgIdentifier} className="mb-4">
                            <p className="text-xs font-semibold text-gray-700 mb-2">{orgName}</p>
                            {orgRights.length > 0 && (
                              <div className="mb-2">
                                <div className="flex items-center gap-1.5 mb-1">
                                  <Building2 className="w-4 h-4 text-gray-500" />
                                  <p className="text-xs font-medium text-gray-500 uppercase">
                                    {t('users.orgLevelRights')}
                                  </p>
                                </div>
                                {orgRights.map((r, i) => {
                                  const isEditingThis =
                                    editingRight !== null &&
                                    editingRight.userId === user.id &&
                                    editingRight.orgIdentifier === r.orgIdentifier &&
                                    editingRight.functionId === null &&
                                    editingRight.currentRight === r.right;
                                  return (
                                    <div key={i} className="flex items-center justify-between bg-white p-2 rounded border mb-1">
                                      <span className="text-sm">{orgName}</span>
                                      {isSelf ? (
                                        <Badge variant={getRoleBadgeVariant(r.right)}>{t(`role.${r.right}`)}</Badge>
                                      ) : (
                                        <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
                                          {isEditingThis ? (
                                            <select
                                              className="text-sm border rounded px-1 py-0.5"
                                              defaultValue={r.right}
                                              autoFocus
                                              onChange={(e) => {
                                                onChangeRight(user.id, r.orgIdentifier, null, e.target.value as 'read' | 'write' | 'admin', r.right);
                                                setEditingRight(null);
                                              }}
                                              onBlur={() => setEditingRight(null)}
                                            >
                                              <option value="read">{t('role.read')}</option>
                                              <option value="write">{t('role.write')}</option>
                                              <option value="admin">{t('role.admin')}</option>
                                            </select>
                                          ) : (
                                            <Badge
                                              variant={getRoleBadgeVariant(r.right)}
                                              className="cursor-pointer hover:opacity-75"
                                              onClick={(e) => {
                                                e.stopPropagation();
                                                setEditingRight({ userId: user.id, orgIdentifier: r.orgIdentifier, functionId: null, currentRight: r.right as 'read' | 'write' | 'admin' });
                                              }}
                                            >
                                              {t(`role.${r.right}`)}
                                            </Badge>
                                          )}
                                          <Button
                                            variant="ghost"
                                            size="sm"
                                            className="h-7 w-7 p-0 hover:bg-red-50"
                                            onClick={(e) => {
                                              e.stopPropagation();
                                              setConfirmRemove({ userId: user.id, orgIdentifier: r.orgIdentifier, functionId: null, right: r.right as 'read' | 'write' | 'admin' });
                                            }}
                                          >
                                            <X className="w-4 h-4 text-red-500" />
                                          </Button>
                                        </div>
                                      )}
                                    </div>
                                  );
                                })}
                              </div>
                            )}
                            {funcRights.length > 0 && (
                              <div>
                                <div className="flex items-center gap-1.5 mb-1">
                                  <Boxes className="w-4 h-4 text-gray-500" />
                                  <p className="text-xs font-medium text-gray-500 uppercase">
                                    {t('users.functionLevelRights')}
                                  </p>
                                </div>
                                {funcRights.map((r, i) => {
                                  const func = functions.find((f) => f.id === r.functionId);
                                  const isEditingThis =
                                    editingRight !== null &&
                                    editingRight.userId === user.id &&
                                    editingRight.orgIdentifier === r.orgIdentifier &&
                                    editingRight.functionId === r.functionId &&
                                    editingRight.currentRight === r.right;
                                  return (
                                    <div key={i} className="flex items-center justify-between bg-white p-2 rounded border mb-1">
                                      <div>
                                        <p className="text-sm font-medium">{orgName}</p>
                                        <p className="text-xs text-gray-500">{func ? (language === 'sv' ? func.nameSv : func.nameEn) || (language === 'sv' ? func.nameEn : func.nameSv) || func.name : r.functionId}</p>
                                      </div>
                                      {isSelf ? (
                                        <Badge variant={getRoleBadgeVariant(r.right)}>{t(`role.${r.right}`)}</Badge>
                                      ) : (
                                        <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
                                          {isEditingThis ? (
                                            <select
                                              className="text-sm border rounded px-1 py-0.5"
                                              defaultValue={r.right}
                                              autoFocus
                                              onChange={(e) => {
                                                onChangeRight(user.id, r.orgIdentifier, r.functionId, e.target.value as 'read' | 'write' | 'admin', r.right);
                                                setEditingRight(null);
                                              }}
                                              onBlur={() => setEditingRight(null)}
                                            >
                                              <option value="read">{t('role.read')}</option>
                                              <option value="write">{t('role.write')}</option>
                                              <option value="admin">{t('role.admin')}</option>
                                            </select>
                                          ) : (
                                            <Badge
                                              variant={getRoleBadgeVariant(r.right)}
                                              className="cursor-pointer hover:opacity-75"
                                              onClick={(e) => {
                                                e.stopPropagation();
                                                setEditingRight({ userId: user.id, orgIdentifier: r.orgIdentifier, functionId: r.functionId, currentRight: r.right as 'read' | 'write' | 'admin' });
                                              }}
                                            >
                                              {t(`role.${r.right}`)}
                                            </Badge>
                                          )}
                                          <Button
                                            variant="ghost"
                                            size="sm"
                                            className="h-7 w-7 p-0 hover:bg-red-50"
                                            onClick={(e) => {
                                              e.stopPropagation();
                                              setConfirmRemove({ userId: user.id, orgIdentifier: r.orgIdentifier, functionId: r.functionId, right: r.right as 'read' | 'write' | 'admin' });
                                            }}
                                          >
                                            <X className="w-4 h-4 text-red-500" />
                                          </Button>
                                        </div>
                                      )}
                                    </div>
                                  );
                                })}
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}
                </CardContent>
              )}
            </Card>
          );
        })
      )}

      {/* Remove right confirmation */}
      <AlertDialog open={confirmRemove !== null} onOpenChange={(open) => { if (!open) setConfirmRemove(null); }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('confirm.removeRightTitle')}</AlertDialogTitle>
            <AlertDialogDescription>{t('confirm.removeRightDescription')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setConfirmRemove(null)}>
              {t('common.cancel')}
            </AlertDialogCancel>
            <AlertDialogAction
              className="bg-red-600 hover:bg-red-700 text-white"
              onClick={() => {
                if (confirmRemove) {
                  onRemoveRight(confirmRemove.userId, confirmRemove.orgIdentifier, confirmRemove.functionId, confirmRemove.right);
                  setConfirmRemove(null);
                }
              }}
            >
              {t('common.confirm')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Blocked deletion dialog */}
      <AlertDialog open={blockedDeleteUser !== null} onOpenChange={(open) => { if (!open) setBlockedDeleteUser(null); }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('confirm.deleteUserBlockedTitle')}</AlertDialogTitle>
            <AlertDialogDescription>
              {t('confirm.deleteUserBlockedDescription')}
              <br />
              {blockedDeleteUser?.reasons.join(', ')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setBlockedDeleteUser(null)}>
              {t('common.close')}
            </AlertDialogCancel>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Confirm deletion dialog */}
      <AlertDialog open={confirmDeleteUser !== null} onOpenChange={(open) => { if (!open) setConfirmDeleteUser(null); }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('confirm.deleteUserTitle')}</AlertDialogTitle>
            <AlertDialogDescription>
              {isSuperuser
                ? t('confirm.deleteUserDescriptionSuperuser')
                : t('confirm.deleteUserDescriptionAdmin')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setConfirmDeleteUser(null)}>
              {t('common.cancel')}
            </AlertDialogCancel>
            <AlertDialogAction
              className="bg-red-600 hover:bg-red-700 text-white"
              onClick={() => {
                if (confirmDeleteUser) {
                  onDeleteUser(confirmDeleteUser.id);
                  setConfirmDeleteUser(null);
                }
              }}
            >
              {t('common.confirm')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
