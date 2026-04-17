package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.PressureTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.OutputConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static com.gregochr.goldenhour.service.evaluation.PromptBuilder.InversionPotential;

/**
 * Unit tests for {@link PromptBuilder}.
 */
public class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Test
    @DisplayName("getSystemPrompt contains rating scale")
    void getSystemPrompt_containsRatingScale() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt).contains("1\u20135").contains("rating");
    }

    @Test
    @DisplayName("getSystemPrompt does not contain tide guidance (moved to CoastalPromptBuilder)")
    void getSystemPrompt_omitsTideGuidance() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt).doesNotContain("COASTAL TIDE GUIDANCE");
        assertThat(prompt).doesNotContain("tide data may be provided");
    }

    @Test
    @DisplayName("getSystemPrompt contains dual score fields")
    void getSystemPrompt_containsDualScoreFields() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt).contains("fiery_sky").contains("golden_hour");
    }

    @Test
    @DisplayName("getPromptSuffix requests exactly one sentence summary")
    void getPromptSuffix_containsOneSentenceInstruction() {
        String suffix = promptBuilder.getPromptSuffix();

        assertThat(suffix)
                .contains("Rate 1-5")
                .contains("Fiery Sky Potential")
                .contains("Golden Hour Potential")
                .contains("exactly one sentence");
    }

    @Test
    @DisplayName("system prompt enforces single-sentence summary constraint")
    void getSystemPrompt_enforcesOneSentenceSummary() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt).contains("exactly one sentence");
        assertThat(prompt).contains("Do not write two sentences");
    }

    @Test
    @DisplayName("system prompt still contains all schema fields")
    void getSystemPrompt_containsAllSchemaFields() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt)
                .contains("rating")
                .contains("fiery_sky")
                .contains("golden_hour")
                .contains("summary")
                .contains("basic_fiery_sky")
                .contains("basic_golden_hour")
                .contains("basic_summary");
    }

    @Test
    @DisplayName("buildUserMessage contains location data")
    void buildUserMessage_containsLocationData() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Durham UK")
                .contains("SUNSET")
                .contains("Low 10%")
                .contains("Mid 50%")
                .contains("High 30%");
    }

    @Test
    @DisplayName("buildUserMessage contains prompt suffix")
    void buildUserMessage_containsPromptSuffix() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).endsWith(PromptBuilder.PROMPT_SUFFIX);
    }

    @Test
    @DisplayName("buildUserMessage with high AOD includes dust context")
    void buildUserMessage_highAod_includesDustContext() {
        AtmosphericData data = TestAtmosphericData.builder()
                .aod(new BigDecimal("0.50"))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("SAHARAN DUST CONTEXT:")
                .contains("AOD: 0.50", "(elevated)")
                .contains("SW")
                .contains("maximises warm scattering potential");
    }

    @Test
    @DisplayName("buildUserMessage with high dust includes dust context")
    void buildUserMessage_highDust_includesDustContext() {
        AtmosphericData data = TestAtmosphericData.builder()
                .dust(new BigDecimal("65.00"))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("SAHARAN DUST CONTEXT:")
                .contains("Surface dust: 65.00");
    }

    @Test
    @DisplayName("buildUserMessage with low aerosols omits dust context")
    void buildUserMessage_lowAerosols_noDustContext() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("SAHARAN DUST CONTEXT:");
    }

    @Test
    @DisplayName("buildUserMessage with directional cloud includes directional block")
    void buildUserMessage_withDirectionalCloud_includesDirectionalBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(65, 20, 10, 5, 45, 30, null))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("DIRECTIONAL CLOUD (113km sample):")
                .contains("Solar horizon (toward sun): Low 65%, Mid 20%, High 10%")
                .contains("Antisolar horizon (away from sun): Low 5%, Mid 45%, High 30%");
    }

    @Test
    @DisplayName("buildUserMessage without directional cloud omits directional block")
    void buildUserMessage_withoutDirectionalCloud_omitsDirectionalBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("DIRECTIONAL CLOUD");
    }

    @Test
    @DisplayName("buildUserMessage without tide omits tide block")
    void buildUserMessage_withoutTide_omitsTideBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("Tide:");
    }

    @Test
    @DisplayName("buildUserMessage with tide data on base builder still omits tide block")
    void buildUserMessage_withTideData_baseBuilder_omitsTideBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .tide(new TideSnapshot(
                        TideState.HIGH,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        new BigDecimal("4.50"),
                        LocalDateTime.of(2026, 6, 22, 0, 45),
                        new BigDecimal("1.20"),
                        true,
                        LocalDateTime.of(2026, 6, 21, 18, 30),
                        null, null, null, null, null))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("Tide:");
    }

    @Test
    @DisplayName("toCardinal converts degrees to 16-point compass correctly")
    void toCardinal_convertsCorrectly() {
        assertThat(PromptBuilder.toCardinal(0)).isEqualTo("N");
        assertThat(PromptBuilder.toCardinal(45)).isEqualTo("NE");
        assertThat(PromptBuilder.toCardinal(90)).isEqualTo("E");
        assertThat(PromptBuilder.toCardinal(135)).isEqualTo("SE");
        assertThat(PromptBuilder.toCardinal(180)).isEqualTo("S");
        assertThat(PromptBuilder.toCardinal(225)).isEqualTo("SW");
        assertThat(PromptBuilder.toCardinal(270)).isEqualTo("W");
        assertThat(PromptBuilder.toCardinal(315)).isEqualTo("NW");
        assertThat(PromptBuilder.toCardinal(360)).isEqualTo("N");
        assertThat(PromptBuilder.toCardinal(22)).isEqualTo("NNE");
        assertThat(PromptBuilder.toCardinal(202)).isEqualTo("SSW");
    }

    @Test
    @DisplayName("isDustElevated returns true for high AOD")
    void isDustElevated_highAod_returnsTrue() {
        AerosolData aerosol = new AerosolData(
                new BigDecimal("8.50"), new BigDecimal("2.10"),
                new BigDecimal("0.50"), 1200);

        assertThat(PromptBuilder.isDustElevated(aerosol)).isTrue();
    }

    @Test
    @DisplayName("isDustElevated returns true for high dust")
    void isDustElevated_highDust_returnsTrue() {
        AerosolData aerosol = new AerosolData(
                new BigDecimal("8.50"), new BigDecimal("65.00"),
                new BigDecimal("0.10"), 1200);

        assertThat(PromptBuilder.isDustElevated(aerosol)).isTrue();
    }

    @Test
    @DisplayName("isDustElevated returns false for low aerosols")
    void isDustElevated_lowBoth_returnsFalse() {
        AerosolData aerosol = new AerosolData(
                new BigDecimal("8.50"), new BigDecimal("2.10"),
                new BigDecimal("0.12"), 1200);

        assertThat(PromptBuilder.isDustElevated(aerosol)).isFalse();
    }

    @Nested
    @DisplayName("isDustElevated boundary values")
    class IsDustElevatedBoundaryTests {

        @Test
        @DisplayName("AOD exactly 0.30 is NOT elevated (threshold is >0.3)")
        void aodAtBoundary_notElevated() {
            AerosolData aerosol = new AerosolData(
                    BigDecimal.TEN, BigDecimal.TWO,
                    new BigDecimal("0.30"), 1200);
            assertThat(PromptBuilder.isDustElevated(aerosol)).isFalse();
        }

        @Test
        @DisplayName("AOD 0.31 IS elevated (one step above threshold)")
        void aodJustAboveBoundary_elevated() {
            AerosolData aerosol = new AerosolData(
                    BigDecimal.TEN, BigDecimal.TWO,
                    new BigDecimal("0.31"), 1200);
            assertThat(PromptBuilder.isDustElevated(aerosol)).isTrue();
        }

        @Test
        @DisplayName("Dust exactly 50.0 ug/m3 is NOT elevated (threshold is >50)")
        void dustAtBoundary_notElevated() {
            AerosolData aerosol = new AerosolData(
                    BigDecimal.TEN, new BigDecimal("50.00"),
                    new BigDecimal("0.10"), 1200);
            assertThat(PromptBuilder.isDustElevated(aerosol)).isFalse();
        }

        @Test
        @DisplayName("Dust 50.01 ug/m3 IS elevated (one step above threshold)")
        void dustJustAboveBoundary_elevated() {
            AerosolData aerosol = new AerosolData(
                    BigDecimal.TEN, new BigDecimal("50.01"),
                    new BigDecimal("0.10"), 1200);
            assertThat(PromptBuilder.isDustElevated(aerosol)).isTrue();
        }

        @Test
        @DisplayName("Both AOD and dust at boundary — not elevated")
        void bothAtBoundary_notElevated() {
            AerosolData aerosol = new AerosolData(
                    BigDecimal.TEN, new BigDecimal("50.00"),
                    new BigDecimal("0.30"), 1200);
            assertThat(PromptBuilder.isDustElevated(aerosol)).isFalse();
        }

        @Test
        @DisplayName("Null AOD with high dust still elevated")
        void nullAodHighDust_elevated() {
            AerosolData aerosol = new AerosolData(
                    BigDecimal.TEN, new BigDecimal("65.00"),
                    null, 1200);
            assertThat(PromptBuilder.isDustElevated(aerosol)).isTrue();
        }

        @Test
        @DisplayName("Null dust with high AOD still elevated")
        void nullDustHighAod_elevated() {
            AerosolData aerosol = new AerosolData(
                    BigDecimal.TEN, null,
                    new BigDecimal("0.50"), 1200);
            assertThat(PromptBuilder.isDustElevated(aerosol)).isTrue();
        }

        @Test
        @DisplayName("Both AOD and dust null — not elevated")
        void bothNull_notElevated() {
            AerosolData aerosol = new AerosolData(
                    BigDecimal.TEN, null, null, 1200);
            assertThat(PromptBuilder.isDustElevated(aerosol)).isFalse();
        }
    }

    @Nested
    @DisplayName("buildOutputConfig schema")
    class OutputConfigTests {

        @Test
        @DisplayName("Output config is non-null")
        void outputConfig_nonNull() {
            assertThat(promptBuilder.buildOutputConfig()).isNotNull();
        }

        @Test
        @DisplayName("Output config JSON contains required schema fields")
        void outputConfig_containsRequiredFields() {
            var config = promptBuilder.buildOutputConfig();
            String configStr = config.toString();

            assertThat(configStr).contains("rating");
            assertThat(configStr).contains("fiery_sky");
            assertThat(configStr).contains("golden_hour");
            assertThat(configStr).contains("summary");
        }

        @Test
        @DisplayName("Output config JSON contains dual-tier fields")
        void outputConfig_containsDualTierFields() {
            var config = promptBuilder.buildOutputConfig();
            String configStr = config.toString();

            assertThat(configStr).contains("basic_fiery_sky");
            assertThat(configStr).contains("basic_golden_hour");
            assertThat(configStr).contains("basic_summary");
        }

        @Test
        @DisplayName("Output config JSON contains inversion fields")
        void outputConfig_containsInversionFields() {
            var config = promptBuilder.buildOutputConfig();
            String configStr = config.toString();

            assertThat(configStr).contains("inversion_score");
            assertThat(configStr).contains("inversion_potential");
        }

        @Test
        @DisplayName("Output config JSON contains bluebell fields")
        void outputConfig_containsBluebellFields() {
            var config = promptBuilder.buildOutputConfig();
            String configStr = config.toString();

            assertThat(configStr).contains("bluebell_score");
            assertThat(configStr).contains("bluebell_summary");
        }
    }

    // ── Schema guard tests ──────────────────────────────────────────────────
    //
    // These tests validate the output schema against Anthropic's structured
    // output requirements. They prevent future schema changes from breaking
    // batch submissions.

    @Nested
    @DisplayName("Output schema guard tests — Anthropic structured output compliance")
    @SuppressWarnings("unchecked")
    class OutputSchemaGuardTests {

        private static final Set<String> VALID_JSON_SCHEMA_TYPES =
                Set.of("string", "integer", "number", "boolean", "array", "object");

        private Map<String, JsonValue> extractSchema() {
            OutputConfig config = promptBuilder.buildOutputConfig();
            JsonOutputFormat format = config.format().orElseThrow(
                    () -> new AssertionError("OutputConfig.format() is empty"));
            return format.schema()._additionalProperties();
        }

        private Map<String, JsonValue> extractProperties(
                Map<String, JsonValue> schema) {
            var opt = schema.get("properties").asObject();
            assertThat(opt.isPresent())
                    .as("Schema 'properties' must be an object").isTrue();
            return (Map<String, JsonValue>) opt.get();
        }

        /**
         * Recursively walks all nodes in the schema. For each node that is
         * {@code "type":"object"}, invokes the visitor with the path and
         * node map.
         */
        private void walkObjects(Map<String, JsonValue> node, String path,
                ObjectNodeVisitor visitor) {
            var typeVal = node.get("type");
            String typeStr = (String) typeVal.asString().orElse("");
            if ("object".equals(typeStr)) {
                visitor.visit(path, node);
                var propsVal = node.get("properties");
                if (propsVal != null) {
                    var propsOpt = propsVal.asObject();
                    if (propsOpt.isPresent()) {
                        Map<String, JsonValue> props =
                                (Map<String, JsonValue>) propsOpt.get();
                        for (var entry : props.entrySet()) {
                            var childOpt = entry.getValue().asObject();
                            if (childOpt.isPresent()) {
                                Map<String, JsonValue> child =
                                        (Map<String, JsonValue>)
                                                childOpt.get();
                                walkObjects(child,
                                        path + "." + entry.getKey(),
                                        visitor);
                            }
                        }
                    }
                }
            }
        }

        /**
         * Walks all property definitions, invoking the visitor with each
         * property name and its schema map.
         */
        private void walkProperties(Map<String, JsonValue> schema,
                PropertyVisitor visitor) {
            var propsVal = schema.get("properties");
            if (propsVal == null) {
                return;
            }
            var propsOpt = propsVal.asObject();
            if (propsOpt.isPresent()) {
                Map<String, JsonValue> props =
                        (Map<String, JsonValue>) propsOpt.get();
                for (var entry : props.entrySet()) {
                    var childOpt = entry.getValue().asObject();
                    if (childOpt.isPresent()) {
                        Map<String, JsonValue> propSchema =
                                (Map<String, JsonValue>) childOpt.get();
                        visitor.visit(entry.getKey(), propSchema);
                    }
                }
            }
        }

        @FunctionalInterface
        interface ObjectNodeVisitor {
            /** Called for each {@code "type":"object"} node. */
            void visit(String path, Map<String, JsonValue> node);
        }

        @FunctionalInterface
        interface PropertyVisitor {
            /** Called for each property name and schema definition. */
            void visit(String name, Map<String, JsonValue> propSchema);
        }

        @Test
        @DisplayName("Top-level schema type must be 'object'")
        void outputConfig_topLevelMustBeObject() {
            Map<String, JsonValue> schema = extractSchema();
            String type = (String) schema.get("type").asString()
                    .orElse(null);

            assertThat(type)
                    .as("Top-level schema must be type 'object'")
                    .isEqualTo("object");
        }

        @Test
        @DisplayName("All object types must have additionalProperties: false")
        void outputConfig_allObjectTypesMustHaveAdditionalPropertiesFalse() {
            Map<String, JsonValue> schema = extractSchema();
            List<String> violations = new ArrayList<>();

            walkObjects(schema, "$", (path, node) -> {
                var addlProps = node.get("additionalProperties");
                if (addlProps == null) {
                    violations.add(path
                            + ": missing 'additionalProperties'");
                } else {
                    Boolean val = (Boolean) addlProps.asBoolean()
                            .orElse(null);
                    if (!Boolean.FALSE.equals(val)) {
                        violations.add(path
                                + ": 'additionalProperties' must be "
                                + "false, was " + val);
                    }
                }
            });

            assertThat(violations)
                    .as("Every 'type':'object' node must have "
                            + "'additionalProperties': false — "
                            + "Anthropic rejects schemas without it")
                    .isEmpty();
        }

        @Test
        @DisplayName("All required fields must exist in properties")
        void outputConfig_allRequiredFieldsMustExistInProperties() {
            Map<String, JsonValue> schema = extractSchema();
            Map<String, JsonValue> props = extractProperties(schema);

            var reqOpt = schema.get("required").asArray();
            assertThat(reqOpt.isPresent())
                    .as("'required' must be an array").isTrue();
            List<JsonValue> required = (List<JsonValue>) reqOpt.get();

            for (JsonValue reqField : required) {
                var nameOpt = reqField.asString();
                assertThat(nameOpt.isPresent())
                        .as("required entry must be a string: %s",
                                reqField).isTrue();
                String fieldName = (String) nameOpt.get();
                assertThat(props)
                        .as("Required field '%s' not found in properties",
                                fieldName)
                        .containsKey(fieldName);
            }
        }

        @Test
        @DisplayName("All property type values must be valid JSON Schema types")
        void outputConfig_allTypeValuesMustBeValidJsonSchemaTypes() {
            Map<String, JsonValue> schema = extractSchema();
            List<String> violations = new ArrayList<>();

            walkProperties(schema, (name, propSchema) -> {
                var typeVal = propSchema.get("type");
                if (typeVal != null) {
                    String typeStr = (String) typeVal.asString()
                            .orElse(null);
                    if (typeStr != null
                            && !VALID_JSON_SCHEMA_TYPES.contains(typeStr)) {
                        violations.add("Property '" + name
                                + "' has invalid type '" + typeStr + "'");
                    }
                }
            });

            assertThat(violations)
                    .as("All property types must be one of: "
                            + VALID_JSON_SCHEMA_TYPES)
                    .isEmpty();
        }

        @Test
        @DisplayName("Enum values must be non-empty")
        void outputConfig_enumValuesMustBeNonEmpty() {
            Map<String, JsonValue> schema = extractSchema();
            List<String> violations = new ArrayList<>();

            walkProperties(schema, (name, propSchema) -> {
                var enumVal = propSchema.get("enum");
                if (enumVal != null) {
                    List<JsonValue> enumList =
                            (List<JsonValue>) enumVal.asArray()
                                    .orElse(List.of());
                    if (enumList.isEmpty()) {
                        violations.add("Property '" + name
                                + "' has empty enum list");
                    }
                }
            });

            assertThat(violations)
                    .as("Enum properties must have at least one value")
                    .isEmpty();
        }

        @Test
        @DisplayName("Enum types must match declared type")
        void outputConfig_enumTypesMustMatchDeclaredType() {
            Map<String, JsonValue> schema = extractSchema();
            List<String> violations = new ArrayList<>();

            walkProperties(schema, (name, propSchema) -> {
                var typeVal = propSchema.get("type");
                var enumVal = propSchema.get("enum");
                if (typeVal == null || enumVal == null) {
                    return;
                }
                String declaredType = (String) typeVal.asString()
                        .orElse(null);
                List<JsonValue> enumList =
                        (List<JsonValue>) enumVal.asArray()
                                .orElse(List.of());

                for (JsonValue v : enumList) {
                    boolean matches = switch (declaredType) {
                        case "string" -> v.asString().isPresent();
                        case "integer", "number" ->
                                v.asNumber().isPresent();
                        case "boolean" -> v.asBoolean().isPresent();
                        default -> true;
                    };
                    if (!matches) {
                        violations.add("Property '" + name
                                + "' has type '" + declaredType
                                + "' but enum contains non-"
                                + declaredType + " value: " + v);
                    }
                }
            });

            assertThat(violations)
                    .as("Enum values must match the declared property type")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("buildUserMessage with cloud approach data includes CLOUD APPROACH RISK block")
    void buildUserMessage_withCloudApproach_includesCloudApproachBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .cloudApproach(new CloudApproachData(
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 5),
                                new SolarCloudTrend.SolarCloudSlot(2, 15),
                                new SolarCloudTrend.SolarCloudSlot(1, 35),
                                new SolarCloudTrend.SolarCloudSlot(0, 7))),
                        new UpwindCloudSample(87, 228, 70, 15)))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD APPROACH RISK:")
                .contains("Solar horizon low cloud trend (113km):")
                .contains("T-3h=5%")
                .contains("event=7%")
                .contains("[BUILDING]")
                .contains("Upwind sample (87km along 228\u00b0 SW): current=70%, at-event=15%");
    }

    @Test
    @DisplayName("buildUserMessage without cloud approach data omits CLOUD APPROACH RISK block")
    void buildUserMessage_withoutCloudApproach_omitsBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("CLOUD APPROACH RISK");
    }

    @Test
    @DisplayName("buildUserMessage with non-building trend omits BUILDING label")
    void buildUserMessage_nonBuildingTrend_omitsBuildingLabel() {
        AtmosphericData data = TestAtmosphericData.builder()
                .cloudApproach(new CloudApproachData(
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 50),
                                new SolarCloudTrend.SolarCloudSlot(2, 40),
                                new SolarCloudTrend.SolarCloudSlot(1, 20),
                                new SolarCloudTrend.SolarCloudSlot(0, 10))),
                        null))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD APPROACH RISK:")
                .contains("Solar horizon low cloud trend (113km):")
                .doesNotContain("[BUILDING]");
    }

    @Test
    @DisplayName("buildUserMessage with upwind only (no trend) shows upwind block")
    void buildUserMessage_upwindOnly_showsUpwindBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .cloudApproach(new CloudApproachData(
                        null,
                        new UpwindCloudSample(120, 180, 65, 20)))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD APPROACH RISK:")
                .doesNotContain("Solar horizon low cloud trend")
                .contains("Upwind sample (120km along 180\u00b0 S): current=65%, at-event=20%");
    }

    @Test
    @DisplayName("getSystemPrompt contains cloud approach risk guidance")
    void getSystemPrompt_containsCloudApproachGuidance() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt)
                .contains("CLOUD APPROACH RISK:")
                .contains("Solar trend")
                .contains("Upwind sample");
    }

    @Test
    @DisplayName("buildUserMessage with dew point includes dew point and gap in weather block")
    void buildUserMessage_withDewPoint_includesDewPointAndGap() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(5.8)
                .dewPoint(2.2)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Dew point:")
                .contains("2.2")
                .contains("gap");
    }

    @Test
    @DisplayName("buildUserMessage without dew point shows N/A")
    void buildUserMessage_noDewPoint_showsNa() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).contains("Dew point: N/A");
    }

    @Test
    @DisplayName("buildUserMessage with mist trend includes mist trend block")
    void buildUserMessage_withMistTrend_includesMistBlock() {
        MistTrend mistTrend = new MistTrend(java.util.List.of(
                new MistTrend.MistSlot(-2, 15000, 1.0, 6.0),
                new MistTrend.MistSlot(-1, 8000, 2.0, 4.0),
                new MistTrend.MistSlot(0, 4200, 2.2, 3.8)
        ));
        AtmosphericData data = TestAtmosphericData.builder()
                .mistTrend(mistTrend)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("MIST/VISIBILITY TREND")
                .contains("T-2h")
                .contains("T-1h")
                .contains("event")
                .contains("4,200m")
                .contains("gap=");
    }

    @Test
    @DisplayName("buildUserMessage near-dew-point slot gets [NEAR DEW POINT] label")
    void buildUserMessage_nearDewPointSlot_getsLabel() {
        MistTrend mistTrend = new MistTrend(java.util.List.of(
                new MistTrend.MistSlot(0, 3000, 5.8, 6.5) // gap = 0.7°C → AT/NEAR DEW POINT
        ));
        AtmosphericData data = TestAtmosphericData.builder()
                .mistTrend(mistTrend)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).contains("[AT/NEAR DEW POINT]");
    }

    @Test
    @DisplayName("buildUserMessage without mist trend omits mist block")
    void buildUserMessage_noMistTrend_omitsMistBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("MIST/VISIBILITY TREND");
    }

    @Test
    @DisplayName("buildUserMessage with location orientation includes orientation line")
    void buildUserMessage_withOrientation_includesOrientationLine() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationOrientation("sunrise-optimised")
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Location orientation: sunrise-optimised")
                .contains("best suited for sunrise photography");
    }

    @Test
    @DisplayName("buildUserMessage with sunset orientation includes sunset orientation line")
    void buildUserMessage_withSunsetOrientation_includesSunsetOrientationLine() {
        AtmosphericData data = TestAtmosphericData.builder()
                .locationOrientation("sunset-optimised")
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Location orientation: sunset-optimised")
                .contains("best suited for sunset photography");
    }

    @Test
    @DisplayName("buildUserMessage without location orientation omits orientation line")
    void buildUserMessage_noOrientation_omitsOrientationLine() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("Location orientation:");
    }

    @Test
    @DisplayName("getSystemPrompt contains location orientation guidance")
    void getSystemPrompt_containsOrientationGuidance() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt)
                .contains("LOCATION ORIENTATION")
                .contains("sunrise-optimised")
                .contains("sunset-optimised")
                .contains("Reduce fiery_sky by 10-20");
    }

    @Test
    @DisplayName("getSystemPrompt contains mist and visibility guidance")
    void getSystemPrompt_containsMistGuidance() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt)
                .contains("MIST AND VISIBILITY GUIDANCE")
                .contains("temp-dew gap")
                .contains("SUNRISE SPECIFIC")
                .contains("SUNSET SPECIFIC");
    }


    @Test
    @DisplayName("buildUserMessage with thick mid cloud (>80%) includes annotation")
    void buildUserMessage_thickMidCloud_includesAnnotation() {
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(30, 85, 10, 5, 45, 30, null))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).contains("[THICK MID CLOUD — rate 4, not 5]");
    }

    @Test
    @DisplayName("buildUserMessage with thin strip detection includes THIN STRIP annotation")
    void buildUserMessage_thinStrip_includesAnnotation() {
        // Solar low cloud 60%, far solar 25% → drop of 35pp ≥ 30 → thin strip
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(60, 20, 10, 5, 45, 30, 25))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Beyond horizon (226km, solar azimuth): Low 25%")
                .contains("[THIN STRIP — soften low-cloud penalty]");
    }

    @Test
    @DisplayName("buildUserMessage with extensive blanket includes BLANKET annotation")
    void buildUserMessage_extensiveBlanket_includesAnnotation() {
        // Solar low cloud 70%, far solar 65% → both ≥ 50 → extensive blanket
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(70, 20, 10, 5, 45, 30, 65))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Beyond horizon (226km, solar azimuth): Low 65%")
                .contains("[EXTENSIVE BLANKET — full penalty applies]");
    }

    @Test
    @DisplayName("buildUserMessage far solar with no thin strip or blanket omits annotation")
    void buildUserMessage_farSolarNeitherThinStripNorBlanket_noAnnotation() {
        // Solar low cloud 40% (< 50) → neither condition triggers
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(new DirectionalCloudData(40, 20, 10, 5, 45, 30, 10))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Beyond horizon (226km, solar azimuth): Low 10%")
                .doesNotContain("[THIN STRIP")
                .doesNotContain("[EXTENSIVE BLANKET");
    }

    @Test
    @DisplayName("buildUserMessage BUILDING + thin strip confirmed uses priority annotation")
    void buildUserMessage_buildingWithThinStrip_usesPriorityAnnotation() {
        // Thin strip: solar 60%, far 25% (drop 35pp ≥ 30) + building trend
        DirectionalCloudData dc = new DirectionalCloudData(60, 20, 10, 5, 45, 30, 25);
        AtmosphericData data = TestAtmosphericData.builder()
                .directionalCloud(dc)
                .cloudApproach(new CloudApproachData(
                        new SolarCloudTrend(List.of(
                                new SolarCloudTrend.SolarCloudSlot(3, 5),
                                new SolarCloudTrend.SolarCloudSlot(2, 15),
                                new SolarCloudTrend.SolarCloudSlot(1, 35),
                                new SolarCloudTrend.SolarCloudSlot(0, 60))),
                        null))
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("[BUILDING — but THIN STRIP CONFIRMED at event time")
                .doesNotContain("[BUILDING]");
    }

    @Test
    @DisplayName("buildUserMessage with dew point but null temperature shows NaN gap")
    void buildUserMessage_dewPointWithNullTemperature_showsNanGap() {
        AtmosphericData data = TestAtmosphericData.builder()
                .temperature(null)
                .dewPoint(3.5)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).contains("3.5").contains("NaN");
    }

    @Test
    @DisplayName("buildUserMessage mist trend with positive hour shows T+ prefix")
    void buildUserMessage_mistTrendPositiveHour_showsTPlus() {
        MistTrend mistTrend = new MistTrend(List.of(
                new MistTrend.MistSlot(1, 12000, 8.0, 5.0),
                new MistTrend.MistSlot(2, 15000, 9.0, 4.0)
        ));
        AtmosphericData data = TestAtmosphericData.builder()
                .mistTrend(mistTrend)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("T+1h")
                .contains("T+2h");
    }

    @Test
    @DisplayName("buildUserMessage mist trend with gap 1.0-2.0 shows NEAR DEW POINT label")
    void buildUserMessage_mistTrendNearDewPoint_showsLabel() {
        MistTrend mistTrend = new MistTrend(List.of(
                new MistTrend.MistSlot(0, 3000, 6.0, 7.5) // dew=6.0, temp=7.5, gap=1.5°C → NEAR DEW POINT
        ));
        AtmosphericData data = TestAtmosphericData.builder()
                .mistTrend(mistTrend)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("[NEAR DEW POINT]")
                .doesNotContain("[AT/NEAR DEW POINT]");
    }

    @Test
    @DisplayName("buildUserMessage surge section included when significant surge present")
    void buildUserMessage_withSurge_includesSurgeBlock() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.23, 0.12, 0.35, 990.0, 15.0, 60.0, 0.85,
                TideRiskLevel.MODERATE, "Test surge");

        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data, surge, 4.15, 3.80);

        assertThat(message)
                .contains("STORM SURGE FORECAST:")
                .contains("Pressure effect: +0.23m")
                .contains("low pressure 990 hPa")
                .contains("Wind effect: +0.12m")
                .contains("Total estimated surge: +0.35m (upper bound)")
                .contains("Adjusted tidal range: up to 4.2m (predicted 3.8m + 0.35m surge)")
                .contains("Risk level: MODERATE");
    }

    @Test
    @DisplayName("buildUserMessage surge with high pressure shows 'high' label")
    void buildUserMessage_surgeHighPressure_showsHighLabel() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                -0.17, 0.0, 0.0, 1030.0, 5.0, 270.0, 0.0,
                TideRiskLevel.NONE, "No significant surge expected");

        // Non-significant surge should not be included
        AtmosphericData data = TestAtmosphericData.defaults();
        String message = promptBuilder.buildUserMessage(data, surge, null, null);
        assertThat(message).doesNotContain("STORM SURGE FORECAST:");
    }

    @Test
    @DisplayName("buildUserMessage surge without wind component omits wind line")
    void buildUserMessage_surgeNoWindComponent_omitsWindLine() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.15, 0.01, 0.15, 998.0, 3.0, 180.0, 0.1,
                TideRiskLevel.LOW, "Pressure only");

        AtmosphericData data = TestAtmosphericData.defaults();
        String message = promptBuilder.buildUserMessage(data, surge, null, null);

        assertThat(message)
                .contains("STORM SURGE FORECAST:")
                .contains("Pressure effect:")
                .doesNotContain("Wind effect:");
    }

    @Test
    @DisplayName("buildUserMessage surge without adjusted range omits tidal range line")
    void buildUserMessage_surgeNoAdjustedRange_omitsRangeLine() {
        StormSurgeBreakdown surge = new StormSurgeBreakdown(
                0.15, 0.05, 0.20, 998.0, 10.0, 80.0, 0.8,
                TideRiskLevel.LOW, "Test");

        AtmosphericData data = TestAtmosphericData.defaults();
        String message = promptBuilder.buildUserMessage(data, surge, null, null);

        assertThat(message)
                .contains("STORM SURGE FORECAST:")
                .doesNotContain("Adjusted tidal range:");
    }


    // ── Cloud Inversion Tests ───────────────────────────────────────────────

    @Test
    @DisplayName("getSystemPrompt contains cloud inversion guidance with 200m threshold")
    void getSystemPrompt_containsInversionGuidance() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt)
                .contains("CLOUD INVERSION GUIDANCE:")
                .contains("200m+ elevation")
                .doesNotContain("300m+ elevation")
                .contains("Inversion score 7-8")
                .contains("Inversion score 9-10")
                .contains("sea of clouds");
    }

    @Test
    @DisplayName("buildUserMessage with moderate inversion score includes inversion block")
    void buildUserMessage_moderateInversion_includesInversionBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(8.0)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD INVERSION FORECAST:")
                .contains("Score: 8/10")
                .contains("Moderate Cloud Inversion Potential")
                .contains("Visible cloud layer below; light touching cloud tops")
                .contains("Peak at event time");
    }

    @Test
    @DisplayName("buildUserMessage with strong inversion score includes strong block")
    void buildUserMessage_strongInversion_includesStrongBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(9.0)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD INVERSION FORECAST:")
                .contains("Score: 9/10")
                .contains("Strong Cloud Inversion Potential")
                .contains("Dramatic blanket below viewpoint; clear sky above");
    }

    @Test
    @DisplayName("buildUserMessage with score 10 uses strong potential")
    void buildUserMessage_score10_usesStrongPotential() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(10.0)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("Score: 10/10")
                .contains("Strong Cloud Inversion Potential");
    }

    @Test
    @DisplayName("buildUserMessage with inversion score below threshold omits inversion block")
    void buildUserMessage_lowInversion_omitsBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(5.0)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("CLOUD INVERSION FORECAST:");
    }

    @Test
    @DisplayName("buildUserMessage with null inversion score omits inversion block")
    void buildUserMessage_nullInversion_omitsBlock() {
        AtmosphericData data = TestAtmosphericData.defaults();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("CLOUD INVERSION FORECAST:");
    }

    @Test
    @DisplayName("buildUserMessage with inversion score exactly at threshold includes block")
    void buildUserMessage_inversionAtThreshold_includesBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(7.0)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message)
                .contains("CLOUD INVERSION FORECAST:")
                .contains("Score: 7/10")
                .contains("Moderate Cloud Inversion Potential");
    }

    @Test
    @DisplayName("buildUserMessage with inversion score just below threshold omits block")
    void buildUserMessage_inversionJustBelowThreshold_omitsBlock() {
        AtmosphericData data = TestAtmosphericData.builder()
                .inversionScore(6.9)
                .build();

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).doesNotContain("CLOUD INVERSION FORECAST:");
    }

    @Test
    @DisplayName("isInversionLikely returns true for score at threshold")
    void isInversionLikely_atThreshold_returnsTrue() {
        assertThat(PromptBuilder.isInversionLikely(7.0)).isTrue();
    }

    @Test
    @DisplayName("isInversionLikely returns true for score above threshold")
    void isInversionLikely_aboveThreshold_returnsTrue() {
        assertThat(PromptBuilder.isInversionLikely(9.5)).isTrue();
    }

    @Test
    @DisplayName("isInversionLikely returns false for score below threshold")
    void isInversionLikely_belowThreshold_returnsFalse() {
        assertThat(PromptBuilder.isInversionLikely(6.0)).isFalse();
    }

    @Test
    @DisplayName("isInversionLikely returns false for null")
    void isInversionLikely_null_returnsFalse() {
        assertThat(PromptBuilder.isInversionLikely(null)).isFalse();
    }

    @Test
    @DisplayName("InversionPotential.fromScore returns STRONG for 9+")
    void inversionPotential_fromScore_strong() {
        assertThat(InversionPotential.fromScore(9)).isEqualTo(InversionPotential.STRONG);
        assertThat(InversionPotential.fromScore(10)).isEqualTo(InversionPotential.STRONG);
    }

    @Test
    @DisplayName("InversionPotential.fromScore returns MODERATE for 7-8")
    void inversionPotential_fromScore_moderate() {
        assertThat(InversionPotential.fromScore(7)).isEqualTo(InversionPotential.MODERATE);
        assertThat(InversionPotential.fromScore(8)).isEqualTo(InversionPotential.MODERATE);
    }

    @Test
    @DisplayName("InversionPotential.fromScore returns NONE for below 7")
    void inversionPotential_fromScore_none() {
        assertThat(InversionPotential.fromScore(6)).isEqualTo(InversionPotential.NONE);
        assertThat(InversionPotential.fromScore(0)).isEqualTo(InversionPotential.NONE);
    }

    @Test
    @DisplayName("InversionPotential labels are human-readable")
    void inversionPotential_labels_readable() {
        assertThat(InversionPotential.MODERATE.label()).isEqualTo("Moderate Cloud Inversion Potential");
        assertThat(InversionPotential.STRONG.label()).isEqualTo("Strong Cloud Inversion Potential");
        assertThat(InversionPotential.NONE.label()).isEqualTo("No inversion potential");
    }

    // --- FORECAST RELIABILITY block tests ---

    @Nested
    @DisplayName("Forecast reliability block")
    class ForecastReliabilityBlockTests {

        @Test
        @DisplayName("SETTLED stability produces no reliability block")
        void settled_noReliabilityBlock() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .stability(ForecastStability.SETTLED)
                    .stabilityReason("High pressure dominant")
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).doesNotContain("FORECAST RELIABILITY");
        }

        @Test
        @DisplayName("null stability produces no reliability block")
        void nullStability_noReliabilityBlock() {
            AtmosphericData data = TestAtmosphericData.defaults();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).doesNotContain("FORECAST RELIABILITY");
        }

        @Test
        @DisplayName("TRANSITIONAL stability includes reliability block with front timing note")
        void transitional_includesReliabilityBlockWithNote() {
            PressureTrend pt = new PressureTrend(
                    List.of(1015.0, 1014.5, 1014.0, 1013.5, 1013.0, 1012.5, 1012.0),
                    -3.0, "FALLING_RAPIDLY");
            AtmosphericData data = TestAtmosphericData.builder()
                    .stability(ForecastStability.TRANSITIONAL)
                    .stabilityReason("Moderate pressure fall, precip probability 55%")
                    .pressureTrend(pt)
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("FORECAST RELIABILITY");
            assertThat(message).contains("Stability: TRANSITIONAL");
            assertThat(message).contains("Moderate pressure fall, precip probability 55%");
            assertThat(message).contains("-3.0 hPa over 6h (FALLING_RAPIDLY)");
            assertThat(message).contains("Front timing uncertain");
        }

        @Test
        @DisplayName("UNSETTLED stability includes reliability block with active frontal note")
        void unsettled_includesReliabilityBlockWithNote() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .stability(ForecastStability.UNSETTLED)
                    .stabilityReason("Active weather, high precip variance")
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("FORECAST RELIABILITY");
            assertThat(message).contains("Stability: UNSETTLED");
            assertThat(message).contains("Active frontal weather");
        }

        @Test
        @DisplayName("FALLING_RAPIDLY tendency with TRANSITIONAL shows both signals")
        void fallingRapidlyWithTransitional_showsBothSignals() {
            PressureTrend pt = new PressureTrend(
                    List.of(1020.0, 1019.3, 1018.6, 1017.9, 1017.2, 1016.5, 1015.8),
                    -4.2, "FALLING_RAPIDLY");
            AtmosphericData data = TestAtmosphericData.builder()
                    .stability(ForecastStability.TRANSITIONAL)
                    .stabilityReason("Falling pressure")
                    .pressureTrend(pt)
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("FORECAST RELIABILITY");
            assertThat(message).contains("TRANSITIONAL");
            assertThat(message).contains("-4.2 hPa over 6h (FALLING_RAPIDLY)");
            assertThat(message).contains("Front timing uncertain");
        }

        @Test
        @DisplayName("system prompt contains FORECAST RELIABILITY guidance")
        void systemPrompt_containsForecastReliabilityGuidance() {
            String system = promptBuilder.getSystemPrompt();

            assertThat(system).contains("FORECAST RELIABILITY");
            assertThat(system).contains("TRANSITIONAL");
            assertThat(system).contains("UNSETTLED");
            assertThat(system).contains("SETTLED");
        }

        @Test
        @DisplayName("positive pressure tendency formats with + sign")
        void positiveTendency_formatsWithPlusSign() {
            PressureTrend pt = new PressureTrend(
                    List.of(1010.0, 1010.3, 1010.6, 1010.9, 1011.2, 1011.5, 1011.8),
                    1.8, "RISING");
            AtmosphericData data = TestAtmosphericData.builder()
                    .stability(ForecastStability.TRANSITIONAL)
                    .stabilityReason("Rising after front passage")
                    .pressureTrend(pt)
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("+1.8 hPa over 6h (RISING)");
        }

        @Test
        @DisplayName("negative pressure tendency formats with - sign")
        void negativeTendency_formatsWithMinusSign() {
            PressureTrend pt = new PressureTrend(
                    List.of(1020.0, 1019.5, 1019.0, 1018.5, 1018.0, 1017.5, 1017.0),
                    -3.0, "FALLING_RAPIDLY");
            AtmosphericData data = TestAtmosphericData.builder()
                    .stability(ForecastStability.UNSETTLED)
                    .stabilityReason("Deep low approaching")
                    .pressureTrend(pt)
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("-3.0 hPa over 6h (FALLING_RAPIDLY)");
        }
    }

    // --- Stability wiring via AtmosphericData ---

    @Nested
    @DisplayName("Stability wiring into AtmosphericData")
    class StabilityWiringTests {

        @Test
        @DisplayName("withStability sets TRANSITIONAL and reason on AtmosphericData")
        void withStability_transitional_setsFields() {
            AtmosphericData base = TestAtmosphericData.defaults();
            AtmosphericData enriched = base.withStability(
                    ForecastStability.TRANSITIONAL, "Moderate pressure fall");

            assertThat(enriched.stability()).isEqualTo(ForecastStability.TRANSITIONAL);
            assertThat(enriched.stabilityReason()).isEqualTo("Moderate pressure fall");
            // Other fields preserved
            assertThat(enriched.locationName()).isEqualTo(base.locationName());
            assertThat(enriched.cloud()).isEqualTo(base.cloud());
        }

        @Test
        @DisplayName("default AtmosphericData has null stability — PromptBuilder skips block")
        void defaultData_nullStability_noBlock() {
            AtmosphericData data = TestAtmosphericData.defaults();

            assertThat(data.stability()).isNull();
            assertThat(data.stabilityReason()).isNull();

            String message = promptBuilder.buildUserMessage(data);
            assertThat(message).doesNotContain("FORECAST RELIABILITY");
        }

        @Test
        @DisplayName("manual run leaves stability null — no regression in PromptBuilder output")
        void manualRun_nullStability_noReliabilityBlock() {
            // Simulates manual run: stability fields left at null (not enriched)
            AtmosphericData data = TestAtmosphericData.builder()
                    .stability(null)
                    .stabilityReason(null)
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).doesNotContain("FORECAST RELIABILITY");
        }
    }

    // ── Bluebell Conditions Tests ──────────────────────────────────────────

    @Nested
    @DisplayName("Bluebell conditions in user message")
    class BluebellUserMessageTests {

        /** Date inside the bluebell window (April 18 – May 18). */
        private static final LocalDateTime IN_SEASON =
                LocalDateTime.of(2026, 4, 25, 6, 30);

        /** Date outside the bluebell window (June). */
        private static final LocalDateTime OUT_OF_SEASON =
                LocalDateTime.of(2026, 6, 21, 20, 47);

        private BluebellConditionScore excellentWoodland() {
            return new BluebellConditionScore(
                    9, true, true, true, false, false, true,
                    BluebellExposure.WOODLAND,
                    "Misty and still — perfect morning conditions");
        }

        private BluebellConditionScore fairOpenFell() {
            return new BluebellConditionScore(
                    5, false, false, false, true, true, true,
                    BluebellExposure.OPEN_FELL,
                    "Golden hour light but breezy");
        }

        @Test
        @DisplayName("bluebell block appears when score is present and date is in season")
        void inSeason_withScore_includesBluebellBlock() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(IN_SEASON)
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("BLUEBELL CONDITIONS:");
        }

        @Test
        @DisplayName("bluebell block is absent when date is outside season")
        void outOfSeason_withScore_omitsBluebellBlock() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(OUT_OF_SEASON)
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).doesNotContain("BLUEBELL CONDITIONS:");
        }

        @Test
        @DisplayName("bluebell block is absent when score is null even in season")
        void inSeason_nullScore_omitsBluebellBlock() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(IN_SEASON)
                    .bluebellConditionScore(null)
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).doesNotContain("BLUEBELL CONDITIONS:");
        }

        @Test
        @DisplayName("bluebell block renders score value and quality label")
        void inSeason_rendersScoreAndLabel() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(IN_SEASON)
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("Score: 9/10 (Excellent)");
        }

        @Test
        @DisplayName("bluebell block renders exposure name")
        void inSeason_rendersExposure() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(IN_SEASON)
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("Exposure: WOODLAND");
        }

        @Test
        @DisplayName("bluebell block renders OPEN_FELL exposure for fell locations")
        void inSeason_openFell_rendersExposure() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(IN_SEASON)
                    .bluebellConditionScore(fairOpenFell())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("Exposure: OPEN_FELL");
        }

        @Test
        @DisplayName("bluebell block renders condition summary text")
        void inSeason_rendersConditionSummary() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(IN_SEASON)
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message)
                    .contains("Conditions: Misty and still — perfect morning conditions");
        }

        @Test
        @DisplayName("bluebell block renders all boolean detail flags")
        void inSeason_rendersAllBooleanFlags() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(IN_SEASON)
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message)
                    .contains("misty=true")
                    .contains("calm=true")
                    .contains("softLight=true")
                    .contains("goldenHourLight=false")
                    .contains("postRain=false")
                    .contains("dryNow=true");
        }

        @Test
        @DisplayName("bluebell block renders false boolean flags for opposite conditions")
        void inSeason_fairConditions_rendersFalseFlags() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(IN_SEASON)
                    .bluebellConditionScore(fairOpenFell())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message)
                    .contains("misty=false")
                    .contains("calm=false")
                    .contains("softLight=false")
                    .contains("goldenHourLight=true")
                    .contains("postRain=true")
                    .contains("dryNow=true");
        }

        @Test
        @DisplayName("output instruction for bluebell fields appears only in bluebell block")
        void inSeason_includesOutputFieldInstruction() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(IN_SEASON)
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains(
                    "In addition to the standard output fields, also include: "
                    + "bluebell_score (integer, 0-100) and bluebell_summary (string).");
        }

        @Test
        @DisplayName("output instruction is absent when bluebell block is absent")
        void outOfSeason_omitsOutputFieldInstruction() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(OUT_OF_SEASON)
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).doesNotContain("bluebell_score");
            assertThat(message).doesNotContain("bluebell_summary");
        }

        @Test
        @DisplayName("output instruction is absent when score is null in season")
        void inSeason_nullScore_omitsOutputFieldInstruction() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(IN_SEASON)
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).doesNotContain("bluebell_score");
            assertThat(message).doesNotContain("bluebell_summary");
        }

        @Test
        @DisplayName("bluebell block renders different score and label for fair conditions")
        void inSeason_fairScore_rendersCorrectLabel() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(IN_SEASON)
                    .bluebellConditionScore(fairOpenFell())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("Score: 5/10 (Fair)");
        }

        @Test
        @DisplayName("date at start of bluebell window (April 18) includes block")
        void windowStartBoundary_includesBlock() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(LocalDateTime.of(2026, 4, 18, 6, 0))
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("BLUEBELL CONDITIONS:");
        }

        @Test
        @DisplayName("date at end of bluebell window (May 18) includes block")
        void windowEndBoundary_includesBlock() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(LocalDateTime.of(2026, 5, 18, 20, 0))
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).contains("BLUEBELL CONDITIONS:");
        }

        @Test
        @DisplayName("date one day before bluebell window (April 17) omits block")
        void dayBeforeWindow_omitsBlock() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(LocalDateTime.of(2026, 4, 17, 6, 0))
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).doesNotContain("BLUEBELL CONDITIONS:");
        }

        @Test
        @DisplayName("date one day after bluebell window (May 19) omits block")
        void dayAfterWindow_omitsBlock() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(LocalDateTime.of(2026, 5, 19, 6, 0))
                    .bluebellConditionScore(excellentWoodland())
                    .build();

            String message = promptBuilder.buildUserMessage(data);

            assertThat(message).doesNotContain("BLUEBELL CONDITIONS:");
        }
    }

    @Nested
    @DisplayName("Output config includes bluebell fields as optional")
    class OutputConfigBluebellInclusionTests {

        @Test
        @DisplayName("output config contains bluebell_score in properties")
        void outputConfig_containsBluebellScore() {
            String configStr = promptBuilder.buildOutputConfig().toString();

            assertThat(configStr).contains("bluebell_score");
        }

        @Test
        @DisplayName("output config contains bluebell_summary in properties")
        void outputConfig_containsBluebellSummary() {
            String configStr = promptBuilder.buildOutputConfig().toString();

            assertThat(configStr).contains("bluebell_summary");
        }

        @Test
        @DisplayName("bluebell fields are not in the required list")
        void outputConfig_bluebellFieldsNotRequired() {
            String configStr = promptBuilder.buildOutputConfig().toString();
            // required list should not mention bluebell
            String requiredSection = configStr.substring(
                    configStr.indexOf("required="));
            String requiredList = requiredSection.substring(0,
                    requiredSection.indexOf("]") + 1);

            assertThat(requiredList).doesNotContain("bluebell_score");
            assertThat(requiredList).doesNotContain("bluebell_summary");
        }
    }

    @Test
    @DisplayName("system prompt contains bluebell scoring guidance")
    void getSystemPrompt_containsBluebellGuidance() {
        String prompt = promptBuilder.getSystemPrompt();

        assertThat(prompt)
                .contains("BLUEBELL CONDITIONS")
                .contains("bluebell site")
                .contains("Score 8-10")
                .contains("Score 6-7")
                .contains("Score < 6")
                .contains("WOODLAND exposure")
                .contains("OPEN_FELL exposure");
    }
}
