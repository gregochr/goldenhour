package com.gregochr.goldenhour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EvaluationModel}.
 */
class EvaluationModelTest {

    // ── isExtendedThinking ──

    @Test
    @DisplayName("SONNET_ET returns true for isExtendedThinking")
    void isExtendedThinking_sonnetEt_returnsTrue() {
        assertThat(EvaluationModel.SONNET_ET.isExtendedThinking()).isTrue();
    }

    @Test
    @DisplayName("OPUS_ET returns true for isExtendedThinking")
    void isExtendedThinking_opusEt_returnsTrue() {
        assertThat(EvaluationModel.OPUS_ET.isExtendedThinking()).isTrue();
    }

    @Test
    @DisplayName("HAIKU returns false for isExtendedThinking")
    void isExtendedThinking_haiku_returnsFalse() {
        assertThat(EvaluationModel.HAIKU.isExtendedThinking()).isFalse();
    }

    @Test
    @DisplayName("SONNET returns false for isExtendedThinking")
    void isExtendedThinking_sonnet_returnsFalse() {
        assertThat(EvaluationModel.SONNET.isExtendedThinking()).isFalse();
    }

    @Test
    @DisplayName("OPUS returns false for isExtendedThinking")
    void isExtendedThinking_opus_returnsFalse() {
        assertThat(EvaluationModel.OPUS.isExtendedThinking()).isFalse();
    }

    @Test
    @DisplayName("WILDLIFE returns false for isExtendedThinking")
    void isExtendedThinking_wildlife_returnsFalse() {
        assertThat(EvaluationModel.WILDLIFE.isExtendedThinking()).isFalse();
    }

    // ── getModelId ──

    @Test
    @DisplayName("HAIKU model ID includes full dated version string")
    void getModelId_haiku_returnsDatedString() {
        assertThat(EvaluationModel.HAIKU.getModelId()).isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    @DisplayName("SONNET and SONNET_ET share the same model ID")
    void getModelId_sonnetAndSonnetEt_areIdentical() {
        assertThat(EvaluationModel.SONNET_ET.getModelId())
                .isEqualTo(EvaluationModel.SONNET.getModelId());
    }

    @Test
    @DisplayName("OPUS and OPUS_ET share the same model ID")
    void getModelId_opusAndOpusEt_areIdentical() {
        assertThat(EvaluationModel.OPUS_ET.getModelId())
                .isEqualTo(EvaluationModel.OPUS.getModelId());
    }

    @Test
    @DisplayName("SONNET model ID is claude-sonnet-4-6")
    void getModelId_sonnet_isCorrect() {
        assertThat(EvaluationModel.SONNET.getModelId()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    @DisplayName("OPUS model ID is claude-opus-4-6")
    void getModelId_opus_isCorrect() {
        assertThat(EvaluationModel.OPUS.getModelId()).isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("WILDLIFE has null model ID and null version")
    void getModelId_wildlife_isNull() {
        assertThat(EvaluationModel.WILDLIFE.getModelId()).isNull();
        assertThat(EvaluationModel.WILDLIFE.getVersion()).isNull();
    }

    // ── getMaxTokens ──

    @Test
    @DisplayName("SONNET gets 1024 max tokens (extra budget for chain-of-thought)")
    void getMaxTokens_sonnet_returns1024() {
        assertThat(EvaluationModel.SONNET.getMaxTokens()).isEqualTo(1024);
    }

    @Test
    @DisplayName("SONNET_ET also gets 1024 max tokens")
    void getMaxTokens_sonnetEt_returns1024() {
        assertThat(EvaluationModel.SONNET_ET.getMaxTokens()).isEqualTo(1024);
    }

    @Test
    @DisplayName("HAIKU gets 512 max tokens")
    void getMaxTokens_haiku_returns512() {
        assertThat(EvaluationModel.HAIKU.getMaxTokens()).isEqualTo(512);
    }

    @Test
    @DisplayName("OPUS gets 512 max tokens")
    void getMaxTokens_opus_returns512() {
        assertThat(EvaluationModel.OPUS.getMaxTokens()).isEqualTo(512);
    }

    @Test
    @DisplayName("OPUS_ET gets 512 max tokens")
    void getMaxTokens_opusEt_returns512() {
        assertThat(EvaluationModel.OPUS_ET.getMaxTokens()).isEqualTo(512);
    }

    @Test
    @DisplayName("WILDLIFE gets 512 max tokens")
    void getMaxTokens_wildlife_returns512() {
        assertThat(EvaluationModel.WILDLIFE.getMaxTokens()).isEqualTo(512);
    }
}
