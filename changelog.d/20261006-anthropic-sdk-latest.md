### Changed — Anthropic SDK 2.62.0 → 2.68.0: the HTTP/1.1 client is gone

`AppConfig.anthropicClient` is now built with the SDK's own `AnthropicOkHttpClient.builder()` instead of
a hand-assembled, HTTP/1.1-only OkHttp client. That client existed because, on Java 21, OkHttp's HTTP/2
frame writer pinned virtual threads under `synchronized` and deadlocked batches of 200+; it is what held
the SDK at 2.62.0 (2.63.0 removed the constructor it needed) and what the Dependabot ignore rule for
`com.anthropic:anthropic-java` was guarding. The runtime is Java 25 now (JEP 491), so all three go: the
pin, the HTTP/1.1 forcing and the ignore rule. The SDK's default protocols (HTTP/2 with an HTTP/1.1
fallback) are back.

The pool sizing carries over (`maxIdleConnections` 10, `keepAliveDuration` 2 minutes). The old client's
OkHttp timeouts (10 s connect/read/write, 90 s call) are not carried over, because none of them was ever in
force: the SDK overwrites all four on a per-request client from the request's own `Timeout`, identically in
2.62.0 and 2.68.0, so every call ran, and still runs, with connect 60 s, read and write and call 600 s unless
it passes its own `RequestOptions`. That 10-minute ceiling (a silent connection can hold a thread about 30
minutes across the SDK's two retries) is unchanged; setting a short client-wide timeout would also cut off
the batch-results downloads. The Ask door still makes one HTTP attempt per call (`maxRetries(0)` on its
derived client), now pinned against the real SDK rather than a mock.

SDK 2.68.0 made `Message.diagnostics` a required field, which only the tests that build a `Message` by
hand needed to learn about. `WireMockAnthropicClientTestConfiguration` now builds from the production
builder (`AppConfig.anthropicClientBuilder`), so the integration tests' client can no longer drift from
production's options. The explicit `okhttp` dependency stays, runtime-scoped and pinned to 4.12.0.

`AnthropicWireMockFixtureContractTest` no longer pins an SDK quirk that 2.68.0 removed: on 2.62.0
`MessageBatch.ProcessingStatus.of(String)` always built a new instance (so `==` never matched an ended batch,
the `f49959dd` bug), but 2.68.0 returns the interned constant for the three known strings, so its
`isNotSameAs` assertion became false. The test keeps the lesson instead: a known status equals its constant,
and an unknown one (`"ended "`, `"finalised"`) deserialises without throwing and equals none of them, which is
why `BatchPollingService` must keep comparing with `equals`. The production comparisons already do.
