### Tests — the Anthropic WireMock fixture double no longer corrupts a backslash

`AnthropicWireMockFixtures` escaped only the double quote when it interpolated a summary or an
error message, so a backslash silently became a tab or failed the SDK parse, and its `%d` format
was locale-sensitive. Every interpolated string now goes through one `jsonEscape` helper (backslash
and control characters included) while the envelope stays hand-built, and the format is pinned to
`Locale.ROOT`. The contract test that pinned the old behaviour as a known gap is now a positive
round-trip guard, and the reflective sweep feeds backslashes through every factory.
