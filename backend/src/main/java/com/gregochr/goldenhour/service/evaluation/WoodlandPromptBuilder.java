package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.JsonOutputFormat;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.WoodlandVerdictEvaluator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Builds the year-round woodland prompt — the evaluator for a location under a canopy, where the
 * sky is not in the frame.
 *
 * <p><b>Why a sibling of {@link BluebellPromptBuilder} rather than a branch inside it.</b> The two
 * overlap on light and diverge on everything else. Bluebell asks one question inside a six-week
 * window and must hedge <em>bloom</em>; woodland runs all twelve months and must hedge <em>leaf
 * state</em>, which changes what the wood even is — translucent spring green, flat summer dark,
 * autumn colour, bare winter graphics. Folding both into one conditional system prompt would put
 * two unrelated phenological caveats and two seasonal rubrics in one string, and buy nothing:
 * Anthropic caches each distinct system prompt separately, so sharing a builder does not share a
 * cache entry. Two constants, two cache entries, one sign per file.
 *
 * <p><b>Leaf-state honesty (the load-bearing caveat).</b> The date is known, so the <em>season</em>
 * is known. What is not known is the wood's actual state on the day — whether the beeches have
 * turned, whether a gale has already stripped them, whether the spring flush is early. That is
 * phenology and it is not in Open-Meteo. This prompt therefore scores <em>conditions given the
 * season's typical state</em>, and is told to hedge specifics it cannot see, exactly as the
 * bluebell prompt hedges bloom.
 *
 * <p><b>Recent rain is an input, and its window is bounded.</b> Rain that has recently stopped
 * saturates bark and foliage and is one of the strongest predictors of a vivid wood, so
 * {@code precedingPrecipitationMm} is reported. It covers the <em>hours</em> before the slot, not
 * days — which is the right window for wet-surface saturation but says nothing about whether the
 * wood has had a wet season. The prompt is told that distinction explicitly, so it cannot infer a
 * lush or parched wood it was never shown. (A multi-day accumulation is what the WATERFALL subject
 * still lacks, discharge being its dominant term; that is a different gap.)
 *
 * <p><b>Relationship to {@link WoodlandVerdictEvaluator}.</b> That class stays the deterministic,
 * free, always-available verdict. Its verdict and flags are passed here as a hint block — input,
 * not answer — mirroring how the bluebell prompt consumes {@code BluebellConditionService}. Note
 * its own Javadoc argues a Claude-rated woodland subject should wait for recorded outcomes; that
 * evidence cannot arrive while the outcome-recording chain is unwired, and the project already
 * ships one Claude-rated non-sky subject (bluebell) on the same footing.
 */
@Component("woodlandPromptBuilder")
public class WoodlandPromptBuilder {

    /**
     * System prompt for the woodland evaluator. Year-round, leaf-state-honest, and narrow: it
     * scores photographic conditions under a canopy and returns a 1–5 rating with a one-sentence
     * summary. It shares no rubric with the sky prompt, because a wood has no sky in the frame.
     */
    static final String SYSTEM_PROMPT =
            "You are a woodland photography conditions advisor for landscape photographers in the "
            + "UK. The location is under tree canopy: THE SKY IS NOT IN THE FRAME. Never score "
            + "sunset colour, cloud drama, or horizon clarity — none of it is visible from inside "
            + "a wood. Score how good the conditions are for photographing the wood itself.\n\n"
            + "LEAF-STATE ASSUMPTION (read first — this bounds your whole answer):\n"
            + "You are given the date, so you know the season. You do NOT know the wood's actual "
            + "state on the day — whether the beeches have turned, whether a gale has already "
            + "stripped them, whether the spring flush is early or late. Score CONDITIONS FOR THE "
            + "SEASON'S TYPICAL STATE, and hedge anything you cannot see. Say 'if the colour has "
            + "turned', not 'the beeches are glowing'.\n\n"
            + "RATING (1-5):\n"
            + "  1 = forget it (unsafe, or light that no exposure holds)\n"
            + "  2 = poor (workable only for the determined)\n"
            + "  3 = fair (a competent photographer gets a decent frame)\n"
            + "  4 = go (genuinely good light for the wood)\n"
            + "  5 = drop everything (mist with low sun through the trunks; reserve for the rare "
            + "morning)\n\n"
            + "LIGHT IS THE PRIMARY FACTOR, and its polarity is the OPPOSITE of open-landscape "
            + "work:\n"
            + "- Heavy overcast is GOOD. A thick even sky is a softbox over the whole wood; it "
            + "holds the range between bright canopy and dark trunk that nothing else can.\n"
            + "- Broken cloud with the sun behind it is the WORST case. Dapple through leaves gives "
            + "blinding hotspots against crushed shade in the same frame — harder than clear sky.\n"
            + "- Clear bright sun is poor for the same reason, though a low sun early or late can "
            + "rake through the trunks horizontally and work.\n"
            + "- MIST is the prize, and the single thing most worth travelling for. Mist with a low "
            + "sun gives shafts and separation between trunks that no other condition produces. "
            + "Convey urgency when it is on offer. But fog so thick that nothing resolves beyond a "
            + "few metres is a white frame, not atmosphere — mark it down.\n\n"
            + "WIND is discounted but not ignored:\n"
            + "- The canopy shelters the interior, so ordinary breezes matter far less than they "
            + "would on open ground.\n"
            + "- Dim woodland light forces long exposures, which re-sensitise the frame to even "
            + "small leaf movement — so a moderate breeze still costs you the long-exposure and "
            + "macro options.\n"
            + "- Strong wind is a SAFETY matter, not an aesthetic one. A wood in a gale drops "
            + "branches; that is a stand-down regardless of how good the light is.\n\n"
            + "RAIN: active rain during the window is bad (drops on the front element). Rain that "
            + "has recently STOPPED is a strong positive — wet bark and wet foliage saturate, and "
            + "greens and autumn colour deepen markedly. You are given the rain that fell in the "
            + "HOURS before the slot, which is what wets the surfaces. You are NOT given the days "
            + "or weeks before, so judge wet-surface saturation only; do not assert the wood is "
            + "lush, parched, or in drought.\n\n"
            + "SEASON (use the date; hedge the specifics):\n"
            + "- SPRING (Mar-May): fresh translucent green, the most luminous the wood gets. "
            + "Backlight through new leaves is a subject in itself. High ceiling.\n"
            + "- SUMMER (Jun-Aug): heavy dark canopy, green-on-green, little tonal separation. The "
            + "hardest season — mist or rain-saturated colour is usually what rescues it.\n"
            + "- AUTUMN (Sep-Nov): peak season. Colour plus mist is the combination the whole year "
            + "builds to; be generous when both are present, and note that colour without mist is "
            + "still well worth the trip.\n"
            + "- WINTER (Dec-Feb): bare graphic trunks. Needs mist, frost or snow to sing; plain "
            + "grey damp woodland in January is genuinely mediocre and should be scored as such. "
            + "Frost or fresh snow with still air lifts it sharply.\n\n"
            + "SUBJECT-QUALITY INPUT: a WOODLAND CONDITIONS block carrying a deterministic verdict "
            + "and flags is provided as a hint. Treat it as input, not the answer — apply the "
            + "rubric above and form your own judgement. It knows nothing about the season.\n\n"
            + "Summaries must be exactly one sentence. Do not write two sentences even if "
            + "separated by a semicolon, dash, or conjunction. Do not use double-quote characters "
            + "within the summary text.\n\n"
            + "HEADLINE FIELD (optional): you may also output a `headline` field — a single short "
            + "fragment (4-9 words) capturing the verdict at a glance, in Claude's voice, that "
            + "reads naturally on its own. It must hedge leaf state the same way the summary does. "
            + "Do not write \"headline:\" or include the rating in the text. Do not use "
            + "double-quote characters within the headline text.\n\n"
            + "CRITICAL OUTPUT FORMAT RULES:\n"
            + "- Respond ONLY with a single valid JSON object.\n"
            + "- Do NOT write reasoning, thinking, or commentary inside the JSON values.\n"
            + "- Do NOT use markdown, asterisks, or bullet points anywhere in the output.\n"
            + "- The first character of your response MUST be {.\n"
            + "- The last character of your response MUST be }.\n"
            + "- You MUST include both required fields: rating, summary.";

    /** Prompt suffix appended to the user message. */
    static final String PROMPT_SUFFIX =
            "Rate the woodland-photography conditions 1-5 for this slot, then summarise in exactly "
            + "one sentence.";

    /** Conversion factor from m/s to km/h, mirroring the deterministic evaluator. */
    private static final double MS_TO_KMH = 3.6;

    private final WoodlandVerdictEvaluator woodlandVerdictEvaluator;

    /**
     * Creates the builder.
     *
     * @param woodlandVerdictEvaluator supplies the deterministic verdict and flags used as the
     *                                 subject-quality hint block
     */
    public WoodlandPromptBuilder(WoodlandVerdictEvaluator woodlandVerdictEvaluator) {
        this.woodlandVerdictEvaluator = woodlandVerdictEvaluator;
    }

    /**
     * Returns the woodland system prompt.
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
     * Builds the woodland user message: location, event, date (so the model can reason about
     * season), the woodland-relevant weather facts, and the deterministic hint block.
     *
     * <p>Unlike the bluebell builder this requires no precomputed condition score — the
     * deterministic verdict is derived here from the same {@link AtmosphericData} the message
     * already reports, so there is no augmentor precondition for a caller to violate.
     *
     * @param data the atmospheric data for the slot
     * @return the formatted user message
     */
    public String buildUserMessage(AtmosphericData data) {
        var w = data.weather();
        var cloud = data.cloud();
        var comfort = data.comfort();

        var metrics = new WoodlandVerdictEvaluator.WoodlandMetrics(
                cloud.lowCloudPercent(), cloud.midCloudPercent(), w.visibilityMetres(),
                w.precipitationMm(), w.windSpeedMs());
        Verdict verdict = woodlandVerdictEvaluator.determineVerdict(metrics);
        List<String> flags = woodlandVerdictEvaluator.buildFlags(metrics);

        double windKmh = w.windSpeedMs() != null ? w.windSpeedMs().doubleValue() * MS_TO_KMH : 0.0;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Location: %s. %s: %s UTC.%n",
                data.locationName(), data.targetType(), data.solarEventTime()));
        sb.append(String.format("Date: %s (month: %s)%n",
                data.solarEventTime().toLocalDate(),
                data.solarEventTime().getMonth()));
        sb.append(String.format("Wind: %.1f km/h (%.2f m/s)%n", windKmh,
                w.windSpeedMs() != null ? w.windSpeedMs().doubleValue() : 0.0));
        sb.append(String.format("Cloud cover: low %d%%, mid %d%% (combined diffuse cover %d%%), "
                        + "high %d%%%n",
                cloud.lowCloudPercent(), cloud.midCloudPercent(),
                Math.min(100, cloud.lowCloudPercent() + cloud.midCloudPercent()),
                cloud.highCloudPercent()));
        sb.append(String.format("Visibility: %,dm%n", w.visibilityMetres()));
        sb.append(String.format("Temperature: %s, Dew point: %s, Humidity: %d%%%n",
                comfort.temperatureCelsius() != null
                        ? String.format("%.1f°C", comfort.temperatureCelsius()) : "N/A",
                w.dewPointCelsius() != null
                        ? String.format("%.1f°C", w.dewPointCelsius()) : "N/A",
                w.humidityPercent()));
        sb.append(String.format("Precipitation at the slot: %s mm%n",
                w.precipitationMm() != null
                        ? String.format("%.2f", w.precipitationMm().doubleValue()) : "0.00"));
        sb.append(String.format("Rain in the preceding hours: %s%n",
                w.precedingPrecipitationMm() != null
                        ? String.format("%.2f mm", w.precedingPrecipitationMm())
                        : "not available"));
        if (w.snowDepthMetres() != null && w.snowDepthMetres() > 0) {
            sb.append(String.format("Snow depth: %.2f m%n", w.snowDepthMetres()));
        }
        sb.append(String.format("WOODLAND CONDITIONS (deterministic hint, season-blind):%n"
                        + "Verdict: %s%n"
                        + "Flags: %s%n",
                verdict.name(), flags.isEmpty() ? "(none notable)" : String.join(", ", flags)));
        sb.append(getPromptSuffix());
        return sb.toString();
    }

    /**
     * Builds the structured-output configuration constraining the response to {@code rating}
     * (1-5), {@code summary}, and an optional {@code headline}.
     *
     * <p>Deliberately identical in shape to the bluebell config, so the result path (parser,
     * visitor, gloss) needs no woodland-specific response handling.
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
