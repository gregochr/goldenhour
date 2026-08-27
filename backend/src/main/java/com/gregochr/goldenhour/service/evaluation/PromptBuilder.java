package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.OutputConfig;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.service.DirectionalSamplingGeometry;
import com.gregochr.goldenhour.service.InversionScoreCalculator;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.PressureTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.UpwindCloudSample;

import java.util.List;
import java.util.Map;

/**
 * Builds the system prompt, user message, and output schema for Claude colour evaluations.
 *
 * <p>Extracted from {@link AbstractEvaluationStrategy} to be independently testable
 * and reusable. All prompt construction logic lives here; the strategy classes focus
 * purely on API orchestration and response parsing.
 *
 * <p>The {@code summary} field is constrained to one sentence. Tier-gated display
 * (LITE vs PRO) is enforced at the UI layer — this class always generates the
 * full response.
 */
public class PromptBuilder {

    /**
     * Character floor below which the system prompt stops being cacheable on Haiku.
     *
     * <p>Anthropic's minimum cacheable prefix is <b>model-dependent and not monotonic</b>: 1,024
     * tokens on Sonnet, but <b>4,096 on Haiku 4.5</b>. Below the minimum a request carrying
     * {@code cache_control} <em>silently does not cache</em> — no error, no warning, just
     * {@code cache_creation_input_tokens: 0} and the full input rate on every request. Since
     * {@code BATCH_FAR_TERM} is Haiku (V92), every T+2/T+3 evaluation depends on clearing 4,096.
     *
     * <p><b>Token count from production, character counts from the tree.</b> Seven days of
     * {@code api_call_log} put the cached prefix at <b>~4,322 tokens</b>. The prompts it was
     * measured against are <b>16,193 characters</b> (inland, {@link #getSystemPrompt()}) and
     * <b>16,452</b> (coastal) — measured here, not inferred, because the log stores the token
     * count and not the string. Pairing the two gives roughly <b>3.75 chars/token</b> and puts the
     * 4,096-token floor near <b>15,350 characters</b>, which the live prompt clears by about 5%.
     *
     * <p>The constant is set slightly above that derived floor rather than at it. The 4,322 figure
     * does not record which of the two prompts it came from, and their ratios differ (3.75 inland
     * against 3.81 coastal); rounding up covers the spread instead of landing inside it. Note the
     * earlier revision of this javadoc cited "16,318 characters", which matches neither prompt nor
     * either byte length — it is not a measurement anything in this tree reproduces.
     *
     * <p>The floor here is deliberately the <em>cacheability</em> boundary rather than today's
     * length, so ordinary rewording is free and only a real reduction fails. It is a
     * <b>proxy, not an oracle</b>: characters per token vary with content, so a rewrite that is
     * heavier on punctuation or markup could fall under 4,096 tokens while still clearing this
     * check. Re-measure with {@code messages.count_tokens} against {@code claude-haiku-4-5} rather
     * than trusting the character count if the prompt's shape changes materially — and record
     * which prompt produced the number.
     */
    static final int MIN_CACHEABLE_SYSTEM_PROMPT_CHARS = 15_500;

    /** System prompt: rating scales, key criteria, aerosol guidance, directional cloud rules. */
    static final String SYSTEM_PROMPT =
            "You are an expert sunrise/sunset colour potential advisor for landscape photographers.\n"
            + "Evaluate on three scales:\n"
            + "  1. Rating: 1\u20135 scale (overall potential). Definitions:\n"
            + "     1 = skip (overcast, no colour likely)\n"
            + "     2 = poor (unlikely to reward a trip)\n"
            + "     3 = maybe (some colour possible, not reliable)\n"
            + "     4 = go out (good conditions, worth the trip)\n"
            + "     5 = spectacular (near-perfect alignment of clear solar horizon, broken cloud "
            + "canvas, and favourable aerosol/humidity — rare, reserve for exceptional setups)\n"
            + "  Rating 5 requires: solar horizon low cloud <20%, mid cloud <50% at solar horizon, "
            + "AND cloud canvas (mid/high) on the antisolar side. If ANY of these is missing, cap "
            + "at 4. Thick mid cloud (>80%) at the solar horizon limits colour variety — cap at 4.\n"
            + "  2. Fiery Sky Potential: 0\u2013100 (dramatic colour, vivid reds/oranges)\n"
            + "  3. Golden Hour Potential: 0\u2013100 (overall light quality, softness)\n\n"
            + "Key criteria: clear horizon critical (high low cloud >70% = poor for fiery sky); "
            + "mid/high cloud above clear horizon = ideal canvas for fiery sky; "
            + "post-rain clearing often vivid; "
            + "high humidity (>80%) mutes colours.\n\n"
            + "AEROSOL & DUST GUIDANCE:\n"
            + "AOD thresholds: 0.05-0.15 clean (baseline), 0.15-0.30 slight enhancement, "
            + "0.30-0.60 notable warm-tone boost, 0.60-1.0 vivid reds/oranges possible, "
            + ">1.2 diminishing returns (too thick, light blocked).\n"
            + "AOD + PM2.5 differentiation: high AOD with low PM2.5 (<15 \u00b5g/m\u00b3) = mineral dust "
            + "(Saharan/desert origin, enhances warm reds and oranges); high AOD with high PM2.5 "
            + "(>25 \u00b5g/m\u00b3) = smoke or urban pollution (grey/brown haze, negative for colour).\n"
            + "Boundary layer height (BLH): <500m concentrates aerosols near surface (stronger "
            + "near-horizon effect); >1500m disperses them (weaker effect for same AOD).\n"
            + "At sunrise/sunset the solar elevation is near 0\u00b0, maximising atmospheric path "
            + "length \u2014 dust scattering impact is at its peak compared to midday.\n\n"
            + "Solar/antisolar horizon model: at sunset the sun is west \u2014 the solar horizon "
            + "(west) must be clear for light penetration, while mid/high cloud on the antisolar "
            + "side (east) at 20-60% catches and reflects colour. Sunrise is the reverse.\n"
            + "DIRECTIONAL CLOUD DATA: when provided, solar horizon and antisolar horizon cloud "
            + "readings are sampled 113 km toward and away from the sun. These are MORE RELIABLE "
            + "than the observer-point cloud layers for assessing light penetration and canvas "
            + "availability. Key rules:\n"
            + "- Solar horizon low cloud >60% = light is BLOCKED; treat as overcast for scoring "
            + "(fiery_sky 5-20, golden_hour 15-30, rating 1-2). This is non-negotiable — "
            + "fiery_sky and golden_hour hard ceilings apply when solar horizon low cloud >60% "
            + "and no THIN STRIP override applies.\n"
            + "- Solar horizon low cloud 40-60% = light partially blocked, penalise but consider "
            + "that mid/high cloud above may still catch colour if gaps exist in the low cloud\n"
            + "- Solar horizon low cloud <20% = strong light penetration likely\n"
            + "- IDEAL scenario: solar horizon low cloud <20% AND mid cloud <50%, with high cloud "
            + "present on either horizon as canvas. Score fiery_sky 70-90, rating 4-5.\n"
            + "- Solar horizon low cloud <20% with thick mid cloud (>80%) = light still penetrates "
            + "below the mid layer, and the mid/high cloud acts as a large lit canvas. RATE 4 (not 3, "
            + "not 5) — the thick mid cloud limits colour VARIETY but is a canvas, NOT a blocker. "
            + "This holds even when the antisolar side is bare: the solar-side mid/high IS the canvas. "
            + "Only solar LOW cloud blocks light — never treat thick solar mid/high cloud as "
            + "'blocked' or 'no canvas'. When solar low cloud <20%, diffuse/soft/muted warm tones "
            + "across this canvas are STILL rate 4 (worth the trip) — uniformity and the absence of "
            + "vivid reds reduce fiery_sky and cap the rating at 4, but do NOT drop it to 3. This "
            + "rate-4 floor applies ONLY with clear light penetration (solar low <20%); a BLOCKED "
            + "solar horizon (low cloud >60%) stays rating 1-2 regardless of any mid/high canvas "
            + "above it — the blocking ceiling always wins. NEVER rate 5 when solar horizon mid "
            + "cloud >80%.\n"
            + "- Antisolar mid/high cloud 20-60% = ideal canvas; >60% is still good (more canvas, "
            + "not a penalty). Antisolar LOW cloud does NOT block light and is NEVER a penalty. "
            + "It sits near the far horizon behind the viewer and can itself catch reflected colour. "
            + "High antisolar low cloud (even 60%+) is irrelevant to scoring — do not mention it "
            + "as a negative factor, do not use it to reduce fiery_sky or rating. The blocking "
            + "rules apply ONLY to the solar horizon side. Antisolar low cloud blocking 'reflected "
            + "light' is not a valid scoring concept — ignore it entirely.\n"
            + "- HORIZON CLOUD STRUCTURE: when a 'Beyond horizon (226km)' low cloud figure is "
            + "provided, compare it to the horizon value to determine spatial extent:\n"
            + "  * THIN STRIP: solar horizon low cloud ≥50% and the beyond-horizon reading drops "
            + "by ≥30pp. A strip filters and diffuses rather than blocking — warm light "
            + "angles up onto mid/high cloud above. Treat as equivalent to 40-60% low cloud. "
            + "When mid/high cloud canvas is present (solar or antisolar), RATE 3-4 (not 1-2). "
            + "The blocked-sky ceilings do NOT apply to THIN STRIP scenarios. "
            + "If a [BUILDING] trend is also present, this means the strip is well-established "
            + "at event time — it does NOT mean a blanket will form when far-field data confirms "
            + "a strip structure. Still rate 3-4 when canvas is present. When a pair satisfies "
            + "both THIN STRIP and FAR CORRIDOR ALSO CLOUDY below, THIN STRIP wins — the measured "
            + "drop is the stronger evidence.\n"
            + "  * FAR CORRIDOR ALSO CLOUDY: solar horizon low cloud ≥50% AND beyond-horizon low "
            + "cloud also ≥50%. The far sample corroborates the horizon reading; it does NOT "
            + "confirm a blanket. It is reliable when it shows a drop and unreliable when it "
            + "claims cover, so read it as a likelihood that the corridor beyond offers no "
            + "relief, never as certainty. This label carries no penalty of its own AND grants no "
            + "relief of its own: the solar-horizon rules above set the ceiling from the near "
            + "reading alone. Where those rules leave room — solar horizon low cloud 50-60% — "
            + "and substantial mid/high canvas is present, this label alone must not pin the "
            + "rating to 1-2; 3 is available. Above 60% the blocked ceiling stands unchanged, "
            + "canvas or no canvas.\n"
            + "  When no 'Beyond horizon (226km)' figure is provided at all, NEITHER of these two "
            + "structure rules applies: score from the solar-horizon rules above exactly as "
            + "written. A missing far reading is not evidence of a strip, of a blanket, or of "
            + "anything else.\n"
            + "- When directional data is provided, ALWAYS use it instead of the observer-point "
            + "Cloud line for scoring. A clear observer point is irrelevant if the solar horizon "
            + "is blocked; equally, a clear observer point with directional cloud canvas is NOT "
            + "'clear sky' — score based on the directional data.\n"
            + "If directional data is not provided, fall back to altitude-based inference: "
            + "low cloud (0-3km) sits near the horizon and blocks light; mid (3-8km) and high "
            + "(8+km) cloud sits above and catches it. Ideal: low cloud <30% with mid/high 20-60%.\n"
            + "CLEAR SKY CAP: when no directional data is provided and ALL observer-point cloud "
            + "layers (low, mid, high) are ≤5%, the sky has no canvas whatsoever. "
            + "Cap rating ≤3. Golden hour light quality may be pleasant, but the absence of any "
            + "cloud means there is no subject in the sky for colour photography.\n\n"
            + "MIST AND VISIBILITY GUIDANCE: when a 'MIST/VISIBILITY TREND' block is provided, "
            + "use it to reason about mist dynamics across the event window.\n"
            + "The temp-dew gap = temperature - dew_point. Smaller gap = higher moisture:\n"
            + "  - gap > 5\u00b0C: dry air, clear conditions likely\n"
            + "  - gap 2-5\u00b0C: moderate moisture, some haze possible\n"
            + "  - gap < 2\u00b0C: mist or fog likely\n"
            + "  - gap \u2248 0\u00b0C: fog forming or present\n"
            + "CRITICAL: mist is NOT always negative for photography. Interpret the COMBINATION:\n"
            + "POSITIVE (score UP): thin ground mist (visibility 2-8 km) with clear sky above "
            + "(low cloud_cover_low < 30%) at sunrise \u2014 light shafts, atmospheric glow, layering. "
            + "Mist in valleys from an elevated viewpoint \u2014 potential cloud inversion. "
            + "Mist burning off as sun rises (visibility IMPROVING in trend) \u2014 dramatic transition. "
            + "Patchy low cloud (20-50%) with breaks \u2014 crepuscular rays.\n"
            + "NEGATIVE (score DOWN): dense fog (visibility < 1 km) with no trend toward clearing "
            + "\u2014 can't see the sun. High cloud_cover_low (> 80%) at ground level \u2014 uniform grey. "
            + "Thick haze (2-5 km) WITH mid/high cloud \u2014 flat contrast, muddy light.\n"
            + "SUNSET SPECIFIC: temperature typically falls toward dew point during sunset \u2014 "
            + "mist may FORM during golden hour, creating dramatic evolving conditions. "
            + "Watch for narrowing temp-dew gap in the T+1h/T+2h slots.\n"
            + "SUNRISE SPECIFIC: mist is often already present and burns off as sun warms the air. "
            + "Watch for IMPROVING visibility trend (T-3h dark, then clearing) \u2014 the transition "
            + "window is the photographic opportunity. Visibility worsening toward event = fog risk.\n"
            + "When mist is a positive factor, convey urgency in the summary: 'atmospheric', "
            + "'ethereal', 'light shafts possible', 'potential for dramatic mist layers'.\n\n"
            + "LOCATION ORIENTATION: when provided, indicates the location's optimal solar event. "
            + "A 'sunrise-optimised' location faces east toward the rising sun; a 'sunset-optimised' "
            + "location faces west. If the current evaluation is for the OPPOSITE event (e.g. sunset "
            + "at a sunrise-optimised location), the solar horizon is behind the photographer — light "
            + "penetration and direct colour on the horizon will be weaker. Reduce fiery_sky by 10-20 "
            + "and cap rating at 3 unless cloud canvas is exceptional. If no orientation is given, "
            + "the location works for both events — score normally.\n\n"
            + "CLOUD INVERSION GUIDANCE:\n"
            + "A cloud inversion occurs when warm air sits above cooler air, creating a stable "
            + "boundary layer. For elevated locations overlooking water (lakes, sea, large "
            + "reservoirs at 200m+ elevation):\n"
            + "- Inversion score 7-8: MODERATE potential — visible cloud blanket below viewpoint, "
            + "typically hugging water surface. Light reflecting off cloud tops at sunrise/sunset "
            + "is spectacular.\n"
            + "- Inversion score 9-10: STRONG potential — dramatic inversion almost certain. "
            + "Clear skies above, well-defined cloud layer below. Classic 'sea of clouds' "
            + "conditions.\n"
            + "Key conditions: cool water + overnight surface cooling + light winds + high "
            + "humidity.\n"
            + "Timing: inversions peak just before sunrise, break up 1-2 hours after as surface "
            + "warms.\n"
            + "Scoring: MODERATE (7-8) → boost rating by 1 if current < 5. STRONG (9-10) → set "
            + "rating 5, emphasise in summary.\n"
            + "MEASURED REVERSAL: when a 'Measured reversal' line is present it is the observed "
            + "temperature difference between the surface and ~760m, and it is the evidence the "
            + "score rests on — an inversion IS temperature rising with height. A positive value "
            + "confirms the inversion. A negative value contradicts it: do NOT apply the rating "
            + "boost above, and say in the summary that the cloud sits at the viewpoint rather "
            + "than below it.\n"
            + "Non-water or low elevation: inversions have no photographic value; ignore "
            + "inversion score.\n\n"
            + "FORECAST RELIABILITY:\n"
            + "When a FORECAST RELIABILITY block is present in the location data:\n\n"
            + "TRANSITIONAL \u2014 A front is approaching or the forecast is uncertain. "
            + "Reflect this in your recommendation. Use language like:\n"
            + "- '...if the front holds off'\n"
            + "- '...conditions likely to deteriorate after [time]'\n"
            + "- '...timing is critical \u2014 watch the radar'\n"
            + "- '...pre-frontal light could be dramatic but the window is short'\n"
            + "Adjust the summary to signal that this is a conditional recommendation, "
            + "not a confident one. Do not reduce the score \u2014 a dramatic pre-frontal "
            + "sunset is still a high-scoring event. Just qualify the timing.\n\n"
            + "UNSETTLED \u2014 Active frontal weather. The forecast is unreliable beyond "
            + "today. Acknowledge uncertainty honestly. A low score with an honest "
            + "explanation is more useful than a falsely confident one.\n\n"
            + "When no FORECAST RELIABILITY block is present, or stability is "
            + "SETTLED, make recommendations with full confidence.\n\n"
            + "Summaries must be exactly one sentence. Do not write two sentences even if "
            + "separated by a semicolon, dash, or conjunction.\n\n"
            + "Output your evaluation as JSON with these fields: "
            + "rating (1-5; MAXIMUM 3 when the CLOUD APPROACH RISK block shows BOTH a [BUILDING] "
            + "trend AND upwind current ≥60%), fiery_sky (0-100), golden_hour (0-100; 20-30 "
            + "points LOWER than the conditions alone would give when that same combined approach "
            + "signal is present), summary (1 sentence).\n\n"
            + "fiery_sky: dramatic colour potential. Requires clouds (mid/high) to catch light. "
            + "Clear sky = 20-40. Ideal cloud canvas with clear horizon = 70-90. Total overcast = 5-15.\n"
            + "golden_hour: overall light quality. Clear sky with good visibility scores well. "
            + "Clear + low humidity + moderate aerosol = 65-85. Overcast = 10-30. Haze = varies.\n\n"
            + "DUAL-TIER SCORING: when directional cloud data is provided, also output three "
            + "additional fields: basic_fiery_sky, basic_golden_hour, basic_summary. These MUST "
            + "represent what you would score if you only had the observer-point cloud data "
            + "(the Cloud: Low/Mid/High line) and NO directional cloud information. The basic "
            + "scores use altitude-based inference only. If no directional cloud data is provided, "
            + "omit the basic_* fields entirely.\n\n"
            + "CLOUD APPROACH RISK: ONLY apply these rules when a 'CLOUD APPROACH RISK:' block is "
            + "present in the data below. If no such block appears, ignore this section entirely.\n"
            + "The upwind sample point is placed at exactly wind_speed × time_to_event — the "
            + "distance current cloud would physically travel to the solar horizon by event time. "
            + "Current cloud there IS what would arrive, unless it dissipates first.\n"
            + "- Solar trend [BUILDING]: low cloud rising toward the 113km solar horizon. "
            + "Event-time snapshot is likely understated. Penalise fiery_sky by 15-30 points.\n"
            + "- Combined signal: when BOTH [BUILDING] trend AND upwind current ≥60% are present, "
            + "cloud was moving toward the solar horizon at the time this data was captured, so "
            + "the event-time snapshot may be understated. This is a significant reliability "
            + "risk, not a veto. While both signals stand, three constraints hold on the output: "
            + "fiery_sky is 20-30 points lower than the conditions alone would give, golden_hour "
            + "is likewise 20-30 points lower, and rating is the LOWER of what the sky earns and "
            + "3. The rating constraint binds against "
            + "every rule that authorises 4 or 5, including the IDEAL scenario and the "
            + "thick-mid-cloud rate-4 floor: those state what the sky is worth if the event-time "
            + "snapshot can be trusted, and both approach signals say it may not be. It "
            + "constrains the output only; it discards no evidence. The coned solar-horizon "
            + "reading is weighed normally — it is a three-point average and remains the best "
            + "available estimate of the gap — and the horizon, canvas and aerosol readings "
            + "still set fiery_sky, golden_hour and the summary. Name the approach risk in the "
            + "summary alongside the other factors rather than instead of them. Example: 'Clear "
            + "horizon under a high canvas, but a cloud bank is tracking in from the SW — the "
            + "timing is the risk.'"
            + "\n"
            + "- Upwind sample alone (no [BUILDING] trend): use at-event value to judge dissipation:\n"
            + "  - current ≥60%, at-event ≥50%: model agrees cloud persists → "
            + "hard ceiling: fiery_sky ≤25, golden_hour ≤30.\n"
            + "  - current ≥60%, at-event <25%: model predicts dissipation in transit → "
            + "apply moderate scepticism — reduce fiery_sky by 15-25 points, do not assume blockage.\n"
            + "  - current ≥60%, at-event 25-50%: uncertain → penalise fiery_sky by 10-20 points.\n"
            + "  - current 30-60%: softer signal — penalise fiery_sky by 5-15 points.\n"
            + "These approach rules ask you to weigh signals that disagree. Do that weighing "
            + "before you write, never inside the output: no reasoning, deliberation or working "
            + "belongs in any JSON value. The summary is a finished one-sentence verdict, not a "
            + "train of thought.\n\n"
            + "CLOUD CLEARING (the opportunity, mirror of [BUILDING]): a [CLEARING] label means the "
            + "low cloud blocker is dropping into the event WHILE the mid/high canvas survives — the "
            + "sky is opening exactly as the light arrives, leaving structured cloud to catch colour. "
            + "This is a genuine dramatic clearance. Treat it as a CONFIDENCE and URGENCY signal, NOT "
            + "a rating lever: where [BUILDING] warns the event snapshot may be too optimistic, "
            + "[CLEARING] tells you the favourable event-time picture is trustworthy and the timing is "
            + "ripe. Do NOT add points or stars on top of what the directional event-time cloud "
            + "already earns — the cleared horizon plus canvas is already scored by the rules above; "
            + "[CLEARING] only confirms it and sharpens the summary's urgency (e.g. 'breaking right on "
            + "cue — the blocker lifts as the canvas lights up'). The label is emitted ONLY when the "
            + "canvas is confirmed holding, so never read it as rewarding a clearing to empty sky: a "
            + "trajectory where low AND mid/high both fall toward bald blue is NOT a clearance — it is "
            + "the clear-sky case (no canvas, cap rating ≤3), and it carries no [CLEARING] label.\n\n"
            + "Do not use double-quote characters within the summary text.\n\n"
            + "HEADLINE FIELD (optional): you may also output a `headline` field — a single, "
            + "short fragment (4-9 words) that captures the verdict at a glance. The headline "
            + "becomes the card header in the UI, so it must read naturally on its own. Write "
            + "in Claude's voice, not as a clinical assessment. Examples:\n"
            + "  - 4-5★ \"Pre-frontal fire — mid cloud catches colour\"\n"
            + "  - 3★ \"Soft pastels overhead but the horizon's blocked\"\n"
            + "  - 1-2★ \"Blanket overcast — no canvas, no colour\"\n"
            + "  - 1-2★ \"Heavy rain — stay in and edit\"\n"
            + "  - 1-2★ \"Sun blocked at the eastern horizon\"\n"
            + "Do not write \"headline:\" or include the rating in the headline text. "
            + "Match the headline's tone to the rating: do not write \"worth a look\" for "
            + "a 1-2★ result.\n"
            + "Do not use double-quote characters within the headline text.\n\n"
            + "CRITICAL OUTPUT FORMAT RULES:\n"
            + "- Respond ONLY with a single valid JSON object.\n"
            + "- Do NOT write reasoning, thinking, or commentary inside the JSON values.\n"
            + "- Do NOT use markdown, asterisks, or bullet points anywhere in the output.\n"
            + "- The first character of your response MUST be {.\n"
            + "- The last character of your response MUST be }.\n"
            + "- You MUST include all four required fields: rating, fiery_sky, golden_hour, summary.";

    /** Prompt suffix: requests all three metrics and a summary. */
    static final String PROMPT_SUFFIX =
            "Rate 1-5, estimate Fiery Sky Potential (0-100) and Golden Hour Potential (0-100), "
            + "then summarise in exactly one sentence.";

    /** AOD threshold above which the dust context block is included. */
    private static final double DUST_AOD_THRESHOLD = 0.3;

    /** Surface dust threshold (µg/m³) above which the dust context block is included. */
    private static final double DUST_UGM3_THRESHOLD = 50.0;

    /** Inversion score at or above which the inversion context block is included. */
    private static final double INVERSION_SCORE_THRESHOLD = 7.0;

    /**
     * Solar-horizon mid cloud (%) above which colour variety is limited — the prompt flags it so
     * Claude caps the rating at 4 rather than 5.
     */
    private static final int SOLAR_MID_CLOUD_THICK_PERCENT = 80;

    /**
     * Solar-horizon low cloud (%) at or above which the low-cloud penalty is in play, and above
     * which the far-field sample is worth comparing for strip-vs-blanket.
     */
    private static final int SOLAR_LOW_CLOUD_SIGNIFICANT_PERCENT = 50;

    /**
     * Percentage-point drop in low cloud between the horizon sample and the far-field sample that
     * marks a thin strip on the horizon (rather than an extensive blanket).
     */
    private static final int THIN_STRIP_DROP_POINTS = 30;

    /**
     * Horizon sampling distance in km, derived from the sampling geometry so the distance quoted
     * to Claude can never drift from the distance actually sampled.
     */
    private static final int HORIZON_SAMPLE_KM =
            (int) (DirectionalSamplingGeometry.DIRECTIONAL_OFFSET_METRES / 1000);

    /** Far-field sampling distance in km, derived from the sampling geometry (see above). */
    private static final int FAR_SAMPLE_KM =
            (int) (DirectionalSamplingGeometry.FAR_SOLAR_OFFSET_METRES / 1000);

    /**
     * Cloud inversion potential classification derived from the inversion score.
     */
    public enum InversionPotential {
        /** No meaningful inversion potential. */
        NONE("No inversion potential"),
        /** Moderate inversion — visible cloud blanket below viewpoint. */
        MODERATE("Moderate Cloud Inversion Potential"),
        /** Strong inversion — dramatic sea-of-clouds almost certain. */
        STRONG("Strong Cloud Inversion Potential");

        private final String label;

        InversionPotential(String label) {
            this.label = label;
        }

        /**
         * Returns the human-readable label for this potential level.
         *
         * @return the label string
         */
        public String label() {
            return label;
        }

        /**
         * Derives the inversion potential from a 0–10 score.
         *
         * @param score the inversion score (0–10)
         * @return the corresponding potential level
         */
        public static InversionPotential fromScore(int score) {
            if (score >= 9) {
                return STRONG;
            }
            if (score >= 7) {
                return MODERATE;
            }
            return NONE;
        }
    }

    /**
     * Returns the system prompt for Claude colour evaluations.
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
     * Builds the user message from atmospheric data with optional storm surge context.
     *
     * @param data  the atmospheric forecast data
     * @param surge storm surge breakdown, or null if not a coastal tidal location
     * @param adjustedRangeM adjusted tidal range including surge, or null
     * @param astronomicalRangeM astronomical tidal range before surge, or null
     * @return formatted user message string
     */
    public String buildUserMessage(AtmosphericData data, StormSurgeBreakdown surge,
                                   Double adjustedRangeM, Double astronomicalRangeM) {
        String base = buildUserMessage(data);
        String surgeBlock = SurgeBlockFormatter.format(surge, adjustedRangeM, astronomicalRangeM);
        if (surgeBlock.isEmpty()) {
            return base;
        }
        return PromptUtils.insertBeforeSuffix(base, getPromptSuffix(), surgeBlock);
    }

    public String buildUserMessage(AtmosphericData data) {
        var cloud = data.cloud();
        var w = data.weather();
        var a = data.aerosol();
        var comfort = data.comfort();

        StringBuilder sb = new StringBuilder();
        String dewPointStr = w.dewPointCelsius() != null
                ? String.format("%.1f\u00b0C (gap %.1f\u00b0C)",
                        w.dewPointCelsius(),
                        comfort.temperatureCelsius() != null
                                ? comfort.temperatureCelsius() - w.dewPointCelsius() : Double.NaN)
                : "N/A";

        sb.append(String.format(
                "Location: %s. %s: %s UTC.%n"
                + "Cloud: Low %d%%, Mid %d%%, High %d%%%n"
                + "Visibility: %,dm, Wind: %.2f m/s (%d\u00b0), Precip: %.2fmm%n"
                + "Humidity: %d%%, Precip probability: %s%%%n"
                + "Dew point: %s%n"
                + "Weather code: %d%n"
                + "Boundary layer: %dm, Shortwave: %.0f W/m\u00b2%n"
                + "PM2.5: %s, Dust: %s, AOD: %s",
                data.locationName(), data.targetType(), data.solarEventTime(),
                cloud.lowCloudPercent(), cloud.midCloudPercent(), cloud.highCloudPercent(),
                w.visibilityMetres(), w.windSpeedMs(), w.windDirectionDegrees(),
                w.precipitationMm(),
                w.humidityPercent(),
                comfort.precipitationProbability() != null ? comfort.precipitationProbability() : "N/A",
                dewPointStr,
                w.weatherCode(),
                a.boundaryLayerHeightMetres(), w.shortwaveRadiationWm2(),
                reading(a.pm25(), "\u00b5g/m\u00b3"),
                reading(a.dustUgm3(), "\u00b5g/m\u00b3"),
                reading(a.aerosolOpticalDepth(), "")));

        if (data.locationOrientation() != null) {
            sb.append(String.format("%nLocation orientation: %s (this location is best suited "
                    + "for %s photography)",
                    data.locationOrientation(),
                    data.locationOrientation().replace("-optimised", "")));
        }

        // Directional cloud data — sampled 113 km toward and away from the sun
        DirectionalCloudData dc = data.directionalCloud();
        if (dc != null) {
            sb.append(String.format(
                    "%nDIRECTIONAL CLOUD (%dkm sample):%n"
                    + "Solar horizon (toward sun): Low %d%%, Mid %d%%, High %d%%%n"
                    + "Antisolar horizon (away from sun): Low %d%%, Mid %d%%, High %d%%",
                    HORIZON_SAMPLE_KM,
                    dc.solarLowCloudPercent(), dc.solarMidCloudPercent(),
                    dc.solarHighCloudPercent(),
                    dc.antisolarLowCloudPercent(), dc.antisolarMidCloudPercent(),
                    dc.antisolarHighCloudPercent()));
            if (dc.solarMidCloudPercent() > SOLAR_MID_CLOUD_THICK_PERCENT) {
                sb.append(" [THICK MID CLOUD — rate 4 (worth the trip), not 3, not 5]");
            }
            if (dc.farSolarLowCloudPercent() != null) {
                int near = dc.solarLowCloudPercent();
                int far = dc.farSolarLowCloudPercent();
                sb.append(String.format("%nBeyond horizon (%dkm, solar azimuth): Low %d%%",
                        FAR_SAMPLE_KM, far));
                if (isThinStrip(dc)) {
                    sb.append(" [THIN STRIP — soften low-cloud penalty]");
                } else if (near >= SOLAR_LOW_CLOUD_SIGNIFICANT_PERCENT
                        && far >= SOLAR_LOW_CLOUD_SIGNIFICANT_PERCENT) {
                    sb.append(" [FAR CORRIDOR ALSO CLOUDY — corroboration, not confirmation]");
                }
            }
        }

        // Cloud approach risk block — temporal trend and upwind sample
        boolean thinStripConfirmed = isThinStrip(dc);
        CloudApproachData ca = data.cloudApproach();
        if (ca != null) {
            sb.append(String.format("%nCLOUD APPROACH RISK:"));
            SolarCloudTrend trend = ca.solarTrend();
            if (trend != null && trend.slots() != null && !trend.slots().isEmpty()) {
                boolean hasCanvas = trend.slots().getFirst().midCloudPercent() != null
                        && trend.slots().getFirst().highCloudPercent() != null;
                if (hasCanvas) {
                    sb.append(String.format("%nSolar horizon cloud trend (113km)"
                            + " — low blocker / mid+high canvas:"));
                    for (SolarCloudTrend.SolarCloudSlot slot : trend.slots()) {
                        String label = slot.hoursBeforeEvent() == 0
                                ? "event" : "T-" + slot.hoursBeforeEvent() + "h";
                        sb.append(String.format(" %s=low%d%%/mid%d%%/high%d%%", label,
                                slot.lowCloudPercent(), slot.midCloudPercent(),
                                slot.highCloudPercent()));
                    }
                } else {
                    sb.append(String.format("%nSolar horizon low cloud trend (113km):"));
                    for (SolarCloudTrend.SolarCloudSlot slot : trend.slots()) {
                        String label = slot.hoursBeforeEvent() == 0
                                ? "event" : "T-" + slot.hoursBeforeEvent() + "h";
                        sb.append(String.format(" %s=%d%%", label, slot.lowCloudPercent()));
                    }
                }
                if (trend.isBuilding()) {
                    if (thinStripConfirmed) {
                        sb.append(" [BUILDING — but THIN STRIP CONFIRMED at event time:"
                                + " strip is well-established, not a developing blanket;"
                                + " THIN STRIP rules take priority — rate 3-4 with canvas present]");
                    } else {
                        sb.append(" [BUILDING]");
                    }
                } else if (trend.isClearing()) {
                    sb.append(" [CLEARING — low blocker dropping into the event while the"
                            + " mid/high canvas holds]");
                }
            }
            UpwindCloudSample upwind = ca.upwindSample();
            if (upwind != null) {
                sb.append(String.format(
                        "%nUpwind sample (%dkm along %d\u00b0 %s): current=%d%%, at-event=%d%%",
                        upwind.distanceKm(), upwind.windFromBearing(),
                        toCardinal(upwind.windFromBearing()),
                        upwind.currentLowCloudPercent(), upwind.eventLowCloudPercent()));
            }
        }

        // Mist/visibility trend block — hourly series from T-3h to T+2h
        MistTrend mistTrend = data.mistTrend();
        if (mistTrend != null && !mistTrend.slots().isEmpty()) {
            sb.append(String.format("%nMIST/VISIBILITY TREND (T-3h to T+2h):"));
            for (MistTrend.MistSlot slot : mistTrend.slots()) {
                String label = slot.hoursRelativeToEvent() == 0 ? "event"
                        : (slot.hoursRelativeToEvent() < 0 ? "T" + slot.hoursRelativeToEvent() + "h"
                        : "T+" + slot.hoursRelativeToEvent() + "h");
                double gap = slot.temperatureCelsius() - slot.dewPointCelsius();
                String gapLabel = gap < 1.0 ? " [AT/NEAR DEW POINT]"
                        : gap < 2.0 ? " [NEAR DEW POINT]"
                        : "";
                sb.append(String.format(" %s: vis=%,dm temp=%.1f\u00b0C dew=%.1f\u00b0C (gap=%.1f\u00b0C)%s",
                        label,
                        slot.visibilityMetres(),
                        slot.temperatureCelsius(),
                        slot.dewPointCelsius(),
                        gap,
                        gapLabel));
            }
        }

        // Conditional dust enrichment block — only when aerosol levels are elevated
        if (isDustElevated(a)) {
            sb.append(String.format(
                    "%nSAHARAN DUST CONTEXT:%n"
                    + "AOD: %s (elevated), Surface dust: %s \u00b5g/m\u00b3%n"
                    + "Wind: %s (%d\u00b0) at %s m/s%n"
                    + "Boundary layer: %dm%n"
                    + "Elevated AOD with low solar elevation at %s maximises warm scattering potential.",
                    a.aerosolOpticalDepth(), a.dustUgm3(),
                    toCardinal(w.windDirectionDegrees()), w.windDirectionDegrees(),
                    w.windSpeedMs(),
                    a.boundaryLayerHeightMetres(),
                    data.targetType()));
        }

        // Cloud inversion forecast — elevated water-overlooking locations only
        Double inversionScore = data.inversionScore();
        if (isInversionLikely(inversionScore)) {
            // Round, never truncate. intValue() reported a 9.5 as 9, so the whole 9.0–9.99 range
            // collapsed onto its floor while only an exact 10.0 could ever read as 10 — which is
            // why every inversion Claude was ever shown said "9/10".
            int reported = (int) Math.round(inversionScore);
            InversionPotential potential = InversionPotential.fromScore(reported);
            sb.append(String.format(
                    "%nCLOUD INVERSION FORECAST:%n"
                    + "Score: %d/10 (%s)%n"
                    + "Expected: %s%n"
                    + "Timing: Peak at event time, dissipates 1-2 hours after as surface warms.",
                    reported,
                    potential.label(),
                    potential == InversionPotential.STRONG
                            ? "Dramatic blanket below viewpoint; clear sky above"
                            : "Visible cloud layer below; light touching cloud tops"));
            appendInversionReversal(sb, data);
        }

        // Bluebell left the standard prompt in Pass 3 — it has its own prompt
        // (BluebellPromptBuilder) and visitor. The standard prompt scores the sky alone.

        // Forecast reliability block — only for TRANSITIONAL or UNSETTLED conditions
        ForecastStability stability = data.stability();
        if (stability != null && stability != ForecastStability.SETTLED) {
            sb.append(String.format("%nFORECAST RELIABILITY:%nStability: %s", stability));
            if (data.stabilityReason() != null) {
                sb.append(String.format("%nReason: %s", data.stabilityReason()));
            }
            PressureTrend pt = data.pressureTrend();
            if (pt != null) {
                sb.append(String.format(
                        "%nPressure tendency at event window: %+.1f hPa over 6h (%s)",
                        pt.tendencyHpa6h(), pt.tendencyLabel()));
            }
            if (stability == ForecastStability.TRANSITIONAL) {
                sb.append(String.format(
                        "%nNote: Front timing uncertain. Qualify time-sensitive advice"
                        + " \u2014 conditions may change significantly within the golden"
                        + " hour window."));
            } else if (stability == ForecastStability.UNSETTLED) {
                sb.append(String.format(
                        "%nNote: Active frontal weather. Conditions may be deteriorating"
                        + " faster than the model shows. Be honest about uncertainty."));
            }
        }

        sb.append("\n").append(getPromptSuffix());
        return sb.toString();
    }

    /**
     * Builds the structured output configuration constraining Claude's response to the
     * evaluation JSON schema.
     *
     * @return the output configuration with JSON schema constraint
     */
    public OutputConfig buildOutputConfig() {
        return OutputConfig.builder()
                .format(JsonOutputFormat.builder()
                        .schema(JsonOutputFormat.Schema.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.ofEntries(
                                        Map.entry("rating", Map.of(
                                                "type", "integer",
                                                "enum", List.of(1, 2, 3, 4, 5),
                                                "description",
                                                "1-5. MAXIMUM 3 when the CLOUD APPROACH RISK "
                                                        + "block shows BOTH a [BUILDING] trend "
                                                        + "AND upwind current >= 60%.")),
                                        Map.entry("fiery_sky", Map.of(
                                                "type", "integer",
                                                "description", "0-100 inclusive.")),
                                        Map.entry("golden_hour", Map.of(
                                                "type", "integer",
                                                "description",
                                                "0-100 inclusive. 20-30 points LOWER than the "
                                                        + "conditions alone would give when the "
                                                        + "CLOUD APPROACH RISK block shows BOTH a "
                                                        + "[BUILDING] trend AND upwind current "
                                                        + ">= 60%.")),
                                        Map.entry("summary", Map.of(
                                                "type", "string",
                                                "description",
                                                "One sentence in Claude's voice explaining the "
                                                        + "rating from the actual conditions; never "
                                                        + "a placeholder such as 'test', "
                                                        + "'placeholder', or an ellipsis.")),
                                        Map.entry("basic_fiery_sky", Map.of(
                                                "type", "integer",
                                                "description", "0-100 inclusive.")),
                                        Map.entry("basic_golden_hour", Map.of(
                                                "type", "integer",
                                                "description", "0-100 inclusive.")),
                                        Map.entry("basic_summary", Map.of(
                                                "type", "string",
                                                "description",
                                                "One sentence explaining the basic (altitude-only) "
                                                        + "rating; never a placeholder.")),
                                        Map.entry("inversion_score", Map.of(
                                                "type", "integer",
                                                "description", "0-10 inclusive.")),
                                        Map.entry("inversion_potential", Map.of(
                                                "type", "string",
                                                "enum", List.of("NONE", "MODERATE", "STRONG"))),
                                        Map.entry("headline", Map.of(
                                                "type", "string",
                                                "description",
                                                "4-9 word card header in Claude's voice.")))))
                                .putAdditionalProperty("required", JsonValue.from(
                                        List.of("rating", "fiery_sky", "golden_hour", "summary")))
                                .putAdditionalProperty("additionalProperties",
                                        JsonValue.from(false))
                                .build())
                        .build())
                .build();
    }

    /**
     * Converts a wind direction in degrees (0-360) to a 16-point compass cardinal.
     *
     * @param degrees wind direction in degrees (meteorological convention)
     * @return compass cardinal (e.g. "N", "SW", "ENE")
     */
    static String toCardinal(int degrees) {
        return PromptUtils.toCardinal(degrees);
    }

    /**
     * Renders an air-quality reading for the prompt, or {@code N/A} when it was never measured.
     *
     * <p>The unit rides on the value rather than on the format string, so an absent reading prints
     * a clean {@code PM2.5: N/A} instead of {@code PM2.5: N/Aµg/m³}. {@code N/A} is this prompt's
     * existing vocabulary for a missing reading — dew point and precipitation probability both use
     * it a few lines above — so Claude is not being taught a new token.
     *
     * <p>⚠️ The alternative is worse than it looks. These values used to arrive as a hard zero
     * whenever air quality ran short of the forecast window, and the system prompt grades AOD
     * against {@code 0.05-0.15 clean (baseline)} — so {@code AOD: 0.000} did not read as "no
     * data", it read as exceptionally clean air, and PM2.5 {@code 0.00} corroborated it. Absence
     * has to be sayable, or it gets said as a measurement.
     *
     * @param value the reading, or {@code null} when absent
     * @param unit  unit suffix to append when present; empty for dimensionless AOD
     * @return the formatted reading, or {@code N/A}
     */
    private static String reading(java.math.BigDecimal value, String unit) {
        return value == null ? "N/A" : value.toPlainString() + unit;
    }

    /**
     * Returns {@code true} if aerosol levels are elevated enough to warrant the dust context block.
     *
     * @param aerosol the aerosol data
     * @return true when AOD exceeds 0.3 or surface dust exceeds 50 µg/m³
     */
    static boolean isDustElevated(AerosolData aerosol) {
        return (aerosol.aerosolOpticalDepth() != null
                        && aerosol.aerosolOpticalDepth().doubleValue() > DUST_AOD_THRESHOLD)
                || (aerosol.dustUgm3() != null
                        && aerosol.dustUgm3().doubleValue() > DUST_UGM3_THRESHOLD);
    }

    /**
     * Appends the measured temperature reversal behind the inversion score, when one exists.
     *
     * <p>The score alone is a number Claude can only take on trust — and, being handed the answer,
     * echo back. The reversal is the independent evidence underneath it: how much warmer the air
     * at ~760 m is than the surface, which is what "inversion" means. Omitted silently when
     * Open-Meteo returned no pressure-level temperature, since the score is already capped below
     * the STRONG band in that case.
     *
     * @param sb   the prompt under construction
     * @param data the atmospheric data at event time
     */
    private static void appendInversionReversal(StringBuilder sb, AtmosphericData data) {
        if (data.comfort() == null || data.comfort().temperatureCelsius() == null) {
            return;
        }
        Double reversal = InversionScoreCalculator.reversalCelsius(
                data.weather(), data.comfort().temperatureCelsius());
        if (reversal == null) {
            return;
        }
        sb.append(String.format(
                "%nMeasured reversal: %+.1f°C between the surface and ~760m (925 hPa) "
                + "— positive means temperature RISES with height, which is the inversion "
                + "itself. Weigh this over the score if the two disagree.",
                reversal));
    }

    /**
     * Returns {@code true} if the inversion score is high enough to include inversion context.
     *
     * @param inversionScore the inversion likelihood score (0–10), or null
     * @return true when score is at or above the threshold (7.0)
     */
    static boolean isInversionLikely(Double inversionScore) {
        return inversionScore != null && inversionScore >= INVERSION_SCORE_THRESHOLD;
    }

    /**
     * Returns {@code true} when the solar-horizon low cloud is significant but drops sharply by
     * the far-field sample — a thin strip sitting on the horizon rather than an extensive blanket.
     *
     * <p>The prompt uses this in two places: to label the directional-cloud block, and to soften
     * the cloud-approach wording. Both must agree, hence the single definition.
     *
     * @param dc directional cloud data, or {@code null} when unavailable
     * @return true if the horizon low cloud reads as a thin strip
     */
    static boolean isThinStrip(DirectionalCloudData dc) {
        return dc != null && dc.farSolarLowCloudPercent() != null
                && dc.solarLowCloudPercent() >= SOLAR_LOW_CLOUD_SIGNIFICANT_PERCENT
                && (dc.solarLowCloudPercent() - dc.farSolarLowCloudPercent())
                        >= THIN_STRIP_DROP_POINTS;
    }
}
