### Changed — Anthropic Java SDK 2.60.0 → 2.62.0, and why not 2.68.0

Dependabot's bump to 2.68.0 (#1012) does not compile: 2.63.0 removed the
`OkHttpClient(okhttp3.OkHttpClient, Backend)` constructor `AppConfig.anthropicClient` uses to
force HTTP/1.1, which exists because OkHttp's HTTP/2 frame writer pins virtual threads under
`synchronized` on Java 21 and deadlocked batches of 200+. The SDK's own builder offers no protocol
control, so taking the bump would silently reintroduce HTTP/2. The version moves to 2.62.0, the
last release with the constructor; the pin and its two exits (JDK 24+ via JEP 491, or an in-house
`com.anthropic.core.http.HttpClient`) are recorded in the pom, the AppConfig javadoc and
CLAUDE.md, and Dependabot now ignores `>= 2.63.0` for this artifact.
