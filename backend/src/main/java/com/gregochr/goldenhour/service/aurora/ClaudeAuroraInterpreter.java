package com.gregochr.goldenhour.service.aurora;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.OvationReading;
import com.gregochr.goldenhour.model.SolarWindReading;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.TonightWindow;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Uses a single Claude API call to interpret real-time space weather data and produce
 * per-location aurora photography scores.
 *
 * <p>Constructs a rich prompt combining NOAA SWPC data (Kp trend, forecast, OVATION
 * probability, solar wind Bz, active alerts) with per-location cloud cover and dark-sky
 * data. Claude rates each viable location on a 1–5 star scale with a one-line summary
 * and a multi-factor detail breakdown.
 *
 * <p>The prompt includes a {@link TriggerType} context so Claude uses planning language
 * ("forecast tonight — plan your evening") for daytime forecast alerts vs urgent language
 * ("happening now — get out there") for real-time alerts.
 *
 * <p>Aurora is an inherently qualitative, multi-signal assessment — Claude is well-suited
 * to integrating Kp, Bz trend, OVATION probability, cloud, and lunar interference into a
 * coherent score, whereas a rule-based scorer would miss interactions between signals.
 */
@Service
public class ClaudeAuroraInterpreter {

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeAuroraInterpreter.class);

    /** Claude model used for aurora interpretation (Haiku — fast, cost-effective, factual). */
    private static final String MODEL = "claude-haiku-4-5";

    /** Maximum tokens for the aurora scoring response. */
    private static final int MAX_TOKENS = 1024;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/London"));

    private static final String SYSTEM_PROMPT = """
            You are an expert aurora photography advisor. You will receive space weather data
            and a list of photography locations. For each location, produce a 1–5 star rating
            for aurora photography conditions, with a one-line summary and a brief multi-factor
            detail.

            Output ONLY valid JSON — no prose, no code fences, no markdown.
            The JSON must be an array of objects, one per location, in the same order as
            the input list.

            Each object must have exactly these fields:
            - "name": the location name (string, unchanged from input)
            - "stars": integer 1–5
            - "summary": one-line summary suitable for a push notification (max 120 chars,
              no double-quote characters inside the string)
            - "detail": 3–5 bullet points with ✓/–/✗ icons covering: geomagnetic activity,
              cloud outlook, solar wind Bz, moonlight, and dark skies (no double-quote
              characters inside the string)

            Scoring guidance:
            - STRONG (Kp 7+): base 4★. MODERATE (Kp 5–6): base 3★.
            - Bz persistently negative (−10 nT or below): +0.5. Positive: −0.5.
            - OVATION probability > 50% at 55°N: +0.5.
            - Cloud cover < 30%: +1. 30–60%: 0. 60–80%: −1. > 80%: −1.5.
            - Moon below horizon or < 20% illuminated: +0.5. Severe moonlight: −1.
            - Bortle 1–2: +0.5. Bortle 3–4: 0. Bortle 5+: −0.5.
            - Clamp final score to 1–5 inclusive.

            Tone guidance based on trigger_type in the payload:
            - "forecast_lookahead": conditions are forecast, not yet happening. The user has
              hours to prepare. Use planning language in summary: "forecast tonight", "expected
              from HH:MM", "plan your evening", "charge your batteries". Do NOT say "happening
              now" or imply urgency.
            - "realtime": aurora is happening RIGHT NOW. The user must act immediately. Use
              urgent language: "happening now", "get out there", "don't wait", "conditions
              peaking". Do NOT use hedging future language.

            Be concise and factual. Do not invent or hallucinate data not provided.
            """;

    private final AnthropicApiClient anthropicApiClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the interpreter.
     *
     * @param anthropicApiClient resilient Anthropic API client
     * @param objectMapper       Jackson mapper for parsing Claude's JSON response
     */
    public ClaudeAuroraInterpreter(AnthropicApiClient anthropicApiClient,
            ObjectMapper objectMapper) {
        this.anthropicApiClient = anthropicApiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Scores all viable locations in a single Claude API call.
     *
     * @param level            the current alert level (MODERATE or STRONG)
     * @param viableLocations  locations that passed weather triage
     * @param cloudByLocation  average cloud cover (0–100) per location
     * @param spaceWeather     NOAA SWPC data for context
     * @param metOfficeText    Met Office space weather narrative (may be null)
     * @param triggerType      whether this alert is forecast-based or real-time
     * @param tonightWindow    tonight's dark period (may be null for real-time alerts)
     * @return scored aurora forecast results, one per viable location
     */
    public List<AuroraForecastScore> interpret(AlertLevel level,
            List<LocationEntity> viableLocations,
            Map<LocationEntity, Integer> cloudByLocation,
            SpaceWeatherData spaceWeather,
            String metOfficeText,
            TriggerType triggerType,
            TonightWindow tonightWindow) {
        if (viableLocations.isEmpty()) {
            return List.of();
        }

        String userMessage = buildUserMessage(level, viableLocations, cloudByLocation,
                spaceWeather, metOfficeText, triggerType, tonightWindow);

        LOG.info("Aurora Claude call: {} locations, level={}, trigger={}",
                viableLocations.size(), level, triggerType);

        Message response = anthropicApiClient.createMessage(
                MessageCreateParams.builder()
                        .model(MODEL)
                        .maxTokens(MAX_TOKENS)
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder().text(SYSTEM_PROMPT).build()))
                        .addUserMessage(userMessage)
                        .build());

        String raw = response.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));

        return parseResponse(raw, level, viableLocations, cloudByLocation);
    }

    /**
     * Builds the user message with all space weather data, trigger context, and location details.
     */
    String buildUserMessage(AlertLevel level,
            List<LocationEntity> locations,
            Map<LocationEntity, Integer> cloudByLocation,
            SpaceWeatherData spaceWeather,
            String metOfficeText,
            TriggerType triggerType,
            TonightWindow tonightWindow) {
        StringBuilder sb = new StringBuilder();

        // Trigger type context — tells Claude whether to use planning or urgent language
        sb.append("TRIGGER TYPE: ").append(triggerType.name().toLowerCase()).append("\n");
        sb.append("CURRENT TIME (UTC): ").append(
                ZonedDateTime.now(ZoneOffset.UTC).toLocalDateTime().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n");

        if (tonightWindow != null) {
            sb.append("TONIGHT'S DARK WINDOW (UK): ")
                    .append(TIME_FMT.format(tonightWindow.dusk()))
                    .append(" – ")
                    .append(TIME_FMT.format(tonightWindow.dawn()))
                    .append(" (nautical dusk to dawn)\n");
        }
        sb.append("\n");

        sb.append("CURRENT ALERT LEVEL: ").append(level.name())
                .append(" — ").append(level.description()).append("\n\n");

        // Kp trend (last 5 readings)
        sb.append("KP TREND (recent, oldest→newest):\n");
        List<KpReading> kpReadings = spaceWeather.recentKp();
        int startKp = Math.max(0, kpReadings.size() - 5);
        for (KpReading r : kpReadings.subList(startKp, kpReadings.size())) {
            sb.append(String.format("  %s  Kp=%.1f%n", r.timestamp().withZoneSameInstant(ZoneOffset.UTC)
                    .toLocalTime(), r.kp()));
        }
        sb.append("\n");

        // Kp forecast (next 24h)
        sb.append("KP FORECAST (next 24 hours):\n");
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime cutoff = now.plusHours(24);
        for (KpForecast f : spaceWeather.kpForecast()) {
            if (f.from().isAfter(cutoff)) {
                break;
            }
            if (f.to().isBefore(now)) {
                continue;
            }
            sb.append(String.format("  %s–%s  Kp=%.1f%n",
                    f.from().withZoneSameInstant(ZoneOffset.UTC).toLocalTime(),
                    f.to().withZoneSameInstant(ZoneOffset.UTC).toLocalTime(),
                    f.kp()));
        }
        sb.append("\n");

        // OVATION
        OvationReading ovation = spaceWeather.ovation();
        if (ovation != null) {
            sb.append(String.format("OVATION AURORA PROBABILITY at 55°N: %.1f%%%n%n",
                    ovation.probabilityAtLatitude()));
        }

        // Solar wind Bz (last 15 readings = 15 minutes)
        List<SolarWindReading> solarWind = spaceWeather.recentSolarWind();
        if (!solarWind.isEmpty()) {
            sb.append("SOLAR WIND Bz (last 15 min, oldest→newest):\n");
            int startSw = Math.max(0, solarWind.size() - 15);
            for (SolarWindReading r : solarWind.subList(startSw, solarWind.size())) {
                sb.append(String.format("  Bz=%.1f nT", r.bzNanoTesla()));
                if (r.speedKmPerSec() > 0) {
                    sb.append(String.format(", speed=%.0f km/s", r.speedKmPerSec()));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Active alerts
        if (!spaceWeather.activeAlerts().isEmpty()) {
            sb.append("ACTIVE NOAA ALERTS:\n");
            spaceWeather.activeAlerts().forEach(a ->
                    sb.append("  [").append(a.messageType()).append("] ")
                            .append(a.message(), 0, Math.min(200, a.message().length()))
                            .append("\n"));
            sb.append("\n");
        }

        // Met Office narrative
        if (metOfficeText != null && !metOfficeText.isBlank()) {
            sb.append("MET OFFICE SPACE WEATHER FORECAST:\n").append(metOfficeText).append("\n\n");
        }

        // Location list
        sb.append("LOCATIONS TO SCORE (").append(locations.size()).append(" locations):\n");
        for (int i = 0; i < locations.size(); i++) {
            LocationEntity loc = locations.get(i);
            int cloud = cloudByLocation.getOrDefault(loc, 50);
            sb.append(String.format("  %d. %s — lat=%.3f, Bortle=%s, cloud=%d%%%n",
                    i + 1, loc.getName(), loc.getLat(),
                    loc.getBortleClass() != null ? loc.getBortleClass() : "unknown",
                    cloud));
        }
        sb.append("\nReturn a JSON array with ").append(locations.size())
                .append(" objects in the same order.");

        return sb.toString();
    }

    /**
     * Parses Claude's JSON array response into {@link AuroraForecastScore} objects.
     *
     * <p>Falls back to 1★ for any location whose entry cannot be parsed.
     */
    List<AuroraForecastScore> parseResponse(String raw, AlertLevel level,
            List<LocationEntity> locations,
            Map<LocationEntity, Integer> cloudByLocation) {
        String cleaned = raw.trim()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .trim();

        List<AuroraForecastScore> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            for (int i = 0; i < locations.size(); i++) {
                LocationEntity loc = locations.get(i);
                int cloud = cloudByLocation.getOrDefault(loc, 50);
                if (i < root.size()) {
                    JsonNode node = root.get(i);
                    int stars = clamp(node.path("stars").asInt(1), 1, 5);
                    String summary = node.path("summary").asText("Aurora conditions assessed");
                    String detail = node.path("detail").asText("");
                    results.add(new AuroraForecastScore(loc, stars, level, cloud, summary, detail));
                } else {
                    results.add(fallbackScore(loc, level, cloud));
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse Claude aurora response: {}. Using fallback scores.", e.getMessage());
            for (LocationEntity loc : locations) {
                results.add(fallbackScore(loc, level, cloudByLocation.getOrDefault(loc, 50)));
            }
        }
        return results;
    }

    private AuroraForecastScore fallbackScore(LocationEntity loc, AlertLevel level, int cloud) {
        String summary = level.name().charAt(0) + level.name().substring(1).toLowerCase()
                + " geomagnetic activity — conditions could not be assessed";
        return new AuroraForecastScore(loc, 1, level, cloud, summary,
                "– Score unavailable — error parsing Claude response");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
