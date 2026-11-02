### Changed — Anthropic Java SDK 3.3.0

The Anthropic Java SDK moves from 2.71.0 to 3.3.0 (API version 2026-10-21). The Ask engine's
builder call is renamed to `addUserMessageOfContentBlockParams`, and the SDK now sends the API
key as `Authorization: Bearer <key>` instead of `x-api-key`; production keeps the SDK default.
