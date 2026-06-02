package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TideSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden-master test for the fully assembled Claude colour-evaluation prompt.
 *
 * <p><b>Why this exists.</b> The ~140 existing prompt tests assert substrings only.
 * A refactor that reorders or reshapes the assembled prompt would pass those tests
 * while changing what Claude actually sees. This test captures the <em>entire</em>
 * assembled prompt (system prompt + per-location user message) byte-for-byte against
 * committed fixtures, so any change to the assembled text — wording, section order,
 * or block presence — fails loudly. It is the safety net under the v2.13 visitor
 * relocation: the visitor must keep these fixtures green (the prompt is unchanged in
 * v2.13.1; only its assembly structure moves).
 *
 * <p><b>The seam under test is real, not mocked.</b> The prompt is assembled through
 * the production path — {@link BatchRequestFactory#selectBuilder} chooses the builder,
 * and the chosen {@link PromptBuilder} / {@link CoastalPromptBuilder} produces the text.
 * The surge-overload selection ({@code data.surge() != null ? buildUserMessage(data,
 * surge, ...) : buildUserMessage(data)}) is the identical ternary used in all three live
 * call sites — {@link BatchRequestFactory#buildForecastRequest}, {@code
 * EvaluationServiceImpl.evaluateNowForecast}, and {@code ClaudeEvaluationStrategy}. Only
 * the genuinely-external input — the {@link AtmosphericData} forecast record — is pinned,
 * via {@link TestAtmosphericData} with fixed times and fixed weather. The builders are
 * pure functions of {@code AtmosphericData} (no clock, no randomness), so the assembled
 * prompt is fully deterministic.
 *
 * <p><b>What is pinned for determinism:</b> {@code solarEventTime}, all tide times, tide
 * heights, cloud/weather/aerosol values, surge values, and the bluebell-season date. No
 * value in the assembly path reads the wall clock.
 *
 * <p><b>Regenerating fixtures.</b> When the prompt is <em>intentionally</em> changed
 * (e.g. v2.13.2 prompt decomposition), regenerate with:
 * <pre>./mvnw test -Dtest=PromptGoldenMasterTest -Dprompt.golden.regenerate=true</pre>
 * then review the diff before committing. Without the flag the test asserts equality.
 *
 * <p><b>Archetype coverage.</b> One fixture per distinct location-content branch of the
 * assembly:
 * <ul>
 *   <li>{@code inland-landscape} — base {@link PromptBuilder}, no location-fact blocks.</li>
 *   <li>{@code coastal-tidal} — {@link CoastalPromptBuilder}: coastal system suffix + tide block.</li>
 *   <li>{@code woodland-bluebell} — base builder + bluebell block (in-season date).</li>
 *   <li>{@code darksky-inversion} — base builder + orientation + cloud-inversion blocks
 *       (the content an elevated dark-sky/astro overlook actually carries; dark-sky type
 *       itself does not alter the colour prompt — see note below).</li>
 *   <li>{@code coastal-surge} — {@link CoastalPromptBuilder} + tide + storm-surge blocks.</li>
 * </ul>
 *
 * <p><b>Wildlife archetype is intentionally absent.</b> Pure-wildlife locations are routed
 * to {@code NoOpEvaluationStrategy} and never produce a Claude colour prompt (confirmed in
 * the v2.13 investigation). There is no assembled prompt to capture, so there is no fixture.
 *
 * <p><b>Dark-sky note.</b> A dark-sky/astro location does not, by type, change the colour
 * prompt — it flows through the same base builder as any inland landscape. The
 * {@code darksky-inversion} fixture therefore exercises the inversion + orientation content
 * such locations genuinely carry, rather than duplicating {@code inland-landscape}.
 */
class PromptGoldenMasterTest {

    private static final String FIXTURE_DIR = "prompt-golden";
    private static final String SYSTEM_USER_DELIMITER =
            System.lineSeparator() + "========== USER MESSAGE ==========" + System.lineSeparator();

    /** Real assembly seam under test — real builders, no mocks. */
    private final BatchRequestFactory factory =
            new BatchRequestFactory(new PromptBuilder(), new CoastalPromptBuilder());

    @Test
    @DisplayName("inland landscape: base builder, no location-fact blocks")
    void inlandLandscape() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Hadrians Wall UK")
                .solarEventTime(LocalDateTime.of(2026, 6, 21, 20, 47))
                .targetType(TargetType.SUNSET)
                .build();
        assertGolden("inland-landscape", data);
    }

    @Test
    @DisplayName("coastal tidal: coastal builder + tide block")
    void coastalTidal() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Saltwick Bay UK")
                .solarEventTime(LocalDateTime.of(2026, 6, 21, 20, 47))
                .targetType(TargetType.SUNSET)
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 21, 10),
                        new BigDecimal("5.10"),
                        LocalDateTime.of(2026, 6, 22, 3, 25),
                        new BigDecimal("1.30"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 21, 10),
                        null,
                        LunarTideType.SPRING_TIDE, "Full Moon", false,
                        TideStatisticalSize.EXTRA_HIGH))
                .build();
        assertGolden("coastal-tidal", data);
    }

    @Test
    @DisplayName("woodland bluebell: base builder + bluebell block (in season)")
    void woodlandBluebell() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Hardcastle Crags UK")
                .solarEventTime(LocalDateTime.of(2026, 5, 5, 20, 30))
                .targetType(TargetType.SUNSET)
                .bluebellConditionScore(new BluebellConditionScore(
                        8, true, true, true, false, true, false,
                        BluebellExposure.WOODLAND,
                        "Peak flowering, misty and still under the canopy"))
                .build();
        assertGolden("woodland-bluebell", data);
    }

    @Test
    @DisplayName("dark-sky/astro overlook: base builder + orientation + inversion blocks")
    void darkskyInversion() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Kielder UK")
                .solarEventTime(LocalDateTime.of(2026, 6, 21, 4, 30))
                .targetType(TargetType.SUNRISE)
                .locationOrientation("sunrise-optimised")
                .inversionScore(9.0)
                .build();
        assertGolden("darksky-inversion", data);
    }

    @Test
    @DisplayName("coastal with storm surge: coastal builder + tide + surge blocks")
    void coastalSurge() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Saltwick Bay UK")
                .solarEventTime(LocalDateTime.of(2026, 6, 21, 20, 47))
                .targetType(TargetType.SUNSET)
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 21, 10),
                        new BigDecimal("6.20"),
                        LocalDateTime.of(2026, 6, 22, 3, 25),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 21, 10),
                        null,
                        LunarTideType.KING_TIDE, "New Moon", true,
                        TideStatisticalSize.EXTRA_EXTRA_HIGH))
                .surge(new StormSurgeBreakdown(
                        0.18, 0.17, 0.35, 998.0, 14.0, 90.0, 0.85,
                        TideRiskLevel.MODERATE,
                        "Low pressure plus onshore wind raises water level above prediction"))
                .adjustedRangeMetres(5.35)
                .astronomicalRangeMetres(5.00)
                .build();
        assertGolden("coastal-surge", data);
    }

    /**
     * Assembles the prompt for {@code data} through the production seam and compares it
     * to the committed fixture (or writes the fixture when regenerating).
     *
     * @param name fixture base name (no extension)
     * @param data the pinned atmospheric data
     */
    private void assertGolden(String name, AtmosphericData data) {
        String assembled = assemble(data);
        if (Boolean.getBoolean("prompt.golden.regenerate")) {
            writeFixture(name, assembled);
            return;
        }
        String expected = readFixture(name);
        assertThat(assembled)
                .as("Assembled prompt for archetype '%s' differs from golden master "
                        + "src/test/resources/%s/%s.txt. If this change is intentional, "
                        + "regenerate with -Dprompt.golden.regenerate=true and review the diff.",
                        name, FIXTURE_DIR, name)
                .isEqualTo(expected);
    }

    /**
     * Builds the assembled prompt through the real assembly path: select builder by tide
     * presence, pick the surge overload by surge presence (identical to all three live call
     * sites), and concatenate system + user with a stable delimiter.
     */
    private String assemble(AtmosphericData data) {
        PromptBuilder builder = factory.selectBuilder(data);
        String system = builder.getSystemPrompt();
        String user = data.surge() != null
                ? builder.buildUserMessage(data, data.surge(),
                        data.adjustedRangeMetres(), data.astronomicalRangeMetres())
                : builder.buildUserMessage(data);
        return system + SYSTEM_USER_DELIMITER + user;
    }

    private String readFixture(String name) {
        String resource = "/" + FIXTURE_DIR + "/" + name + ".txt";
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                fail("Missing golden-master fixture: src/test/resources" + resource
                        + " — regenerate with -Dprompt.golden.regenerate=true");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixture " + resource, e);
        }
    }

    private void writeFixture(String name, String content) {
        Path path = Path.of("src", "test", "resources", FIXTURE_DIR, name + ".txt");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write fixture " + path, e);
        }
    }
}
