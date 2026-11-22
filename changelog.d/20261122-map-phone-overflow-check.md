### Test — Map phone overflow check

A new smoke spec, `map-phone-overflow.spec.js`, checks the Map's phone states (default, Best bet reason, Regions, Filters, windows, List, callout) at 360, 375 and 390 wide under Chromium mobile emulation. It asserts no element outside the viewport, no inner horizontal scroller and no clipped overflow, measured on boxes because the Map root and sheets hide overflow from the document's own scroll width. No source changed: nothing overflowed at those widths. (At 320 the List sheet's segmented control overflows by about 3px; that width stays report-only.)
