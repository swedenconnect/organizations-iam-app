import { useState, useEffect, useCallback } from 'react';
import { LoginForm } from '@/app/components/LoginForm';
import { OrganizationList } from '@/app/components/OrganizationList';
import { OrganizationForm } from '@/app/components/OrganizationForm';
import { UserList } from '@/app/components/UserList';
import { UserForm } from '@/app/components/UserForm';
import { FunctionList } from '@/app/components/FunctionList';
import { FunctionForm } from '@/app/components/FunctionForm';
import { Header } from '@/app/components/Header';
import { Footer } from '@/app/components/Footer';
import { Organization, User, UserOrganizationRole, FunctionType, OrganizationFunction, AdminSessionData } from '@/types';
import { LastAdminError } from '@/services/userService';
import { Button } from '@/app/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/app/components/ui/tabs';
import { Building2, Users as UsersIcon, Plus, Boxes, HelpCircle } from 'lucide-react';
import { toast } from 'sonner';
import { Toaster } from '@/app/components/ui/sonner';
import { LanguageProvider, useLanguage } from '@/app/contexts/LanguageContext';
import { ThemeProvider } from '@/app/contexts/ThemeContext';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/app/components/ui/dialog';
import { ErrorDialog } from '@/app/components/ErrorDialog';
import {
  getOrganizations,
  createOrganization,
  updateOrganization,
  deleteOrganization,
  getUsers,
  createUser,
  updateUser,
  deleteUser,
  getUserById,
  getUserRoles,
  addUserToOrganization,
  removeUserFromOrganization,
  addUserToFunction,
  removeUserFromFunction,
  removeAllUserRoles,
  removeAllOrganizationRoles,
  getFunctions,
  createFunction,
  updateFunction,
  deleteFunction,
  getOrganizationFunctions,
  assignFunctionsToOrganization,
  removeOrganizationFunctions,
  detachFunctionFromOrganization,
  fetchSessionData,
} from '../services/index';

interface CurrentUser {
  sub: string;
  name: string;
  email: string;
}

function AppContent() {
  const { t, language } = useLanguage();
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [authChecked, setAuthChecked] = useState(false);
  const [sessionData, setSessionData] = useState<AdminSessionData | null>(null);

  // State for data
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [userRoles, setUserRoles] = useState<UserOrganizationRole[]>([]);
  const [functions, setFunctions] = useState<FunctionType[]>([]);
  const [organizationFunctions, setOrganizationFunctions] = useState<OrganizationFunction[]>([]);

  const [allowFunctionRemoval, setAllowFunctionRemoval] = useState(false);
  const [allowOrgRights, setAllowOrgRights] = useState(true);
  const [functionConstraint, setFunctionConstraint] = useState<string | null>(null);
  const [orgConstraint, setOrgConstraint] = useState<string | null>(null);

  const [selectedOrg, setSelectedOrg] = useState<Organization | null>(null);
  const [selectedUser, setSelectedUser] = useState<User | null>(null);
  const [selectedFunction, setSelectedFunction] = useState<FunctionType | null>(null);
  const [activeTab, setActiveTab] = useState<string>('organizations');
  const [expandOrgId, setExpandOrgId] = useState<string | null>(null);
  const [isOrgFormOpen, setIsOrgFormOpen] = useState(false);
  const [isUserFormOpen, setIsUserFormOpen] = useState(false);
  const [isFunctionFormOpen, setIsFunctionFormOpen] = useState(false);
  const [isHelpDialogOpen, setIsHelpDialogOpen] = useState(false);
  const [errorDialog, setErrorDialog] = useState<{ title: string; description: string } | null>(null);

  const showError = (title: string, description: string) => {
    setErrorDialog({ title, description });
  };


  const loadData = useCallback(async (skipFunctions = false, skipOrganizations = false, skipUsers = false) => {
    const [orgs, usersData, roles, orgFuncs, funcs] = await Promise.all([
      skipOrganizations ? Promise.resolve(null) : getOrganizations(),
      skipUsers ? Promise.resolve(null) : getUsers(),
      getUserRoles(),
      skipOrganizations ? Promise.resolve(null) : getOrganizationFunctions(),
      skipFunctions ? Promise.resolve(null) : getFunctions(),
    ]);

    if (orgs !== null) setOrganizations(orgs);
    if (usersData !== null) setUsers(usersData);
    setUserRoles(roles);
    if (orgFuncs !== null) setOrganizationFunctions(orgFuncs);
    if (funcs !== null) setFunctions(funcs);
  }, []);

  // Check authentication state on mount, then load data if authenticated
  useEffect(() => {
    const init = async () => {
      try {
        const response = await fetch('/api/me');
        if (response.ok) {
          const user: CurrentUser = await response.json();
          setCurrentUser(user);

          const session = await fetchSessionData();
          if (session !== null) {
            setSessionData(session);

            // Map OrganizationData → Organization
            // TODO: retire getOrganizations() from localStorage once all data is API-sourced
            const adminOrgIds = new Set(session.adminOrgIdentifiers ?? []);

            const mappedOrgs: Organization[] = session.organizations
              .filter((o) => session.superuser || adminOrgIds.has(o.orgIdentifier))
              .map((o) => ({
                id: o.orgIdentifier,
                organizationNumber: o.orgIdentifier,
                nameSv: o.nameSv ?? '',
                nameEn: o.nameEn ?? '',
                contactEmail: o.contactEmail ?? undefined,
                additionalData: o.contactPhone ? { contactPhone: o.contactPhone } : undefined,
              }));

            // Derive OrganizationFunction[] from attachedFunctions
            const derivedOrgFunctions: OrganizationFunction[] = session.organizations
              .filter((o) => session.superuser || adminOrgIds.has(o.orgIdentifier))
              .flatMap((o) =>
                o.attachedFunctions.map((funcName) => ({
                  id: `${o.orgIdentifier}:${funcName}`,
                  organizationId: o.orgIdentifier,
                  functionId: funcName,
                }))
              );

            const mappedUsers: User[] = session.users.map((u) => ({
              id: u.userId,
              personalIdentityNumber: u.personalIdentityNumber ?? '',
              name: [u.firstName, u.lastName].filter(Boolean).join(' ') || u.username || u.userId,
              email: u.email ?? '',
              phoneNumber: u.phoneNumber ?? undefined,
              superuser: u.superuser,
              rights: u.rights,
            }));

            const mappedFunctions: FunctionType[] = session.functions.map((f) => ({
              id: f.id,
              name: f.id,
              nameSv: f.nameSv ?? '',
              nameEn: f.nameEn ?? '',
              descriptionSv: f.descriptionSv ?? '',
              descriptionEn: f.descriptionEn ?? '',
            }));

            setOrganizations(mappedOrgs);
            setOrganizationFunctions(derivedOrgFunctions);
            setUsers(mappedUsers);
            setFunctions(mappedFunctions);
            setAllowFunctionRemoval(session.allowFunctionRemoval ?? false);
            setAllowOrgRights(session.allowOrgRights ?? true);
            setFunctionConstraint(session.functionConstraint ?? null);
            setOrgConstraint(session.orgConstraint ?? null);

            // Derive UserOrganizationRole[] from user rights in session
            const derivedUserRoles: UserOrganizationRole[] = session.users.flatMap((u) =>
              u.rights
                .filter((r) => session.superuser || adminOrgIds.has(r.orgIdentifier))
                .map((r) => ({
                  id: `${u.userId}:${r.orgIdentifier}:${r.functionId ?? '*'}`,
                  userId: u.userId,
                  organizationId: r.orgIdentifier,
                  functionId: r.functionId ?? undefined,
                  role: r.right as 'read' | 'write' | 'admin',
                }))
            );
            setUserRoles(derivedUserRoles);
          } else {
            await loadData();
          }
        }
      } catch {
        // Not logged in – stay on login page
      } finally {
        setAuthChecked(true);
      }
    };

    init();
  }, [loadData]);

  const handleLogout = async () => {
    await fetch('/logout', { method: 'POST' });
    window.location.href = '/';
  };

  const handleSaveOrganization = async (org: Omit<Organization, 'id'> & { id?: string }) => {
    try {
      if (org.id) {
        // Update existing
        const updatedOrg = await updateOrganization(org.id, org);
        setOrganizations(organizations.map((o) => (o.id === org.id ? updatedOrg : o)));
        toast.success(t('toast.orgUpdated'));
      } else {
        // Create new
        const newOrg = await createOrganization(org);
        setOrganizations([...organizations, newOrg]);
        toast.success(t('toast.orgCreated'));
      }
      setIsOrgFormOpen(false);
      setSelectedOrg(null);
    } catch (error) {
      console.error('Error saving organization:', error);
      if (error instanceof Error && error.message === 'DUPLICATE_ORG_NUMBER') {
        showError(t('error.title.saveFailed'), t('error.body.duplicateOrgNumber'));
      } else {
        showError(t('error.title.saveFailed'), t('error.body.generic'));
      }
    }
  };

  const handleDeleteOrganization = async (id: string) => {
    try {
      await deleteOrganization(id);
      const attachedFunctionIds = organizationFunctions
        .filter((of) => of.organizationId === id)
        .map((of) => of.functionId);
      await removeAllOrganizationRoles(id);
      await removeOrganizationFunctions(id, attachedFunctionIds);

      setOrganizations(organizations.filter((o) => o.id !== id));
      setUserRoles(userRoles.filter((r) => r.organizationId !== id));
      setOrganizationFunctions(organizationFunctions.filter((of) => of.organizationId !== id));

      toast.success(t('toast.orgDeleted'));
    } catch (error) {
      console.error('Error deleting organization:', error);
      if (error instanceof Error && error.message === 'HAS_ATTACHED_FUNCTIONS') {
        showError(t('error.title.deleteFailed'), t('error.body.orgHasAttachedFunctions'));
      } else if (error instanceof Error && error.message === 'FORBIDDEN') {
        showError(t('error.title.notAllowed'), t('error.body.forbidden'));
      } else {
        showError(t('error.title.deleteFailed'), t('error.body.generic'));
      }
    }
  };

  const handleDetachFunctionFromOrg = async (organizationId: string, functionId: string) => {
    try {
      await detachFunctionFromOrganization(organizationId, functionId);
      setOrganizationFunctions((prev) =>
        prev.filter((of) => !(of.organizationId === organizationId && of.functionId === functionId))
      );
      setUserRoles((prev) =>
        prev.filter((r) => !(r.organizationId === organizationId && r.functionId === functionId))
      );
      toast.success(t('toast.functionDetached'));
    } catch (error) {
      console.error('Error detaching function from org:', error);
      if (error instanceof Error && error.message === 'FORBIDDEN') {
        showError(t('error.title.notAllowed'), t('error.body.forbidden'));
      } else {
        showError(t('error.title.operationFailed'), t('error.body.generic'));
      }
    }
  };

  const handleEditOrganization = (org: Organization) => {
    setSelectedOrg(org);
    setIsOrgFormOpen(true);
  };

  const handleSaveUser = async (user: Omit<User, 'id'> & { id?: string }) => {
    try {
      if (user.id) {
        // Update existing
        const updatedUser = await updateUser(user.id, user);
        // Preserve rights from current state — updateUser does not return them
        setUsers(prev => prev.map(u =>
          u.id === user.id
            ? { ...updatedUser, rights: u.rights }
            : u
        ));
        toast.success(t('toast.userUpdated'));
      } else {
        // Create new
        const newUser = await createUser(user);
        setUsers([...users, newUser]);
        toast.success(t('toast.userCreated'));
      }
      setIsUserFormOpen(false);
      setSelectedUser(null);
    } catch (error) {
      console.error('Error saving user:', error);
      if (error instanceof Error && error.message === 'DUPLICATE_PERSONAL_IDENTITY_NUMBER') {
        showError(t('error.title.saveFailed'), t('error.body.duplicatePin'));
      } else {
        showError(t('error.title.saveFailed'), t('error.body.generic'));
      }
    }
  };

  const handleDeleteUser = async (id: string) => {
    const targetUser = users.find(u => u.id === id);
    if (!targetUser) return;

    try {
      if (sessionData?.superuser) {
        // Superuser: permanent Keycloak deletion
        await deleteUser(id);
        setUsers(prev => prev.filter(u => u.id !== id));
        setUserRoles(prev => prev.filter(r => r.userId !== id));
        toast.success(t('toast.userDeleted'));
      } else {
        // Non-superuser: strip all visible rights
        const rights = targetUser.rights ?? [];
        await Promise.all(
          rights.map(r =>
            r.functionId
              ? removeUserFromFunction(r.orgIdentifier, r.functionId, id, r.right)
              : removeUserFromOrganization(r.orgIdentifier, id, r.right)
          )
        );
        setUsers(prev => prev.filter(u => u.id !== id));
        setUserRoles(prev => prev.filter(r => r.userId !== id));
        toast.success(t('toast.userRightsRemoved'));
      }
    } catch (error) {
      console.error('Error removing user:', error);
      showError(t('error.title.deleteFailed'), t('error.body.generic'));
    }
  };

  const handleAddUserToOrg = async (organizationId: string, userId: string, role: string) => {
    try {
      const newRole = await addUserToOrganization(organizationId, userId, role as 'read' | 'write' | 'admin');
      setUserRoles([...userRoles, newRole]);
      // If the user is not yet in local state (e.g. imported from another org), fetch and add them.
      if (!users.some((u) => u.id === userId)) {
        const imported = await getUserById(userId);
        if (imported) {
          setUsers((prev) => [...prev, imported]);
        }
      }
      toast.success(t('toast.userAdded'));
    } catch (error) {
      console.error('Error adding user to organization:', error);
      showError(t('error.title.operationFailed'), t('error.body.generic'));
    }
  };

  const handleRemoveUserFromOrg = async (organizationId: string, userId: string, right: string) => {
    try {
      await removeUserFromOrganization(organizationId, userId, right as 'read' | 'write' | 'admin');
      setUserRoles(userRoles.filter((r) => !(r.organizationId === organizationId && r.userId === userId && !r.functionId && r.role === right)));
      setUsers(prev => prev.map(u =>
        u.id !== userId ? u : {
          ...u,
          rights: (u.rights ?? []).filter(r =>
            !(r.orgIdentifier === organizationId && r.functionId === null && r.right === right)
          ),
        }
      ));
      toast.success(t('toast.userRemoved'));
    } catch (error) {
      if (error instanceof LastAdminError) throw error;
      console.error('Error removing user from organization:', error);
      showError(t('error.title.operationFailed'), t('error.body.generic'));
    }
  };

  const handleAssignFunctionsToOrg = async (organizationId: string, functionIds: string[]) => {
    try {
      const newOrgFunctions = await assignFunctionsToOrganization(organizationId, functionIds);

      setOrganizationFunctions([...organizationFunctions, ...newOrgFunctions]);

      setSessionData((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          organizations: prev.organizations.map((o) =>
            o.orgIdentifier === organizationId
              ? { ...o, attachedFunctions: [...o.attachedFunctions, ...functionIds] }
              : o
          ),
        };
      });

      toast.success(t('toast.functionsAssigned'));
    } catch (error) {
      console.error('Error assigning functions to organization:', error);
      if (error instanceof Error && error.message === 'FORBIDDEN') {
        showError(t('error.title.notAllowed'), t('error.body.forbidden'));
      } else {
        showError(t('error.title.operationFailed'), t('error.body.generic'));
      }
    }
  };

  const handleAddUserToFunction = async (organizationId: string, functionId: string, userId: string, role: string) => {
    try {
      const newRole = await addUserToFunction(organizationId, functionId, userId, role as 'read' | 'write' | 'admin');
      setUserRoles([...userRoles, newRole]);
      // If the user is not yet in local state (e.g. imported from another org), fetch and add them.
      if (!users.some((u) => u.id === userId)) {
        const imported = await getUserById(userId);
        if (imported) {
          setUsers((prev) => [...prev, imported]);
        }
      }
      toast.success(t('toast.userAdded'));
    } catch (error) {
      console.error('Error adding user to function:', error);
      showError(t('error.title.operationFailed'), t('error.body.generic'));
    }
  };

  const handleRemoveUserFromFunction = async (organizationId: string, functionId: string, userId: string, right: string) => {
    try {
      await removeUserFromFunction(organizationId, functionId, userId, right as 'read' | 'write' | 'admin');
      setUserRoles(
        userRoles.filter(
          (r) => !(r.organizationId === organizationId && r.functionId === functionId && r.userId === userId && r.role === right)
        )
      );
      setUsers(prev => prev.map(u =>
        u.id !== userId ? u : {
          ...u,
          rights: (u.rights ?? []).filter(r =>
            !(r.orgIdentifier === organizationId && r.functionId === functionId && r.right === right)
          ),
        }
      ));
      toast.success(t('toast.userRemoved'));
    } catch (error) {
      if (error instanceof LastAdminError) throw error;
      console.error('Error removing user from function:', error);
      showError(t('error.title.operationFailed'), t('error.body.generic'));
    }
  };

  const handleChangeUserRight = async (
    organizationId: string,
    functionId: string | undefined,
    userId: string,
    newRight: 'read' | 'write' | 'admin',
    oldRight: 'read' | 'write' | 'admin'
  ) => {
    try {
      if (functionId) {
        await addUserToFunction(organizationId, functionId, userId, newRight);
      } else {
        await addUserToOrganization(organizationId, userId, newRight);
      }
      setUserRoles(prev => prev.map(r => {
        const matches =
          r.userId === userId &&
          r.organizationId === organizationId &&
          (functionId ? r.functionId === functionId : !r.functionId) &&
          r.role === oldRight;
        return matches ? { ...r, role: newRight } : r;
      }));
      setUsers(prev => prev.map(u => {
        if (u.id !== userId) return u;
        return {
          ...u,
          rights: (u.rights ?? []).map(r => {
            const matches =
              r.orgIdentifier === organizationId &&
              (functionId ? r.functionId === functionId : r.functionId === null) &&
              r.right === oldRight;
            return matches ? { ...r, right: newRight } : r;
          }),
        };
      }));
      toast.success(t('toast.rightChanged'));
    } catch (error) {
      if (error instanceof LastAdminError) throw error;
      console.error('Error changing user right:', error);
      showError(t('error.title.operationFailed'), t('error.body.generic'));
    }
  };

  const handleUserListRemoveRight = (
    userId: string,
    orgIdentifier: string,
    functionId: string | null,
    right: 'read' | 'write' | 'admin'
  ) => {
    if (functionId) {
      handleRemoveUserFromFunction(orgIdentifier, functionId, userId, right);
    } else {
      handleRemoveUserFromOrg(orgIdentifier, userId, right);
    }
  };

  const handleUserListChangeRight = (
    userId: string,
    orgIdentifier: string,
    functionId: string | null,
    newRight: 'read' | 'write' | 'admin',
    oldRight: 'read' | 'write' | 'admin'
  ) => {
    handleChangeUserRight(orgIdentifier, functionId ?? undefined, userId, newRight, oldRight);
  };

  const handleEditUser = (user: User) => {
    setSelectedUser(user);
    setIsUserFormOpen(true);
  };

  const handleCreateOrganization = () => {
    setSelectedOrg(null);
    setIsOrgFormOpen(true);
  };

  const handleCreateUser = () => {
    setSelectedUser(null);
    setIsUserFormOpen(true);
  };

  const handleNavigateToOrg = (orgId: string) => {
    setExpandOrgId(orgId);
    setActiveTab('organizations');
  };

  const handleCreateFunction = () => {
    setSelectedFunction(null);
    setIsFunctionFormOpen(true);
  };

  const handleSaveFunction = async (func: Omit<FunctionType, 'id'> & { id?: string }) => {
    try {
      if (func.id) {
        // Update existing
        const updatedFunc = await updateFunction(func.id, func);
        setFunctions(functions.map((f) => (f.id === func.id ? updatedFunc : f)));
        toast.success(t('toast.functionUpdated'));
      } else {
        const newFunc = await createFunction(func);
        setFunctions((prev) => [...prev, newFunc]);
        toast.success(t('toast.functionCreated'));
      }
      setIsFunctionFormOpen(false);
      setSelectedFunction(null);
    } catch (error) {
      console.error('Error saving function:', error);
      if (error instanceof Error && error.message === 'DUPLICATE_FUNCTION_NAME') {
        showError(t('error.title.saveFailed'), t('error.body.duplicateFunctionName'));
      } else if (error instanceof Error && error.message === 'FORBIDDEN') {
        showError(t('error.title.notAllowed'), t('error.body.forbidden'));
      } else {
        showError(t('error.title.saveFailed'), t('error.body.generic'));
      }
    }
  };

  const handleEditFunction = (func: FunctionType) => {
    setSelectedFunction(func);
    setIsFunctionFormOpen(true);
  };

  const handleDeleteFunction = async (id: string) => {
    try {
      await deleteFunction(id);
      setFunctions((prev) => prev.filter((f) => f.id !== id));
      setOrganizationFunctions((prev) => prev.filter((of) => of.functionId !== id));
      setUserRoles((prev) => prev.filter((r) => r.functionId !== id));
      toast.success(t('toast.functionDeleted'));
    } catch (error) {
      console.error('Error deleting function:', error);
      if (error instanceof Error && error.message === 'FORBIDDEN') {
        showError(t('error.title.notAllowed'), t('error.body.forbidden'));
      } else {
        showError(t('error.title.deleteFailed'), t('error.body.generic'));
      }
    }
  };

  if (!authChecked) {
    return null;
  }

  if (!currentUser) {
    return <LoginForm />;
  }

  return (
    <div className="min-h-screen bg-background flex flex-col">
      <Toaster />

      {/* Header */}
      <Header onLogout={handleLogout} showLogout={true} currentUser={currentUser} />

      {/* Main Content */}
      <main className="flex-1 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 w-full">
        <div className="bg-white rounded-lg shadow-sm p-6">
          <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-6">
            <div className="flex items-center justify-between border-b">
              <TabsList className="bg-transparent border-0 p-0">
                <TabsTrigger value="organizations" className="flex items-center gap-2">
                  <Building2 className="w-4 h-4" />
                  {t('organizations.title')}
                </TabsTrigger>
                <TabsTrigger value="users" className="flex items-center gap-2">
                  <UsersIcon className="w-4 h-4" />
                  {t('users.title')}
                </TabsTrigger>
              </TabsList>
              {functionConstraint === null && (
                <TabsList className="bg-transparent border-0 p-0">
                  <TabsTrigger value="functions" className="flex items-center gap-2">
                    <Boxes className="w-4 h-4" />
                    {t('functions.title')}
                  </TabsTrigger>
                </TabsList>
              )}
            </div>

            <TabsContent value="organizations" className="space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <h2 className="text-xl font-semibold">{t('organizations.title')}</h2>
                  {((sessionData?.superuser ?? false) || organizations.length !== 1) && (
                    <p className="text-sm text-gray-500 mt-1">
                      {organizations.length} {organizations.length !== 1 ? t('organizations.count_plural') : t('organizations.count')}
                    </p>
                  )}
                </div>
                {(sessionData?.superuser ?? false) && orgConstraint === null && (
                  <Button onClick={handleCreateOrganization} className="bg-primary hover:bg-primary/90">
                    <Plus className="w-4 h-4 mr-2" />
                    {t('organizations.create')}
                  </Button>
                )}
              </div>

              <OrganizationList
                organizations={organizations}
                users={users}
                userRoles={userRoles}
                functions={functions}
                organizationFunctions={organizationFunctions}
                isSuperuser={sessionData?.superuser ?? false}
                allowOrgRights={allowOrgRights}
                orgRights={sessionData?.orgRights ?? []}
                currentUserId={currentUser.sub}
                onEdit={handleEditOrganization}
                onDelete={handleDeleteOrganization}
                onAddUserToOrg={handleAddUserToOrg}
                onRemoveUserFromOrg={handleRemoveUserFromOrg}
                onAssignFunctionsToOrg={handleAssignFunctionsToOrg}
                onAddUserToFunction={handleAddUserToFunction}
                onRemoveUserFromFunction={handleRemoveUserFromFunction}
                onChangeUserRight={handleChangeUserRight}
                onDetachFunctionFromOrg={handleDetachFunctionFromOrg}
                expandOrgId={expandOrgId}
                onExpandOrgHandled={() => setExpandOrgId(null)}
                onUserCreated={(user) => setUsers((prev) => [...prev, user])}
              />
            </TabsContent>

            <TabsContent value="users" className="space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <h2 className="text-xl font-semibold">{t('users.title')}</h2>
                  <p className="text-sm text-gray-500 mt-1">
                    {users.length} {users.length !== 1 ? t('users.count_plural') : t('users.count')}
                  </p>
                </div>
                <Button onClick={handleCreateUser} className="bg-primary hover:bg-primary/90">
                  <Plus className="w-4 h-4 mr-2" />
                  {t('users.create')}
                </Button>
              </div>

              <UserList
                users={users}
                organizations={organizations}
                functions={functions}
                currentUserId={currentUser.sub}
                isSuperuser={sessionData?.superuser ?? false}
                onEdit={handleEditUser}
                onDeleteUser={handleDeleteUser}
                onRemoveRight={handleUserListRemoveRight}
                onChangeRight={handleUserListChangeRight}
              />
            </TabsContent>

            <TabsContent value="functions" className="space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <h2 className="text-xl font-semibold">{t('functions.title')}</h2>
                  <p className="text-sm text-gray-500 mt-1">
                    {functions.length} {functions.length !== 1 ? t('functions.count_plural') : t('functions.count')}
                  </p>
                </div>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    onClick={() => setIsHelpDialogOpen(true)}
                  >
                    <HelpCircle className="w-4 h-4 mr-2" />
                    {t('functions.help')}
                  </Button>
                  {(sessionData?.superuser ?? false) && (
                    <Button onClick={handleCreateFunction} className="bg-primary hover:bg-primary/90">
                      <Plus className="w-4 h-4 mr-2" />
                      {t('functions.create')}
                    </Button>
                  )}
                </div>
              </div>

              <FunctionList
                functions={functions}
                organizations={organizations}
                organizationFunctions={organizationFunctions}
                isSuperuser={sessionData?.superuser ?? false}
                allowFunctionRemoval={allowFunctionRemoval}
                onEdit={handleEditFunction}
                onDelete={handleDeleteFunction}
                onNavigateToOrg={handleNavigateToOrg}
              />
            </TabsContent>
          </Tabs>
        </div>
      </main>

      {/* Forms */}
      <OrganizationForm
        organization={selectedOrg}
        isOpen={isOrgFormOpen}
        isSuperuser={sessionData?.superuser ?? false}
        currentUserOrgAdminIds={(sessionData?.orgRights ?? [])
          .filter(o => o.functions.some(f => f.function === '*' && f.right === 'admin'))
          .map(o => o.orgIdentifier)}
        onClose={() => {
          setIsOrgFormOpen(false);
          setSelectedOrg(null);
        }}
        onSave={handleSaveOrganization}
      />

      <UserForm
        user={selectedUser}
        organizations={organizations}
        functions={functions}
        isOpen={isUserFormOpen}
        currentUserId={currentUser.sub}
        onRemoveRight={handleUserListRemoveRight}
        onChangeRight={handleUserListChangeRight}
        onClose={() => {
          setIsUserFormOpen(false);
          setSelectedUser(null);
        }}
        onSave={handleSaveUser}
      />

      <FunctionForm
        func={selectedFunction}
        isOpen={isFunctionFormOpen}
        onClose={() => {
          setIsFunctionFormOpen(false);
          setSelectedFunction(null);
        }}
        onSave={handleSaveFunction}
      />

      {/* Error dialog */}
      <ErrorDialog
        open={errorDialog !== null}
        title={errorDialog?.title ?? ''}
        description={errorDialog?.description ?? ''}
        onClose={() => setErrorDialog(null)}
      />

      {/* Help Dialog */}
      <Dialog open={isHelpDialogOpen} onOpenChange={setIsHelpDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('functions.helpTitle')}</DialogTitle>
            <DialogDescription className="text-left pt-2">
              {t('functions.helpContent')}
            </DialogDescription>
          </DialogHeader>
        </DialogContent>
      </Dialog>

      {/* Footer */}
      <Footer />
    </div>
  );
}

export default function App() {
  return (
    <LanguageProvider>
      <ThemeProvider>
        <AppContent />
      </ThemeProvider>
    </LanguageProvider>
  );
}
