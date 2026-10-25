/**
 * Reads the one-time token of an emailed link (verification, password reset) from the page's URL
 * and removes it from the address bar, once per page load.
 *
 * <p>The token is a credential. Left in the address it rides in every `Referer` the page sends and
 * in browser history, so it is lifted into memory before React renders and the address is rewritten
 * without it (docs/engineering/token-log-redaction-plan.md, step 2).
 *
 * <p>The fragment (`#token=`) is read first and the query (`?token=`) second, so links mailed in
 * either form work. The backend mails the fragment form; the query is still read for links mailed before that release.
 * Like the query form, `#token=` is accepted on any path (the legacy `/?token=` verification link
 * relies on that).
 */

/** The result of the first call, kept for the life of the page; `undefined` until first read. */
let memo;

const PAIR = 'token=';

/**
 * Finds `token=` pairs in a `&`-separated string.
 *
 * @param {string} raw a query or fragment body, without its leading `?` or `#`
 * @returns {{found: boolean, value: string|null, rest: string}} whether a pair was present, the
 *     decoded value of the first (null when empty) and the text with every such pair removed
 */
function splitToken(raw) {
  const parts = raw.split('&');
  const index = parts.findIndex((p) => p.startsWith(PAIR));
  if (index === -1) {
    return { found: false, value: null, rest: raw };
  }
  let value = parts[index].slice(PAIR.length);
  try {
    value = decodeURIComponent(value.replace(/\+/g, ' '));
  } catch {
    // A malformed escape: keep the raw text rather than lose the token.
  }
  return { found: true, value: value || null, rest: parts.filter((p) => !p.startsWith(PAIR)).join('&') };
}

/**
 * Returns the link token, reading and stripping it from the URL on the first call only.
 *
 * <p>History is touched only when a token pair was present, and only `token=` pairs are removed (all of
 * them, so a duplicate cannot linger), as text: the fragment is never round-tripped through {@code URLSearchParams}, which would mangle
 * the app's own {@code #manage/<tab>} routes. An emptied query or fragment drops its `?` / `#`.
 *
 * @returns {string|null} the token, or null when the URL carried none
 */
export function takeLinkToken() {
  if (memo !== undefined) {
    return memo;
  }
  const { pathname, search, hash } = window.location;
  const fromHash = splitToken(hash.slice(1));
  const fromQuery = splitToken(search.slice(1));
  memo = fromHash.value || fromQuery.value || null;

  if (fromHash.found || fromQuery.found) {
    // Strip every token= pair present, whichever form won, so none is left in the address.
    const newSearch = fromQuery.found ? (fromQuery.rest ? `?${fromQuery.rest}` : '') : search;
    const newHash = fromHash.found ? (fromHash.rest ? `#${fromHash.rest}` : '') : hash;
    window.history.replaceState(window.history.state, '', `${pathname}${newSearch}${newHash}`);
  }
  return memo;
}

/** Test-only: forgets the memo so the next {@link takeLinkToken} reads the URL again. */
export function resetLinkTokenForTests() {
  memo = undefined;
}
