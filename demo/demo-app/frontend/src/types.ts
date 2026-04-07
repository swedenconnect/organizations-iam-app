export interface CurrentUser {
  sub: string;
  name: string;
  personalIdentityNumber: string | null;
  superuser: boolean;
  organizations: OrgEntry[];
}

export interface OrgEntry {
  orgId: string;
  orgName: string;
  right: 'read' | 'write' | 'admin';
}

export interface ContactData {
  address: string;
  telephoneNumber: string;
  emailAddress: string;
}
