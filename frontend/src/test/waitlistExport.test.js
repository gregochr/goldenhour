import { describe, it, expect, vi, afterEach } from 'vitest';
import { downloadTextFile, waitlistCsv, waitlistEmailLines } from '../utils/waitlistExport.js';

describe('waitlistExport', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
    // jsdom has neither; the download test installs them, so take them away again.
    delete globalThis.URL.createObjectURL;
    delete globalThis.URL.revokeObjectURL;
  });

  it('writes the header row, then one row per entry, CRLF-separated', () => {
    const csv = waitlistCsv([
      { email: 'a@example.com', submittedAt: '2026-10-02T07:14:00' },
      { email: 'b@example.com', submittedAt: '2026-10-09T20:40:00' },
    ]);
    expect(csv).toBe('email,submittedAt\r\na@example.com,2026-10-02T07:14:00\r\nb@example.com,2026-10-09T20:40:00\r\n');
  });

  it('keeps submittedAt exactly as the server sent it', () => {
    expect(waitlistCsv([{ email: 'a@example.com', submittedAt: '2026-10-02T07:14:00Z' }]))
      .toContain('a@example.com,2026-10-02T07:14:00Z');
  });

  it('quotes a field holding a comma, a quote or a line break, doubling the quotes (RFC 4180)', () => {
    const csv = waitlistCsv([
      { email: '"odd,one"@example.com', submittedAt: 'x' },
      { email: 'line\nbreak@example.com', submittedAt: null },
    ]);
    expect(csv).toContain('"""odd,one""@example.com",x');
    expect(csv).toContain('"line\nbreak@example.com",');
  });

  it('is just the header for an empty list', () => {
    expect(waitlistCsv([])).toBe('email,submittedAt\r\n');
  });

  it('joins the addresses one per line for the clipboard', () => {
    expect(waitlistEmailLines([{ email: 'a@x.com' }, { email: 'b@x.com' }])).toBe('a@x.com\nb@x.com');
  });

  it.each(['=cmd@x.com', '+1@x.com', '-a@x.com', '@a@x.com', '\tb@x.com'])(
    'defuses a spreadsheet formula in the CSV by prefixing a single quote: %j',
    (email) => {
      const row = waitlistCsv([{ email, submittedAt: 'x' }]).split('\r\n')[1];
      expect(row.startsWith(`'${email}`)).toBe(true);
    },
  );

  it('quotes a defused field that also needs RFC 4180 quoting', () => {
    expect(waitlistCsv([{ email: '=a,b', submittedAt: 'x' }])).toContain('"\'=a,b",x');
  });

  it('leaves the copied addresses raw, formula characters and all', () => {
    expect(waitlistEmailLines([{ email: '=cmd@x.com' }])).toBe('=cmd@x.com');
  });

  it('saves a file through a temporary object URL and revokes it', () => {
    vi.useFakeTimers();
    const create = vi.fn(() => 'blob:fake');
    const revoke = vi.fn();
    globalThis.URL.createObjectURL = create;
    globalThis.URL.revokeObjectURL = revoke;
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    downloadTextFile('waitlist.csv', 'email,submittedAt\r\n');

    expect(create).toHaveBeenCalledTimes(1);
    expect(click).toHaveBeenCalledTimes(1);
    // Not yet: the download may start after the click returns.
    expect(revoke).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1000);
    expect(revoke).toHaveBeenCalledWith('blob:fake');
    expect(document.querySelector('a[download]')).toBeNull();
  });
});
