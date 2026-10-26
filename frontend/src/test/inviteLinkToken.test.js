import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { takeInviteToken, resetInviteTokenForTests } from '../utils/inviteLinkToken.js';

/** Puts the browser on `url` without a navigation; the spy is installed after, so seeding is not counted. */
function openAt(url) {
  window.history.replaceState({ keep: 1 }, '', url);
  return vi.spyOn(window.history, 'replaceState');
}

const address = () => `${window.location.pathname}${window.location.search}${window.location.hash}`;

describe('takeInviteToken', () => {
  beforeEach(() => {
    resetInviteTokenForTests();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    resetInviteTokenForTests();
    window.history.replaceState({}, '', '/');
  });

  it('returns the fragment invite token and removes it from the address, keeping the path', () => {
    const spy = openAt('/register#invite=abc-123');
    expect(takeInviteToken()).toBe('abc-123');
    expect(address()).toBe('/register');
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy.mock.calls[0][0]).toEqual({ keep: 1 });
  });

  it('keeps the query and any other fragment pairs', () => {
    openAt('/register?a=1#x=1&invite=abc&y=2');
    expect(takeInviteToken()).toBe('abc');
    expect(address()).toBe('/register?a=1#x=1&y=2');
  });

  it('does not read a verification or reset token, and leaves such a link untouched', () => {
    const spy = openAt('/verify#token=vtok');
    expect(takeInviteToken()).toBeNull();
    expect(address()).toBe('/verify#token=vtok');
    expect(spy).not.toHaveBeenCalled();
  });

  it('does not read the query form', () => {
    openAt('/register?invite=abc');
    expect(takeInviteToken()).toBeNull();
    expect(address()).toBe('/register?invite=abc');
  });

  it('returns the same token on a second call without touching the address again', () => {
    const spy = openAt('/register#invite=abc');
    expect(takeInviteToken()).toBe('abc');
    expect(takeInviteToken()).toBe('abc');
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('an empty invite= pair yields null and is still removed', () => {
    openAt('/register#invite=');
    expect(takeInviteToken()).toBeNull();
    expect(address()).toBe('/register');
  });

  it('keeps a malformed escape as raw text rather than losing the token', () => {
    openAt('/register#invite=%E0%A4%A');
    expect(takeInviteToken()).toBe('%E0%A4%A');
  });
});
