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
  function: string; // "*" = org-wide
  right: 'admin' | 'write' | 'read';
}

export interface UserOrgRight {
  orgIdentifier: string;
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

export interface AdminSessionData {
  superuser: boolean;
  functionConstraint: string | null;
  orgConstraint: string | null;
  allowFunctionRemoval: boolean;
  allowOrgRights: boolean;
  functions: FunctionData[];
  organizations: OrganizationData[];
  users: UserData[];
  orgRights: UserOrgRight[];
  adminOrgIdentifiers: string[];
}