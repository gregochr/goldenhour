### Changed — Claude model ids come from the SDK's typed constants

`EvaluationModel` no longer types its model ids by hand: each is derived from the SDK's `Model`
constant (`CLAUDE_HAIKU_4_5_20251001`, `CLAUDE_SONNET_4_6`, `CLAUDE_SONNET_5_5`, `CLAUDE_OPUS_4_6`), so a
typo is a compile error and the SDK's own deprecation markers are visible at the point of use. The
strings sent to Anthropic are unchanged, and a test pins every one to the literal it replaced. A second
test fails the build if any id the app sends is marked `@Deprecated` in the SDK, so the next retirement
(Claude Sonnet 4.5 was marked in SDK 2.68) is caught by CI rather than by an outage.

The unread `anthropic.model` key (which still named the now-deprecated `claude-sonnet-4-5-20250929` in
prod and example config) is removed, along with `AnthropicProperties.model`; the model is chosen per run
type in `model_selection`/`EvaluationModel`. A leftover `anthropic.model` line in a hand-edited config is
ignored, not an error.
