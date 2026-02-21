package com.gregochr.goldenhour.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AnthropicProperties;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.springframework.stereotype.Service;

/**
 * Evaluates sunrise/sunset colour potential by calling the Claude API.
 *
 * <p>Sends atmospheric forecast data to Claude and parses the JSON response
 * into a 1-5 colour potential rating and plain-English summary.
 */
@Service
public class EvaluationService {

    private static final String SYSTEM_PROMPT =
            "You are an expert sunrise/sunset colour potential advisor for landscape photographers.\n"
            + "Rate colour potential 1-5 and explain briefly.\n\n"
            + "Key criteria: clear horizon critical (high low cloud >70% = poor); "
            + "mid/high cloud above clear horizon = ideal canvas; "
            + "post-rain clearing often vivid; "
            + "moderate aerosol/dust (AOD 0.1-0.25) enhances red scattering; "
            + "high humidity (>80%) mutes colours; "
            + "low boundary layer traps aerosols near surface; "
            + "fully clear sky = 2-3; total overcast = 1.\n\n"
            + "Solar/antisolar horizon model: at sunset the sun is west — the solar horizon "
            + "(west) must be clear for light penetration, while mid/high cloud on the antisolar "
            + "side (east) at 20-60% catches and reflects colour. Sunrise is the reverse. "
            + "Since data is non-directional, use altitude as proxy: low cloud (0-3km) sits near "
            + "the horizon and blocks light; mid (3-8km) and high (8+km) cloud sits above and "
            + "catches it. Ideal: low cloud <30% with mid/high 20-60%.\n\n"
            + "Respond ONLY with: {\"rating\": <1-5>, \"summary\": \"<2 sentences>\"}";

    private final AnthropicClient client;
    private final AnthropicProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Constructs an {@code EvaluationService}.
     *
     * @param client       configured Anthropic client
     * @param properties   Anthropic configuration (model identifier)
     * @param objectMapper Jackson mapper for parsing Claude's JSON response
     */
    public EvaluationService(AnthropicClient client, AnthropicProperties properties,
            ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluates the colour potential for a solar event.
     *
     * @param data the atmospheric forecast data to evaluate
     * @return Claude's colour potential rating and plain-English explanation
     */
    public SunsetEvaluation evaluate(AtmosphericData data) {
        Message response = client.messages().create(
                MessageCreateParams.builder()
                        .model(properties.getModel())
                        .maxTokens(256)
                        .system(SYSTEM_PROMPT)
                        .addUserMessage(buildUserMessage(data))
                        .build());

        String text = response.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude returned no text content"));

        return parseEvaluation(text);
    }

    private String buildUserMessage(AtmosphericData data) {
        return String.format(
                "Location: %s. %s: %s UTC.%n"
                + "Cloud: Low %d%%, Mid %d%%, High %d%%%n"
                + "Visibility: %,dm, Wind: %.2f m/s (%d\u00b0), Precip: %.2fmm%n"
                + "Humidity: %d%%, Weather code: %d%n"
                + "Boundary layer: %dm, Shortwave: %.0f W/m\u00b2%n"
                + "PM2.5: %s\u00b5g/m\u00b3, Dust: %s\u00b5g/m\u00b3, AOD: %s%n"
                + "Rate 1-5 and explain in 2-3 sentences.",
                data.locationName(), data.targetType(), data.solarEventTime(),
                data.lowCloudPercent(), data.midCloudPercent(), data.highCloudPercent(),
                data.visibilityMetres(), data.windSpeedMs(), data.windDirectionDegrees(),
                data.precipitationMm(),
                data.humidityPercent(), data.weatherCode(),
                data.boundaryLayerHeightMetres(), data.shortwaveRadiationWm2(),
                data.pm25(), data.dustUgm3(), data.aerosolOpticalDepth());
    }

    /**
     * Parses Claude's JSON response text into a {@link SunsetEvaluation}.
     *
     * <p>Package-private so it can be unit-tested independently of the HTTP call.
     *
     * @param json the raw JSON string returned by Claude
     * @return the parsed evaluation
     * @throws IllegalArgumentException if the response cannot be parsed
     */
    SunsetEvaluation parseEvaluation(String json) {
        try {
            String cleaned = json.trim()
                    .replaceAll("(?s)^```(?:json)?\\s*", "")
                    .replaceAll("(?s)\\s*```$", "")
                    .trim();
            JsonNode node = objectMapper.readTree(cleaned);
            int rating = node.get("rating").asInt();
            String summary = node.get("summary").asText();
            return new SunsetEvaluation(rating, summary);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse Claude evaluation response: " + json, e);
        }
    }
}
