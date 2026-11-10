import { User, Organization, FunctionType, UserRightData } from '@/types';
import { Button } from '@/app/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/app/components/ui/card';
import { Badge } from '@/app/components/ui/badge';
import { Input } from '@/app/components/ui/input';
import {
  Pagination,
  PaginationContent,
  PaginationEllipsis,
  PaginationItem,
  PaginationLink,
  PaginationNext,
  PaginationPrevious,
} from '@/app/components/ui/pagination';
import { cn } from '@/app/components/ui/utils';
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
import { useState, useEffect } from 'react';
import { formatPersonalIdentityNumber, getPageNumbers, resolveOrgName } from '@/utils';

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
        const name = org ? resolveOrgName(org, language) : right.orgIdentifier;
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

/** Users shown per page of the list. */
export const USERS_PER_PAGE = 50;

/**
 * The users to show on a page, with the page clamped to what the list actually holds: a page
 * beyond the end, which a search or a deletion can leave behind, shows the last page instead.
 *
 * @param users the users to page through
 * @param page the 0-based page asked for
 * @param size the page size
 */
export function pageOfUsers<T>(users: T[], page: number, size = USERS_PER_PAGE): {
  content: T[];
  currentPage: number;
  totalPages: number;
} {
  const totalPages = Math.max(1, Math.ceil(users.length / size));
  const currentPage = Math.min(Math.max(page, 0), totalPages - 1);
  return {
    content: users.slice(currentPage * size, currentPage * size + size),
    currentPage,
    totalPages,
  };
}

/**
 * The 0-based page holding the given user, or {@code null} when the list does not hold them.
 *
 * @param users the users to page through
 * @param userId the user to look for
 * @param size the page size
 */
export function pageHoldingUser(
  users: { id: string }[], userId: string, size = USERS_PER_PAGE): number | null {
  const index = users.findIndex((u) => u.id === userId);
  return index < 0 ? null : Math.floor(index / size);
}

interface UserListProps {
  users: User[];
  organizations: Organization[];
  functions: FunctionType[];
  currentUserId: string;
  isSuperuser: boolean;
  /** When false, the admin right is read-only: it cannot be granted, changed or removed here. */
  canAssignAdmin: boolean;
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
  /** A user to bring into view, by turning to the page that holds them. */
  revealUserId?: string | null;
  /** Called once the user has been brought into view, so the request is not repeated. */
  onRevealUserHandled?: () => void;
}

export function UserList({ users, organizations, functions, currentUserId, isSuperuser, canAssignAdmin, onEdit, onDeleteUser, onRemoveRight, onChangeRight, revealUserId, onRevealUserHandled }: UserListProps) {
  const { t, language } = useLanguage();
  const [searchTerm, setSearchTerm] = useState('');
  const [page, setPage] = useState(0);
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

  const getOrgName = (org: Organization) => resolveOrgName(org, language);

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

  // The whole set of users is held in state, so the list pages through it here rather than
  // asking the backend for one page: the search above searches every user, not just a page.
  const { content: pagedUsers, currentPage, totalPages } = pageOfUsers(filteredUsers, page);

  // A user just created is on the last page as often as not, so turn to the page holding them.
  useEffect(() => {
    if (!revealUserId) {
      return;
    }
    const target = pageHoldingUser(filteredUsers, revealUserId);
    if (target !== null) {
      setPage(target);
    }
    onRevealUserHandled?.();
  }, [revealUserId, filteredUsers, onRevealUserHandled]);

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
          onChange={(e) => { setSearchTerm(e.target.value); setPage(0); }}
          className="pl-10"
        />
      </div>

      {filteredUsers.length === 0 ? (
        <Card>
          <CardContent className="pt-6">
            <div className="text-center text-gray-500">
              <Users className="w-12 h-12 mx-auto mb-2 opacity-20" />
              <p>{searchTerm
                ? t('common.noResults')
                : isSuperuser ? t('users.emptyCreateFirst') : t('users.emptyNoRightsYet')}</p>
            </div>
          </CardContent>
        </Card>
      ) : (
        <>
        {pagedUsers.map((user) => {
          const isExpanded = expandedUsers.has(user.id);
          const isSelf = user.id === currentUserId;

          return (
            <Card key={user.id} className="overflow-hidden">
              <CardHeader
                className="cursor-pointer hover:bg-gray-50 transition-colors"
                onClick={() => toggleExpanded(user.id)}
              >
                <div className="flex flex-wrap items-start justify-between gap-2 min-w-0">
                  <div className="flex-1 min-w-48 flex items-start gap-3">
                    {isExpanded ? (
                      <ChevronDown className="w-5 h-5 mt-0.5 text-gray-500 flex-shrink-0" />
                    ) : (
                      <ChevronRight className="w-5 h-5 mt-0.5 text-gray-500 flex-shrink-0" />
                    )}
                    <div className="flex-1 min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <Users className="w-5 h-5 shrink-0" />
                        <CardTitle className="wrap-anywhere">{user.name}</CardTitle>
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
                        <div className="flex flex-wrap gap-x-3 mt-2">
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
                        aria-label={t('common.edit')}
                        variant="outline"
                        size="sm"
                        onClick={() => onEdit(user)}
                      >
                        <Pencil className="w-4 h-4" />
                      </Button>
                      <Button
                        aria-label={t('common.delete')}
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
                          <p className="text-sm text-gray-500 flex items-center gap-2 wrap-anywhere">
                            <Mail className="w-4 h-4 shrink-0" />
                            {user.email}
                          </p>
                        )}
                        {user.phoneNumber && (
                          <p className="text-sm text-gray-500 flex items-center gap-2 wrap-anywhere">
                            <Phone className="w-4 h-4 shrink-0" />
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
                            <p className="text-xs font-semibold text-gray-700 mb-2 wrap-anywhere">{orgName}</p>
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
                                    <div key={i} className="flex items-center justify-between gap-2 bg-white p-2 rounded border mb-1">
                                      <span className="text-sm min-w-0 wrap-anywhere">{orgName}</span>
                                      {isSelf || (!canAssignAdmin && r.right === 'admin') ? (
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
                                              {canAssignAdmin && <option value="admin">{t('role.admin')}</option>}
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
                                            aria-label={t('common.remove')}
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
                                    <div key={i} className="flex items-center justify-between gap-2 bg-white p-2 rounded border mb-1">
                                      <div className="min-w-0 wrap-anywhere">
                                        <p className="text-sm font-medium">{orgName}</p>
                                        <p className="text-xs text-gray-500">{func ? (language === 'sv' ? func.nameSv : func.nameEn) || (language === 'sv' ? func.nameEn : func.nameSv) || func.name : r.functionId}</p>
                                      </div>
                                      {isSelf || (!canAssignAdmin && r.right === 'admin') ? (
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
                                              {canAssignAdmin && <option value="admin">{t('role.admin')}</option>}
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
                                            aria-label={t('common.remove')}
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
        })}
        {totalPages > 1 && (
          <Pagination className="mt-2">
            <PaginationContent>
              <PaginationItem>
                <PaginationPrevious
                  onClick={() => setPage(Math.max(0, currentPage - 1))}
                  className={cn(currentPage === 0 && 'pointer-events-none opacity-50')}
                />
              </PaginationItem>
              {getPageNumbers(currentPage, totalPages).map((item, idx) =>
                item === 'ellipsis' ? (
                  <PaginationItem key={`ellipsis-${idx}`}>
                    <PaginationEllipsis />
                  </PaginationItem>
                ) : (
                  <PaginationItem key={item}>
                    <PaginationLink
                      isActive={item === currentPage}
                      onClick={() => setPage(item as number)}
                      className="cursor-pointer"
                    >
                      {(item as number) + 1}
                    </PaginationLink>
                  </PaginationItem>
                )
              )}
              <PaginationItem>
                <PaginationNext
                  onClick={() => setPage(Math.min(totalPages - 1, currentPage + 1))}
                  className={cn(currentPage === totalPages - 1 && 'pointer-events-none opacity-50')}
                />
              </PaginationItem>
            </PaginationContent>
          </Pagination>
        )}
        </>
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
