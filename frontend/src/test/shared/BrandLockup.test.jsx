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

  it('renders the kicker and the tagline', () => {
    render(<BrandLockup />);
    expect(screen.getByText('Field guide to light')).toBeInTheDocument();
    expect(screen.getByText('Golden hour, forecast and ranked by AI')).toBeInTheDocument();
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

  it.each([
    ['header', 'text-[40px]', 'text-[34px]'],
    ['auth', 'text-[34px]', 'text-[40px]'],
  ])('sizes the %s variant wordmark', (variant, expected, notExpected) => {
    render(<BrandLockup variant={variant} />);
    const wordmark = screen.getByRole('heading', { level: 1 });
    expect(wordmark.className).toContain(expected);
    expect(wordmark.className).not.toContain(notExpected);
  });

  it('defaults to the header variant', () => {
    render(<BrandLockup />);
    expect(screen.getByTestId('brand-lockup')).toHaveAttribute('data-variant', 'header');
    expect(screen.getByRole('heading', { level: 1 }).className).toContain('text-[40px]');
  });
});
