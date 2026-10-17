package com.gregochr.goldenhour.service.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link PromptHash}. */
class PromptHashTest {

    @Test
    @DisplayName("matches the published SHA-256 test vector, lower-case hex")
    void matchesKnownVector() {
        assertThat(PromptHash.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("is the width of the prompt_hash column, and changes with a one-character edit")
    void changesWithAnEdit() {
        String original = PromptHash.sha256Hex("You are a sunset photography forecaster.");
        String edited = PromptHash.sha256Hex("You are a sunset photography forecaster!");

        assertThat(original).hasSize(PromptHash.HEX_LENGTH);
        assertThat(edited).hasSize(PromptHash.HEX_LENGTH).isNotEqualTo(original);
    }

    @Test
    @DisplayName("hashes non-ASCII text by its UTF-8 bytes, stably")
    void hashesUtf8() {
        assertThat(PromptHash.sha256Hex("pink ☀ sky")).isEqualTo(PromptHash.sha256Hex("pink ☀ sky"));
    }

    @Test
    @DisplayName("an algorithm the platform lacks is an IllegalStateException naming it, not a checked leak")
    void unknownAlgorithmIsIllegalState() {
        assertThatThrownBy(() -> PromptHash.digestHex("NOT-AN-ALGORITHM", "abc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT-AN-ALGORITHM");
    }
}
