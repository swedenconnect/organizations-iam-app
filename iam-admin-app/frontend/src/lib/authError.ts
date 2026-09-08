/**
 * Resolution of the login rejection reason served by GET /api/auth-error.
 *
 * The backend owns these texts. It answers with a message code and one finished message per
 * supported language, so the login page can switch language without asking again:
 *
 *   { "code": "login.error.noAdminRight", "messages": { "sv": "...", "en": "..." } }
 *
 * or with {} when nothing is stored. Everything here is defensive: any shape that is not
 * recognised resolves to null, and the caller falls back to the generic access-denied text.
 */

/** The shape GET /api/auth-error answers with. Every member is optional; nothing is trusted. */
export interface AuthErrorPayload {
  code?: string;
  messages?: Record<string, string>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asText(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

/**
 * Resolves the message to display for a fetched auth-error payload.
 *
 * The message for the selected language wins. If it is missing, any other language present is used
 * rather than showing nothing. A payload that carries no usable message resolves to null; the raw
 * message code is never returned.
 *
 * @param payload the value fetched from GET /api/auth-error, or anything else
 * @param language the language the page is displayed in
 * @returns the message to display, or null when the caller should fall back
 */
export function resolveAuthErrorMessage(payload: unknown, language: string): string | null {
  if (!isRecord(payload) || !isRecord(payload.messages)) {
    return null;
  }
  const messages = payload.messages;

  const selected = asText(messages[language]);
  if (selected !== null) {
    return selected;
  }
  for (const value of Object.values(messages)) {
    const text = asText(value);
    if (text !== null) {
      return text;
    }
  }
  return null;
}
