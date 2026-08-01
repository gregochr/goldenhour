import React from 'react';
import PropTypes from 'prop-types';

/**
 * The PhotoCast masthead — three stacked lines (kicker, wordmark, tagline) behind a decorative
 * film-perforation spine. Replaces the former `/logo.png` + extrabold-sans wordmark, which
 * belonged to no part of the Kodachrome Field Guide system the rest of the app uses.
 *
 * <p>Brand presence comes from texture rather than a badge, so there is no image here at all. That
 * leaves `public/logo.png` referenced by nothing in the app — note it is not load-bearing for the
 * icons: `favicon.png` is a byte-identical copy under its own name and the `pwa-*`/`apple-touch`
 * icons are separate derived files, so the bitmap survives in those roles whatever happens to this
 * one. The spine is the whole visual device, which is why the lockup is always flush left: centred,
 * the perforation reads as a stray rule rather than a film edge.
 *
 * <p>The wordmark is the page {@code <h1>} and carries the entire accessible name; the kicker and
 * tagline are plain paragraphs and the spine is {@code aria-hidden}, so nothing decorative leaks
 * into the heading. The wordmark text must stay exactly {@code PhotoCast} and must stay inside a
 * heading — {@code src/test/e2e/forecast.spec.js} locates the signed-in app by it.
 *
 * @param {object} props
 * @param {'header'|'auth'} [props.variant] - `header` (40px wordmark) for the signed-in app
 *   masthead; `auth` (34px) for the sign-in, register and change-password screens.
 */
export default function BrandLockup({ variant = 'header' }) {
  const isHeader = variant === 'header';
  return (
    <div data-testid="brand-lockup" data-variant={variant} className="relative pl-[26px]">
      <span
        aria-hidden="true"
        data-testid="brand-lockup-spine"
        className="absolute left-0 top-0 bottom-0 w-[11px] border-r border-plex-border"
        style={{
          background:
            'repeating-linear-gradient(180deg, var(--color-plex-border-light) 0 7px, transparent 7px 15px)',
        }}
      />
      <p className="font-mono text-[10px] uppercase tracking-[0.2em] text-plex-coral">
        Field guide to light
      </p>
      <h1
        className={`font-serif font-semibold text-plex-text leading-none tracking-[-0.025em] mt-[7px] ${
          isHeader ? 'text-[40px]' : 'text-[34px]'
        }`}
      >
        PhotoCast
      </h1>
      <p className="font-serif italic text-base text-plex-text-secondary mt-[7px]">
        Golden hour, forecast and ranked by AI
      </p>
    </div>
  );
}

BrandLockup.propTypes = {
  variant: PropTypes.oneOf(['header', 'auth']),
};
