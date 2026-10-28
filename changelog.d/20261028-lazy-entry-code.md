### Changed — sign-in pages, settings and Ask UI load on demand (performance plan P2d)

The sign-in, register, forgot, reset and change-password pages are now one lazy route chunk, the settings dialog (with the sub-region picker) is fetched the first time it opens, and Ask's dock and sheet are fetched the first time they open. The first-load closure falls from 236.21 KB to 218.50 KB gzip. Ask's dock and sheet identifiers moved to `ask/askIds.js` so the shell reads them without importing the components. No behaviour change.
