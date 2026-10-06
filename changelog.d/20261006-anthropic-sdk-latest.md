### Changed — Anthropic SDK 2.62.0 → 2.68.0: the HTTP/1.1 client is gone

`AppConfig.anthropicClient` is now built with the SDK's own `AnthropicOkHttpClient.builder()` instead of
a hand-assembled, HTTP/1.1-only OkHttp client. That client existed because, on Java 21, OkHttp's HTTP/2
frame writer pinned virtual threads under `synchronized` and deadlocked batches of 200+; it is what held
the SDK at 2.62.0 (2.63.0 removed the constructor it needed) and what the Dependabot ignore rule for
`com.anthropic:anthropic-java` was guarding. The runtime is Java 25 now (JEP 491), so all three go: the
pin, the HTTP/1.1 forcing and the ignore rule. The SDK's default protocols (HTTP/2 with an HTTP/1.1
fallback) are back.

The pool sizing carries over (`maxIdleConnections` 10, `keepAliveDuration` 2 minutes). The old client's
"90 second call timeout" is not carried over, on purpose: the SDK overwrote it with each request's own
timeout, so it never applied to an SDK call, and setting one on the SDK builder would cut off the batch
results downloads. The Ask door still makes one HTTP attempt per call (`maxRetries(0)` on its derived
client), now pinned against the real SDK rather than a mock.

SDK 2.68.0 made `Message.diagnostics` a required field, which only the tests that build a `Message` by
hand needed to learn about. `WireMockAnthropicClientTestConfiguration` now builds from the production
builder (`AppConfig.anthropicClientBuilder`), so the integration tests' client can no longer drift from
production's options. The explicit `okhttp` dependency stays, runtime-scoped and pinned to 4.12.0.
