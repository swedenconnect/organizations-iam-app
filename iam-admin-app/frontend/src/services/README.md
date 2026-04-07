# Services Layer

This directory contains the data service layer for the application. All data operations are abstracted into dedicated service functions, preparing the codebase for backend integration.

## Architecture

The service layer follows these principles:

1. **All data operations are async functions** - Even though they currently use mock data or localStorage, they return Promises so that real API calls can be dropped in with zero changes to components.

2. **Clear API endpoint mapping** - Every service function has a `// TODO: Replace with fetch('/api/...')` comment indicating exactly which API endpoint it will map to.

3. **Separation of concerns** - Components only handle UI and call service functions; all business logic resides in the service layer.

4. **Type safety** - All functions are fully typed using TypeScript interfaces from `/src/types.ts`.

## Service Files

### `storageService.ts`
Handles all localStorage operations. These will be replaced with server-side session/storage when backend is integrated.

**Functions:**
- `loadFromStorage<T>()` - Load data from localStorage
- `saveToStorage<T>()` - Save data to localStorage  
- `clearAllStorage()` - Clear all application data

### `organizationService.ts`
Handles all organization-related data operations.

**Functions:**
- `getOrganizations()` → `GET /api/organizations`
- `createOrganization()` → `POST /api/organizations`
- `updateOrganization()` → `PUT /api/organizations/:id`
- `deleteOrganization()` → `DELETE /api/organizations/:id`
- `getOrganizationById()` → `GET /api/organizations/:id`

### `userService.ts`
Handles all user-related data operations and user-role assignments.

**Functions:**
- `getUsers()` → `GET /api/users`
- `createUser()` → `POST /api/users`
- `updateUser()` → `PUT /api/users/:id`
- `deleteUser()` → `DELETE /api/users/:id`
- `getUserById()` → `GET /api/users/:id`
- `getUserRoles()` → `GET /api/user-roles`
- `addUserToOrganization()` → `POST /api/user-roles`
- `removeUserFromOrganization()` → `DELETE /api/user-roles`
- `addUserToFunction()` → `POST /api/user-roles`
- `removeUserFromFunction()` → `DELETE /api/user-roles`
- `removeAllUserRoles()` → `DELETE /api/users/:id/roles`
- `removeAllOrganizationRoles()` → `DELETE /api/organizations/:id/roles`

### `functionService.ts`
Handles all function-related data operations and organization-function assignments.

**Functions:**
- `getFunctions()` → `GET /api/functions`
- `createFunction()` → `POST /api/functions`
- `updateFunction()` → `PUT /api/functions/:id`
- `deleteFunction()` → `DELETE /api/functions/:id`
- `getFunctionById()` → `GET /api/functions/:id`
- `getOrganizationFunctions()` → `GET /api/organization-functions`
- `assignFunctionsToOrganization()` → `POST /api/organizations/:id/functions`
- `removeOrganizationFunctions()` → `DELETE /api/organizations/:id/functions`
- `removeFunctionFromOrganizations()` → `DELETE /api/functions/:id/organizations`

## Backend Integration Guide

When implementing the actual backend, follow these steps:

1. **Replace mock returns with fetch calls** - Search for `// TODO: Replace with fetch` comments
2. **Update error handling** - Add proper error handling for network failures
3. **Add authentication headers** - Include bearer tokens or session cookies
4. **Update response parsing** - Parse JSON responses and handle error responses
5. **Add loading states** - Components may need loading indicators for async operations

### Example Migration

**Before (current mock):**
```typescript
export async function getOrganizations(): Promise<Organization[]> {
  // TODO: Replace with fetch('/api/organizations')
  return loadFromStorage(STORAGE_KEYS.ORGANIZATIONS, INITIAL_ORGANIZATIONS);
}
```

**After (with real API):**
```typescript
export async function getOrganizations(): Promise<Organization[]> {
  const response = await fetch('/api/organizations', {
    headers: {
      'Authorization': `Bearer ${getAuthToken()}`,
      'Content-Type': 'application/json',
    },
  });
  
  if (!response.ok) {
    throw new Error('Failed to fetch organizations');
  }
  
  return response.json();
}
```

## Usage in Components

Components import and use service functions like this:

```typescript
import { getOrganizations, createOrganization } from '@/services';

// In component
const loadData = async () => {
  const orgs = await getOrganizations();
  setOrganizations(orgs);
};

const handleCreate = async (data) => {
  const newOrg = await createOrganization(data);
  setOrganizations([...organizations, newOrg]);
};
```

## Notes

- All service functions are exported from `/src/services/index.ts` for convenient importing
- Components should never directly access localStorage - always use the service layer
- The current implementation maintains data consistency by updating both localStorage and React state
- When backend is integrated, remove the localStorage operations entirely
