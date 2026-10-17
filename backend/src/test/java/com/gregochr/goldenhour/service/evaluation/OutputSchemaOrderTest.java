package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.OutputConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.gregochr.goldenhour.service.WoodlandVerdictEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the property order of every forecast-lane structured-output schema, as it is SERIALISED
 * into the request.
 *
 * <p>The order is load-bearing: structured outputs emit fields in the schema's property order, so
 * it decides whether the model commits to a rating before or after writing its explanation. It
 * used to come from {@code Map.ofEntries}, whose iteration order changes per JVM run, so it
 * changed with every backend restart.
 */
class OutputSchemaOrderTest {

    private static JsonNode schemaOf(OutputConfig config) throws Exception {
        var mapper = ObjectMappers.jsonMapper();
        return mapper.readTree(mapper.writeValueAsString(config)).path("format").path("schema");
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    @DisplayName("sky/coastal schema lists properties in the fixed order")
    void skySchema_propertyOrder() throws Exception {
        JsonNode schema = schemaOf(new PromptBuilder().buildOutputConfig());

        assertThat(keys(schema.path("properties"))).containsExactly(
                "rating", "fiery_sky", "golden_hour", "summary", "headline",
                "basic_fiery_sky", "basic_golden_hour", "basic_summary",
                "inversion_score", "inversion_potential");
        assertThat(keys(schema.path("properties").path("rating")))
                .containsExactly("type", "enum", "description");
        assertThat(keys(schema.path("properties").path("inversion_potential")))
                .containsExactly("type", "enum");
        assertThat(schema.path("required")).hasSize(4);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("rating");
        assertThat(schema.path("required").get(3).asText()).isEqualTo("summary");
    }

    @Test
    @DisplayName("woodland schema lists properties in the fixed order")
    void woodlandSchema_propertyOrder() throws Exception {
        JsonNode schema = schemaOf(
                new WoodlandPromptBuilder(new WoodlandVerdictEvaluator()).buildOutputConfig());

        assertThat(keys(schema.path("properties")))
                .containsExactly("rating", "summary", "headline");
        assertThat(keys(schema.path("properties").path("headline")))
                .containsExactly("type", "description");
    }

    @Test
    @DisplayName("bluebell schema lists properties in the fixed order")
    void bluebellSchema_propertyOrder() throws Exception {
        JsonNode schema = schemaOf(new BluebellPromptBuilder().buildOutputConfig());

        assertThat(keys(schema.path("properties")))
                .containsExactly("rating", "summary", "headline");
    }

    @Test
    @DisplayName("ordered() preserves argument order")
    void ordered_preservesOrder() {
        assertThat(PromptUtils.ordered("z", 1, "a", 2, "m", 3).keySet())
                .containsExactly("z", "a", "m");
    }

    @Test
    @DisplayName("ordered() rejects an odd argument count")
    void ordered_oddLength_throws() {
        assertThatThrownBy(() -> PromptUtils.ordered("a", 1, "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 arguments");
    }

    @Test
    @DisplayName("ordered() rejects a non-String key")
    void ordered_nonStringKey_throws() {
        assertThatThrownBy(() -> PromptUtils.ordered(1, "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("position 0");
    }
}
