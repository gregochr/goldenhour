/**
 * Reads the waitlist-invitation token of an emailed link (`<app>/register#invite=<token>`) from the page's
 * URL and removes it from the address bar, once per page load.
 *
 * <p>Modelled on `oneTimeLinkToken.js`: the token is a credential, so it is lifted into memory before React
 * renders and the address is rewritten without it. It is its own reader, not a mode of `takeLinkToken`,
 * because that one's `token=` pair is an email-verification or password-reset link and the app routes those
 * differently. Only the fragment form is read (the backend mails nothing else), and only `invite=` pairs are
 * removed, as text, so a neighbouring `#manage/<tab>` route is never mangled.
 */

/** The result of the first call, kept for the life of the page; `undefined` until first read. */
let memo;

const PAIR = 'invite=';

/**
 * Returns the invitation token, reading and stripping it from the URL on the first call only.
 *
 * @returns {string|null} the token, or null when the URL carried none
 */
export function takeInviteToken() {
  if (memo !== undefined) {
    return memo;
  }
  const { pathname, search, hash } = window.location;
  const parts = hash.slice(1).split('&');
  const index = parts.findIndex((p) => p.startsWith(PAIR));
  if (index === -1) {
    memo = null;
    return memo;
  }
  let value = parts[index].slice(PAIR.length);
  try {
    value = decodeURIComponent(value);
  } catch {
    // A malformed escape: keep the raw text rather than lose the token.
  }
  memo = value || null;

  const rest = parts.filter((p) => !p.startsWith(PAIR)).join('&');
  window.history.replaceState(window.history.state, '', `${pathname}${search}${rest ? `#${rest}` : ''}`);
  return memo;
}

/** Test-only: forgets the memo so the next {@link takeInviteToken} reads the URL again. */
export function resetInviteTokenForTests() {
  memo = undefined;
}
