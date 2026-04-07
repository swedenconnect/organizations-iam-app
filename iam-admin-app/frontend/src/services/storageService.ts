/**
 * Storage Service
 * Handles all localStorage operations for the application.
 * These will be replaced with server-side session/storage when backend is integrated.
 */

const STORAGE_KEYS = {
  ORGANIZATIONS: 'admin_app_organizations',
  USERS: 'admin_app_users',
  FUNCTIONS: 'admin_app_functions',
  ORG_FUNCTIONS: 'admin_app_org_functions',
} as const;

/**
 * Load data from localStorage
 */
export async function loadFromStorage<T>(key: string, defaultValue: T): Promise<T> {
  try {
    const stored = localStorage.getItem(key);
    // TODO: Replace with fetch to retrieve persisted data from backend
    return stored ? JSON.parse(stored) : defaultValue;
  } catch (error) {
    console.error(`Error loading ${key} from localStorage:`, error);
    return defaultValue;
  }
}

/**
 * Save data to localStorage
 */
export async function saveToStorage<T>(key: string, value: T): Promise<void> {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    // TODO: Replace with fetch to persist data to backend
  } catch (error) {
    console.error(`Error saving ${key} to localStorage:`, error);
  }
}


export { STORAGE_KEYS };
