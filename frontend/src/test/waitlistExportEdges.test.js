import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { downloadTextFile, waitlistCsv } from '../utils/waitlistExport.js';

describe('waitlistCsv — an absent field is empty, never the word "null"', () => {
  it('writes an empty field for a null or undefined submittedAt or email', () => {
    expect(waitlistCsv([
      { email: 'a@example.com', submittedAt: null },
      { email: 'b@example.com' },
      { email: null, submittedAt: '2026-11-02T10:00:00Z' },
    ])).toBe('email,submittedAt\r\na@example.com,\r\nb@example.com,\r\n,2026-11-02T10:00:00Z\r\n');
  });

  it('quotes a field holding a comma, quote or line break, and guards a formula', () => {
    expect(waitlistCsv([{ email: '=SUM(A1)', submittedAt: 'a,"b"' }]))
      .toBe('email,submittedAt\r\n\'=SUM(A1),"a,""b"""\r\n');
  });
});

describe('downloadTextFile', () => {
  let created;
  let click;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout'] });
    created = [];
    URL.createObjectURL = vi.fn((blob) => {
      created.push(blob);
      return 'blob:fake-url';
    });
    URL.revokeObjectURL = vi.fn();
    click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function recordLink() {
      click.link = { href: this.href, download: this.download, attached: document.body.contains(this) };
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    delete URL.createObjectURL;
    delete URL.revokeObjectURL;
    document.body.innerHTML = '';
  });

  it('hands the text to a CSV blob by default and clicks an attached link with the file name', async () => {
    downloadTextFile('waitlist.csv', 'email,submittedAt\r\n');
    expect(created).toHaveLength(1);
    expect(created[0].type).toBe('text/csv;charset=utf-8');
    expect(await created[0].text()).toBe('email,submittedAt\r\n');
    expect(click).toHaveBeenCalledTimes(1);
    expect(click.link).toEqual({ href: 'blob:fake-url', download: 'waitlist.csv', attached: true });
  });

  it('uses the media type it is given', () => {
    downloadTextFile('emails.txt', 'a@example.com', 'text/plain');
    expect(created[0].type).toBe('text/plain');
  });

  it('removes the link once clicked', () => {
    downloadTextFile('waitlist.csv', 'x');
    expect(document.body.querySelector('a')).toBeNull();
  });

  it('revokes the object URL one second later, not before', () => {
    downloadTextFile('waitlist.csv', 'x');
    expect(URL.revokeObjectURL).not.toHaveBeenCalled();
    vi.advanceTimersByTime(999);
    expect(URL.revokeObjectURL).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:fake-url');
  });
});
