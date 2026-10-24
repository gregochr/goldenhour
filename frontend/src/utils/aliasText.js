/**
 * Turns the comma-separated text an admin types into a list of other names: trimmed, blanks dropped.
 * The server owns every other rule (length, word count, clashes), so none is repeated here.
 *
 * @param {string} text
 * @returns {string[]}
 */
export function parseAliases(text) {
  return String(text ?? '')
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
}

/**
 * Formats a list of other names for the comma-separated field.
 *
 * @param {string[]|undefined} aliases
 * @returns {string}
 */
export function formatAliases(aliases) {
  return Array.isArray(aliases) ? aliases.join(', ') : '';
}

/**
 * The message to show for a failed admin request: the server's own text (it names a clash), else the friendly
 * fallback. Never the transport's "Request failed with status code 500" or "Network Error".
 *
 * @param {unknown} err
 * @param {string} fallback
 * @returns {string}
 */
export function errorText(err, fallback) {
  return err?.response?.data?.error ?? fallback;
}
