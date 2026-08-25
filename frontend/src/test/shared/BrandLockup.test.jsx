import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import BrandLockup from '../../components/shared/BrandLockup.jsx';

describe('BrandLockup', () => {
  it('renders the wordmark as the page h1 with exactly "PhotoCast" as its accessible name', () => {
    // Pinned deliberately: src/test/e2e/forecast.spec.js finds the signed-in app by
    // getByRole('heading', { name: /PhotoCast/ }). Demoting the wordmark out of a heading, or
    // letting the kicker/tagline/spine leak into its accessible name, breaks that e2e — and the
    // e2e does not run in CI, so this is the assertion that would actually catch it.
    render(<BrandLockup />);
    expect(screen.getByRole('heading', { level: 1, name: 'PhotoCast' })).toBeInTheDocument();
  });

  it('renders the wordmark exactly once', () => {
    // A second (sr-only or aria-hidden) copy of the word would turn LoginPage.test.jsx's
    // getByText('PhotoCast') into "Found multiple elements" — getByText ignores only script/style.
    render(<BrandLockup />);
    expect(screen.getAllByText('PhotoCast')).toHaveLength(1);
  });

  it('compact keeps the wordmark a heading and the spine, and drops only the prose', () => {
    // The window-first masthead is ~50px against the header variant's ~90px, and that budget is
    // the point of the redesign — but the spine is the whole visual device, so what goes is the
    // prose. The heading must survive at every size or the e2e locator above breaks.
    render(<BrandLockup variant="compact" />);

    expect(screen.getByRole('heading', { level: 1, name: 'PhotoCast' })).toBeInTheDocument();
    expect(screen.getByTestId('brand-lockup-spine')).toBeInTheDocument();
    expect(screen.queryByText('Field guide to light')).not.toBeInTheDocument();
    expect(screen.queryByText(/Golden hour, forecast/)).not.toBeInTheDocument();
  });

  /**
   * The compact spine's gauge, and why it is asserted as arithmetic rather than as a literal.
   *
   * The defect these pin: `compact` shipped at P4a carrying the header's 15px pitch beside a 20px
   * wordmark, which fits one perforation and 5px of a severed second — visibly a stray dash, and
   * the exact thing the component's "always flush left" rule exists to prevent. The fix changes the
   * gauge, not the height, so what has to hold is the *relationship* between the two numbers. A
   * literal assertion would pass a later bump of the wordmark to 22px, which is the realistic way
   * this breaks again.
   */
  const pitchOf = (spine) => Number(/transparent [\d.]+px ([\d.]+)px\)/.exec(spine.style.background)[1]);

  it('fits at least three whole perforations in the compact lockup, ending on a gap', () => {
    render(<BrandLockup variant="compact" />);
    const pitch = pitchOf(screen.getByTestId('brand-lockup-spine'));
    // The Tailwind arbitrary value is the only place the compact lockup's height is stated — the
    // wordmark is `leading-none`, so its font size IS the lockup height. Read it from there rather
    // than restating it, so the two cannot drift apart silently.
    const heightPx = Number(/text-\[(\d+)px\]/.exec(screen.getByRole('heading', { level: 1 }).className)[1]);

    expect(heightPx % pitch).toBe(0); // ends on a gap, never on a clipped perforation
    expect(heightPx / pitch).toBeGreaterThanOrEqual(3); // enough repeats to read as a pattern
  });

  it('gives compact a tighter gauge than the default (masthead) gauge, rather than the same one at a smaller size', () => {
    // Both variants sharing a pitch is precisely the shipped bug. Asserted as an inequality because
    // the wider gauge's own value is not this test's business — only that compact no longer inherits it.
    const { rerender } = render(<BrandLockup variant="compact" />);
    const compactPitch = pitchOf(screen.getByTestId('brand-lockup-spine'));
    rerender(<BrandLockup />);
    const defaultPitch = pitchOf(screen.getByTestId('brand-lockup-spine'));

    expect(compactPitch).toBeLessThan(defaultPitch);
  });

  it('keeps the same non-compact gauge on the auth variant as everywhere else, which has the prose to carry it', () => {
    // `auth` drops nothing — it is the wide-gauge lockup at 34px — so it must not pick up the
    // compact gauge by being lumped in with "not masthead".
    const { rerender } = render(<BrandLockup variant="auth" />);
    const authPitch = pitchOf(screen.getByTestId('brand-lockup-spine'));
    rerender(<BrandLockup />);

    expect(authPitch).toBe(pitchOf(screen.getByTestId('brand-lockup-spine')));
  });

  it('auth keeps both the kicker and the tagline — drops nothing', () => {
    // The component's own docblock used to claim the tagline was header-only; the JSX says
    // otherwise — it is gated on `!isCompact && !isMasthead`, which `auth` satisfies. This pins the
    // real gating against a future "cleanup" that trusts the docblock again.
    render(<BrandLockup variant="auth" />);
    expect(screen.getByText('For landscape photographers')).toBeInTheDocument();
    expect(screen.getByText('Golden hour, forecast and ranked by AI')).toBeInTheDocument();
  });

  it('names the audience on the auth variant, and only there', () => {
    // A stranger on the sign-in screen has no landing-page context, so this is the one surface
    // where the kicker says who the app is for. Verbatim the landing page's own eyebrow. The
    // signed-in variants keep 'Field guide to light' — the reader there already knows.
    const { rerender } = render(<BrandLockup variant="auth" />);
    expect(screen.getByText('For landscape photographers')).toBeInTheDocument();
    expect(screen.queryByText('Field guide to light')).not.toBeInTheDocument();

    rerender(<BrandLockup />);
    expect(screen.getByText('Field guide to light')).toBeInTheDocument();
    expect(screen.queryByText('For landscape photographers')).not.toBeInTheDocument();
  });

  describe('masthead', () => {
    it('buys back the kicker and the wordmark, but not the tagline', () => {
      // The whole point of the variant: presence without height. The tagline is the line that
      // costs a full row and says least to someone already signed in, so it stays gone.
      render(<BrandLockup variant="masthead" />);

      expect(screen.getByRole('heading', { level: 1, name: 'PhotoCast' })).toBeInTheDocument();
      expect(screen.getByText('Field guide to light')).toBeInTheDocument();
      expect(screen.queryByText(/Golden hour, forecast/)).not.toBeInTheDocument();
    });

    it('carries the kicker on coral, like every other variant that has one', () => {
      render(<BrandLockup variant="masthead" />);
      expect(screen.getByText('Field guide to light').className).toContain('text-plex-coral');
    });

    it('takes the same non-compact gauge as auth, not the compact one', () => {
      // The lockup is ~50px tall again — two lines, not one — so the tight gauge that exists for a
      // 20px lockup would show four perforations in a space built for six.
      const { rerender } = render(<BrandLockup variant="masthead" />);
      const mastheadPitch = pitchOf(screen.getByTestId('brand-lockup-spine'));
      rerender(<BrandLockup variant="auth" />);

      expect(mastheadPitch).toBe(pitchOf(screen.getByTestId('brand-lockup-spine')));
    });

    it('scales the wordmark across the three widths and never below the 21px floor', () => {
      // jsdom resolves no media query, so the class list is the only place these three sizes are
      // observable. Asserted as a set because dropping any one of them silently pins the wordmark
      // to whichever width happens to remain.
      render(<BrandLockup variant="masthead" />);
      const wordmark = screen.getByRole('heading', { level: 1 });

      expect(wordmark.className).toContain('text-[21px]');
      expect(wordmark.className).toContain('sm:text-[25px]');
      expect(wordmark.className).toContain('lg:text-[28px]');
      expect(wordmark.className).not.toContain('text-[20px]');

      const sizes = [...wordmark.className.matchAll(/text-\[(\d+)px\]/g)].map((m) => Number(m[1]));
      expect(Math.min(...sizes)).toBeGreaterThanOrEqual(21);
    });

    it('keeps the kicker above the 8.5px floor at phone width', () => {
      render(<BrandLockup variant="masthead" />);
      const kicker = screen.getByText('Field guide to light');
      const sizes = [...kicker.className.matchAll(/text-\[([\d.]+)px\]/g)].map((m) => Number(m[1]));

      expect(sizes.length).toBeGreaterThan(0);
      expect(Math.min(...sizes)).toBeGreaterThanOrEqual(8.5);
    });

    it('puts the kicker below the wordmark, not above it', () => {
      // At the top edge of the screen a 9.5px line above the wordmark reads as a stray label
      // rather than as part of the lockup — the reason this variant inverts the stack.
      render(<BrandLockup variant="masthead" />);
      const wordmark = screen.getByRole('heading', { level: 1 });
      const kicker = screen.getByText('Field guide to light');

      expect(wordmark.compareDocumentPosition(kicker) & Node.DOCUMENT_POSITION_FOLLOWING)
        .toBeTruthy();
    });
  });

  it('drops the logo image — the lockup is type and texture, no bitmap', () => {
    const { container } = render(<BrandLockup />);
    expect(container.querySelector('img')).toBeNull();
  });

  it('hides the perforation spine from assistive technology', () => {
    render(<BrandLockup />);
    expect(screen.getByTestId('brand-lockup-spine')).toHaveAttribute('aria-hidden', 'true');
  });

  it('carries the kicker on the coral accent, not on ink', () => {
    // The one coral in the system. If this reverts to text-plex-gold it renders bone — identical
    // to the wordmark — and the three lines flatten into one tone with nothing to catch the eye.
    render(<BrandLockup />);
    expect(screen.getByText('Field guide to light').className).toContain('text-plex-coral');
  });

  it('sizes the auth variant wordmark', () => {
    render(<BrandLockup variant="auth" />);
    const wordmark = screen.getByRole('heading', { level: 1 });
    expect(wordmark.className).toContain('text-[34px]');
    expect(wordmark.className).not.toContain('text-[21px]');
  });

  it('defaults to the masthead variant', () => {
    render(<BrandLockup />);
    expect(screen.getByTestId('brand-lockup')).toHaveAttribute('data-variant', 'masthead');
    expect(screen.getByRole('heading', { level: 1 }).className).toContain('text-[21px]');
  });
});
