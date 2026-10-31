/**
 * Export helpers for the admin Waitlist (data-tabs-redesign-plan.md §8): the whole list, never the search result.
 */

/** How long the object URL lives after the click, so the download can start. */
const REVOKE_DELAY_MS = 1000;

/**
 * One CSV field. A field a spreadsheet would read as a formula (it starts with = + - @ tab or CR) gets a single
 * quote in front (OWASP CSV injection); then RFC 4180: quoted when it holds a comma, a quote or a line break,
 * with quotes doubled. Only the CSV is guarded: Copy emails stays raw, because those are used as addresses.
 */
function csvField(value) {
  let text = value == null ? '' : String(value);
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/**
 * The waitlist as CSV: a header row {@code email,submittedAt}, then one row per entry, CRLF-separated (RFC 4180),
 * with {@code submittedAt} exactly as the server sent it (an ISO instant).
 *
 * @param {Array<{email: string, submittedAt: string|null}>} entries
 * @returns {string}
 */
export function waitlistCsv(entries) {
  const lines = ['email,submittedAt', ...entries.map((e) => `${csvField(e.email)},${csvField(e.submittedAt)}`)];
  return `${lines.join('\r\n')}\r\n`;
}

/**
 * The waitlist's addresses, one per line, for the clipboard.
 *
 * @param {Array<{email: string}>} entries
 * @returns {string}
 */
export function waitlistEmailLines(entries) {
  return entries.map((e) => e.email).join('\n');
}

/**
 * Saves text as a file through a temporary object URL (client-side; nothing is sent anywhere).
 *
 * @param {string} filename the suggested file name
 * @param {string} text the file's contents
 * @param {string} [type] the media type
 */
export function downloadTextFile(filename, text, type = 'text/csv;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Not synchronously: some browsers start the download after the click returns and would find the URL gone.
  setTimeout(() => URL.revokeObjectURL(url), REVOKE_DELAY_MS);
}
