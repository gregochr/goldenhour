/**
 * The class lists the admin Users screen's form controls share (the edit and add panels). Defined once and
 * applied at several sites, so the scan in `formFieldFocusRules.test.js` reads them here.
 */

/** A text input. */
export const inputClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-hidden focus:ring-1 focus:ring-plex-gold max-[639px]:min-h-11';

/** A field label. */
export const labelClass = 'block text-xs text-plex-text-secondary mb-1';

/** A select. */
export const selectClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text focus:outline-hidden focus:ring-1 focus:ring-plex-gold max-[639px]:min-h-11';

/** A text button that reads as a link (Edit, Clear filters, Cancel). */
export const textButtonClass = 'text-sm text-plex-text-secondary hover:text-plex-text rounded-sm px-1 py-0.5 focus-visible:outline-2 focus-visible:outline-plex-gold cursor-pointer max-[639px]:min-h-11';

/** The mono uppercase label that heads a panel section. */
export const sectionLabelClass = 'font-mono text-[10px] uppercase tracking-[0.18em] text-plex-text-muted';
