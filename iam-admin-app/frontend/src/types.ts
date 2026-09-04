// Type definitions for the application
export interface Organization {
  id: string;
  organizationNumber: string;
  nameSv: string;
  nameEn: string;
  contactEmail?: string;
  additionalData?: Record<string, string>;
}

export interface User {
  id: string;
  personalIdentityNumber: string;
  name: string;
  email: string;
  phoneNumber?: string;
  superuser?: boolean;
  rights?: UserRightData[];
}

export interface FunctionType {
  id: string;
  name: string;       // unique identifier (immutable)
  nameSv: string;
  nameEn: string;
  descriptionSv: string;
  descriptionEn: string;
}

export interface OrganizationFunction {
  id: string;
  organizationId: string;
  functionId: string;
}

export interface UserOrganizationRole {
  id: string;
  userId: string;
  organizationId: string;
  functionId?: string; // If present, rights are on function; else on org
  role: 'read' | 'write' | 'admin';
}

// Session data loaded from /api/session after login

export interface FunctionData {
  id: string;
  nameSv: string | null;
  nameEn: string | null;
  descriptionSv: string | null;
  descriptionEn: string | null;
}

export interface UserFunctionRight {
  function: string; // a function attached to the organization
  right: 'admin' | 'write' | 'read';
}

export interface UserOrgRight {
  orgIdentifier: string;
  // Provenance only: the right granted at the organization level, absent if none. Confers no
  // access by itself — effective rights are in `functions`, which lists only attached functions
  // and may be empty. Use it solely to decide who may administer the organization itself.
  orgLevelRight?: 'admin' | 'write' | 'read' | null;
  functions: UserFunctionRight[];
}

export interface OrganizationData {
  orgIdentifier: string;
  nameSv: string | null;
  nameEn: string | null;
  groupId: string;
  attachedFunctions: string[];
  contactEmail?: string | null;
  contactPhone?: string | null;
}

export interface UserRightData {
  orgIdentifier: string;
  functionId: string | null;
  right: 'admin' | 'write' | 'read';
}

export interface UserData {
  userId: string;
  username: string | null;
  firstName: string | null;
  lastName: string | null;
  email: string | null;
  personalIdentityNumber: string | null;
  phoneNumber?: string | null;
  superuser: boolean;
  rights: UserRightData[];
}

export interface OrganizationPage {
  content: OrganizationData[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface UserPage {
  content: UserData[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface ManagedClient {
  id: string;
  oidcClient: boolean;
  resourceServer: boolean;
  clientId: string;
  name: string | null;
  functions: string[];
  redirectUris: string[];
  jwksUri: string | null;
  jwksString: string | null;
  serviceAccount: boolean;
  orgRightsIdToken: boolean;
  orgRightsAccessToken: boolean;
  enabled: boolean;
}

export interface ManagedClientInput {
  clientId: string;
  name: string;
  oidcClient: boolean;
  resourceServer: boolean;
  functions: string[];
  redirectUris: string[];
  jwksUri: string | null;
  jwksString: string | null;
  orgRightsIdToken: boolean;
  orgRightsAccessToken: boolean;
}

export interface ReconciliationReport {
  clients: number;
  created: number;
  removed: number;
  errors: string[];
}

export interface AdminSessionData {
  superuser: boolean;
  functionConstraint: string | null;
  orgConstraint: string | null;
  allowFunctionRemoval: boolean;
  allowOrgRights: boolean;
  functions: FunctionData[];
  orgRights: UserOrgRight[];
  adminOrgIdentifiers: string[];
}