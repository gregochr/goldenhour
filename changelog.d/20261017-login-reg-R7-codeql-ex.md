### Fixed — the postcode-unavailable handler no longer ignores its exception

`GlobalExceptionHandler`'s 502 handler for `PostcodeLookupUnavailableException` took its exception and used nothing from it
(CodeQL alert 233, "useless parameter"). It now logs the exception's fixed message at DEBUG through the class's logger; the
response is unchanged — a 502 with the fixed "Postcode lookup is unavailable right now" sentence — and no upstream text reaches
the log or the body.
