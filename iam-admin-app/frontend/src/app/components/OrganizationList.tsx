import { Organization, User, UserOrganizationRole, FunctionType, OrganizationFunction, UserOrgRight } from '@/types';
import { Button } from '@/app/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/app/components/ui/card';
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
import { Pencil, Trash2, Building2, Search, ChevronDown, ChevronRight, User as UserIcon, Plus, X, Boxes, Info, Unlink } from 'lucide-react';
import { useLanguage } from '@/app/contexts/LanguageContext';
import { useState, useEffect } from 'react';
import { AddUserToOrgDialog } from '@/app/components/AddUserToOrgDialog';
import { AssignFunctionsDialog } from '@/app/components/AssignFunctionsDialog';
import { AddUserToFunctionDialog } from '@/app/components/AddUserToFunctionDialog';
import { formatOrgNumber, canAdminOrg, canAdminFunction } from '@/utils';
import { LastAdminError } from '@/services/userService';

interface OrganizationListProps {
  organizations: Organization[];
  currentPage: number;
  totalPages: number;
  totalElements: number;
  searchTerm: string;
  onPageChange: (page: number) => void;
  onSearch: (term: string) => void;
  users: User[];
  userRoles: UserOrganizationRole[];
  functions: FunctionType[];
  organizationFunctions: OrganizationFunction[];
  isSuperuser: boolean;
  allowOrgRights: boolean;
  orgRights: UserOrgRight[];
  currentUserId: string;
  onEdit: (org: Organization) => void;
  onDelete: (id: string) => void;
  onAddUserToOrg: (organizationId: string, userId: string, role: string) => void;
  onRemoveUserFromOrg: (organizationId: string, userId: string, right: string) => Promise<void>;
  onAssignFunctionsToOrg: (organizationId: string, functionIds: string[]) => void;
  onAddUserToFunction: (organizationId: string, functionId: string, userId: string, role: string) => void;
  onRemoveUserFromFunction: (organizationId: string, functionId: string, userId: string, right: string) => Promise<void>;
  onChangeUserRight: (
    organizationId: string,
    functionId: string | undefined,
    userId: string,
    newRight: 'read' | 'write' | 'admin',
    oldRight: 'read' | 'write' | 'admin'
  ) => Promise<void>;
  onDetachFunctionFromOrg: (organizationId: string, functionId: string) => void;
  expandOrgId: string | null;
  onExpandOrgHandled: () => void;
  onUserCreated: (user: User) => void;
}

export function OrganizationList({
  organizations,
  currentPage,
  totalPages,
  totalElements,
  searchTerm,
  onPageChange,
  onSearch,
  users,
  userRoles,
  functions,
  organizationFunctions,
  isSuperuser,
  allowOrgRights,
  orgRights,
  currentUserId,
  onEdit,
  onDelete,
  onAddUserToOrg,
  onRemoveUserFromOrg,
  onAssignFunctionsToOrg,
  onAddUserToFunction,
  onRemoveUserFromFunction,
  onChangeUserRight,
  onDetachFunctionFromOrg,
  expandOrgId,
  onExpandOrgHandled,
  onUserCreated,
}: OrganizationListProps) {
  const { t, language } = useLanguage();
  const [expandedOrgs, setExpandedOrgs] = useState<Set<string>>(new Set());
  const [editingRight, setEditingRight] = useState<{
    userId: string;
    orgId: string;
    functionId?: string;
    currentRight: 'read' | 'write' | 'admin';
  } | null>(null);
  const [confirmRemove, setConfirmRemove] = useState<{
    orgId: string;
    userId: string;
    functionId?: string;
    right: string;
  } | null>(null);
  const [confirmDeleteOrg, setConfirmDeleteOrg] = useState<Organization | null>(null);
  const [confirmDeleteOrgBlocked, setConfirmDeleteOrgBlocked] = useState<Organization | null>(null);
  const [confirmDetachFunction, setConfirmDetachFunction] = useState<{
    orgId: string;
    func: FunctionType;
  } | null>(null);
  const [lastAdminError, setLastAdminError] = useState<{ scope: string; userName: string } | null>(null);

  useEffect(() => {
    if (!isSuperuser && organizations.length === 1) {
      setExpandedOrgs(new Set([organizations[0].id]));
    }
  }, [isSuperuser, organizations]);

  useEffect(() => {
    if (!expandOrgId) return;
    setExpandedOrgs((prev) => new Set([...prev, expandOrgId]));
    onExpandOrgHandled();
    setTimeout(() => {
      document.getElementById(`org-card-${expandOrgId}`)?.scrollIntoView({
        behavior: 'smooth',
        block: 'start',
      });
    }, 50);
  }, [expandOrgId, onExpandOrgHandled]);

  const [addUserDialogOpen, setAddUserDialogOpen] = useState(false);
  const [selectedOrgForAddUser, setSelectedOrgForAddUser] = useState<Organization | null>(null);
  const [assignFunctionsDialogOpen, setAssignFunctionsDialogOpen] = useState(false);
  const [selectedOrgForAssignFunctions, setSelectedOrgForAssignFunctions] = useState<Organization | null>(null);
  const [addUserToFunctionDialogOpen, setAddUserToFunctionDialogOpen] = useState(false);
  const [selectedOrgForAddUserToFunction, setSelectedOrgForAddUserToFunction] = useState<Organization | null>(null);
  const [selectedFunctionForAddUser, setSelectedFunctionForAddUser] = useState<FunctionType | null>(null);

  const getOrgName = (org: Organization) => {
    if (org.nameSv || org.nameEn) {
      return language === 'sv' ? org.nameSv : org.nameEn;
    }
    return (org as any).name || 'Unnamed Organization';
  };


  const getUsersForOrganization = (orgId: string) => {
    const orgUserRoles = userRoles.filter((role) => role.organizationId === orgId && !role.functionId);
    return orgUserRoles.map((role) => {
      const user = users.find((u) => u.id === role.userId);
      return { user, role: role.role };
    }).filter((item) => item.user !== undefined);
  };

  const getFunctionsForOrganization = (orgId: string) => {
    const orgFunctions = organizationFunctions.filter((of) => of.organizationId === orgId);
    return orgFunctions
      .map((of) => {
        const func = functions.find((f) => f.id === of.functionId);
        if (!func) return null;
        const functionUsers = getUsersForFunction(orgId, of.functionId);
        return { func, users: functionUsers };
      })
      .filter((item) => item !== null) as Array<{ func: FunctionType; users: Array<{ user: User; role: string }> }>;
  };

  const getUsersForFunction = (orgId: string, functionId: string) => {
    const functionUserRoles = userRoles.filter(
      (role) => role.organizationId === orgId && role.functionId === functionId
    );
    return functionUserRoles
      .map((role) => {
        const user = users.find((u) => u.id === role.userId);
        if (!user) return null;
        return { user, role: role.role };
      })
      .filter((item) => item !== null) as Array<{ user: User; role: string }>;
  };

  const toggleExpanded = (orgId: string) => {
    const newExpanded = new Set(expandedOrgs);
    if (newExpanded.has(orgId)) {
      newExpanded.delete(orgId);
    } else {
      newExpanded.add(orgId);
    }
    setExpandedOrgs(newExpanded);
  };

  const getPageNumbers = (current: number, total: number): (number | 'ellipsis')[] => {
    if (total <= 7) return Array.from({ length: total }, (_, i) => i);
    const pages: (number | 'ellipsis')[] = [0];
    if (current > 2) pages.push('ellipsis');
    for (let i = Math.max(1, current - 1); i <= Math.min(total - 2, current + 1); i++) {
      pages.push(i);
    }
    if (current < total - 3) pages.push('ellipsis');
    pages.push(total - 1);
    return pages;
  };

  return (
    <div className="space-y-4">
      {/* Search */}
      {(isSuperuser || totalElements !== 1) && (
        <div className="relative">
          <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400" />
          <Input
            type="text"
            placeholder={t('search.organizations')}
            value={searchTerm}
            onChange={(e) => onSearch(e.target.value)}
            className="pl-10"
          />
        </div>
      )}

      {organizations.length === 0 ? (
        <Card>
          <CardContent className="pt-6">
            <div className="text-center text-gray-500">
              <Building2 className="w-12 h-12 mx-auto mb-2 opacity-20" />
              <p>{searchTerm ? t('common.noResults') : 'No organizations yet. Create your first one!'}</p>
            </div>
          </CardContent>
        </Card>
      ) : (
        <>
        {organizations.map((org) => {
          const orgUsers = getUsersForOrganization(org.id);
          const orgFunctions = getFunctionsForOrganization(org.id);
          const isExpanded = expandedOrgs.has(org.id);

          return (
            <Card key={org.id} id={`org-card-${org.id}`} className="overflow-hidden">
              <CardHeader
                className="cursor-pointer hover:bg-gray-50 transition-colors"
                onClick={() => toggleExpanded(org.id)}
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
                        <Building2 className="w-5 h-5" />
                        <CardTitle>{getOrgName(org)}</CardTitle>
                        <span className="text-sm text-gray-500">• {formatOrgNumber(org.organizationNumber)}</span>
                      </div>
                      {!isExpanded && (
                        <div className="flex gap-3 mt-2">
                          {orgUsers.length > 0 && (
                            <p className="text-xs text-gray-400">
                              {orgUsers.length} {orgUsers.length === 1 ? t('users.count') : t('users.count_plural')}
                            </p>
                          )}
                          {orgFunctions.length > 0 && (
                            <p className="text-xs text-gray-400">
                              {orgFunctions.length} {orgFunctions.length === 1 ? t('functions.count') : t('functions.count_plural')}
                            </p>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                  <div className="flex gap-2" onClick={(e) => e.stopPropagation()}>
                    {canAdminOrg(isSuperuser, orgRights, org.id) && (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => onEdit(org)}
                      >
                        <Pencil className="w-4 h-4" />
                      </Button>
                    )}
                    {isSuperuser && (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => {
                          if (orgFunctions.length > 0) {
                            setConfirmDeleteOrgBlocked(org);
                          } else {
                            setConfirmDeleteOrg(org);
                          }
                        }}
                      >
                        <Trash2 className="w-4 h-4 text-red-500" />
                      </Button>
                    )}
                  </div>
                </div>
              </CardHeader>

              {isExpanded && (
                <CardContent className="border-t bg-gray-50 space-y-4 pt-4">
                  {/* Organization-level Users */}
                  {(allowOrgRights
                      ? canAdminOrg(isSuperuser, orgRights, org.id)
                      : orgUsers.length > 0) && (
                  <div>
                    <div className="flex items-center justify-between mb-3">
                      <h4 className="text-sm font-medium flex items-center gap-2">
                        <UserIcon className="w-4 h-4" />
                        {t('org.usersWithAccess')}
                      </h4>
                      {allowOrgRights && canAdminOrg(isSuperuser, orgRights, org.id) && (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => {
                            setSelectedOrgForAddUser(org);
                            setAddUserDialogOpen(true);
                          }}
                          className="flex items-center gap-1.5"
                        >
                          <Plus className="w-4 h-4" />
                          {t('org.addUser')}
                        </Button>
                      )}
                    </div>
                    {orgUsers.length === 0 ? (
                      <p className="text-sm text-gray-500 italic">{t('org.noUsers')}</p>
                    ) : (
                      <div className="space-y-2">
                        {orgUsers.map(({ user, role }) => {
                          const isSelf = user!.id === currentUserId;
                          const isEditing =
                            !isSelf &&
                            editingRight?.userId === user!.id &&
                            editingRight?.orgId === org.id &&
                            !editingRight?.functionId;
                          return (
                            <div key={user!.id} className="flex items-center justify-between gap-3 p-2 bg-white rounded border">
                              <div className="flex-1 min-w-0">
                                <p className="text-sm font-medium truncate">{user!.name}</p>
                                <p className="text-xs text-gray-500 truncate">{user!.email}</p>
                              </div>
                              <div className="flex items-center gap-2 flex-shrink-0">
                                {isEditing ? (
                                  <select
                                    className="text-xs border rounded px-1 py-0.5"
                                    defaultValue={role}
                                    autoFocus
                                    onChange={async (e) => {
                                      const newRight = e.target.value as 'read' | 'write' | 'admin';
                                      try {
                                        await onChangeUserRight(
                                          org.id,
                                          undefined,
                                          user!.id,
                                          newRight,
                                          editingRight!.currentRight
                                        );
                                      } catch (error) {
                                        if (error instanceof LastAdminError) {
                                          const userName = users.find(u => u.id === user!.id)?.name ?? '';
                                          setLastAdminError({ scope: error.scope, userName });
                                        } else {
                                          throw error;
                                        }
                                      } finally {
                                        setEditingRight(null);
                                      }
                                    }}
                                    onBlur={() => setEditingRight(null)}
                                  >
                                    <option value="read">{t('role.read')}</option>
                                    <option value="write">{t('role.write')}</option>
                                    <option value="admin">{t('role.admin')}</option>
                                  </select>
                                ) : (
                                  <span
                                    className={`px-2 py-1 text-xs rounded-full bg-primary/10 text-primary${!isSelf ? ' cursor-pointer hover:ring-1 hover:ring-primary' : ''}`}
                                    onClick={!isSelf ? () => setEditingRight({ userId: user!.id, orgId: org.id, currentRight: role as 'read' | 'write' | 'admin' }) : undefined}
                                  >
                                    {t(`role.${role}`)}
                                  </span>
                                )}
                                {!isSelf && (
                                  <Button
                                    variant="ghost"
                                    size="sm"
                                    onClick={() => setConfirmRemove({ orgId: org.id, userId: user!.id, right: role })}
                                    className="h-7 w-7 p-0 hover:bg-red-50"
                                  >
                                    <X className="w-4 h-4 text-red-500" />
                                  </Button>
                                )}
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </div>
                  )}

                  {/* Functions */}
                  <div>
                    <div className="flex items-center justify-between mb-3">
                      <h4 className="text-sm font-medium flex items-center gap-2">
                        <Boxes className="w-4 h-4" />
                        {t('functions.orgFunctions')}
                      </h4>
                      {isSuperuser && (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => {
                            setSelectedOrgForAssignFunctions(org);
                            setAssignFunctionsDialogOpen(true);
                          }}
                          className="flex items-center gap-1.5"
                        >
                          <Plus className="w-4 h-4" />
                          {t('functions.assignToOrg')}
                        </Button>
                      )}
                    </div>
                    {orgFunctions.length === 0 ? (
                      <p className="text-sm text-gray-500 italic">{t('functions.noFunctions')}</p>
                    ) : (
                      <div className="space-y-3">
                        {orgFunctions.map(({ func, users: functionUsers }) => (
                          <div key={func.id} className="p-3 bg-white rounded border">
                            <div className="flex items-start justify-between mb-2">
                              <div className="flex-1">
                                <p className="text-sm font-medium">
                                  {(language === 'sv' ? func.nameSv : func.nameEn) ||
                                    (language === 'sv' ? func.nameEn : func.nameSv) ||
                                    func.name}
                                </p>
                                <p className="text-xs text-gray-500">
                                  {(language === 'sv' ? func.descriptionSv : func.descriptionEn) ||
                                    (language === 'sv' ? func.descriptionEn : func.descriptionSv) ||
                                    ''}
                                </p>
                              </div>
                              <div className="flex items-center gap-2">
                                {isSuperuser && (
                                  <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={() => {
                                      // TODO: Replace this URL with the actual external site URL for this function
                                      const externalUrl = `https://example.com/add-information?function=${func.id}&org=${org.id}`;
                                      window.open(externalUrl, '_blank', 'noopener,noreferrer');
                                    }}
                                    className="flex items-center gap-1.5"
                                  >
                                    <Info className="w-3 h-3" />
                                    {t('org.addInformation')}
                                  </Button>
                                )}
                                {canAdminFunction(isSuperuser, orgRights, org.id, func.id) && (
                                  <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={() => {
                                      setSelectedOrgForAddUserToFunction(org);
                                      setSelectedFunctionForAddUser(func);
                                      setAddUserToFunctionDialogOpen(true);
                                    }}
                                    className="flex items-center gap-1.5"
                                  >
                                    <Plus className="w-3 h-3" />
                                    {t('org.addUser')}
                                  </Button>
                                )}
                                {isSuperuser && (
                                  <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={() => setConfirmDetachFunction({ orgId: org.id, func })}
                                    className="flex items-center gap-1.5"
                                  >
                                    <Unlink className="w-3 h-3" />
                                    {t('functions.detachFromOrg')}
                                  </Button>
                                )}
                              </div>
                            </div>
                            {functionUsers.length > 0 && (
                              <div className="space-y-1.5 mt-2 pt-2 border-t">
                                {functionUsers.map(({ user, role }) => {
                                  const isSelf = user.id === currentUserId;
                                  const isEditing =
                                    !isSelf &&
                                    editingRight?.userId === user.id &&
                                    editingRight?.orgId === org.id &&
                                    editingRight?.functionId === func.id;
                                  return (
                                    <div key={user.id} className="flex items-center justify-between gap-2 text-xs">
                                      <span className="truncate">{user.name}</span>
                                      <div className="flex items-center gap-1.5 flex-shrink-0">
                                        {isEditing ? (
                                          <select
                                            className="text-xs border rounded px-1 py-0.5"
                                            defaultValue={role}
                                            autoFocus
                                            onChange={async (e) => {
                                              const newRight = e.target.value as 'read' | 'write' | 'admin';
                                              try {
                                                await onChangeUserRight(
                                                  org.id,
                                                  func.id,
                                                  user.id,
                                                  newRight,
                                                  editingRight!.currentRight
                                                );
                                              } catch (error) {
                                                if (error instanceof LastAdminError) {
                                                  const userName = users.find(u => u.id === user.id)?.name ?? '';
                                                  setLastAdminError({ scope: error.scope, userName });
                                                } else {
                                                  throw error;
                                                }
                                              } finally {
                                                setEditingRight(null);
                                              }
                                            }}
                                            onBlur={() => setEditingRight(null)}
                                          >
                                            <option value="read">{t('role.read')}</option>
                                            <option value="write">{t('role.write')}</option>
                                            <option value="admin">{t('role.admin')}</option>
                                          </select>
                                        ) : (
                                          <span
                                            className={`px-1.5 py-0.5 rounded bg-gray-100${!isSelf ? ' cursor-pointer hover:ring-1 hover:ring-gray-400' : ''}`}
                                            onClick={!isSelf ? () => setEditingRight({ userId: user.id, orgId: org.id, functionId: func.id, currentRight: role as 'read' | 'write' | 'admin' }) : undefined}
                                          >
                                            {t(`role.${role}`)}
                                          </span>
                                        )}
                                        {!isSelf && (
                                          <Button
                                            variant="ghost"
                                            size="sm"
                                            onClick={() => setConfirmRemove({ orgId: org.id, userId: user.id, functionId: func.id, right: role })}
                                            className="h-6 w-6 p-0 hover:bg-red-50"
                                          >
                                            <X className="w-3 h-3 text-red-500" />
                                          </Button>
                                        )}
                                      </div>
                                    </div>
                                  );
                                })}
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
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
                  onClick={() => onPageChange(Math.max(0, currentPage - 1))}
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
                      onClick={() => onPageChange(item as number)}
                      className="cursor-pointer"
                    >
                      {(item as number) + 1}
                    </PaginationLink>
                  </PaginationItem>
                )
              )}
              <PaginationItem>
                <PaginationNext
                  onClick={() => onPageChange(Math.min(totalPages - 1, currentPage + 1))}
                  className={cn(currentPage === totalPages - 1 && 'pointer-events-none opacity-50')}
                />
              </PaginationItem>
            </PaginationContent>
          </Pagination>
        )}
        </>
      )}

      {/* Dialogs */}
      <AddUserToOrgDialog
        open={addUserDialogOpen}
        onClose={() => setAddUserDialogOpen(false)}
        organization={selectedOrgForAddUser}
        users={users}
        userRoles={userRoles}
        currentUserId={currentUserId}
        onAddUserToOrg={onAddUserToOrg}
        onUserCreated={onUserCreated}
      />
      <AssignFunctionsDialog
        open={assignFunctionsDialogOpen}
        onClose={() => setAssignFunctionsDialogOpen(false)}
        organization={selectedOrgForAssignFunctions}
        functions={functions}
        organizationFunctions={organizationFunctions}
        onAssignFunctionsToOrg={onAssignFunctionsToOrg}
      />
      <AddUserToFunctionDialog
        open={addUserToFunctionDialogOpen}
        onClose={() => setAddUserToFunctionDialogOpen(false)}
        organization={selectedOrgForAddUserToFunction}
        func={selectedFunctionForAddUser}
        users={users}
        userRoles={userRoles}
        currentUserId={currentUserId}
        onAddUserToFunction={onAddUserToFunction}
        onUserCreated={onUserCreated}
      />

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
              onClick={async () => {
                if (confirmRemove) {
                  const snapshot = confirmRemove;
                  setConfirmRemove(null);
                  try {
                    if (snapshot.functionId) {
                      await onRemoveUserFromFunction(snapshot.orgId, snapshot.functionId, snapshot.userId, snapshot.right);
                    } else {
                      await onRemoveUserFromOrg(snapshot.orgId, snapshot.userId, snapshot.right);
                    }
                  } catch (error) {
                    if (error instanceof LastAdminError) {
                      const userName = users.find(u => u.id === snapshot.userId)?.name ?? '';
                      setLastAdminError({ scope: error.scope, userName });
                    } else {
                      throw error;
                    }
                  }
                }
              }}
            >
              {t('common.confirm')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Delete organization confirmation */}
      <AlertDialog
        open={confirmDeleteOrg !== null}
        onOpenChange={(open) => { if (!open) setConfirmDeleteOrg(null); }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('confirm.deleteOrgTitle')}</AlertDialogTitle>
            <AlertDialogDescription>{t('confirm.deleteOrgDescription')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setConfirmDeleteOrg(null)}>
              {t('common.cancel')}
            </AlertDialogCancel>
            <AlertDialogAction
              className="bg-red-600 hover:bg-red-700 text-white"
              onClick={() => {
                if (confirmDeleteOrg) {
                  onDelete(confirmDeleteOrg.id);
                  setConfirmDeleteOrg(null);
                }
              }}
            >
              {t('common.confirm')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Delete organization blocked — has attached functions */}
      <AlertDialog
        open={confirmDeleteOrgBlocked !== null}
        onOpenChange={(open) => { if (!open) setConfirmDeleteOrgBlocked(null); }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('confirm.deleteOrgBlockedTitle')}</AlertDialogTitle>
            <AlertDialogDescription>{t('confirm.deleteOrgBlockedDescription')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogAction onClick={() => setConfirmDeleteOrgBlocked(null)}>
              {t('common.close')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Last admin error dialog */}
      <AlertDialog
        open={lastAdminError !== null}
        onOpenChange={(open) => { if (!open) setLastAdminError(null); }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('error.lastAdminTitle')}</AlertDialogTitle>
            <AlertDialogDescription>
              {t('error.lastAdminDescription').replace('{name}', lastAdminError?.userName ?? '')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogAction onClick={() => setLastAdminError(null)}>
              {t('common.close')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Detach function confirmation */}
      <AlertDialog
        open={confirmDetachFunction !== null}
        onOpenChange={(open) => { if (!open) setConfirmDetachFunction(null); }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('confirm.detachFunctionTitle')}</AlertDialogTitle>
            <AlertDialogDescription>{t('confirm.detachFunctionDescription')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setConfirmDetachFunction(null)}>
              {t('common.cancel')}
            </AlertDialogCancel>
            <AlertDialogAction
              className="bg-red-600 hover:bg-red-700 text-white"
              onClick={() => {
                if (confirmDetachFunction) {
                  onDetachFunctionFromOrg(confirmDetachFunction.orgId, confirmDetachFunction.func.id);
                  setConfirmDetachFunction(null);
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
