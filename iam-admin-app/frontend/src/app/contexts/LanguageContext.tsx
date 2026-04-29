import { createContext, useContext, useState, ReactNode } from 'react';

type Language = 'en' | 'sv';

interface LanguageContextType {
  language: Language;
  setLanguage: (lang: Language) => void;
  t: (key: string) => string;
}

const LanguageContext = createContext<LanguageContextType | undefined>(undefined);

const translations = {
  en: {
    // Header
    'header.adminPortal': 'Admin Portal',
    'header.logout': 'Logout',
    'header.language': 'English',
    
    // Login
    'login.title': 'Admin Login',
    'login.description': 'Sign in to manage organizations and users',
    'login.button': 'Login',
    'login.authenticating': 'Authenticating...',
    'login.demo': 'Demo: Click to simulate OpenID Connect authentication',
    'login.accessDenied': 'You do not have administrative rights to perform user delegation. Please contact your system administrator.',
    'login.sessionExpired': 'Your session has expired. Please log in again.',

    // Dashboard
    'dashboard.title': 'Admin Dashboard',
    'dashboard.description': 'Manage organizations and users',
    
    // Organizations
    'organizations.title': 'Organizations',
    'organizations.count': 'organization',
    'organizations.count_plural': 'organizations',
    'organizations.create': 'Create Organization',
    'organizations.createDescription': 'Add a new organization to the system',
    'organizations.edit': 'Edit Organization',
    'organizations.editDescription': 'Edit organization details',
    'organizations.number': 'Organization Number',
    'organizations.nameSv': 'Organization Name (Swedish)',
    'organizations.nameEn': 'Organization Name (English)',
    'organizations.contactEmail': 'Contact Email',
    'organizations.contactPhone': 'Contact Phone Number',
    'organizations.invalidOrgNumber': 'Organization number must be 10 digits',
    
    // Users
    'users.title': 'Users',
    'users.count': 'user',
    'users.count_plural': 'users',
    'users.create': 'Create User',
    'users.edit': 'Edit User',
    'users.delete': 'Delete',
    'users.name': 'Name',
    'users.email': 'Email',
    'users.details': 'Details',
    'users.uniqueIdentity': 'Personal Identity Number',
    'users.superuser': 'System administrator (superuser)',
    'users.phoneNumber': 'Telephone Number',
    'users.organizations': 'Organizations',
    'users.role': 'Role',
    'users.assignedRights': 'Assigned Rights',
    'users.manageRightsFromOrg': 'Manage user rights from the Organizations tab',
    'users.orgLevelRights': 'Organization-level Rights',
    'users.functionLevelRights': 'Function-level Rights',
    'users.functions': 'Function Rights',
    
    // Functions
    'functions.title': 'Functions',
    'functions.count': 'function',
    'functions.count_plural': 'functions',
    'functions.create': 'Create Function',
    'functions.edit': 'Edit Function',
    'functions.delete': 'Delete',
    'functions.name': 'Function Name',
    'functions.displayName': 'Display Name',
    'functions.description': 'Description',
    'functions.nameSv': 'Name (Swedish)',
    'functions.nameEn': 'Name (English)',
    'functions.descriptionSv': 'Description (Swedish)',
    'functions.descriptionEn': 'Description (English)',
    'functions.nameHint': 'Unique identifier, lowercase letters, digits, hyphens and underscores only',
    'functions.assignedOrgs': 'Assigned Organizations',
    'functions.noOrgs': 'Not assigned to any organizations',
    'functions.orgFunctions': 'Functions',
    'functions.noFunctions': 'No functions assigned',
    'functions.assignToOrg': 'Assign Functions',
    'functions.assignedToOrg': 'Functions assigned to organization',
    'functions.alreadyAttached': '(already attached)',
    'functions.empty': 'No functions yet. Create your first one!',
    'functions.detachFromOrg': 'De-assign',
    'functions.help': 'Help',
    'functions.helpTitle': 'What is a Function?',
    'functions.helpContent': 'A Function represents a specific area of functionality or service that can be assigned to organizations. Users can be granted rights on specific functions within an organization, allowing for granular access control beyond organization-level permissions. For example, if an organization has access to multiple services, you can assign different users to different functions based on their responsibilities.',
    
    // Roles
    'role.read': 'Read',
    'role.write': 'Write',
    'role.admin': 'Admin',
    
    // Common
    'common.save': 'Save',
    'common.cancel': 'Cancel',
    'common.edit': 'Edit',
    'common.delete': 'Delete',
    'common.close': 'Close',
    'common.search': 'Search',
    'common.searchPlaceholder': 'Search...',
    'common.noResults': 'No results found',
    'common.contact': 'Contact Information',
    'common.confirm': 'Confirm',
    'common.ok': 'OK',
    'confirm.removeRight': 'Are you sure you want to remove this right?',
    'confirm.removeRightTitle': 'Remove right',
    'confirm.removeRightDescription': 'Are you sure you want to remove this right? This action cannot be undone.',
    'confirm.deleteUserTitle': 'Remove user',
    'confirm.deleteUserDescriptionSuperuser': 'Are you sure you want to permanently delete this user? This action cannot be undone.',
    'confirm.deleteUserDescriptionAdmin': 'Are you sure you want to remove all rights for this user? The user will remain in the system but will lose access to your organization.',
    'confirm.deleteUserBlockedTitle': 'Cannot remove user',
    'confirm.deleteUserBlockedDescription': 'This user is the last administrator for the following organizations or functions. Assign another administrator before removing this user:',
    'confirm.deleteFuncTitle': 'Delete function',
    'confirm.deleteFuncDescriptionPrefix': 'This will permanently delete the function "',
    'confirm.deleteFuncDescriptionSuffix': '". All organizations connected to this function will also lose this assignment. This action cannot be undone.',
    'confirm.deleteOrgTitle': 'Delete organization',
    'confirm.deleteOrgDescription': 'Are you sure you want to permanently delete this organization? This action cannot be undone.',
    'confirm.deleteOrgBlockedTitle': 'Cannot delete organization',
    'confirm.deleteOrgBlockedDescription': 'All functions must be de-assigned before the organization can be deleted.',
    'confirm.detachFunctionTitle': 'De-assign function',
    'confirm.detachFunctionDescription': 'Are you sure you want to remove this function from the organization? Users assigned to this function will lose their access.',

    // Search
    'search.organizations': 'Search organizations by name or number',
    'search.users': 'Search users by name or ID',
    'search.functions': 'Search functions by name or description',
    
    // Organization details
    'org.usersWithAccess': 'Users with access on organizational level',
    'org.noUsers': 'No users have access to this organization',
    'org.clickToExpand': 'Click to view users',
    'org.addUser': 'Add User',
    'org.addUserToOrg': 'Add User to Organization',
    'org.selectExistingUser': 'Select existing user',
    'org.createNewUser': 'Create new user',
    'org.createAndAddUser': 'Create and add',
    'org.selectUser': 'Select a user to add',
    'org.selectRole': 'Select role',
    'org.userAlreadyAdded': 'No users available to add',
    'org.removeUser': 'Remove user access',
    'org.functions': 'Functions',
    'org.noFunctions': 'No functions assigned to this organization',
    'org.assignFunctions': 'Assign Functions',
    'org.addInformation': 'Add Information',
    'org.userAlreadyExists': 'User already registered',
    'org.userAlreadyExistsInfo': 'A user with this personal identity number already exists. You can add this user directly.',
    'org.userExistsNotVisible': 'This person already has an account in the system and belongs to another organization. Do you want to assign them access here?',
    'org.assignExistingUser': 'Assign user',

    // Toasts
    'toast.loginSuccess': 'Successfully logged in!',
    'toast.logoutSuccess': 'Logged out successfully',
    'toast.orgCreated': 'Organization created successfully',
    'toast.orgUpdated': 'Organization updated successfully',
    'toast.orgDeleted': 'Organization deleted successfully',
    'toast.functionDetached': 'Function removed from organization',
    'toast.userCreated': 'User created successfully',
    'toast.userUpdated': 'User updated successfully',
    'toast.userDeleted': 'User deleted successfully',
    'toast.userAdded': 'User added to organization',
    'toast.userRemoved': 'User removed from organization',
    'toast.functionsAssigned': 'Function assigned to organization',
    'toast.functionCreated': 'Function created successfully',
    'toast.functionUpdated': 'Function updated successfully',
    'toast.functionDeleted': 'Function deleted',
    'toast.rightChanged': 'Right updated',
    'toast.userRightsRemoved': 'User rights removed',

    // Errors (dialog messages)
    'error.lastAdminTitle': 'Cannot change right',
    'error.lastAdminDescription': '{name} is the only administrator on this organization. Assign another administrator before changing this right.',

    'error.title.saveFailed':      'Save failed',
    'error.title.deleteFailed':    'Delete failed',
    'error.title.operationFailed': 'Operation failed',
    'error.title.notAllowed':      'Not allowed',

    'error.body.duplicateOrgNumber':      'An organization with this number already exists.',
    'error.body.orgHasAttachedFunctions': 'All functions must be de-assigned before the organization can be deleted.',
    'error.body.forbidden':               'You do not have permission to perform this action.',
    'error.body.duplicatePin':            'A user with this personal identity number already exists.',
    'error.body.duplicateFunctionName':   'A function with this name already exists.',
    'error.body.generic':                 'An unexpected error occurred. Please try again.',

    // Validation
    'validation.required': 'Required',
    'validation.emailRequired': 'Valid email required',
    'validation.pin12digits': 'Must be 12 digits (with or without dash)',
    'validation.pin12digitsPlaceholder': '12 digits',
    'validation.createUserFailed': 'Failed to create user. Please try again.',

    // Footer
    'footer.tagline': 'Secure and reliable communication for Sweden',
    'footer.eservices': 'E-services',
    'footer.contact': 'Contact',
    'footer.personalData': 'Personal data',
    'footer.accessibility': 'Accessibility',
    'footer.cookies': 'Cookies',
  },
  sv: {
    // Header
    'header.adminPortal': 'Administrationsportal',
    'header.logout': 'Logga ut',
    'header.language': 'Svenska',
    
    // Login
    'login.title': 'Administratörsinloggning',
    'login.description': 'Logga in för att hantera organisationer och användare',
    'login.button': 'Logga in',
    'login.authenticating': 'Autentiserar...',
    'login.demo': 'Demo: Klicka för att simulera OpenID Connect-autentisering',
    'login.accessDenied': 'Du har inte administrativa rättigheter att utföra användardelegering. Kontakta din systemadministratör.',
    'login.sessionExpired': 'Din session har gått ut. Logga in igen.',
    
    // Dashboard
    'dashboard.title': 'Administratörspanel',
    'dashboard.description': 'Hantera organisationer och användare',
    
    // Organizations
    'organizations.title': 'Organisationer',
    'organizations.count': 'organisation',
    'organizations.count_plural': 'organisationer',
    'organizations.create': 'Skapa organisation',
    'organizations.createDescription': 'Lägg till en ny organisation i systemet',
    'organizations.edit': 'Redigera organisation',
    'organizations.editDescription': 'Redigera organisationsdetaljer',
    'organizations.number': 'Organisationsnummer',
    'organizations.nameSv': 'Organisationsnamn (Svenska)',
    'organizations.nameEn': 'Organisationsnamn (Engelska)',
    'organizations.contactEmail': 'Kontakt E-post',
    'organizations.contactPhone': 'Kontakt Telefonnummer',
    'organizations.invalidOrgNumber': 'Organisationsnummer måste vara 10 siffror',
    
    // Users
    'users.title': 'Användare',
    'users.count': 'användare',
    'users.count_plural': 'användare',
    'users.create': 'Skapa användare',
    'users.edit': 'Redigera användare',
    'users.delete': 'Ta bort',
    'users.name': 'Namn',
    'users.email': 'E-post',
    'users.details': 'Detaljer',
    'users.uniqueIdentity': 'Personnummer',
    'users.superuser': 'Systemadministratör (superanvändare)',
    'users.phoneNumber': 'Telefonnummer',
    'users.organizations': 'Organisationer',
    'users.role': 'Roll',
    'users.assignedRights': 'Tilldelade rättigheter',
    'users.manageRightsFromOrg': 'Hantera användarrättigheter från fliken Organisationer',
    'users.orgLevelRights': 'Organisationsnivåns rättigheter',
    'users.functionLevelRights': 'Funktionsnivåns rättigheter',
    'users.functions': 'Funktionsrättigheter',
    
    // Functions
    'functions.title': 'Funktioner',
    'functions.count': 'funktion',
    'functions.count_plural': 'funktioner',
    'functions.create': 'Skapa funktion',
    'functions.edit': 'Redigera funktion',
    'functions.delete': 'Ta bort',
    'functions.name': 'Funktionsnamn',
    'functions.displayName': 'Visningsnamn',
    'functions.description': 'Beskrivning',
    'functions.nameSv': 'Namn (svenska)',
    'functions.nameEn': 'Namn (engelska)',
    'functions.descriptionSv': 'Beskrivning (svenska)',
    'functions.descriptionEn': 'Beskrivning (engelska)',
    'functions.nameHint': 'Unikt id, bara gemener, siffror, bindestreck och understreck',
    'functions.assignedOrgs': 'Tilldelade organisationer',
    'functions.noOrgs': 'Inte tilldelad några organisationer',
    'functions.orgFunctions': 'Funktioner',
    'functions.noFunctions': 'Inga funktioner tilldelade',
    'functions.assignToOrg': 'Tilldela funktioner',
    'functions.assignedToOrg': 'Funktioner tilldelade organisationen',
    'functions.alreadyAttached': '(redan tilldelad)',
    'functions.empty': 'Inga funktioner ännu. Skapa din första!',
    'functions.detachFromOrg': 'Koppla bort',
    'functions.help': 'Hjälp',
    'functions.helpTitle': 'Vad är en funktion?',
    'functions.helpContent': 'En funktion representerar ett specifikt område av funktionalitet eller tjänst som kan tilldelas organisationer. Användare kan ges rättigheter på specifika funktioner inom en organisation, vilket möjliggör granulär åtkomstkontroll utöver organisationsnivåns behörigheter. Till exempel, om en organisation har åtkomst till flera tjänster kan du tilldela olika användare till olika funktioner baserat på deras ansvar.',
    
    // Roles
    'role.read': 'Läs',
    'role.write': 'Skriv',
    'role.admin': 'Admin',
    
    // Common
    'common.save': 'Spara',
    'common.cancel': 'Avbryt',
    'common.edit': 'Redigera',
    'common.delete': 'Ta bort',
    'common.close': 'Stäng',
    'common.search': 'Sök',
    'common.searchPlaceholder': 'Sök...',
    'common.noResults': 'Inga resultat hittades',
    'common.contact': 'Kontaktinformation',
    'common.confirm': 'Bekräfta',
    'common.ok': 'OK',
    'confirm.removeRight': 'Är du säker på att du vill ta bort denna behörighet?',
    'confirm.removeRightTitle': 'Ta bort behörighet',
    'confirm.removeRightDescription': 'Är du säker på att du vill ta bort denna behörighet? Åtgärden kan inte ångras.',
    'confirm.deleteUserTitle': 'Ta bort användare',
    'confirm.deleteUserDescriptionSuperuser': 'Är du säker på att du vill ta bort denna användare permanent? Åtgärden kan inte ångras.',
    'confirm.deleteUserDescriptionAdmin': 'Är du säker på att du vill ta bort alla behörigheter för denna användare? Användaren finns kvar i systemet men förlorar åtkomst till din organisation.',
    'confirm.deleteUserBlockedTitle': 'Kan inte ta bort användare',
    'confirm.deleteUserBlockedDescription': 'Denna användare är den sista administratören för följande organisationer eller funktioner. Tilldela en annan administratör innan du tar bort användaren:',
    'confirm.deleteFuncTitle': 'Ta bort funktion',
    'confirm.deleteFuncDescriptionPrefix': 'Detta tar permanent bort funktionen "',
    'confirm.deleteFuncDescriptionSuffix': '". Alla organisationer kopplade till den här funktionen kommer också att förlora denna tilldelning. Åtgärden kan inte ångras.',
    'confirm.deleteOrgTitle': 'Ta bort organisation',
    'confirm.deleteOrgDescription': 'Är du säker på att du vill ta bort denna organisation permanent? Åtgärden kan inte ångras.',
    'confirm.deleteOrgBlockedTitle': 'Kan inte ta bort organisation',
    'confirm.deleteOrgBlockedDescription': 'Alla funktioner måste kopplas bort innan organisationen kan tas bort.',
    'confirm.detachFunctionTitle': 'Koppla bort funktion',
    'confirm.detachFunctionDescription': 'Är du säker på att du vill ta bort denna funktion från organisationen? Användare tilldelade till funktionen förlorar sin åtkomst.',

    // Search
    'search.organizations': 'Sök organisationer efter namn eller nummer',
    'search.users': 'Sök användare efter namn eller ID',
    'search.functions': 'Sök funktioner efter namn eller beskrivning',
    
    // Organization details
    'org.usersWithAccess': 'Användare med åtkomst på organisationsnivå',
    'org.noUsers': 'Inga användare har åtkomst till denna organisation',
    'org.clickToExpand': 'Klicka för att visa användare',
    'org.addUser': 'Lägg till användare',
    'org.addUserToOrg': 'Lägg till användare i organisation',
    'org.selectExistingUser': 'Välj befintlig användare',
    'org.createNewUser': 'Skapa ny användare',
    'org.createAndAddUser': 'Skapa och lägg till',
    'org.selectUser': 'Välj en användare att lägga till',
    'org.selectRole': 'Välj roll',
    'org.userAlreadyAdded': 'Inga användare att lägga till',
    'org.removeUser': 'Ta bort användaråtkomst',
    'org.functions': 'Funktioner',
    'org.noFunctions': 'Inga funktioner tilldelade denna organisation',
    'org.assignFunctions': 'Tilldela funktioner',
    'org.addInformation': 'Lägg till information',
    'org.userAlreadyExists': 'Användaren finns redan registrerad',
    'org.userAlreadyExistsInfo': 'En användare med detta personnummer finns redan. Du kan lägga till användaren direkt.',
    'org.userExistsNotVisible': 'Den här personen har redan ett konto i systemet och tillhör en annan organisation. Vill du ge dem behörighet här?',
    'org.assignExistingUser': 'Tilldela användare',

    // Toasts
    'toast.loginSuccess': 'Inloggningen lyckades!',
    'toast.logoutSuccess': 'Utloggad',
    'toast.orgCreated': 'Organisation skapad',
    'toast.orgUpdated': 'Organisation uppdaterad',
    'toast.orgDeleted': 'Organisation borttagen',
    'toast.functionDetached': 'Funktion bortkopplad från organisation',
    'toast.userCreated': 'Användare skapad',
    'toast.userUpdated': 'Användare uppdaterad',
    'toast.userDeleted': 'Användare borttagen',
    'toast.userAdded': 'Användare lagt till i organisation',
    'toast.userRemoved': 'Användare borttagen från organisation',
    'toast.functionsAssigned': 'Funktion tilldelad organisation',
    'toast.functionCreated': 'Funktion skapad',
    'toast.functionUpdated': 'Funktion uppdaterad',
    'toast.functionDeleted': 'Funktion borttagen',
    'toast.rightChanged': 'Behörighet uppdaterad',
    'toast.userRightsRemoved': 'Användarbehörigheter borttagna',

    // Errors (dialog messages)
    'error.lastAdminTitle': 'Kan inte ändra behörighet',
    'error.lastAdminDescription': '{name} är den enda administratören på den här organisationen. Tilldela en annan administratör innan du ändrar behörigheten.',

    'error.title.saveFailed':      'Kunde inte spara',
    'error.title.deleteFailed':    'Kunde inte ta bort',
    'error.title.operationFailed': 'Åtgärden misslyckades',
    'error.title.notAllowed':      'Inte tillåtet',

    'error.body.duplicateOrgNumber':      'En organisation med detta nummer finns redan.',
    'error.body.orgHasAttachedFunctions': 'Alla funktioner måste kopplas bort innan organisationen kan tas bort.',
    'error.body.forbidden':               'Du saknar behörighet att utföra denna åtgärd.',
    'error.body.duplicatePin':            'En användare med detta personnummer finns redan.',
    'error.body.duplicateFunctionName':   'En funktion med detta namn finns redan.',
    'error.body.generic':                 'Ett oväntat fel uppstod. Försök igen.',

    // Validation
    'validation.required': 'Obligatoriskt',
    'validation.emailRequired': 'Ange en giltig e-postadress',
    'validation.pin12digits': 'Måste vara 12 siffror (med eller utan bindestreck)',
    'validation.pin12digitsPlaceholder': '12 siffror',
    'validation.createUserFailed': 'Det gick inte att skapa användaren. Försök igen.',

    // Footer
    'footer.tagline': 'Säker och tillförlitlig kommunikation för Sverige',
    'footer.eservices': 'E-tjänster',
    'footer.contact': 'Kontakt',
    'footer.personalData': 'Personuppgifter',
    'footer.accessibility': 'Tillgänglighet',
    'footer.cookies': 'Kakor',
  },
};

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [language, setLanguage] = useState<Language>(() => {
    const stored = localStorage.getItem('language');
    return (stored === 'en' || stored === 'sv') ? stored : 'sv';
  });

  const setLanguageAndPersist = (lang: Language) => {
    localStorage.setItem('language', lang);
    setLanguage(lang);
  };

  const t = (key: string): string => {
    try {
      return translations[language][key as keyof typeof translations.en] || key;
    } catch (error) {
      console.error('Translation error:', error);
      return key;
    }
  };

  return (
    <LanguageContext.Provider value={{ language, setLanguage: setLanguageAndPersist, t }}>
      {children}
    </LanguageContext.Provider>
  );
}

export function useLanguage() {
  const context = useContext(LanguageContext);
  if (context === undefined) {
    throw new Error('useLanguage must be used within a LanguageProvider');
  }
  return context;
}
