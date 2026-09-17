import { createContext, useContext, ReactNode } from 'react';
import { UserRegistrationSettings } from '@/types';

/**
 * The settings applied until the session has been loaded. They mirror the defaults of
 * iam.admin.user-registration in the backend.
 */
export const DEFAULT_USER_REGISTRATION_SETTINGS: UserRegistrationSettings = {
  allowSelectUserId: false,
  allowTemporaryPassword: false,
  eidAttributeRequired: true,
  personalNumberEnabled: true,
  hsaIdEnabled: false,
  orgAffiliationEnabled: false,
  efosIdEnabled: false,
};

const UserRegistrationContext = createContext<UserRegistrationSettings>(
  DEFAULT_USER_REGISTRATION_SETTINGS
);

interface UserRegistrationProviderProps {
  settings: UserRegistrationSettings;
  children: ReactNode;
}

/** Makes the user registration settings available to every create-user form. */
export function UserRegistrationProvider({ settings, children }: UserRegistrationProviderProps) {
  return (
    <UserRegistrationContext.Provider value={settings}>
      {children}
    </UserRegistrationContext.Provider>
  );
}

/** Returns the user registration settings delivered on the session. */
export function useUserRegistration(): UserRegistrationSettings {
  return useContext(UserRegistrationContext);
}
