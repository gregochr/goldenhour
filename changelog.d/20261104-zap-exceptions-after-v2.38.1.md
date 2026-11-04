### Changed — ZAP exceptions trimmed after v2.38.1

The weekly ZAP gate no longer tolerates the backend error-disclosure (`90022`) and backend
cross-origin-resource-policy (`90004`) alerts, which stopped firing in v2.38.1. The frontend `90004`
exception is now keyed to its `90004-2` sub-alert (Cross-Origin-Embedder-Policy, deliberately not
set) and is permanent, so any other `90004` sub-alert fails the gate again. A unit test covers the
sub-alert matching.
