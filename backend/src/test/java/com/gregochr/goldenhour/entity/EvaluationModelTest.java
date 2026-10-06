package com.gregochr.goldenhour.entity;

import com.anthropic.models.messages.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
    @DisplayName("SONNET_55 gets 4096 max tokens (thinking tokens count against the limit)")
    void getMaxTokens_sonnet55_returns4096() {
        assertThat(EvaluationModel.SONNET_55.getMaxTokens()).isEqualTo(4096);
    }

    @Test
    @DisplayName("SONNET_55 is claude-sonnet-5-5 v5.5, not extended thinking, own pricing tier")
    void sonnet55_identity() {
        assertThat(EvaluationModel.SONNET_55.getModelId()).isEqualTo("claude-sonnet-5-5");
        assertThat(EvaluationModel.SONNET_55.getVersion()).isEqualTo("5.5");
        assertThat(EvaluationModel.SONNET_55.isExtendedThinking()).isFalse();
        assertThat(EvaluationModel.SONNET_55.pricingTier())
                .isEqualTo(EvaluationModel.PricingTier.SONNET_55);
    }

    @Test
    @DisplayName("every enum name fits the VARCHAR(10) model columns and round-trips valueOf")
    void names_fitVarchar10_andRoundTrip() {
        for (EvaluationModel model : EvaluationModel.values()) {
            assertThat(model.name().length()).isLessThanOrEqualTo(10);
            assertThat(EvaluationModel.valueOf(model.name())).isEqualTo(model);
        }
        assertThat(EvaluationModel.valueOf("SONNET_55")).isEqualTo(EvaluationModel.SONNET_55);
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

    // ── SDK constants ──

    /** The ids as they were typed by hand, before they were derived from the SDK's constants. */
    private static final Map<EvaluationModel, String> WIRE_IDS = new EnumMap<>(EvaluationModel.class);

    static {
        WIRE_IDS.put(EvaluationModel.HAIKU, "claude-haiku-4-5-20251001");
        WIRE_IDS.put(EvaluationModel.SONNET, "claude-sonnet-4-6");
        WIRE_IDS.put(EvaluationModel.SONNET_ET, "claude-sonnet-4-6");
        WIRE_IDS.put(EvaluationModel.SONNET_55, "claude-sonnet-5-5");
        WIRE_IDS.put(EvaluationModel.OPUS, "claude-opus-4-6");
        WIRE_IDS.put(EvaluationModel.OPUS_ET, "claude-opus-4-6");
        WIRE_IDS.put(EvaluationModel.WILDLIFE, null);
    }

    @Test
    @DisplayName("every model id is identical to the literal the app sent before the SDK constants")
    void getModelId_everyModel_matchesTheLiteralOnTheWire() {
        assertThat(WIRE_IDS).containsOnlyKeys(EvaluationModel.values());
        for (EvaluationModel model : EvaluationModel.values()) {
            assertThat(model.getModelId()).as(model.name()).isEqualTo(WIRE_IDS.get(model));
        }
    }

    @Test
    @DisplayName("every id the app sends is a model the SDK knows, and none of them is deprecated")
    void getModelId_everyModel_isAnUndeprecatedSdkConstant() {
        for (EvaluationModel model : EvaluationModel.values()) {
            if (model.getModelId() == null) {
                continue;
            }
            List<Field> constants = sdkConstantsFor(model.getModelId());
            assertThat(constants).as("%s: no SDK constant has the id %s", model, model.getModelId()).isNotEmpty();
            for (Field constant : constants) {
                assertThat(isDeprecated(constant))
                        .as("%s uses %s, which the SDK has deprecated: move to a live model", model, constant.getName())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("the deprecation check reads the Deprecated attribute off a field's class file")
    void isDeprecated_readsTheAttribute()throws NoSuchFieldException {
        assertThat(isDeprecated(DeprecationFixture.class.getDeclaredField("OLD"))).isTrue();
        assertThat(isDeprecated(DeprecationFixture.class.getDeclaredField("LIVE"))).isFalse();
    }

    @Test
    @DisplayName("the SDK marks Claude Sonnet 4.5 deprecated, which is what the check exists to catch")
    void sdkConstants_sonnet45_isDeprecated() {
        List<Field> constants = sdkConstantsFor("claude-sonnet-4-5-20250929");
        assertThat(constants).isNotEmpty();
        assertThat(constants).allMatch(EvaluationModelTest::isDeprecated);
    }

    /** Every public static {@link Model} constant on the SDK's class whose id equals {@code id}. */
    private static List<Field> sdkConstantsFor(String id) {
        List<Field> matches = new ArrayList<>();
        for (Field field : Model.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Model.class
                    && id.equals(valueOf(field).asString())) {
                matches.add(field);
            }
        }
        return matches;
    }

    private static Model valueOf(Field constant) {
        try {
            return (Model) constant.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(constant.getName() + " is not readable", e);
        }
    }

    /**
     * Whether the field's class file carries the {@code Deprecated} attribute. Reflection cannot see
     * it: the SDK is Kotlin, which marks a deprecated constant with that attribute and with no
     * runtime-visible {@code java.lang.Deprecated} annotation, so {@code isAnnotationPresent} answers
     * false for a constant the SDK has retired. The class file is read with the JDK's own
     * {@code java.lang.classfile} API.
     */
    private static boolean isDeprecated(Field field) {
        Class<?> owner = field.getDeclaringClass();
        String resource = "/" + owner.getName().replace('.', '/') + ".class";
        try (InputStream in = owner.getResourceAsStream(resource)) {
            assertThat(in).as("class file for %s", owner.getName()).isNotNull();
            ClassModel classModel = ClassFile.of().parse(in.readAllBytes());
            return classModel.fields().stream()
                    .filter(f -> f.fieldName().equalsString(field.getName()))
                    .anyMatch(f -> f.findAttribute(Attributes.deprecated()).isPresent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stands in for the SDK's constants, so the deprecation check is proven without depending on them. */
    private static final class DeprecationFixture {
        @Deprecated
        static final String OLD = "old";
        static final String LIVE = "live";

        private DeprecationFixture() {
        }
    }
}
