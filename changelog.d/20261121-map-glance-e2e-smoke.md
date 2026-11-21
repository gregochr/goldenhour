### Test — Map glance E2E smoke

A new Playwright smoke spec, `map-glance.spec.js`, covers the Glance-to-Map doors on the seeded fixture: a Plan card opens the Map with its own window selected; the Map's List door opens the ranked list, Escape closes it, and a row opens that place's callout (never more than two dialogs deep); and the "Best bet · why" pill opens and closes its reason panel. The warning chips' Details toggle is not covered, because the seeded fixture carries no Met Office warning.
