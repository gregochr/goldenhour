# StructuredOutputConfig migration — investigation summary (not implemented)

> **Status: adversarially reviewed and closed. Do not migrate.** The initial investigation
> (below, up to "Proposed fix path") got its bottom-line recommendation right but two of
> its supporting claims wrong. A Fable adversarial review (`## Adversarial review verdict`
> at the end of this document) corrected both, and found a third, worse problem the
> original pass missed entirely: the only schema shape this SDK version can produce would
> silently corrupt `basic_*`/`inversion_*` scores into fabricated zeros for evaluations
> where those fields don't apply. Read the verdict section first; the rest of this
> document is preserved as the investigation trail, not as current guidance.

## Why this came up

The Anthropic Java SDK was bumped 2.54.0 → 2.57.0 via dependabot (#602), on top of an
already-long chain of unreviewed dependabot bumps going back to 2.15.0. A survey of the
SDK changelog across that whole range surfaced `StructuredOutputConfig` (added in 2.54.0):
a reflection-based helper that derives an `OutputConfig` (and its JSON Schema) directly
from a `Class<T>`, as an alternative to hand-building JSON Schema as `Map`/`JsonValue`
literals.

Three classes in this codebase currently hand-build their `OutputConfig` this way:
`PromptBuilder.buildOutputConfig()`, `WoodlandPromptBuilder.buildOutputConfig()`, and
`BluebellPromptBuilder.buildOutputConfig()`. All three use the same pattern —
`JsonOutputFormat.Schema.builder().putAdditionalProperty("properties", JsonValue.from(Map.ofEntries(...)))`
— which is verbose and has no compile-time type safety (a typo'd field name or type
string is a runtime/API-validation failure, not a compile error). Swapping it for a
Java record was flagged as a plausible maintainability cleanup.

## Why it is not a pure mechanical refactor

The JSON Schema `description` strings in these methods are not documentation — they are
live prompt content that Claude reads as part of generating a structured response, and at
least one of them encodes conditionally-worded scoring instructions:

```java
Map.entry("rating", Map.of(
        "type", "integer",
        "enum", List.of(1, 2, 3, 4, 5),
        "description",
        "1-5. MAXIMUM 3 when the CLOUD APPROACH RISK "
                + "block shows BOTH a [BUILDING] trend "
                + "AND upwind current >= 60%.")),
...
Map.entry("golden_hour", Map.of(
        "type", "integer",
        "description",
        "0-100 inclusive. 20-30 points LOWER than the "
                + "conditions alone would give when the "
                + "CLOUD APPROACH RISK block shows BOTH a "
                + "[BUILDING] trend AND upwind current "
                + ">= 60%.")),
```

This is the same mechanism CLAUDE.md documents at length under "Cloud approach risk" —
an empirically-tuned demotion, measured against 545 production firings over six months,
with an explicit standing warning ("Never give the far reading escalatory force again")
about a prior version of this exact logic causing a measured accuracy regression. This is
squarely the kind of prompt-adjacent code the project's UI-review-cadence and
prompt-regression-test rules exist to protect, even though it lives in Java, not in the
system-prompt string.

## Current implementation (verbatim)

`PromptBuilder.buildOutputConfig()` — 11 fields
(`backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:668-727`):

```java
public OutputConfig buildOutputConfig() {
    return OutputConfig.builder()
            .format(JsonOutputFormat.builder()
                    .schema(JsonOutputFormat.Schema.builder()
                            .putAdditionalProperty("type", JsonValue.from("object"))
                            .putAdditionalProperty("properties", JsonValue.from(Map.ofEntries(
                                    Map.entry("rating", Map.of(
                                            "type", "integer",
                                            "enum", List.of(1, 2, 3, 4, 5),
                                            "description",
                                            "1-5. MAXIMUM 3 when the CLOUD APPROACH RISK "
                                                    + "block shows BOTH a [BUILDING] trend "
                                                    + "AND upwind current >= 60%.")),
                                    Map.entry("fiery_sky", Map.of(
                                            "type", "integer",
                                            "description", "0-100 inclusive.")),
                                    Map.entry("golden_hour", Map.of(
                                            "type", "integer",
                                            "description",
                                            "0-100 inclusive. 20-30 points LOWER than the "
                                                    + "conditions alone would give when the "
                                                    + "CLOUD APPROACH RISK block shows BOTH a "
                                                    + "[BUILDING] trend AND upwind current "
                                                    + ">= 60%.")),
                                    Map.entry("summary", Map.of(
                                            "type", "string",
                                            "description",
                                            "One sentence in Claude's voice explaining the "
                                                    + "rating from the actual conditions; never "
                                                    + "a placeholder such as 'test', "
                                                    + "'placeholder', or an ellipsis.")),
                                    Map.entry("basic_fiery_sky", Map.of(
                                            "type", "integer",
                                            "description", "0-100 inclusive.")),
                                    Map.entry("basic_golden_hour", Map.of(
                                            "type", "integer",
                                            "description", "0-100 inclusive.")),
                                    Map.entry("basic_summary", Map.of(
                                            "type", "string",
                                            "description",
                                            "One sentence explaining the basic (altitude-only) "
                                                    + "rating; never a placeholder.")),
                                    Map.entry("inversion_score", Map.of(
                                            "type", "integer",
                                            "description", "0-10 inclusive.")),
                                    Map.entry("inversion_potential", Map.of(
                                            "type", "string",
                                            "enum", List.of("NONE", "MODERATE", "STRONG"))),
                                    Map.entry("headline", Map.of(
                                            "type", "string",
                                            "description",
                                            "4-9 word card header in Claude's voice.")))))
                            .putAdditionalProperty("required", JsonValue.from(
                                    List.of("rating", "fiery_sky", "golden_hour", "summary")))
                            .putAdditionalProperty("additionalProperties",
                                    JsonValue.from(false))
                            .build())
                    .build())
            .build();
}
```

`WoodlandPromptBuilder.buildOutputConfig()` and `BluebellPromptBuilder.buildOutputConfig()`
are identical to each other — a smaller 3-field schema:

```java
public OutputConfig buildOutputConfig() {
    return OutputConfig.builder()
            .format(JsonOutputFormat.builder()
                    .schema(JsonOutputFormat.Schema.builder()
                            .putAdditionalProperty("type", JsonValue.from("object"))
                            .putAdditionalProperty("properties", JsonValue.from(Map.ofEntries(
                                    Map.entry("rating", Map.of(
                                            "type", "integer",
                                            "enum", List.of(1, 2, 3, 4, 5))),
                                    Map.entry("summary", Map.of("type", "string")),
                                    Map.entry("headline", Map.of(
                                            "type", "string",
                                            "description",
                                            "4-9 word card header in Claude's voice.")))))
                            .putAdditionalProperty("required", JsonValue.from(
                                    List.of("rating", "summary")))
                            .putAdditionalProperty("additionalProperties",
                                    JsonValue.from(false))
                            .build())
                    .build())
            .build();
}
```

Note that both variants carry `rating`'s `enum: [1,2,3,4,5]` constraint, and both leave
at least one field (`headline` in the small schema; `basic_*`/`inversion_*`/`headline` in
the large one) out of `required`. There is no schema in this codebase that is exempt from
the two issues found below — the small schema is lower-risk (fewer fields, no conditional
prose) but not risk-free.

## What was prototyped

Before touching any production code, I compiled and ran the SDK's reflection-based path
standalone (outside the repo, against the real `anthropic-java-core-2.57.0.jar` pulled
from the local `.m2` cache) to see what it actually produces, rather than assuming.

One API-shape note worth recording: `com.anthropic.core.StructuredOutputsKt.outputFormatFromClass(Class, JsonSchemaLocalValidation)`
— the function `javap` shows as a plausible entry point — is compiled with the
`ACC_SYNTHETIC` flag (a Kotlin default-arguments artifact) and is **not callable from
Java source**; `javac` rejects it as an unresolved symbol despite `javap` listing it as
public. The actual Java-facing entry point is the builder API:

```java
StructuredOutputConfig<EvalOutput> config = StructuredOutputConfig.<EvalOutput>builder()
        .format(EvalOutput.class)
        .build();
OutputConfig outputConfig = config.rawOutputConfig();
```

I defined a record shaped like `PromptBuilder`'s 11-field schema, with
`@JsonPropertyDescription` on every field that currently carries a hand-written
description (Jackson annotations, since the SDK's schema generator wraps
`victools`/Jackson under the hood), and printed the resulting schema. Verbatim output
(reformatted for readability; the tool printed one line):

```
type=object
properties={
  basic_fiery_sky={type=integer}
  basic_golden_hour={type=integer}
  basic_summary={type=string}
  fiery_sky={type=integer, description="0-100 inclusive."}
  golden_hour={type=integer, description="0-100 inclusive. 20-30 points LOWER than the
      conditions alone would give when the CLOUD APPROACH RISK block shows BOTH a
      [BUILDING] trend AND upwind current >= 60%."}
  headline={type=string, description="4-9 word card header in Claude's voice."}
  inversion_potential={type=string, enum=[NONE, MODERATE, STRONG]}
  inversion_score={type=integer, description="0-10 inclusive."}
  rating={type=integer, description="1-5. MAXIMUM 3 when the CLOUD APPROACH RISK block
      shows BOTH a [BUILDING] trend AND upwind current >= 60%."}
  summary={type=string, description="One sentence in Claude's voice explaining the
      rating from the actual conditions; never a placeholder such as 'test',
      'placeholder', or an ellipsis."}
}
required=[basic_fiery_sky, basic_golden_hour, basic_summary, fiery_sky, golden_hour,
    headline, inversion_potential, inversion_score, rating, summary]
additionalProperties=false
```

(Plus a top-level `$schema: https://json-schema.org/draft/2020-12/schema` key the
hand-built version does not emit — probably harmless, not investigated further given the
two issues below.)

## Findings — two concrete regressions

**1. `rating`'s `enum: [1,2,3,4,5]` constraint is silently dropped.**

Current schema: `{"type":"integer","enum":[1,2,3,4,5],"description":"..."}` — the `enum`
array makes this a **server-side hard constraint**; Claude's structured-output generation
cannot return a `rating` outside `{1,2,3,4,5}` and satisfy the schema.

Generated schema: `{"type":"integer","description":"..."}` — no `enum`. A plain Java
`int`/`Integer` field carries no metadata the schema generator can read as "exactly these
five values," and this SDK version exposes no annotation hook (equivalent to
`@JsonSchemaInject` in some other Java JSON-schema generators) that I could find to
re-inject an arbitrary raw schema fragment. The description text still says "1-5", but
that becomes a soft (prose) constraint instead of a hard (schema-enforced) one — Claude
could return `0`, `6`, or `100` and the response would still validate.

The same applies to `WoodlandPromptBuilder`/`BluebellPromptBuilder`'s `rating` field,
which carries the identical `enum: [1,2,3,4,5]`.

`inversion_potential` is unaffected — it's a real Java `enum` type
(`NONE`/`MODERATE`/`STRONG`), and Java enums *do* generate a JSON Schema `enum` array
correctly from `victools`' default behaviour. The difference is that `rating` needs an
`enum` constraint on a **primitive integer type**, which has no equivalent Java construct
that maps cleanly (a Java `enum RatingValue { ONE, TWO, ... }` would generate a *string*
enum of the constant names, not an integer enum of `1`–`5`, without further custom
serialization work).

**2. Every currently-optional field becomes `required`.**

Current schema: `required: ["rating", "fiery_sky", "golden_hour", "summary"]` only.
`basic_fiery_sky`, `basic_golden_hour`, `basic_summary`, `inversion_score`,
`inversion_potential`, and `headline` are all deliberately **absent** from `required` —
these fields are populated conditionally depending on user tier (LITE vs PRO scoring)
and location metadata (inversion-eligible locations only), and are expected to come back
`null` when they don't apply. (`WoodlandPromptBuilder`/`BluebellPromptBuilder` leave
`headline` optional for the same reason.)

Generated schema: **all ten fields** appear in `required`. The schema generator treats
any field whose Java type isn't itself expressed as optional/nullable as a required
property — plain boxed types (`Integer`, `String`) are not enough on their own.

If this reached production unchanged, Claude would be schema-forced to emit values for
`basic_fiery_sky`/`basic_golden_hour`/`basic_summary`/`inversion_score`/
`inversion_potential`/`headline` on *every* call, including ones where those concepts
don't apply — meaning either fabricated/placeholder values, or a validation failure on
every non-PRO or non-inversion-eligible evaluation. This is a much larger and more
mechanical-looking failure than issue #1, and the kind of thing that would likely surface
immediately in production (broken evaluations) rather than silently, but it's still a
correctness gap in the naive migration path, not something to paper over.

## Proposed fix path, if this is pursued

1. **Re-inject `rating`'s `enum` after generation.** Rather than trusting the generator
   for that one field, post-process the generated schema's `properties.rating` object to
   add `"enum": [1,2,3,4,5]` back in before sending it — effectively a hybrid: generated
   schema as the base, one manual patch for the one field the generator can't express.
   This is a small, localized, testable transform, but it means the "clean Class<T>"
   story isn't actually clean — there's still a manual schema-editing step in the
   pipeline, which undercuts a chunk of the original maintainability argument.

2. **Wrap every currently-optional field in `Optional<T>`** in the record
   (`basic_fiery_sky`, `basic_golden_hour`, `basic_summary`, `inversion_score`,
   `inversion_potential`, `headline`) and verify the generator actually drops them from
   `required` as a result. **This is unverified** — the prototype run above used plain
   boxed types throughout and did not test the `Optional<T>`-wrapped path at all. That's
   the first thing to check before trusting this as the fix, not an assumption to build
   on.

3. **Assert schema equality, not just "it compiles."** Before pointing the generated
   schema at a live prompt, add a test that diffs the fully-generated schema against the
   current hand-built one field-by-field — types, description text verbatim (a
   re-wrapped or re-punctuated description string is still a prompt change), the `enum`
   arrays, the `required` set, and `additionalProperties`. A test that only asserts "the
   code compiles and returns *an* `OutputConfig`" would have let both of the above issues
   through unnoticed.

4. **Re-run the sky-rating eval harness and prompt-regression suite** against both the
   old and new schema before and after, per the project's own standing rule (see
   `docs/engineering/sky-rating-eval-harness.md` and the CLAUDE.md prohibition on
   changing prompt-regression assertions) — an enum/required change is exactly the kind
   of change that can shift model behaviour in ways a code-level diff cannot show.

5. **Treat `PromptBuilder`'s 11-field schema as materially higher-risk than
   `WoodlandPromptBuilder`/`BluebellPromptBuilder`'s 3-field schema**, even after 1–4 are
   done. `PromptBuilder`'s schema carries the conditionally-worded, empirically-tuned
   cloud-approach-risk prose that CLAUDE.md flags with an explicit standing warning about
   a prior regression in this exact area. I would not migrate it in the same change (or
   possibly at all) as the smaller two.

## My assessment (subject to review)

Both blockers apply to *every* schema in this codebase, including the smallest one — there
is no genuinely free migration target here, only a lower-risk one. The maintainability
win (typed record vs. `Map.ofEntries` literals) is real but modest, and the safe path to
get there requires: a custom enum re-injection step (undercutting some of the "just use a
class" simplicity), an unverified assumption about `Optional<T>` behaviour that needs
testing before it can be trusted, a new schema-equality test, and a full eval-harness
re-run — for a change to code that this project has already been burned by changing
carelessly once (the cloud-approach-risk veto history documented in CLAUDE.md).

**My inclination is to hold off** — the cost of doing this safely doesn't look small
relative to the benefit, and the failure mode of doing it unsafely is a silent scoring
change in a system with no automated ground truth (`actual_outcome` is documented
elsewhere in this repo as empty). I would want a stronger driver — e.g., an active need
to add several more schema fields where the current `Map.ofEntries` pattern is becoming
the actual bottleneck — before spending the verification effort above.

This assessment has not been adversarially challenged yet. Specific things worth
attacking:
- Is there in fact a hook in this SDK version (or a newer one) for injecting an arbitrary
  raw schema fragment onto one field — i.e. is finding #1 actually a dead end, or did the
  prototype just not look hard enough?
- Does `Optional<T>` wrapping actually solve finding #2 as assumed, or does it introduce
  its own new problems (e.g. `Optional` fields serializing/deserializing oddly through
  the rest of the parsing pipeline in `SunsetEvaluationParser`, which reads plain
  `JsonNode` values, not the record itself)?
- Is the "hold off" recommendation too conservative given the actual blast radius —
  would a schema-equality test plus one eval-harness run be sufficient confidence, making
  this cheaper to ship safely than assessed here?
- Is there a reuse angle been missed — e.g. could the generated schema be produced once
  at startup/test-time and hand-verified into a golden-file fixture, rather than trusting
  runtime reflection on every request?

---

## Adversarial review verdict (Fable, 2026-08-25)

Requested because this touches live prompt content. Fable read the write-up above, then
independently verified every claim against the real source (`PromptBuilder.java`,
`WoodlandPromptBuilder.java`, `BluebellPromptBuilder.java`, `SunsetEvaluationParser.java`,
CLAUDE.md) and against the actual SDK bytecode and compiled probes — not just the prose.

**Same conclusion, corrected reasoning: hold off, do not migrate.** But the write-up above
got two of its three supporting claims wrong, and missed the strongest reason to hold off
entirely.

### Finding 1 (lost `rating` enum) — the write-up was WRONG. A working fix exists.

Disassembling `com.anthropic.core.StructuredOutputsKt.extractSchema` (`javap -v`) shows
the generator is a `victools` `SchemaGeneratorConfigBuilder` with a **`Swagger2Module`**
registered — meaning `io.swagger.v3.oas.annotations.media.@Schema` is honoured, and
`swagger-annotations` is already on this backend's classpath transitively via springdoc.
A compiled probe confirms `@Schema(allowableValues = {"1","2","3","4","5"}, description =
"...")` on the `rating` field generates `{"type":"integer","enum":[1,2,3,4,5],
"description":"..."}` — a genuine integer enum, description intact, passing the SDK's own
local schema validator. No hybrid post-processing step is needed for this part; the
write-up's "no annotation hook, dead end" claim (finding 1 as originally written) simply
didn't look hard enough.

### Finding 2 (over-broad `required`) — the write-up UNDERSTATED it. It is not fixable at all.

The same disassembly shows the generator's required-check is
`forFields().withRequiredCheck(lambda)`, and the lambda body is literally `iconst_1;
ireturn` — **it returns `true` unconditionally, for every field, always.** This is not a
default that annotations can override; it is hard-coded into this SDK version. A battery
of compiled probes confirms nothing gets a field out of `required`:

| Attempted fix | Result |
|---|---|
| `Optional<Integer>` etc. | Field becomes nullable (`type: [integer, null]`) but **stays required** |
| `@org.jetbrains.annotations.Nullable` | No effect — class-retention annotation, invisible to runtime reflection |
| `@jakarta.annotation.Nullable` (RUNTIME) | Nullable in type, but **stays required** |
| `@Schema(requiredMode = NOT_REQUIRED)` | Ignored — field stays in `required` |

So the write-up's proposed fix #2 ("wrap in `Optional<T>` and verify it drops from
`required`") was flagged as unverified and turned out to be impossible, not just
unverified. There is no way, in this SDK version, to express "this field is genuinely
optional" in a reflection-generated schema. The only expressible shape is
**required-but-nullable**.

### Finding 3 (missed entirely by the original write-up) — required-but-nullable corrupts data downstream.

Because required-but-nullable is the only shape available, migrating would force Claude
to emit every field on every call — `null` for the ones that don't apply. But
`SunsetEvaluationParser.parseEvaluationWithMetadata` (`SunsetEvaluationParser.java:225-241`)
reads those fields as:

```java
Integer basicFierySky = node.has("basic_fiery_sky")
        ? node.get("basic_fiery_sky").asInt() : null;
```

A compiled probe against this project's actual `tools.jackson` version confirms:
`node.has("basic_fiery_sky")` is `true` for an explicit JSON `null`, and
`node.get("basic_fiery_sky").asInt()` on that null node returns **`0`**, not `null`. So a
migrated schema wouldn't just be "more verbose" — it would silently convert "this field
does not apply" (LITE-tier evaluation, non-inversion-eligible location) into a persisted
`basic_fiery_sky` / `basic_golden_hour` / `inversion_score` of **0**, indistinguishable
from a real bottom-of-scale rating. `.stringValue()` (used for the `String` fields) is
null-safe and unaffected — this is specifically an `.asInt()`-on-null problem, so it hits
`basic_fiery_sky`, `basic_golden_hour`, and `inversion_score`.

This is the strongest single reason not to migrate: it is a silent correctness bug that a
schema-equality test wouldn't even reach (schema equality already fails first, on
`required` differing unfixably), and the sky-rating eval harness would need to be
specifically constructed to exercise the LITE/non-inversion path to catch it at all.

### Why "hold off" stands, on firmer ground than before

A schema-equality test against the current hand-built schema **cannot pass**, full stop —
`required` differs in a way nothing can fix, plus a `$schema` key the hand-built version
doesn't emit, plus alphabetical vs. hash-order field ordering. Any migration necessarily
ships a semantically different schema. Evaluating whether that difference matters would
need eval-harness evidence, and per this repo's own documented state, `actual_outcome` has
zero rows — there is no ground truth to arbitrate a behaviour shift in exactly the prompt
area (`rating`/`golden_hour`'s cloud-approach-risk wording) already burned once, per
CLAUDE.md's standing warning. The maintainability upside was also smaller than the
original framing suggested: only three schemas exist in this codebase, and two of the
three (`WoodlandPromptBuilder`, `BluebellPromptBuilder`) are byte-for-byte identical.

**Conclusion: do not migrate `buildOutputConfig()` to `StructuredOutputConfig` in this SDK
version.** Revisit only if a future SDK release changes the hard-coded `required` check —
worth a one-`javap` check (`StructuredOutputsKt.extractSchema$lambda$0` or its renamed
successor) the next time `com.anthropic:anthropic-java` gets a dependabot bump that touches
`StructuredOutputsKt`, rather than re-deriving this whole investigation from scratch.
