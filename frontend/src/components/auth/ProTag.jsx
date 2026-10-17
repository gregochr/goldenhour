import React from 'react';

/**
 * The spec's small `PRO` tag (login-registration plan §1 #25): mono 10px, 0.12em tracking, a 1px
 * light border, a 3px radius. Not {@code ProPill}, which is sentence-case sans on a filled chip and
 * is shared by Ask and the rating lock — the geometry cannot be reached from it without changing
 * both. The text is "Pro" and CSS upper-cases it, so the accessible name is the word, not letters.
 */
export default function ProTag() {
  return (
    <span
      className="font-mono text-[10px] uppercase tracking-[0.12em] text-plex-text border border-plex-border-light rounded-[3px] px-[5px] py-px"
      data-testid="pro-tag"
    >
      Pro
    </span>
  );
}
