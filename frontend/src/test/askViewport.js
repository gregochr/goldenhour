/**
 * A viewport for the Ask suites: a `matchMedia` that evaluates the width queries the app uses
 * (`(max-width: Npx)`, `(min-width: Npx)`, joined with `and` and, as `resolveInitialTab`'s is, with a comma) against a width a test sets
 * and moves.
 *
 * <p>jsdom has no layout, so a breakpoint can only be tested by what the code DOES at a width — and
 * that needs a `matchMedia` that answers by width. `setup.js`'s default answers "no match" to
 * everything (the desktop branch); this replaces it for one test and {@link resetViewport} puts it
 * back. Queries it does not understand answer "no match", which is the same fail-quiet direction.
 */

const original = typeof window === 'undefined' ? null : window.matchMedia;

/** Whether one `(min|max-width: Npx)` clause holds at `width`. */
function clauseMatches(clause, width) {
  const m = /\(\s*(min|max)-width:\s*(\d+(?:\.\d+)?)px\s*\)/.exec(clause);
  if (!m) return false;
  const limit = Number(m[2]);
  return m[1] === 'min' ? width >= limit : width <= limit;
}

/** Whether a whole query holds at `width`: comma-separated alternatives, each clauses joined by `and`. */
function queryMatches(query, width) {
  return query.split(',').some((alternative) => alternative.trim().split(/\s+and\s+/)
    .every((clause) => clauseMatches(clause, width)));
}

/**
 * Installs the width-aware `matchMedia` and returns a handle to move the width.
 *
 * @param {number} width the starting width in CSS px
 * @returns {{resize: function(number): void, width: number}}
 */
export function installViewport(width) {
  const handle = { width };
  const lists = new Set();
  window.innerWidth = width;
  window.matchMedia = (query) => {
    const list = {
      media: query,
      get matches() { return queryMatches(query, handle.width); },
      onchange: null,
      lastMatches: queryMatches(query, handle.width),
      handlers: new Set(),
      addEventListener(type, fn) { if (type === 'change') list.handlers.add(fn); },
      removeEventListener(type, fn) { if (type === 'change') list.handlers.delete(fn); },
      addListener() {},
      removeListener() {},
      dispatchEvent() { return false; },
    };
    lists.add(list);
    return list;
  };
  handle.resize = (next) => {
    handle.width = next;
    window.innerWidth = next;
    lists.forEach((list) => {
      // A real MediaQueryList fires `change` only when `matches` changes; so does this.
      const before = list.lastMatches;
      const matches = queryMatches(list.media, next);
      list.lastMatches = matches;
      if (matches !== before) list.handlers.forEach((fn) => fn({ matches, media: list.media }));
    });
  };
  return handle;
}

/** Puts back the `matchMedia` that was there before the first {@link installViewport}. */
export function resetViewport() {
  if (original) window.matchMedia = original;
  window.innerWidth = 1024;
}
