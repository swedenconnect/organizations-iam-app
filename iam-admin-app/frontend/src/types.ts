// Type definitions for the application
export interface Organization {
  id: string;
  organizationNumber: string;
  /** The name registered at Bolagsverket. Mandatory, and the fallback whenever no display name is set. */
  legalName: string;
  /** Optional Swedish display name. */
  nameSv?: string | null;
  /** Optional English display name. */
  nameEn?: string | null;
  contactEmail?: string;
  additionalData?: Record<string, string>;
}

export interface User {
  id: string;
  personalIdentityNumber: string;
  /** Organizational affiliation on the format userID@organization-number. */
  orgAffiliation?: string;
  name: string;
  email: string;
  phoneNumber?: string;
  superuser?: boolean;
  rights?: UserRightData[];
}

/** The values POST /api/users accepts. Which of them the backend honours is decided by the
 *  iam.admin.user-registration settings delivered on the session. */
export interface CreateUserInput {
  name: string;
  email: string;
  /** The Keycloak user ID (username) to assign. Only honoured when allowSelectUserId is set. */
  userId?: string;
  personalIdentityNumber?: string;
  orgAffiliation?: string;
  phoneNumber?: string;
  /** Initial password that the user must change at first login. */
  temporaryPassword?: string;
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
  legalName: string;
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
  orgAffiliation?: string | null;
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

/** The iam.admin.user-registration settings, telling the create-user forms what to render. */
export interface UserRegistrationSettings {
  allowSelectUserId: boolean;
  allowTemporaryPassword: boolean;
  eidAttributeRequired: boolean;
  personalNumberEnabled: boolean;
  hsaIdEnabled: boolean;
  orgAffiliationEnabled: boolean;
  efosIdEnabled: boolean;
}

export interface AdminSessionData {
  superuser: boolean;
  functionConstraint: string | null;
  orgConstraint: string | null;
  allowFunctionRemoval: boolean;
  allowOrgRights: boolean;
  allowAdminAssigningAdmin: boolean;
  userRegistration: UserRegistrationSettings;
  functions: FunctionData[];
  orgRights: UserOrgRight[];
  adminOrgIdentifiers: string[];
}