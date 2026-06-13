package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;

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
 * Golden-master test for the dedicated bluebell prompt ({@link BluebellPromptBuilder}), the new
 * Claude contract Pass 3 introduces.
 *
 * <p>Sibling to {@link PromptGoldenMasterTest} (which guards the sky prompt). This pins the
 * entire assembled bluebell prompt — system + user message — byte-for-byte for the two
 * exposure archetypes, so any wording or structural change fails loudly. The builder is a pure
 * function of {@link AtmosphericData} (no clock, no randomness), so the assembly is fully
 * deterministic from the pinned inputs.
 *
 * <p><b>The new-fixture half of the Pass 3 commit-1 contract review.</b> The
 * {@code PromptGoldenMasterTest} diff shows bluebell <em>leaving</em> the standard prompt; these
 * fixtures show the dedicated bluebell prompt <em>arriving</em>. Together they are the reviewable
 * contract change.
 *
 * <p><b>Phenology honesty is part of the contract.</b> Each fixture is asserted to carry the
 * BLOOM ASSUMPTION caveat and to score conditions, not bloom — guarding against a future edit
 * that lets the prompt imply bloom confirmation it does not have.
 *
 * <p><b>Regenerating fixtures.</b> When the bluebell prompt is intentionally changed, regenerate
 * with {@code ./mvnw test -Dtest=BluebellPromptGoldenMasterTest -Dprompt.golden.regenerate=true}
 * and review the diff before committing.
 */
class BluebellPromptGoldenMasterTest {

    private static final String FIXTURE_DIR = "prompt-golden";
    private static final String SYSTEM_USER_DELIMITER =
            System.lineSeparator() + "========== USER MESSAGE ==========" + System.lineSeparator();

    private final BluebellPromptBuilder builder = new BluebellPromptBuilder();

    @Test
    @DisplayName("woodland: mist + calm + soft light, the 5-star canopy case")
    void woodland() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Hardcastle Crags UK")
                .solarEventTime(LocalDateTime.of(2026, 5, 5, 6, 5))
                .targetType(TargetType.SUNRISE)
                .lowCloud(80).midCloud(50).highCloud(30)
                .visibility(1500)
                .windSpeed(new BigDecimal("1.50"))
                .precipitation(BigDecimal.ZERO)
                .temperature(7.5)
                .dewPoint(7.0)
                .bluebellConditionScore(new BluebellConditionScore(
                        8, true, true, true, false, true, true,
                        BluebellExposure.WOODLAND,
                        "Misty and still — perfect morning conditions"))
                .build();
        assertGolden("bluebell-woodland", data);
    }

    @Test
    @DisplayName("open fell: calm + golden-hour light across an exposed slope")
    void openFell() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationName("Rannerdale Knotts UK")
                .solarEventTime(LocalDateTime.of(2026, 5, 5, 20, 25))
                .targetType(TargetType.SUNSET)
                .lowCloud(10).midCloud(20).highCloud(15)
                .visibility(30000)
                .windSpeed(new BigDecimal("2.00"))
                .precipitation(BigDecimal.ZERO)
                .temperature(12.0)
                .dewPoint(6.0)
                .bluebellConditionScore(new BluebellConditionScore(
                        7, false, true, false, true, false, true,
                        BluebellExposure.OPEN_FELL,
                        "still air, golden hour light"))
                .build();
        assertGolden("bluebell-openfell", data);
    }

    /**
     * Assembles the bluebell prompt and compares it to the committed fixture (or writes it when
     * regenerating), after asserting the phenology-honesty contract holds.
     *
     * @param name fixture base name (no extension)
     * @param data the pinned atmospheric data with a populated bluebell condition score
     */
    private void assertGolden(String name, AtmosphericData data) {
        String assembled = builder.getSystemPrompt() + SYSTEM_USER_DELIMITER
                + builder.buildUserMessage(data);

        // Phenology honesty is a contract requirement, not just fixture text: the prompt must
        // tell Claude it does not know bloom state and to score conditions given assumed bloom.
        assertThat(assembled)
                .as("the bluebell prompt must keep the bloom-assumption caveat")
                .contains("BLOOM ASSUMPTION")
                .contains("CONDITIONS GIVEN ASSUMED BLOOM")
                .contains("never assert it");

        if (Boolean.getBoolean("prompt.golden.regenerate")) {
            writeFixture(name, assembled);
            return;
        }
        assertThat(assembled)
                .as("Assembled bluebell prompt for archetype '%s' differs from golden master "
                        + "src/test/resources/%s/%s.txt. If intentional, regenerate with "
                        + "-Dprompt.golden.regenerate=true and review the diff.",
                        name, FIXTURE_DIR, name)
                .isEqualTo(readFixture(name));
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
