package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.SpaceWeatherAlert;
import com.gregochr.goldenhour.model.SolarWindReading;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClaudeAuroraInterpreter} — prompt building and response parsing.
 *
 * <p>Does not call the real Anthropic API; tests the package-visible parsing and
 * message-building methods directly.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeAuroraInterpreterTest {

    @Mock
    private AnthropicApiClient anthropicApiClient;

    private ClaudeAuroraInterpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new ClaudeAuroraInterpreter(anthropicApiClient, new ObjectMapper());
    }

    // -------------------------------------------------------------------------
    // interpret() — full pipeline with mocked Anthropic SDK
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("interpret invokes Claude API and returns parsed scores")
    void interpret_invokesClaudeAndReturnsParsedScores() {
        LocationEntity loc = buildLocation(1L, "Galloway", 55.0, -4.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(6.0);

        String jsonResponse = "[{\"name\":\"Galloway\",\"stars\":4,"
                + "\"summary\":\"Strong aurora\",\"detail\":\"✓ Kp 6\"}]";

        // Mock the Anthropic SDK chain
        Message mockMessage = mock(Message.class);
        ContentBlock mockBlock = mock(ContentBlock.class);
        TextBlock mockTextBlock = mock(TextBlock.class);

        when(anthropicApiClient.createMessage(any())).thenReturn(mockMessage);
        when(mockMessage.content()).thenReturn(List.of(mockBlock));
        when(mockBlock.isText()).thenReturn(true);
        when(mockBlock.asText()).thenReturn(mockTextBlock);
        when(mockTextBlock.text()).thenReturn(jsonResponse);

        List<AuroraForecastScore> scores = interpreter.interpret(
                AlertLevel.MODERATE, List.of(loc), Map.of(loc, 30), data, null);

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).stars()).isEqualTo(4);
        assertThat(scores.get(0).summary()).isEqualTo("Strong aurora");
    }

    @Test
    @DisplayName("interpret returns empty list when no viable locations")
    void interpret_emptyLocations_returnsEmpty() {
        SpaceWeatherData data = minimalSpaceWeather(6.0);

        List<AuroraForecastScore> scores = interpreter.interpret(
                AlertLevel.MODERATE, List.of(), Map.of(), data, null);

        assertThat(scores).isEmpty();
    }

    // -------------------------------------------------------------------------
    // buildUserMessage
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buildUserMessage includes alert level and location count")
    void buildUserMessage_containsAlertLevelAndLocationCount() {
        LocationEntity loc = buildLocation(1L, "Cairngorms", 57.1, -3.8, 2);
        SpaceWeatherData data = minimalSpaceWeather(6.0);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 40), data, null);

        assertThat(msg).contains("MODERATE");
        assertThat(msg).contains("LOCATIONS TO SCORE (1 locations)");
        assertThat(msg).contains("Cairngorms");
    }

    @Test
    @DisplayName("buildUserMessage includes Kp trend section")
    void buildUserMessage_containsKpTrend() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(5.5);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 50), data, null);

        assertThat(msg).contains("KP TREND");
        assertThat(msg).contains("Kp=5.5");
    }

    @Test
    @DisplayName("buildUserMessage includes OVATION probability when present")
    void buildUserMessage_includesOvation() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        OvationReading ovation = new OvationReading(now, 35.0, 55.0);
        SpaceWeatherData data = new SpaceWeatherData(
                List.of(new KpReading(now, 5.5)), List.of(), ovation, List.of(), List.of());

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 30), data, null);

        assertThat(msg).contains("OVATION");
        assertThat(msg).contains("35.0%");
    }

    @Test
    @DisplayName("buildUserMessage includes Met Office text when provided")
    void buildUserMessage_includesMetOfficeText() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(5.0);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 50), data, "G2 storm in progress");

        assertThat(msg).contains("MET OFFICE");
        assertThat(msg).contains("G2 storm in progress");
    }

    @Test
    @DisplayName("buildUserMessage omits Met Office section when text is blank")
    void buildUserMessage_omitsMetOfficeWhenBlank() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(5.0);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 50), data, "");

        assertThat(msg).doesNotContain("MET OFFICE");
    }

    @Test
    @DisplayName("buildUserMessage includes solar wind Bz when readings present")
    void buildUserMessage_includesSolarWindBz() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        SolarWindReading wind = new SolarWindReading(now, -8.5, 480.0, 5.2);
        SpaceWeatherData data = new SpaceWeatherData(
                List.of(new KpReading(now, 5.5)), List.of(), null, List.of(wind), List.of());

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 50), data, null);

        assertThat(msg).contains("SOLAR WIND");
        assertThat(msg).contains("Bz=-8.5 nT");
    }

    @Test
    @DisplayName("buildUserMessage includes active alerts section")
    void buildUserMessage_includesActiveAlerts() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        SpaceWeatherAlert alert = new SpaceWeatherAlert("W", "20250801-WA-001", now,
                "G2 Watch issued for 2025-08-01");
        SpaceWeatherData data = new SpaceWeatherData(
                List.of(new KpReading(now, 5.5)), List.of(), null, List.of(), List.of(alert));

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 50), data, null);

        assertThat(msg).contains("NOAA ALERTS");
        assertThat(msg).contains("G2 Watch");
    }

    @Test
    @DisplayName("buildUserMessage limits Kp trend to last 5 readings")
    void buildUserMessage_limitsKpTrendToFive() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        ZonedDateTime base = ZonedDateTime.of(2025, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        // Supply 8 readings; only last 5 should appear
        List<KpReading> kpReadings = List.of(
                new KpReading(base, 1.0),
                new KpReading(base.plusHours(1), 2.0),
                new KpReading(base.plusHours(2), 3.0),
                new KpReading(base.plusHours(3), 4.0),
                new KpReading(base.plusHours(4), 5.0),
                new KpReading(base.plusHours(5), 6.0),
                new KpReading(base.plusHours(6), 7.0),
                new KpReading(base.plusHours(7), 8.0)
        );
        SpaceWeatherData data = new SpaceWeatherData(kpReadings, List.of(), null, List.of(), List.of());

        String msg = interpreter.buildUserMessage(AlertLevel.STRONG, List.of(loc),
                Map.of(loc, 20), data, null);

        // Reading at index 0 (Kp=1.0) and 1 (Kp=2.0) should not appear; last 5 are 4.0–8.0
        assertThat(msg).doesNotContain("Kp=1.0");
        assertThat(msg).doesNotContain("Kp=2.0");
        assertThat(msg).doesNotContain("Kp=3.0");
        assertThat(msg).contains("Kp=8.0");
    }

    // -------------------------------------------------------------------------
    // parseResponse — valid JSON
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseResponse parses valid JSON array correctly")
    void parseResponse_validJson_parsesCorrectly() {
        LocationEntity loc = buildLocation(1L, "Galloway", 55.0, -4.0, 2);
        String json = """
                [
                  {
                    "name": "Galloway",
                    "stars": 4,
                    "summary": "Strong aurora possible tonight",
                    "detail": "✓ Kp 6+ active\\n✓ Low cloud cover"
                  }
                ]
                """;

        List<AuroraForecastScore> scores = interpreter.parseResponse(json, AlertLevel.MODERATE,
                List.of(loc), Map.of(loc, 30));

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).stars()).isEqualTo(4);
        assertThat(scores.get(0).summary()).isEqualTo("Strong aurora possible tonight");
        assertThat(scores.get(0).location()).isEqualTo(loc);
        assertThat(scores.get(0).cloudPercent()).isEqualTo(30);
    }

    @Test
    @DisplayName("parseResponse strips code fences before parsing")
    void parseResponse_codeFencedJson_stripsFences() {
        LocationEntity loc = buildLocation(1L, "Kielder", 55.2, -2.6, 2);
        String json = """
                ```json
                [{"name":"Kielder","stars":3,"summary":"Moderate","detail":"–"}]
                ```""";

        List<AuroraForecastScore> scores = interpreter.parseResponse(json, AlertLevel.MODERATE,
                List.of(loc), Map.of(loc, 50));

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).stars()).isEqualTo(3);
    }

    @Test
    @DisplayName("parseResponse clamps stars to 1–5 inclusive")
    void parseResponse_clampsStars() {
        LocationEntity locA = buildLocation(1L, "A", 55.0, -2.0, 2);
        LocationEntity locB = buildLocation(2L, "B", 55.0, -3.0, 2);
        String json = """
                [
                  {"name":"A","stars":0,"summary":"Too low","detail":"–"},
                  {"name":"B","stars":6,"summary":"Too high","detail":"–"}
                ]
                """;

        List<AuroraForecastScore> scores = interpreter.parseResponse(json, AlertLevel.MODERATE,
                List.of(locA, locB), Map.of(locA, 20, locB, 20));

        assertThat(scores.get(0).stars()).isEqualTo(1);
        assertThat(scores.get(1).stars()).isEqualTo(5);
    }

    @Test
    @DisplayName("parseResponse uses fallback 1-star score when JSON is malformed")
    void parseResponse_malformedJson_fallbackScore() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);

        List<AuroraForecastScore> scores = interpreter.parseResponse("not-valid-json",
                AlertLevel.MODERATE, List.of(loc), Map.of(loc, 50));

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).stars()).isEqualTo(1);
        assertThat(scores.get(0).summary()).contains("could not be assessed");
    }

    @Test
    @DisplayName("parseResponse uses fallback for locations missing from Claude response")
    void parseResponse_missingLocationsInResponse_fallback() {
        LocationEntity locA = buildLocation(1L, "A", 55.0, -2.0, 2);
        LocationEntity locB = buildLocation(2L, "B", 55.0, -3.0, 2);
        // Claude only returned one entry for two locations
        String json = "[{\"name\":\"A\",\"stars\":4,\"summary\":\"Good\",\"detail\":\"–\"}]";

        List<AuroraForecastScore> scores = interpreter.parseResponse(json, AlertLevel.MODERATE,
                List.of(locA, locB), Map.of(locA, 20, locB, 50));

        assertThat(scores).hasSize(2);
        assertThat(scores.get(0).stars()).isEqualTo(4);
        assertThat(scores.get(1).stars()).isEqualTo(1); // fallback for locB
    }

    @Test
    @DisplayName("parseResponse handles empty JSON array without throwing")
    void parseResponse_emptyJsonArray_returnsFallbacks() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);

        List<AuroraForecastScore> scores = interpreter.parseResponse("[]", AlertLevel.MODERATE,
                List.of(loc), Map.of(loc, 50));

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).stars()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LocationEntity buildLocation(long id, String name, double lat, double lon, int bortle) {
        return LocationEntity.builder()
                .id(id).name(name).lat(lat).lon(lon).bortleClass(bortle).build();
    }

    private SpaceWeatherData minimalSpaceWeather(double kp) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        return new SpaceWeatherData(
                List.of(new KpReading(now, kp)), List.of(), null, List.of(), List.of());
    }
}
