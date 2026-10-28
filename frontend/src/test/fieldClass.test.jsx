import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import LoginPage from '../components/LoginPage.jsx';
import RegisterPage from '../components/RegisterPage.jsx';
import ChangePasswordPage from '../components/ChangePasswordPage.jsx';
import ForgotPasswordPage from '../components/auth/ForgotPasswordPage.jsx';
import ResetPasswordPage from '../components/auth/ResetPasswordPage.jsx';
import PostcodeCapture from '../components/auth/PostcodeCapture.jsx';
import UserSettingsModal from '../components/UserSettingsModal.jsx';
import { AuthProvider } from '../context/AuthContext.jsx';
import * as AuthApi from '../api/authApi.js';
import { getSettings } from '../api/settingsApi.js';

vi.mock('../api/authApi.js');
// Only the Settings test below reads the API; PostcodeCapture and the auth pages never call it here.
vi.mock('../api/settingsApi.js', () => ({
  getSettings: vi.fn(),
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  refreshDriveTimes: vi.fn(),
  saveMapColourPreferences: vi.fn(),
  reverseLookupPostcode: vi.fn(),
}));

const here = (rel) => fileURLToPath(new URL(rel, import.meta.url));
const read = (rel) => readFileSync(here(rel), 'utf8');
const css = read('../index.css').replace(/\/\*[\s\S]*?\*\//g, '');

function installTurnstileMock() {
  window.turnstile = {
    render: vi.fn((_container, opts) => {
      if (opts.callback) opts.callback('test-turnstile-token');
      return 'widget-id-1';
    }),
    remove: vi.fn(),
    reset: vi.fn(),
  };
}

/** Every text-like input in a container: not checkboxes, not the hidden Turnstile field. */
const textInputs = (container) => [...container.querySelectorAll('input')]
  .filter((i) => !['checkbox', 'hidden'].includes(i.type));

/**
 * `.field` REPLACES an input's class string. These three utilities are the ones that would beat it:
 * Tailwind 4's `utilities` layer outranks `components`, so a leftover `text-sm` wins over
 * `text-base sm:text-sm` (the iOS focus-zoom fix) and the other two repaint the old input.
 */
function expectOnlyTheSharedFieldStyle(input) {
  const label = `${input.id || input.name || input.type} (${input.className})`;
  const classes = input.className.split(/\s+/).filter(Boolean);
  expect(classes, label).toContain('field');
  expect(classes, label).not.toContain('text-sm');
  expect(classes, label).not.toContain('bg-plex-surface-light');
  expect(classes, label).not.toContain('rounded');
  expect(classes, label).not.toContain('border-red-700');
  expect(input.className, label).not.toMatch(/placeholder-plex/);
  // The allowlist is the real rule: any OTHER utility (text-xs, text-lg, bg-plex-surface, px-4 …)
  // also outranks the components layer, so only these three may ever sit beside `.field`.
  classes.forEach((c) => expect(['field', 'field-error', 'pr-10'], label).toContain(c));
}

const renderAuth = (ui) => render(<AuthProvider>{ui}</AuthProvider>);

describe('every auth input carries the shared .field style and nothing that outranks it', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    installTurnstileMock();
  });

  afterEach(() => {
    delete window.turnstile;
  });

  it('sign-in: username and password', () => {
    const { container } = renderAuth(<LoginPage />);

    const inputs = textInputs(container);
    expect(inputs.map((i) => i.id)).toEqual(['login-username', 'login-password']);
    inputs.forEach(expectOnlyTheSharedFieldStyle);
  });

  it('change password: both fields', () => {
    const { container } = renderAuth(<ChangePasswordPage />);

    const inputs = textInputs(container);
    expect(inputs.map((i) => i.id)).toEqual(['cp-new', 'cp-confirm']);
    inputs.forEach(expectOnlyTheSharedFieldStyle);
  });

  it('forgot password: the email field', () => {
    const { container } = renderAuth(<ForgotPasswordPage onBackToLogin={vi.fn()} />);

    const inputs = textInputs(container);
    expect(inputs.map((i) => i.id)).toEqual(['forgot-email']);
    inputs.forEach(expectOnlyTheSharedFieldStyle);
  });

  it('reset password: both fields, with the eye room', () => {
    const { container } = renderAuth(
      <ResetPasswordPage token="t" onDone={vi.fn()} onRequestNewLink={vi.fn()} />,
    );

    const inputs = textInputs(container);
    expect(inputs.map((i) => i.id)).toEqual(['reset-new', 'reset-confirm']);
    inputs.forEach(expectOnlyTheSharedFieldStyle);
    inputs.forEach((i) => expect(i.className).toContain('pr-10'));
  });

  it('the postcode capture: its field shares a row with a button, so flex-1 and min-w-0 are the only extras', () => {
    const { container } = renderAuth(
      <PostcodeCapture
        heading="Where's home?" sub="x" primaryLabel="Save" secondaryLabel="Skip"
        onSaved={vi.fn()} onSkip={vi.fn()}
      />,
    );

    const inputs = textInputs(container);
    expect(inputs.map((i) => i.id)).toEqual(['postcode-input']);
    const classes = inputs[0].className.split(/\s+/).filter(Boolean);
    expect(classes).toContain('field');
    expect(classes).not.toContain('text-sm');
    expect(classes).not.toContain('bg-plex-surface-light');
    expect(classes).not.toContain('rounded');
    classes.forEach((c) => expect(['field', 'field-error', 'flex-1', 'min-w-0']).toContain(c));
  });

  it('settings: the home postcode field shares a row with Look up, so flex-1 and min-w-0 are the only extras', async () => {
    getSettings.mockResolvedValue({
      username: 'alice', role: 'LITE_USER', homePostcode: null, homePlaceName: null, driveTimesCalculatedAt: null,
    });
    render(<UserSettingsModal onClose={vi.fn()} />);

    const input = await screen.findByTestId('settings-postcode-input');
    const classes = input.className.split(/\s+/).filter(Boolean);
    expect(classes).toContain('field');
    expect(classes).not.toContain('text-sm');
    expect(classes).not.toContain('bg-plex-surface-light');
    expect(classes).not.toContain('rounded');
    classes.forEach((c) => expect(['field', 'field-error', 'flex-1', 'min-w-0']).toContain(c));
  });

  it('settings: the danger zone\'s password field is the shared PasswordInput on .field, with only its eye\'s padding beside it', async () => {
    getSettings.mockResolvedValue({
      username: 'alice', role: 'LITE_USER', homePostcode: null, homePlaceName: null, driveTimesCalculatedAt: null,
    });
    const user = userEvent.setup();
    render(<UserSettingsModal onClose={vi.fn()} />);
    await user.click(await screen.findByTestId('settings-delete-open'));

    const input = screen.getByTestId('settings-delete-password');
    expect(input.className).toBe('field pr-10');
    expect(input).toHaveAttribute('type', 'password');
  });

  it('register: the form step, with and without the error state', async () => {
    const user = userEvent.setup();
    const { container } = renderAuth(<RegisterPage onBackToLogin={vi.fn()} />);

    const inputs = textInputs(container);
    expect(inputs.map((i) => i.id)).toEqual(['reg-email', 'reg-username']);
    inputs.forEach(expectOnlyTheSharedFieldStyle);
    inputs.forEach((i) => expect(i.className).not.toContain('field-error'));

    // An invalid username swaps the border via .field-error, which is added BESIDE .field.
    await user.type(screen.getByTestId('reg-username'), 'a');
    expect(screen.getByTestId('reg-username').className.split(/\s+/)).toEqual(
      expect.arrayContaining(['field', 'field-error']),
    );
    expect(screen.getByTestId('reg-email').className).not.toContain('field-error');
  });

  it('register: the waitlist step', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue({ response: { status: 403, data: {} } });
    const { container } = renderAuth(<RegisterPage onBackToLogin={vi.fn()} />);

    await user.type(screen.getByTestId('reg-username'), 'newuser');
    await user.type(screen.getByTestId('reg-email'), 'new@example.com');
    await user.click(screen.getByTestId('reg-terms-accepted'));
    await user.click(screen.getByTestId('reg-submit'));

    await screen.findByTestId('waitlist-step');
    const inputs = textInputs(container);
    expect(inputs.map((i) => i.getAttribute('data-testid'))).toEqual(['waitlist-email']);
    inputs.forEach(expectOnlyTheSharedFieldStyle);
  });

  it('register: the set-password step', async () => {
    vi.spyOn(AuthApi, 'verifyEmail').mockResolvedValue(undefined);
    const { container } = renderAuth(<RegisterPage verifyToken="tok" onBackToLogin={vi.fn()} />);

    await screen.findByTestId('set-password-form');
    const inputs = textInputs(container);
    expect(inputs.map((i) => i.id)).toEqual(['sp-password', 'sp-confirm']);
    inputs.forEach(expectOnlyTheSharedFieldStyle);
    // The eye needs room; that is the one layout utility allowed beside .field.
    inputs.forEach((i) => expect(i.className).toContain('pr-10'));
    await waitFor(() => expect(AuthApi.verifyEmail).toHaveBeenCalledWith('tok'));
  });
});

describe('the .field rules in index.css', () => {
  const rule = (selector) => {
    const start = css.indexOf(`${selector} {`);
    expect(start, `${selector} must be findable`).toBeGreaterThan(-1);
    return css.slice(start, css.indexOf('}', start));
  };

  it('.field is the spec utility list, with 16px text below sm and 14px from sm', () => {
    const block = rule('.field');
    ['w-full', 'bg-plex-bg', 'border', 'border-plex-border', 'rounded-lg', 'px-3', 'py-2', 'text-base',
      'sm:text-sm', 'text-plex-text', 'placeholder:text-plex-text-muted', 'focus:outline-hidden',
      'focus:ring-1', 'focus:ring-plex-gold'].forEach((u) => {
      expect(block, u).toMatch(new RegExp(`(^|\\s)${u.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(\\s|;)`));
    });
    expect(block).not.toMatch(/(^|\s)text-sm(\s|;)/);
  });

  it('.field-error swaps the border to the error token and comes AFTER .field', () => {
    expect(rule('.field-error')).toMatch(/border-plex-error/);
    expect(css.indexOf('.field-error {')).toBeGreaterThan(css.indexOf('.field {'));
  });
});

describe('the auth screens use status tokens, not raw colours', () => {
  const dir = here('../components/auth');
  const sources = [
    '../components/LoginPage.jsx',
    '../components/RegisterPage.jsx',
    '../components/ChangePasswordPage.jsx',
    ...readdirSync(dir).filter((f) => f.endsWith('.jsx')).map((f) => `../components/auth/${f}`),
  ];

  it.each(sources)('%s names no raw red, green or amber utility and no muted hint ink', (file) => {
    const text = read(file);
    expect(text).not.toMatch(/\b(?:text|border|bg)-(?:red|green|amber)-\d{3}\b/);
    if (file.endsWith('/PostcodeCapture.jsx')) {
      // The one deliberate exception (login-registration plan §2 #6): the postcode printed beneath
      // the place name in the confirmation row is mono 11px in muted ink, as the spec draws it. It is
      // a value, not helper text; every hint in the file is secondary ink.
      expect(text.match(/text-plex-text-muted/g)).toHaveLength(1);
      expect(text).toMatch(/font-mono text-\[11px\] tracking-\[0\.06em\] text-plex-text-muted/);
    } else {
      expect(text).not.toMatch(/text-plex-text-muted/);
    }
    expect(text).not.toMatch(/placeholder-plex/);
  });

  it('the set of scanned sources includes the shared components and the forgot and reset pages', () => {
    expect(sources.filter((s) => s.includes('/auth/')).sort()).toEqual([
      '../components/auth/AuthHeading.jsx',
      '../components/auth/AuthPages.jsx',
      '../components/auth/AuthShell.jsx',
      '../components/auth/ForgotPasswordPage.jsx',
      '../components/auth/PasswordChecklist.jsx',
      '../components/auth/PasswordInput.jsx',
      '../components/auth/PostcodeCapture.jsx',
      '../components/auth/PostcodePromptOverlay.jsx',
      '../components/auth/ProTag.jsx',
      '../components/auth/ResetPasswordPage.jsx',
    ]);
  });
});

describe('the Settings dialog uses status tokens, not raw colours', () => {
  const text = read('../components/UserSettingsModal.jsx');
  // The role badges keep their own palette on purpose (README task 13: "leave the role badges"),
  // so the one const that holds them is cut out before the scan rather than the scan loosened.
  const withoutBadges = text.replace(/const ROLE_LABELS = \{[\s\S]*?\n\};/, '');

  it('the badge const is still the only place a raw red, green, amber or sky utility appears', () => {
    expect(text).toMatch(/(?:text|border|bg)-(?:red|amber|sky)-\d{3}/);
    expect(withoutBadges).not.toMatch(/\b(?:text|border|bg)-(?:red|green|amber|sky|emerald)-\d{2,3}\b/);
  });

  it('names the error and ok tokens where the raw colours were', () => {
    // At least the five error lines and two dots R4c converted; a later section may add more.
    expect(withoutBadges.match(/text-plex-error/g).length).toBeGreaterThanOrEqual(5);
    expect(withoutBadges.match(/bg-plex-ok/g).length).toBeGreaterThanOrEqual(2);
  });

  it('draws the danger zone\'s error line from the error token and its Delete button from .btn-danger', () => {
    // R5b: the 403/409/429 line and the Delete button, beside the five lines R4c converted.
    expect(withoutBadges).toMatch(/className="text-sm text-plex-error -mt-1"/);
    expect(withoutBadges).toMatch(/className=\{`btn-danger /);
  });

  it('carries no per-file outline utility: .field owns the focus ring', () => {
    expect(withoutBadges).not.toMatch(/outline-(none|hidden)/);
  });
});
