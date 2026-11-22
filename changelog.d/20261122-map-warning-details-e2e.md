### Test — Map warning Details E2E

The seeded E2E fixture now carries one Met Office weather warning (a Yellow rain warning over the Lake District's six
places, valid for the T+1 sunset window only), written through the same repository the poller uses, with the smoke script
giving the warnings feature a fake key, a dead loopback base URL and a 24-hour staleness bound. A new Playwright case in
`map-glance.spec.js` opens the Map on that window and drives the chip's Details toggle (expand, collapse, the panel's close
button and Escape). Test-only: no production code changes.
