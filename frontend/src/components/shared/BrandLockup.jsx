import React from 'react';
import PropTypes from 'prop-types';

/**
 * Film gauges, keyed by how tall the lockup they run beside is.
 *
 * <p><b>The spine is a repeating pattern, so it needs enough repeats to read as one.</b> `header`
 * (7px perforation on a 15px pitch) runs beside ~90px of stacked prose and shows six. Dropping the
 * prose takes the lockup to the wordmark's own 20px, where that same pitch fits one perforation and
 * a severed second — a stray dash, not a film edge, which is the exact failure the component's
 * "always flush left" rule exists to avoid.
 *
 * <p>So `compact` changes the **gauge**, not the height: a 3px perforation on a 5px pitch fits four
 * in the 20px available. The alternative — a min-height on the lockup — buys the repeats by
 * spending the height budget the variant exists to protect (a 44px floor takes the masthead from
 * 59px to 74px, against a design that asks for ~50px).
 *
 * <p>Two numbers are load-bearing rather than tuned by eye. **20 is exactly 4 × 5**, so the pattern
 * ends on a gap instead of a clipped perforation — the defect being fixed, reintroduced at a
 * smaller size if the pitch does not divide the height. And **3px is whole at 1× as well as 2×**;
 * the duty cycle rises from the header's 0.47 to 0.60 because holding 0.47 would mean a 2.33px
 * perforation, which antialiases to mush on a non-retina display.
 */
const SPINE_GAUGE = {
  header: 'repeating-linear-gradient(180deg, var(--color-plex-border-light) 0 7px, transparent 7px 15px)',
  compact: 'repeating-linear-gradient(180deg, var(--color-plex-border-light) 0 3px, transparent 3px 5px)',
};

/**
 * Kicker copy, by variant — the one line of the lockup that changes with where it is read.
 *
 * <p>`auth` names the audience because a stranger on the sign-in screen has no landing-page
 * context; it is verbatim the landing page's own eyebrow. Everywhere else the reader has already
 * signed in and knows who the app is for, so the kicker says what the app is instead.
 */
const KICKER = {
  auth: 'For landscape photographers',
  default: 'Field guide to light',
};

/**
 * The PhotoCast masthead — stacked lines (kicker, wordmark, tagline) behind a decorative
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
 * <p><b>The compact variant drops the kicker and tagline, never the spine.</b> The spine is the
 * whole visual device — without it the wordmark is just a serif word, and the lockup stops being a
 * lockup. What goes instead is the prose, because the window-first masthead is ~50px tall against
 * this one's ~90px and that budget is the entire point of the redesign. Keeping the spine means
 * keeping it *legible* at that height, which is a change of gauge rather than a change of size —
 * see {@link SPINE_GAUGE}. The wordmark stays an
 * {@code <h1>} at every size: {@code src/test/e2e/forecast.spec.js:46} finds the signed-in app with
 * {@code getByRole('heading', {name: /PhotoCast/})}, so a variant that demoted it would break that
 * suite the moment the window-first layout became the default.
 *
 * <p><b>`masthead` is what `compact` became once the slim bar had to carry brand presence again.</b>
 * Compacting the signed-in header to one line cost the wordmark two thirds of its size, the coral
 * kicker and the whole spine pattern, and the result read as chrome rather than as a masthead. This
 * variant buys the presence back without buying back the height: the wordmark returns to 28px (25
 * on a tablet, 21 on a phone, which is the type floor) and the kicker returns, but the italic
 * tagline does not — it is the line that costs a whole row and says least to someone already signed
 * in. Because the lockup is ~50px again, the spine goes back to the header gauge; `compact` keeps
 * its own tighter one and stays in use where the prose genuinely cannot fit
 * ({@code PlanLayoutErrorBoundary}).
 *
 * @param {object} props
 * @param {'header'|'auth'|'compact'|'masthead'} [props.variant] - `header` (40px wordmark) for the
 *   v1 signed-in app masthead; `auth` (34px) for the sign-in, register and change-password screens;
 *   `compact` (20px, no kicker or tagline) for a lockup with no room for prose; `masthead`
 *   (21/25/28px, kicker, no tagline) for the window-first band.
 */
export default function BrandLockup({ variant = 'header' }) {
  const isHeader = variant === 'header';
  const isCompact = variant === 'compact';
  const isMasthead = variant === 'masthead';
  return (
    <div
      data-testid="brand-lockup"
      data-variant={variant}
      className={isMasthead
        ? 'relative pl-[22px] sm:pl-[25px] flex flex-col items-start'
        : 'relative pl-[26px]'}
    >
      <span
        aria-hidden="true"
        data-testid="brand-lockup-spine"
        className="absolute left-0 top-0 bottom-0 w-[11px] border-r border-plex-border"
        style={{ background: isCompact ? SPINE_GAUGE.compact : SPINE_GAUGE.header }}
      />
      {/* Above the wordmark everywhere except the window-first band, where it sits under it: there
          the wordmark is the top edge of the whole screen, and a 9.5px line above it reads as a
          stray label rather than as part of the lockup. */}
      {!isCompact && !isMasthead && (
        <p className="font-mono text-[10px] uppercase tracking-[0.2em] text-plex-coral">
          {variant === 'auth' ? KICKER.auth : KICKER.default}
        </p>
      )}
      <h1
        className={`font-serif font-semibold text-plex-text leading-none ${
          isMasthead
            ? 'tracking-[-0.022em] text-[21px] sm:text-[25px] lg:text-[28px]'
            : 'tracking-[-0.025em]'
        } ${isCompact ? 'text-[20px]' : ''} ${!isCompact && !isMasthead ? 'mt-[7px]' : ''} ${
          isHeader ? 'text-[40px]' : ''
        } ${variant === 'auth' ? 'text-[34px]' : ''}`}
      >
        PhotoCast
      </h1>
      {isMasthead && (
        <p className="font-mono uppercase text-plex-coral text-[8.5px] tracking-[0.16em] mt-1 sm:text-[9.5px] sm:tracking-[0.2em] sm:mt-1.5">
          {KICKER.default}
        </p>
      )}
      {!isCompact && !isMasthead && (
        <p className="font-serif italic text-base text-plex-text-secondary mt-[7px]">
          Golden hour, forecast and ranked by AI
        </p>
      )}
    </div>
  );
}

BrandLockup.propTypes = {
  variant: PropTypes.oneOf(['header', 'auth', 'compact', 'masthead']),
};
