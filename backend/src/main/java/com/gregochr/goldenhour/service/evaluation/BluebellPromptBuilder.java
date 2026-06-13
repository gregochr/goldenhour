package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.JsonOutputFormat;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Builds the dedicated bluebell-conditions prompt — a standalone evaluator, NOT a decorator
 * over the sky {@link PromptBuilder}.
 *
 * <p><b>Why standalone.</b> Pass 3 of the forecast-score re-architecture extracts bluebell from
 * the standard prompt (where it moonlighted as a rating-boost rule) into its own prompt and a
 * {@code BluebellVisitor}. The sky rubric (directional cloud, aerosol, fiery-sky canvas) is
 * irrelevant to whether a bluebell wood photographs well, so this builder shares none of it; it
 * asks a single focused question and returns a 1–5 bluebell rating plus a one-sentence summary.
 *
 * <p><b>Phenology honesty (the load-bearing caveat).</b> Whether the bluebells are actually in
 * bloom — and at peak versus gone over — is true phenology. It is not in Open-Meteo and is
 * modelled nowhere; the system leans on the configured season window as a bloom proxy. This
 * prompt therefore scores <em>conditions given assumed bloom</em> — "if the bluebells are out,
 * how good is this morning for photographing them" — and is told explicitly NOT to imply bloom
 * confirmation it does not have. The user-facing summary must hedge ("ideal light if they're in
 * bloom"), never assert ("the bluebells are spectacular tonight"). If a phenology estimate is
 * ever derived it can be fed in as an extra input line — a genuine advantage of a Claude prompt
 * over a flag-counter — without restructuring this contract.
 *
 * <p><b>Exposure-differentiated rubric.</b> WOODLAND and OPEN_FELL photograph under opposite
 * ideal weather, so the rubric branches on {@link BluebellExposure}. The deterministic
 * {@code BluebellConditionService} is demoted to a subject-quality INPUT block (its flags and
 * summary), not the score — Claude weighs the rubric.
 */
@Component("bluebellPromptBuilder")
public class BluebellPromptBuilder {

    /**
     * System prompt for the bluebell evaluator. Exposure-differentiated, phenology-honest, and
     * deliberately narrow: it scores photographic conditions for a bluebell display assumed to be
     * in bloom, returning a 1–5 rating and a one-sentence summary.
     */
    static final String SYSTEM_PROMPT =
            "You are a bluebell photography conditions advisor for landscape photographers in the "
            + "UK. A bluebell location has been identified as in season. Score how good the "
            + "conditions are on the morning/evening in question for photographing the display.\n\n"
            + "BLOOM ASSUMPTION (read first — this bounds your whole answer):\n"
            + "You do NOT know whether the bluebells are actually open, at peak, or gone over. "
            + "That is flowering phenology and it is not in the data you are given. Score "
            + "CONDITIONS GIVEN ASSUMED BLOOM: 'if the bluebells are out, how good is this slot "
            + "for shooting them'. Do NOT claim the bluebells are spectacular, peaking, or "
            + "carpeting the ground — you cannot see them. The summary must hedge bloom (e.g. "
            + "'ideal light if they're in flower'), never assert it.\n\n"
            + "RATING (1-5):\n"
            + "  1 = forget it (the conditions ruin the shot — wind shredding the flowers, heavy "
            + "rain, or flat harsh light)\n"
            + "  2 = poor (workable only for the determined; conditions fight you)\n"
            + "  3 = fair (a competent photographer gets a decent frame)\n"
            + "  4 = go (genuinely good light and stillness for the display)\n"
            + "  5 = drop everything (the rare morning — soft directional light or mist with calm "
            + "air; reserve for the exceptional)\n\n"
            + "WOODLAND exposure (sheltered under canopy):\n"
            + "- LIGHT is the primary factor. Bright overcast is favourable — it lifts the carpet "
            + "evenly without blowing the highlights. Harsh direct high sun is penalised: it "
            + "dapples into blinding hotspots and crushed shade that no exposure recovers.\n"
            + "- MIST with low sun is the 5-star case — shafts and crepuscular rays through the "
            + "trunks over the carpet. Convey urgency when it is on offer.\n"
            + "- WIND is heavily discounted but NOT ignored. The canopy shelters the flowers, so "
            + "only sustained strong wind (roughly 20-25 mph / 32-40 km/h or gusty) matters — and "
            + "in April the canopy is only part-leafed, so gusts still reach the ground. Dim "
            + "woodland light also forces long exposures, which re-sensitise the frame to even "
            + "residual flower movement. So discount light breezes heavily; still respect a real "
            + "blow.\n"
            + "- ACTIVE rain during the window is bad (drops on the lens, no shooting). Recent "
            + "rain that has stopped is a positive — it saturates colour and freshens the ground.\n\n"
            + "OPEN_FELL exposure (exposed hillside, no canopy):\n"
            + "- WIND is the primary gate, at face value — there is no canopy, so a stiff breeze "
            + "shreds the flowers and ends the shot. Calm air is essential.\n"
            + "- GOLDEN-HOUR directional light across the slope is favourable — low warm sun rakes "
            + "the hillside and models the flowers. The state of the sky behind the fell matters "
            + "too (a flat white sky is a weaker backdrop than broken cloud catching colour).\n"
            + "- MIST against a backdrop (a lake below, the valley) can be extraordinary.\n\n"
            + "SUBJECT-QUALITY INPUT: a CONDITIONS ANALYSIS block of deterministic flags is "
            + "provided as a hint. Treat it as input, not the answer — apply the rubric above and "
            + "form your own judgement. It does NOT carry bloom information.\n\n"
            + "Summaries must be exactly one sentence. Do not write two sentences even if "
            + "separated by a semicolon, dash, or conjunction. Do not use double-quote characters "
            + "within the summary text.\n\n"
            + "HEADLINE FIELD (optional): you may also output a `headline` field — a single short "
            + "fragment (4-9 words) capturing the verdict at a glance, in Claude's voice, that "
            + "reads naturally on its own. It must hedge bloom the same way the summary does. Do "
            + "not write \"headline:\" or include the rating in the text. Do not use double-quote "
            + "characters within the headline text.\n\n"
            + "CRITICAL OUTPUT FORMAT RULES:\n"
            + "- Respond ONLY with a single valid JSON object.\n"
            + "- Do NOT write reasoning, thinking, or commentary inside the JSON values.\n"
            + "- Do NOT use markdown, asterisks, or bullet points anywhere in the output.\n"
            + "- The first character of your response MUST be {.\n"
            + "- The last character of your response MUST be }.\n"
            + "- You MUST include both required fields: rating, summary.";

    /** Prompt suffix appended to the user message. */
    static final String PROMPT_SUFFIX =
            "Rate the bluebell-photography conditions 1-5 (assuming the flowers are in bloom), "
            + "then summarise in exactly one sentence.";

    /** Conversion factor from m/s to km/h, mirroring the deterministic scorer. */
    private static final double MS_TO_KMH = 3.6;

    /**
     * Returns the bluebell system prompt.
     *
     * @return the system prompt string
     */
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Returns the prompt suffix appended to each user message.
     *
     * @return the prompt suffix string
     */
    public String getPromptSuffix() {
        return PROMPT_SUFFIX;
    }

    /**
     * Builds the bluebell user message: location, event, the bluebell-relevant weather facts, and
     * the deterministic CONDITIONS ANALYSIS hint block (with exposure).
     *
     * <p>Requires {@link AtmosphericData#bluebellConditionScore()} to be present — the caller
     * (Pass 3 collector) only routes in-season bluebell sites to this builder, and the augmentor
     * populates the condition score for exactly those. A null score is a programming error.
     *
     * @param data the atmospheric data, with a populated bluebell condition score
     * @return the formatted user message
     */
    public String buildUserMessage(AtmosphericData data) {
        BluebellConditionScore bb = data.bluebellConditionScore();
        if (bb == null) {
            throw new IllegalStateException(
                    "BluebellPromptBuilder requires a bluebell condition score for "
                            + data.locationName() + "; the collector must only route in-season "
                            + "bluebell sites here.");
        }
        var w = data.weather();
        var cloud = data.cloud();
        var comfort = data.comfort();
        double windKmh = w.windSpeedMs() != null ? w.windSpeedMs().doubleValue() * MS_TO_KMH : 0.0;
        int avgCloud = (cloud.lowCloudPercent() + cloud.midCloudPercent()
                + cloud.highCloudPercent()) / 3;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Location: %s. %s: %s UTC.%n",
                data.locationName(), data.targetType(), data.solarEventTime()));
        sb.append(String.format("Exposure: %s%n", bb.exposure().name()));
        sb.append(String.format("Wind: %.1f km/h (%.2f m/s)%n", windKmh,
                w.windSpeedMs() != null ? w.windSpeedMs().doubleValue() : 0.0));
        sb.append(String.format("Cloud cover (avg of low/mid/high): %d%% "
                        + "(low %d%%, mid %d%%, high %d%%)%n",
                avgCloud, cloud.lowCloudPercent(), cloud.midCloudPercent(),
                cloud.highCloudPercent()));
        sb.append(String.format("Visibility: %,dm%n", w.visibilityMetres()));
        sb.append(String.format("Temperature: %s, Dew point: %s%n",
                comfort.temperatureCelsius() != null
                        ? String.format("%.1f°C", comfort.temperatureCelsius()) : "N/A",
                w.dewPointCelsius() != null
                        ? String.format("%.1f°C", w.dewPointCelsius()) : "N/A"));
        sb.append(String.format("Precipitation: %s mm%n",
                w.precipitationMm() != null
                        ? String.format("%.2f", w.precipitationMm().doubleValue()) : "0.00"));
        sb.append(String.format("CONDITIONS ANALYSIS (deterministic hint, no bloom info):%n"
                        + "Flags: misty=%s, calm=%s, softLight=%s, goldenHourLight=%s, "
                        + "postRain=%s, dryNow=%s%n"
                        + "Note: %s%n",
                bb.misty(), bb.calm(), bb.softLight(), bb.goldenHourLight(),
                bb.postRain(), bb.dryNow(), bb.summary()));
        sb.append(getPromptSuffix());
        return sb.toString();
    }

    /**
     * Builds the structured-output configuration constraining the response to {@code rating}
     * (1-5), {@code summary}, and an optional {@code headline}.
     *
     * @return the output configuration
     */
    public OutputConfig buildOutputConfig() {
        return OutputConfig.builder()
                .format(JsonOutputFormat.builder()
                        .schema(JsonOutputFormat.Schema.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.ofEntries(
                                        Map.entry("rating", Map.of(
                                                "type", "integer",
                                                "enum", List.of(1, 2, 3, 4, 5))),
                                        Map.entry("summary", Map.of("type", "string")),
                                        Map.entry("headline", Map.of(
                                                "type", "string",
                                                "description",
                                                "4-9 word card header in Claude's voice.")))))
                                .putAdditionalProperty("required", JsonValue.from(
                                        List.of("rating", "summary")))
                                .putAdditionalProperty("additionalProperties",
                                        JsonValue.from(false))
                                .build())
                        .build())
                .build();
    }
}
