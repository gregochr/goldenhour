### Fixed — an Ask follow-up keeps the ★ in an earlier answer's summary

When a follow-up question sent the earlier answers back, the client stripped emoji from each summary
using the browser's Unicode tables. Some engines count ★ (U+2605) as a pictograph, so "4★" went back
to the server as "4". The star is now kept on every engine; the server never refused it.
