### Accessibility — muted text meets 4.5:1 contrast on the darkest panels

`--color-plex-text-muted` was bone at 0.42 alpha, which measured 3.5:1 on `plex-bg` and `plex-surface`, so the 10-11 px Operations-tab headings failed axe `color-contrast` (WCAG 1.4.3). The token is now bone at 0.53 alpha (same hue): 4.97:1 on `plex-bg`, 4.84:1 on `plex-surface`, 4.65:1 on `plex-surface-light`. It stays well below `plex-text-secondary` (0.66), so the hierarchy holds. The Map tab's panel override is unchanged.
