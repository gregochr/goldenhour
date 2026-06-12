package com.gregochr.goldenhour.service.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideContext;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.ForecastDataAugmentor;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.BatchSuccess;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler.ForecastIdentity;
import com.gregochr.goldenhour.service.evaluation.visitor.RatingCombiner;
import com.gregochr.goldenhour.service.evaluation.visitor.SkyVisitor;
import com.gregochr.goldenhour.service.evaluation.visitor.TideVisitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Golden-master test for the serialised per-region cache payload
 * ({@code cached_evaluation.results_json}).
 *
 * <p><b>Why this exists (v2.13.2 step 2).</b> The v2.13.1 golden masters pin the assembled
 * <em>prompt</em> ({@link PromptGoldenMasterTest}); the tests that touch {@code results_json}
 * assert fields/behaviour, not byte-stability of the serialised payload. The v2.13.2
 * investigation (§4) found <em>no</em> golden master on the cache payload. This closes that gap:
 * it captures today's serialised {@code List<BriefingEvaluationResult>} <em>before</em> the
 * v2.13.2 decomposition (step 3), so that step's intentional change — stripping tide from the
 * coastal prompt (coastal ratings shift ~1★) then re-adding it via a {@code TideVisitor} — is
 * provably distinguishable from unintended drift (a field newly emptied, order changed, a
 * rating moved unexpectedly).
 *
 * <p><b>Pinned vs tolerated (the design decision).</b> The serialised element is a
 * {@link BriefingEvaluationResult}. We <em>pin exactly</em> the JSON structure (field names +
 * order) and the deterministic fields — {@code locationName}, {@code rating},
 * {@code fierySkyPotential}, {@code goldenHourPotential}, and the absent triage fields
 * ({@code triageReason}/{@code triageMessage}, null and so omitted on the scored path). These
 * are the surface v2.13.2 changes (especially {@code rating}). We <em>tolerate</em>
 * {@code summary} and {@code headline}: each is asserted present-and-a-string (headline may be
 * legitimately absent), but its exact text is NOT pinned, because Claude prose is
 * non-deterministic across runs — pinning it would yield false positives on every run for
 * reasons unrelated to the decomposition, training the team to ignore red.
 *
 * <p><b>How tolerance is implemented.</b> Fixtures under {@code src/test/resources/cache-golden/}
 * store the serialised payload with the {@code summary}/{@code headline} <em>values</em> replaced
 * by {@code <<SUMMARY>>}/{@code <<HEADLINE>>} placeholders. The test serialises the live payload,
 * asserts prose is a present non-blank string, then re-serialises with prose replaced by the same
 * placeholders and asserts the normalised string equals the fixture exactly. Field names, order,
 * presence, and every numeric/rating are therefore pinned; only prose text is tolerated.
 *
 * <p><b>Determinism (no Claude call).</b> The numeric/structural output is driven from a fixed
 * {@link SunsetEvaluation} — as if Claude had already returned it — through the <em>real</em>
 * construction seam: {@link ForecastResultHandler#parseBatchResponse} runs the real
 * {@link RatingCombiner} over {@link SkyVisitor} and builds the {@link BriefingEvaluationResult}
 * exactly as production does. Only the JSON parser is stubbed, to inject the fixed evaluation —
 * the parser is upstream of and distinct from the serialisation seam, which uses a real
 * Jackson-2 {@link com.fasterxml.jackson.databind.ObjectMapper} configured identically to the
 * production {@code AppConfig} bean that {@link BriefingEvaluationService} serialises with.
 *
 * <p>In v2.13.1 the combiner is field-equal to {@code eval.rating()} (one applied visitor), so the
 * pinned rating equals the input rating — that is correct, and is the baseline step 3 will change.
 * Coastal-ness is inert in the combiner today; the archetypes nonetheless set realistic
 * {@link LocationType}/{@link TideType} so step 3's {@code TideVisitor} picks the right archetype.
 *
 * <p><b>Regenerating fixtures.</b> When the payload is <em>intentionally</em> changed (e.g.
 * v2.13.2 step 3 regenerates the coastal-aligned rating), regenerate with:
 * <pre>./mvnw test -Dtest=CachePayloadGoldenMasterTest -Dcache.golden.regenerate=true</pre>
 * then review the diff before committing. Without the flag the test asserts equality.
 */
@ExtendWith(MockitoExtension.class)
class CachePayloadGoldenMasterTest {

    private static final String FIXTURE_DIR = "cache-golden";
    private static final LocalDate DATE = LocalDate.of(2026, 6, 21);
    private static final TargetType SUNSET = TargetType.SUNSET;
    private static final String SUMMARY_PLACEHOLDER = "<<SUMMARY>>";
    private static final String HEADLINE_PLACEHOLDER = "<<HEADLINE>>";

    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private ClaudeEvaluationStrategy parsingStrategy;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private ForecastDataAugmentor forecastDataAugmentor;
    @Mock
    private ForecastScoreWriter forecastScoreWriter;

    /**
     * Parser handle the handler passes to the (stubbed) parser. A real Jackson-3 mapper rather
     * than a mock — it is never exercised for serialisation, only forwarded to the stubbed
     * {@code parseEvaluationWithMetadata}, so there is nothing to mock.
     */
    private final tools.jackson.databind.ObjectMapper parserHandle =
            new tools.jackson.databind.ObjectMapper();

    /**
     * The serialisation seam under test: a Jackson-2 mapper configured identically to the
     * production {@code AppConfig.objectMapper()} bean that {@link BriefingEvaluationService}
     * serialises {@code results_json} with — {@code new ObjectMapper().registerModule(JavaTimeModule)}.
     */
    private final com.fasterxml.jackson.databind.ObjectMapper productionMapper =
            new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("coastal with aligned tide: sky 3 (sky-only) + spring tide 5 → averaged to 4")
    void coastalAligned() {
        // Berwick-Upon-Tweed (SEASCAPE). Post-decomposition: Claude scores the SKY ALONE (3★ —
        // the old +1 tide boost is gone from the prompt), and the TideVisitor re-adds an aligned
        // spring tide (5★). RatingCombiner averages: avg(3, 5) = 4. The fixture pins the new
        // provenance; the value happens to land back on 4.
        LocationEntity location = seascape("Berwick-Upon-Tweed", 1L, "Northumberland Coast");
        SunsetEvaluation eval = new SunsetEvaluation(
                3, 72, 68, "Clearing western sky over the harbour.",
                null, null, null, null, null, null, null,
                "Fiery tide-lit finish");
        when(forecastDataAugmentor.deriveTideContext(location, DATE, SUNSET))
                .thenReturn(Optional.of(tideContext(true, true, LunarTideType.SPRING_TIDE)));
        assertGolden("coastal-aligned", location, eval);
    }

    @Test
    @DisplayName("coastal misaligned: sky 3 + tide 1 → dragged to 2 (R1 — misaligned penalises)")
    void coastalMisaligned() {
        // Spittal Beach (SEASCAPE). Sky-only is still 3★, but under R1 a misaligned tide at a
        // tidal location now scores 1★ and averages in: avg(3, 1) = 2. This fixture CHANGES from
        // the pre-decomposition 3★ — the intended R1 behaviour (misaligned tide = no foreground).
        LocationEntity location = seascape("Spittal Beach", 2L, "Northumberland Coast");
        SunsetEvaluation eval = new SunsetEvaluation(
                3, 55, 60, "Decent western light but the tide is out of phase.",
                null, null, null, null, null, null, null,
                "Soft light, low water");
        when(forecastDataAugmentor.deriveTideContext(location, DATE, SUNSET))
                .thenReturn(Optional.of(tideContext(false, false, LunarTideType.REGULAR_TIDE)));
        assertGolden("coastal-misaligned", location, eval);
    }

    @Test
    @DisplayName("inland: sky-only today and after — proves no phantom tide, legit-null headline")
    void inland() {
        // Keswick (LANDSCAPE) — non-coastal, sky-only today and after decomposition. Headline
        // omitted to exercise the legitimately-absent (NON_NULL) headline branch.
        LocationEntity location = landscape("Keswick", 3L, "Lake District");
        SunsetEvaluation eval = new SunsetEvaluation(
                3, 58, 62, "Broken cloud over the fells with a chance of colour.");
        assertGolden("inland", location, eval);
    }

    @Test
    @DisplayName("sky not forecast (inland): 1★ + exact not-forecast summary, null headline, no triage")
    void skyNotForecast() {
        // Buttermere (LANDSCAPE) — a parseable Claude response that omitted the rating. Decision B
        // changes inland sky-empty from a silent null to a visible 1★ with an honest label. This
        // fixture (inland-shaped: no TideVisitor) guards that change. The substituted summary is
        // deterministic code-injected text, so it is pinned EXACTLY (not normalised to a
        // placeholder like Claude prose).
        LocationEntity location = landscape("Buttermere", 4L, "Lake District");
        SunsetEvaluation eval = new SunsetEvaluation(
                null, 40, 45, "Claude prose that must be overridden by the not-forecast summary.");

        String serialised = serialisePayload(location, eval);
        JsonNode array = readTree(serialised);
        JsonNode element = array.get(0);
        assertThat(element.get("rating").asInt())
                .as("sky-not-forecast must substitute 1★").isEqualTo(1);
        assertThat(element.get("summary").asText())
                .as("sky-not-forecast summary is pinned exactly (code-injected, not Claude prose)")
                .isEqualTo(ForecastResultHandler.SKY_NOT_FORECAST_SUMMARY);
        assertThat(element.has("headline"))
                .as("null headline must be omitted (NON_NULL)").isFalse();
        assertThat(element.has("triageReason"))
                .as("must NOT render with triage stand-down treatment").isFalse();
        assertThat(element.has("triageMessage"))
                .as("must NOT render with triage stand-down treatment").isFalse();

        // Structural golden (prose normalised) guards field order/presence + the 1★ rating.
        String normalised = normalise(array);
        if (Boolean.getBoolean("cache.golden.regenerate")) {
            writeFixture("sky-not-forecast", normalised);
            return;
        }
        assertThat(normalised)
                .as("Serialised sky-not-forecast payload differs from golden master "
                        + "src/test/resources/%s/sky-not-forecast.json. Regenerate with "
                        + "-Dcache.golden.regenerate=true and review the diff.", FIXTURE_DIR)
                .isEqualTo(readFixture("sky-not-forecast"));
    }

    /**
     * Serialises the payload for {@code location}+{@code eval} through the production seam and
     * compares the normalised result to the committed fixture (or writes it when regenerating).
     *
     * @param name     fixture base name (no extension)
     * @param location the archetype location
     * @param eval     the fixed Claude evaluation (as if already parsed)
     */
    private void assertGolden(String name, LocationEntity location, SunsetEvaluation eval) {
        String serialised = serialisePayload(location, eval);

        JsonNode array = readTree(serialised);
        assertProsePresent(array, name);
        String normalised = normalise(array);

        if (Boolean.getBoolean("cache.golden.regenerate")) {
            writeFixture(name, normalised);
            return;
        }
        String expected = readFixture(name);
        assertThat(normalised)
                .as("Serialised cache payload for archetype '%s' differs from golden master "
                        + "src/test/resources/%s/%s.json. If this change is intentional (e.g. "
                        + "v2.13.2 step 3), regenerate with -Dcache.golden.regenerate=true and "
                        + "review the diff.", name, FIXTURE_DIR, name)
                .isEqualTo(expected);
    }

    /**
     * Builds the {@code List<BriefingEvaluationResult>} through the real combiner + construction
     * seam ({@link ForecastResultHandler#parseBatchResponse}) from the fixed evaluation, then
     * serialises it exactly as {@link BriefingEvaluationService#persistToDb} does:
     * {@code objectMapper.writeValueAsString(new ArrayList<>(results.values()))}.
     */
    private String serialisePayload(LocationEntity location, SunsetEvaluation eval) {
        ForecastResultHandler handler = new ForecastResultHandler(
                briefingEvaluationService,
                Map.of(EvaluationModel.HAIKU, parsingStrategy),
                jobRunService, parserHandle,
                new RatingCombiner(List.of(new SkyVisitor(), new TideVisitor())),
                forecastDataAugmentor, forecastScoreWriter);

        String customId = "fc-" + location.getId() + "-2026-06-21-SUNSET";
        String rawText = "{\"injected-by-stub\":true}";
        ClaudeBatchOutcome outcome = ClaudeBatchOutcome.success(
                customId, rawText, new TokenUsage(500, 200, 0, 1000), EvaluationModel.HAIKU);
        when(parsingStrategy.parseEvaluationWithMetadata(eq(rawText), eq(parserHandle)))
                .thenReturn(new ClaudeEvaluationStrategy.ParseResult(eval, false));

        ForecastIdentity identity = new ForecastIdentity(location.getId(), DATE, SUNSET);
        Optional<BatchSuccess> parsed = handler.parseBatchResponse(
                location, identity, outcome,
                ResultContext.forBatch(99L, "msgbatch_x", BatchTriggerSource.SCHEDULED));

        assertThat(parsed).as("handler should return a parsed success").isPresent();

        // Mirror persistToDb: results map → list of values → writeValueAsString.
        List<BriefingEvaluationResult> resultList = new ArrayList<>();
        resultList.add(parsed.get().result());
        try {
            return productionMapper.writeValueAsString(resultList);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise cache payload", e);
        }
    }

    /**
     * Asserts every element carries a present non-blank {@code summary} string and, when present,
     * a textual {@code headline} — the tolerated (shape-only) prose contract.
     */
    private void assertProsePresent(JsonNode array, String name) {
        assertThat(array.isArray()).as("payload for '%s' must serialise as a JSON array", name)
                .isTrue();
        for (JsonNode element : array) {
            assertThat(element.has("summary"))
                    .as("element in '%s' must carry a summary field", name).isTrue();
            JsonNode summary = element.get("summary");
            assertThat(summary.isTextual())
                    .as("summary in '%s' must be a string", name).isTrue();
            assertThat(summary.asText().isBlank())
                    .as("summary in '%s' must be non-blank", name).isFalse();
            if (element.has("headline")) {
                assertThat(element.get("headline").isTextual())
                        .as("headline in '%s', when present, must be a string", name).isTrue();
            }
        }
    }

    /**
     * Replaces the {@code summary}/{@code headline} <em>values</em> with fixed placeholders while
     * preserving field order and presence, then re-serialises. This pins structure + numerics +
     * rating exactly and tolerates prose text.
     */
    private String normalise(JsonNode array) {
        for (JsonNode element : array) {
            ObjectNode object = (ObjectNode) element;
            if (object.has("summary")) {
                object.put("summary", SUMMARY_PLACEHOLDER);
            }
            if (object.has("headline")) {
                object.put("headline", HEADLINE_PLACEHOLDER);
            }
        }
        try {
            return productionMapper.writeValueAsString(array);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to re-serialise normalised payload", e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return productionMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse serialised payload", e);
        }
    }

    /** Builds a re-derived {@link TideContext} with the given alignment and lunar classification. */
    private static TideContext tideContext(boolean tightAligned, boolean widenedAligned,
            LunarTideType lunar) {
        TideSnapshot snapshot = new TideSnapshot(
                TideState.HIGH, null, null, null, null,
                tightAligned, null, null, lunar, null, null, null);
        return new TideContext(snapshot, widenedAligned);
    }

    private LocationEntity seascape(String name, long id, String regionName) {
        LocationEntity location = baseLocation(name, id, regionName);
        location.setLocationType(Set.of(LocationType.SEASCAPE));
        location.setTideType(Set.of(TideType.HIGH));
        return location;
    }

    private LocationEntity landscape(String name, long id, String regionName) {
        LocationEntity location = baseLocation(name, id, regionName);
        location.setLocationType(Set.of(LocationType.LANDSCAPE));
        return location;
    }

    private LocationEntity baseLocation(String name, long id, String regionName) {
        LocationEntity location = new LocationEntity();
        location.setId(id);
        location.setName(name);
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        location.setRegion(region);
        return location;
    }

    private String readFixture(String name) {
        String resource = "/" + FIXTURE_DIR + "/" + name + ".json";
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                fail("Missing golden-master fixture: src/test/resources" + resource
                        + " — regenerate with -Dcache.golden.regenerate=true");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixture " + resource, e);
        }
    }

    private void writeFixture(String name, String content) {
        Path path = Path.of("src", "test", "resources", FIXTURE_DIR, name + ".json");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write fixture " + path, e);
        }
    }
}
