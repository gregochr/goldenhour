import { describe, it, expect, beforeEach } from 'vitest';
import { resetInviteTokenForTests, takeInviteToken } from '../utils/inviteLinkToken.js';

describe('inviteLinkToken edges', () => {
  beforeEach(() => {
    resetInviteTokenForTests();
    window.history.replaceState(null, '', '/');
  });

  it('decodes an escaped token', () => {
    window.history.replaceState(null, '', '/#invite=a%2Bb%20c');
    expect(takeInviteToken()).toBe('a+b c');
  });

  it('keeps the raw text of a token with a malformed escape', () => {
    window.history.replaceState(null, '', '/#invite=abc%E0%A4%A');
    expect(takeInviteToken()).toBe('abc%E0%A4%A');
  });

  it('strips the pair and keeps the rest of the fragment', () => {
    window.history.replaceState(null, '', '/path?x=1#manage/users&invite=tok');
    expect(takeInviteToken()).toBe('tok');
    expect(window.location.pathname + window.location.search + window.location.hash)
      .toBe('/path?x=1#manage/users');
  });

  it('is null when the fragment carries no invite', () => {
    window.history.replaceState(null, '', '/#manage/users');
    expect(takeInviteToken()).toBeNull();
    expect(window.location.hash).toBe('#manage/users');
  });
});
