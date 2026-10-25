import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { takeLinkToken, resetLinkTokenForTests } from '../utils/oneTimeLinkToken.js';

/** Puts the browser on `url` without a navigation; the spy is installed after, so seeding is not counted. */
function openAt(url) {
  window.history.replaceState({ keep: 1 }, '', url);
  return vi.spyOn(window.history, 'replaceState');
}

const address = () => `${window.location.pathname}${window.location.search}${window.location.hash}`;

describe('takeLinkToken', () => {
  beforeEach(() => {
    resetLinkTokenForTests();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    resetLinkTokenForTests();
    window.history.replaceState({}, '', '/');
  });

  it('returns the query token and removes it from the address, keeping the path', () => {
    const spy = openAt('/reset?token=abc');
    expect(takeLinkToken()).toBe('abc');
    expect(address()).toBe('/reset');
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy.mock.calls[0][0]).toEqual({ keep: 1 });
  });

  it('keeps every other query parameter and the fragment', () => {
    openAt('/verify?a=1&token=abc&b=2#manage/metrics');
    expect(takeLinkToken()).toBe('abc');
    expect(address()).toBe('/verify?a=1&b=2#manage/metrics');
  });

  it('leaves a URL with no token untouched and never calls replaceState', () => {
    const spy = openAt('/?x=1#manage/metrics');
    expect(takeLinkToken()).toBeNull();
    expect(address()).toBe('/?x=1#manage/metrics');
    expect(spy).not.toHaveBeenCalled();
  });

  it('returns the same token on a second call without touching history again', () => {
    const spy = openAt('/verify?token=abc');
    expect(takeLinkToken()).toBe('abc');
    expect(takeLinkToken()).toBe('abc');
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('forgets the memo when reset, so a new page load reads afresh', () => {
    openAt('/verify?token=one');
    expect(takeLinkToken()).toBe('one');
    resetLinkTokenForTests();
    openAt('/verify?token=two');
    expect(takeLinkToken()).toBe('two');
  });

  it('reads and drops a fragment-only token, including the emptied #', () => {
    openAt('/reset#token=xyz');
    expect(takeLinkToken()).toBe('xyz');
    expect(address()).toBe('/reset');
  });

  it('keeps the other fragment keys when stripping #token=', () => {
    openAt('/reset#token=xyz&a=b');
    expect(takeLinkToken()).toBe('xyz');
    expect(address()).toBe('/reset#a=b');
  });

  it('prefers the fragment when both forms are present, and strips both', () => {
    openAt('/reset?token=fromquery#token=fromhash');
    expect(takeLinkToken()).toBe('fromhash');
    expect(address()).toBe('/reset');
  });
});
