import { describe, expect, it } from 'vitest';
import { resolveAuthErrorMessage } from './authError';

const payload = {
  code: 'login.error.noAdminRight',
  messages: {
    sv: 'Du har inga administrativa rättigheter i någon organisation.',
    en: 'You have no administrative rights in any organization.',
  },
};

describe('resolveAuthErrorMessage', () => {
  it('returns the message for the selected language', () => {
    expect(resolveAuthErrorMessage(payload, 'sv')).toBe(payload.messages.sv);
    expect(resolveAuthErrorMessage(payload, 'en')).toBe(payload.messages.en);
  });

  it('falls back to another language when the selected one is missing', () => {
    const onlyEnglish = { code: payload.code, messages: { en: payload.messages.en } };
    expect(resolveAuthErrorMessage(onlyEnglish, 'sv')).toBe(payload.messages.en);
  });

  it('falls back to another language when the selected one is blank', () => {
    const blankSwedish = { code: payload.code, messages: { sv: '   ', en: payload.messages.en } };
    expect(resolveAuthErrorMessage(blankSwedish, 'sv')).toBe(payload.messages.en);
  });

  it('returns null for an empty response', () => {
    expect(resolveAuthErrorMessage({}, 'sv')).toBeNull();
  });

  it('returns null when nothing was fetched', () => {
    expect(resolveAuthErrorMessage(null, 'sv')).toBeNull();
    expect(resolveAuthErrorMessage(undefined, 'sv')).toBeNull();
  });

  it('returns null when no language carries a message', () => {
    expect(resolveAuthErrorMessage({ code: payload.code, messages: {} }, 'sv')).toBeNull();
    expect(resolveAuthErrorMessage({ code: payload.code, messages: { sv: '', en: '' } }, 'sv')).toBeNull();
  });

  it('never returns the message code', () => {
    expect(resolveAuthErrorMessage({ code: payload.code }, 'sv')).toBeNull();
  });

  it('ignores values that are not strings', () => {
    expect(resolveAuthErrorMessage({ messages: { sv: 42, en: null } }, 'sv')).toBeNull();
    expect(resolveAuthErrorMessage({ messages: { sv: 42, en: payload.messages.en } }, 'sv'))
      .toBe(payload.messages.en);
  });

  it('rejects shapes that are not an object', () => {
    expect(resolveAuthErrorMessage('boom', 'sv')).toBeNull();
    expect(resolveAuthErrorMessage(['sv'], 'sv')).toBeNull();
    expect(resolveAuthErrorMessage({ messages: 'nope' }, 'sv')).toBeNull();
    expect(resolveAuthErrorMessage({ messages: ['nope'] }, 'sv')).toBeNull();
  });
});
