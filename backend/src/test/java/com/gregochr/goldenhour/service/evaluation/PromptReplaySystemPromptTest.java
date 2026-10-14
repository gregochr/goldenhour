package com.gregochr.goldenhour.service.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the system-prompt override logic of {@link PromptReplayHarnessTest} without the API: with no
 * override file the production prompt is returned untouched.
 */
class PromptReplaySystemPromptTest {

    private static final String SUFFIX = CoastalPromptBuilder.COASTAL_SYSTEM_PROMPT_SUFFIX;

    @Test
    void noOverrideReturnsProductionPromptByteIdentical() {
        String inland = new PromptBuilder().getSystemPrompt();
        String coastal = new CoastalPromptBuilder().getSystemPrompt();

        assertThat(PromptReplayHarnessTest.resolveSystemPrompt(inland, null, null)).isSameAs(inland);
        assertThat(PromptReplayHarnessTest.resolveSystemPrompt(coastal, SUFFIX, null)).isSameAs(coastal);
        assertThat(PromptReplayHarnessTest.describeSystemPrompt(null)).isEqualTo("production");
    }

    @Test
    void overrideReplacesInlandPromptVerbatim(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("r1.txt");
        String text = "custom — prompt\nline 2\n";
        Files.writeString(file, text, StandardCharsets.UTF_8);

        assertThat(PromptReplayHarnessTest.resolveSystemPrompt("prod", null, file)).isEqualTo(text);
    }

    @Test
    void coastalOverrideGetsSuffixOnceOnly(@TempDir Path dir) throws Exception {
        Path inlandBased = dir.resolve("a.txt");
        Files.writeString(inlandBased, "base", StandardCharsets.UTF_8);
        Path coastalBased = dir.resolve("b.txt");
        Files.writeString(coastalBased, "base" + SUFFIX, StandardCharsets.UTF_8);

        assertThat(PromptReplayHarnessTest.resolveSystemPrompt("prod", SUFFIX, inlandBased))
                .isEqualTo("base" + SUFFIX);
        assertThat(PromptReplayHarnessTest.resolveSystemPrompt("prod", SUFFIX, coastalBased))
                .isEqualTo("base" + SUFFIX);
    }

    @Test
    void emptyOrMissingOverrideFailsLoudly(@TempDir Path dir) throws Exception {
        Path empty = dir.resolve("empty.txt");
        Files.writeString(empty, "");

        assertThatThrownBy(() -> PromptReplayHarnessTest.resolveSystemPrompt("p", null, empty))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PromptReplayHarnessTest.resolveSystemPrompt("p", null,
                dir.resolve("missing.txt"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void describeNamesFileAndHashPrefix(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("r1.txt");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);

        // SHA-256("abc") starts ba7816bf8f01
        assertThat(PromptReplayHarnessTest.describeSystemPrompt(file))
                .isEqualTo("r1.txt@sha256:ba7816bf8f01");
    }
}
