package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.SpaceWeatherAlert;
import com.gregochr.goldenhour.model.SolarWindReading;
import com.gregochr.goldenhour.model.TonightWindow;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPhase;
import com.gregochr.solarutils.LunarPosition;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.anthropic.models.messages.MessageCreateParams;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Mock
    private ModelSelectionService modelSelectionService;

    @Mock
    private LunarCalculator lunarCalculator;

    private ClaudeAuroraInterpreter interpreter;

    @BeforeEach
    void setUp() {
        lenient().when(modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION))
                .thenReturn(EvaluationModel.HAIKU);
        lenient().when(lunarCalculator.calculate(any(ZonedDateTime.class),
                anyDouble(), anyDouble()))
                .thenReturn(new LunarPosition(
                        35.0, 180.0, 0.45, LunarPhase.FIRST_QUARTER, 384400));
        interpreter = new ClaudeAuroraInterpreter(
                anthropicApiClient, new ObjectMapper(), modelSelectionService,
                lunarCalculator);
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
                AlertLevel.MODERATE, List.of(loc), Map.of(loc, 30), data, null,
                TriggerType.REALTIME, null);

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).stars()).isEqualTo(4);
        assertThat(scores.get(0).summary()).isEqualTo("Strong aurora");
    }

    @Test
    @DisplayName("interpret passes the model from ModelSelectionService to the API call")
    void interpret_usesConfiguredModel() {
        when(modelSelectionService.getActiveModel(RunType.AURORA_EVALUATION))
                .thenReturn(EvaluationModel.SONNET);
        LocationEntity loc = buildLocation(1L, "Galloway", 55.0, -4.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(6.0);

        String jsonResponse = "[{\"name\":\"Galloway\",\"stars\":4,"
                + "\"summary\":\"Strong aurora\",\"detail\":\"ok\"}]";

        Message mockMessage = mock(Message.class);
        ContentBlock mockBlock = mock(ContentBlock.class);
        TextBlock mockTextBlock = mock(TextBlock.class);
        when(anthropicApiClient.createMessage(any())).thenReturn(mockMessage);
        when(mockMessage.content()).thenReturn(List.of(mockBlock));
        when(mockBlock.isText()).thenReturn(true);
        when(mockBlock.asText()).thenReturn(mockTextBlock);
        when(mockTextBlock.text()).thenReturn(jsonResponse);

        interpreter.interpret(AlertLevel.MODERATE, List.of(loc), Map.of(loc, 30),
                data, null, TriggerType.REALTIME, null);

        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        assertThat(captor.getValue().model().toString())
                .isEqualTo(EvaluationModel.SONNET.getModelId());
    }

    @Test
    @DisplayName("interpret returns empty list when no viable locations")
    void interpret_emptyLocations_returnsEmpty() {
        SpaceWeatherData data = minimalSpaceWeather(6.0);

        List<AuroraForecastScore> scores = interpreter.interpret(
                AlertLevel.MODERATE, List.of(), Map.of(), data, null,
                TriggerType.REALTIME, null);

        assertThat(scores).isEmpty();
    }

    // -------------------------------------------------------------------------
    // buildUserMessage — trigger type context
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buildUserMessage includes TRIGGER TYPE: realtime for real-time alerts")
    void buildUserMessage_realtimeTrigger_includesRealtimeContext() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(6.0);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 40), data, null, TriggerType.REALTIME, null);

        assertThat(msg).contains("TRIGGER TYPE: realtime");
        assertThat(msg).doesNotContain("TONIGHT'S DARK WINDOW");
    }

    @Test
    @DisplayName("buildUserMessage includes TRIGGER TYPE: forecast_lookahead for forecast alerts")
    void buildUserMessage_forecastTrigger_includesForecastContext() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(5.0);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        TonightWindow window = new TonightWindow(now.plusHours(6), now.plusHours(14));

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 40), data, null, TriggerType.FORECAST_LOOKAHEAD, window);

        assertThat(msg).contains("TRIGGER TYPE: forecast_lookahead");
        assertThat(msg).contains("TONIGHT'S DARK WINDOW");
    }

    @Test
    @DisplayName("buildUserMessage omits tonight window section when window is null (real-time)")
    void buildUserMessage_nullWindow_omitsTonightWindowSection() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(6.0);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 40), data, null, TriggerType.REALTIME, null);

        assertThat(msg).doesNotContain("TONIGHT'S DARK WINDOW");
    }

    // -------------------------------------------------------------------------
    // buildUserMessage — lunar conditions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buildUserMessage includes LUNAR CONDITIONS block with phase, illumination, altitude")
    void buildUserMessage_includesLunarConditions() {
        LocationEntity loc = buildLocation(1L, "Galloway", 55.0, -4.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(6.0);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 30), data, null, TriggerType.REALTIME, null);

        assertThat(msg).contains("LUNAR CONDITIONS:");
        assertThat(msg).contains("Phase: FIRST_QUARTER");
        assertThat(msg).contains("Illumination: 45%");
        assertThat(msg).contains("Altitude: 35°");
        assertThat(msg).contains("above horizon");
    }

    @Test
    @DisplayName("buildUserMessage shows 'below horizon' when moon altitude is negative")
    void buildUserMessage_moonBelowHorizon() {
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenReturn(new LunarPosition(
                        -12.0, 270.0, 0.80, LunarPhase.WAXING_GIBBOUS, 384400));
        LocationEntity loc = buildLocation(1L, "Kielder", 55.2, -2.6, 2);
        SpaceWeatherData data = minimalSpaceWeather(5.0);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 30), data, null, TriggerType.REALTIME, null);

        assertThat(msg).contains("LUNAR CONDITIONS:");
        assertThat(msg).contains("Phase: WAXING_GIBBOUS");
        assertThat(msg).contains("Illumination: 80%");
        assertThat(msg).contains("below horizon");
    }

    @Test
    @DisplayName("buildUserMessage omits LUNAR CONDITIONS when calculator fails")
    void buildUserMessage_lunarCalcFails_omitsBlock() {
        when(lunarCalculator.calculate(any(ZonedDateTime.class), anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("Ephemeris error"));
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(6.0);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 40), data, null, TriggerType.REALTIME, null);

        assertThat(msg).doesNotContain("LUNAR CONDITIONS");
        // The rest of the message should still be built correctly
        assertThat(msg).contains("MODERATE");
        assertThat(msg).contains("Test");
    }

    @Test
    @DisplayName("buildUserMessage computes moon at midpoint of tonight window when provided")
    void buildUserMessage_usesTonightWindowMidpoint() {
        ZonedDateTime dusk = ZonedDateTime.of(2026, 4, 10, 20, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dawn = ZonedDateTime.of(2026, 4, 11, 4, 0, 0, 0, ZoneOffset.UTC);
        TonightWindow window = new TonightWindow(dusk, dawn);

        ArgumentCaptor<ZonedDateTime> timeCaptor = ArgumentCaptor.forClass(ZonedDateTime.class);
        when(lunarCalculator.calculate(timeCaptor.capture(), anyDouble(), anyDouble()))
                .thenReturn(new LunarPosition(
                        20.0, 180.0, 0.50, LunarPhase.FIRST_QUARTER, 384400));

        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(5.0);

        interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 40), data, null, TriggerType.FORECAST_LOOKAHEAD, window);

        // Midpoint of 20:00 → 04:00 is midnight
        ZonedDateTime capturedTime = timeCaptor.getValue();
        assertThat(capturedTime.getHour()).isZero();
        assertThat(capturedTime.getDayOfMonth()).isEqualTo(11);
    }

    // -------------------------------------------------------------------------
    // buildUserMessage — existing content checks (updated signatures)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buildUserMessage includes alert level and location count")
    void buildUserMessage_containsAlertLevelAndLocationCount() {
        LocationEntity loc = buildLocation(1L, "Cairngorms", 57.1, -3.8, 2);
        SpaceWeatherData data = minimalSpaceWeather(6.0);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 40), data, null, TriggerType.REALTIME, null);

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
                Map.of(loc, 50), data, null, TriggerType.REALTIME, null);

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
                Map.of(loc, 30), data, null, TriggerType.REALTIME, null);

        assertThat(msg).contains("OVATION");
        assertThat(msg).contains("35.0%");
    }

    @Test
    @DisplayName("buildUserMessage includes Met Office text when provided")
    void buildUserMessage_includesMetOfficeText() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(5.0);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 50), data, "G2 storm in progress", TriggerType.REALTIME, null);

        assertThat(msg).contains("MET OFFICE");
        assertThat(msg).contains("G2 storm in progress");
    }

    @Test
    @DisplayName("buildUserMessage omits Met Office section when text is blank")
    void buildUserMessage_omitsMetOfficeWhenBlank() {
        LocationEntity loc = buildLocation(1L, "Test", 55.0, -2.0, 2);
        SpaceWeatherData data = minimalSpaceWeather(5.0);

        String msg = interpreter.buildUserMessage(AlertLevel.MODERATE, List.of(loc),
                Map.of(loc, 50), data, "", TriggerType.REALTIME, null);

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
                Map.of(loc, 50), data, null, TriggerType.REALTIME, null);

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
                Map.of(loc, 50), data, null, TriggerType.REALTIME, null);

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
                Map.of(loc, 20), data, null, TriggerType.REALTIME, null);

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
    // parseResponse — name-based matching
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseResponse matches entries by name when Claude returns them out of order")
    void parseResponse_outOfOrder_matchesByName() {
        LocationEntity locA = buildLocation(1L, "Galloway", 55.0, -4.0, 2);
        LocationEntity locB = buildLocation(2L, "Kielder", 55.2, -2.6, 2);
        // Claude returned B before A (reversed order)
        String json = """
                [
                  {"name":"Kielder","stars":3,"summary":"Kielder summary","detail":"–"},
                  {"name":"Galloway","stars":5,"summary":"Galloway summary","detail":"–"}
                ]
                """;

        List<AuroraForecastScore> scores = interpreter.parseResponse(json, AlertLevel.MODERATE,
                List.of(locA, locB), Map.of(locA, 20, locB, 40));

        assertThat(scores).hasSize(2);
        assertThat(scores.get(0).location().getName()).isEqualTo("Galloway");
        assertThat(scores.get(0).stars()).isEqualTo(5);
        assertThat(scores.get(1).location().getName()).isEqualTo("Kielder");
        assertThat(scores.get(1).stars()).isEqualTo(3);
    }

    @Test
    @DisplayName("parseResponse matches names case-insensitively")
    void parseResponse_caseInsensitiveNameMatch() {
        LocationEntity loc = buildLocation(1L, "Lindisfarne Priory", 55.7, -1.8, 3);
        String json = """
                [{"name":"lindisfarne priory","stars":4,"summary":"Good conditions","detail":"–"}]
                """;

        List<AuroraForecastScore> scores = interpreter.parseResponse(json, AlertLevel.MODERATE,
                List.of(loc), Map.of(loc, 30));

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).stars()).isEqualTo(4);
        assertThat(scores.get(0).summary()).isEqualTo("Good conditions");
    }

    @Test
    @DisplayName("parseResponse falls back to index when Claude omits name field")
    void parseResponse_noNameField_fallsBackToIndex() {
        LocationEntity loc = buildLocation(1L, "Galloway", 55.0, -4.0, 2);
        String json = "[{\"stars\":3,\"summary\":\"Moderate\",\"detail\":\"–\"}]";

        List<AuroraForecastScore> scores = interpreter.parseResponse(json, AlertLevel.MODERATE,
                List.of(loc), Map.of(loc, 30));

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).stars()).isEqualTo(3);
    }

    @Test
    @DisplayName("parseResponse gives fallback only for the missing location, not all")
    void parseResponse_oneLocationMissing_onlyThatGetsFallback() {
        LocationEntity locA = buildLocation(1L, "Galloway", 55.0, -4.0, 2);
        LocationEntity locB = buildLocation(2L, "Kielder", 55.2, -2.6, 2);
        LocationEntity locC = buildLocation(3L, "Lindisfarne Priory", 55.7, -1.8, 3);
        // Claude returned 2 of 3 locations — Lindisfarne is missing
        String json = """
                [
                  {"name":"Galloway","stars":4,"summary":"Great","detail":"–"},
                  {"name":"Kielder","stars":3,"summary":"OK","detail":"–"}
                ]
                """;

        List<AuroraForecastScore> scores = interpreter.parseResponse(json, AlertLevel.MODERATE,
                List.of(locA, locB, locC), Map.of(locA, 20, locB, 30, locC, 50));

        assertThat(scores).hasSize(3);
        assertThat(scores.get(0).stars()).isEqualTo(4);
        assertThat(scores.get(0).location().getName()).isEqualTo("Galloway");
        assertThat(scores.get(1).stars()).isEqualTo(3);
        assertThat(scores.get(1).location().getName()).isEqualTo("Kielder");
        assertThat(scores.get(2).stars()).isEqualTo(1); // fallback
        assertThat(scores.get(2).summary()).contains("could not be assessed");
    }

    @Test
    @DisplayName("parseResponse with Claude returning extra entries ignores surplus")
    void parseResponse_extraEntries_ignoresSurplus() {
        LocationEntity loc = buildLocation(1L, "Galloway", 55.0, -4.0, 2);
        String json = """
                [
                  {"name":"Galloway","stars":5,"summary":"Excellent","detail":"–"},
                  {"name":"Unknown","stars":3,"summary":"Extra","detail":"–"}
                ]
                """;

        List<AuroraForecastScore> scores = interpreter.parseResponse(json, AlertLevel.MODERATE,
                List.of(loc), Map.of(loc, 10));

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).stars()).isEqualTo(5);
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
