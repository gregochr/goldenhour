# Test Improvement Standards — PhotoCast Backend

## Philosophy

Tests exist to protect features, not to hit coverage targets. A test that
passes when the feature is broken is worse than no test at all — it creates
false confidence.

This document defines the standards for all test improvement work across
the PhotoCast backend. Every prompt in this series references these
standards. Read this before making any test changes.

---

## The three questions every test must answer

1. **What specific behaviour is being tested?** — One test, one behaviour.
   Not "the briefing service works" but "a GO location with mid-cloud ≥80%
   is demoted to STANDDOWN."

2. **What exact inputs produce what exact outputs?** — Specific values, not
   `any()`. If you are testing a cloud threshold at 80%, use 80%, 79%, and
   81% — not `anyInt()`.

3. **What breaks if this test fails?** — If you can't answer this, the test
   is not protecting a feature.

---

## Code smells to eliminate

### 1. `any()` in `verify()` calls

```java
// BAD — verifies Claude was called but not what it was called with
verify(anthropicClient).createMessage(any());

// GOOD — verifies the prompt contains the expected data
ArgumentCaptor<MessageCreateParams> captor =
    ArgumentCaptor.forClass(MessageCreateParams.class);
verify(anthropicClient).createMessage(captor.capture());
String prompt = captor.getValue().messages().get(0).content().toString();
assertThat(prompt).contains("cloud_cover_mid: 85");
assertThat(prompt).contains("BUILDING trend detected");
```

`any()` in `verify()` tells you a method was called. It does not tell you
it was called correctly. A refactor that changes the prompt content will
pass this test even if the Claude output is now wrong.

### 2. `lenient()` stubs

```java
// BAD — suppressing Mockito's "unnecessary stubbing" warning
lenient().when(openMeteoClient.fetchHourly(any(), any()))
    .thenReturn(mockWeather);

// GOOD — if the stub is needed, the test exercises it.
// If Mockito says it's unnecessary, either:
// (a) delete the stub — the code path doesn't need it
// (b) fix the test — it's not exercising the path you think it is
```

`lenient()` is almost always a sign the test is over-specified or testing
the wrong thing. Remove it and fix the underlying issue.

### 3. `any()` in `when()` stubs where the input is knowable

```java
// BAD — accepts any input, masks incorrect data reaching the mock
when(worldTidesClient.fetchTides(any(), any(), any()))
    .thenReturn(mockTides);

// GOOD — only returns mock data for the expected coordinates
when(worldTidesClient.fetchTides(
    eq(55.0383),   // Bamburgh latitude
    eq(-1.7099),   // Bamburgh longitude
    eq(forecastDate)
)).thenReturn(mockTides);
```

Using `any()` in stubs means the test passes even if the wrong coordinates
are passed to the API client. The test should verify the right data is
being requested, not just that some data is returned.

### 4. Missing boundary tests

```java
// BAD — only tests the happy path
@Test
void triageShouldReturnGoForClearSkies() { ... }

// GOOD — tests boundaries explicitly
@Test
void midCloud80PercentShouldDemoteToStanddown() { ... }

@Test
void midCloud79PercentShouldNotDemoteToStanddown() { ... }

@Test
void midCloud60PercentShouldDemoteToMarginal() { ... }

@Test
void midCloud59PercentShouldNotDemoteToMarginalFromMidCloudCheck() { ... }
```

Every threshold in the triage logic should have a test at the exact
boundary value, one below, and one above.

### 5. `@SpringBootTest` overuse

```java
// BAD — loads entire Spring context for a pure unit test
@SpringBootTest
class WeatherTriageServiceTest { ... }

// GOOD — unit test with manual construction or @ExtendWith(MockitoExtension)
@ExtendWith(MockitoExtension.class)
class WeatherTriageServiceTest { ... }
```

`@SpringBootTest` takes 10-30 seconds to load. A unit test takes
milliseconds. Only use `@SpringBootTest` for integration tests that
genuinely need the full context (database, HTTP, etc.).

---

## What good tests look like for each area

### Triage checks

Each demotion rule gets its own test class or nested class. Each test:
- Sets up exact Open-Meteo values at the specific threshold
- Calls the triage service directly
- Asserts the exact verdict (GO / MARGINAL / STANDDOWN)
- Asserts the `standdownReason` string where applicable

```java
@Nested
class MidCloudBlanketCheck {

    @Test
    void atOrAbove80PercentDemotesToStanddown() {
        var slot = triageWithMidCloud(80);
        assertThat(slot.verdict()).isEqualTo(STANDDOWN);
        assertThat(slot.standdownReason()).isEqualTo("Grey ceiling");
    }

    @Test
    void at79PercentDoesNotTriggerStanddown() {
        var slot = triageWithMidCloud(79);
        assertThat(slot.verdict()).isNotEqualTo(STANDDOWN);
    }

    @Test
    void atOrAbove60PercentDemotesToMarginal() {
        var slot = triageWithMidCloud(60);
        assertThat(slot.verdict()).isEqualTo(MARGINAL);
    }

    @Test
    void at59PercentDoesNotTriggerMarginalDemotion() {
        var slot = triageWithMidCloud(59);
        assertThat(slot.verdict()).isEqualTo(GO);
    }
}
```

### Claude prompt builders

Tests for prompt content use `ArgumentCaptor` to capture the actual
message sent to the Anthropic client and assert specific content:

```java
@Test
void buildingTrendFlagAppearsInPrompt() {
    // Arrange — set up atmospheric data with a building trend
    var data = AtmosphericData.builder()
        .cloudCoverLow(List.of(15, 28, 42))  // building trend
        .build();

    // Act
    service.evaluate(location, data, event);

    // Assert — capture and inspect the prompt
    ArgumentCaptor<MessageCreateParams> captor =
        ArgumentCaptor.forClass(MessageCreateParams.class);
    verify(anthropicClient).createMessage(captor.capture());
    String userMessage = extractUserMessage(captor.getValue());

    assertThat(userMessage).contains("BUILDING");
    assertThat(userMessage).contains("cloud_cover_low");
}
```

### Cache behaviour

```java
@Test
void newTriageRunInvalidatesEvaluationCache() {
    // Populate cache
    evaluationCache.put("Northumberland|2026-04-10|SUNSET", cachedResult);
    assertThat(evaluationCache).isNotEmpty();

    // Trigger new triage run
    briefingService.refreshBriefing();

    // Cache must be cleared
    assertThat(evaluationCache).isEmpty();
}
```

### Aurora scoring

```java
@Test
void fullMoonAboveHorizonCapsScoreAtThree() {
    var lunarPosition = LunarPosition.builder()
        .illuminationPercent(82)
        .isAboveHorizon(true)
        .phase(LunarPhase.WANING_GIBBOUS)
        .build();

    when(lunarCalculator.calculate(any(), anyDouble(), anyDouble()))
        .thenReturn(lunarPosition);

    var result = auroraInterpreter.score(auroraData, locations);

    assertThat(result.score()).isLessThanOrEqualTo(3);
    assertThat(result.detail()).containsIgnoringCase("moon");
}
```

---

## Process for each test class

When improving a test class, follow this sequence:

1. **Read the class under test first** — understand what it actually does
   before touching the tests

2. **List every `lenient()` call** — for each one, determine if the stub
   is actually needed. If not, delete stub + usages. If yes, restructure
   the test so it exercises the stubbed path and remove `lenient()`.

3. **List every `any()` in `verify()` calls** — replace with
   `ArgumentCaptor` and assert specific content

4. **List every `any()` in `when()` stubs** — replace with specific values
   where the expected input is knowable from the test context

5. **Identify missing boundary tests** — for every threshold in the class
   under test, add tests at boundary, below, and above

6. **Check `@SpringBootTest` usage** — if the test doesn't need Spring
   context (database, HTTP, event publishing), convert to unit test

7. **Run the full test suite** — all existing tests must still pass

---

## Priority order for test improvement work

Work through these areas in order — highest feature protection value first:

1. **`WeatherTriageService`** — triage rules are the gate for all Claude
   spend. Wrong verdicts = wasted money and misleading UI.

2. **Prompt builders** (`PromptBuilder`, `CoastalPromptBuilder`,
   `BriefingGlossService`) — prompt content drives Claude output quality.
   Regressions here are invisible without prompt content assertions.

3. **`BriefingEvaluationService`** — cache key format, cache invalidation,
   JFDI vs batch routing. Wrong cache behaviour = stale data served to users.

4. **`ClaudeAuroraInterpreter`** — moon penalty logic, Kp thresholds,
   window quality classification. Conservative aurora approach must be
   protected.

5. **`WeatherTriageService` aurora checks** — northward transect, Bortle
   threshold, viewline. Same importance as forecast triage.

6. **`BriefingService`** — region verdict aggregation (GO/MARGINAL from
   non-STANDDOWN locations only), gloss field population, best-bet
   integration.

---

## What NOT to do

- Do not increase coverage by adding tests that don't assert meaningful
  behaviour (e.g. testing that a constructor sets a field)
- Do not add `@Disabled` tests — fix them or delete them
- Do not use `Thread.sleep()` in tests — use `@MockBean` for time-dependent
  behaviour or inject a `Clock`
- Do not test private methods directly — test through the public API
- Do not write a test that could never fail — if you can't imagine a code
  change that would break it, it's not protecting anything
