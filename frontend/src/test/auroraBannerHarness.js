import { act, fireEvent, screen } from '@testing-library/react';

/**
 * Opens a collapsed desktop aurora banner (a MODERATE alert at 640px and up starts as the strip) by
 * pressing its toggle, so a test about the card can reach it. A no-op when the banner is already
 * the card (STRONG) or has no toggle (the phone strip), so it is safe to call unconditionally.
 */
export function expandBanner() {
  const toggle = screen.queryByTestId('aurora-banner-toggle');
  if (toggle && toggle.getAttribute('aria-expanded') === 'false') fireEvent.click(toggle);
}

/** {@link expandBanner} inside an awaited `act`, so the lazy thumbnail chunk it mounts settles too. */
export async function expandBannerAsync() {
  await act(async () => { expandBanner(); });
}
