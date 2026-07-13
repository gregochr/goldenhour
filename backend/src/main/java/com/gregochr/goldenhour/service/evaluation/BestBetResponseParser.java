package com.gregochr.goldenhour.service.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BestBetResult;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DiffersBy;
import com.gregochr.goldenhour.model.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the Claude best-bet JSON response into {@link BestBet} picks and classifies the outcome.
 *
 * <p>Stateless — all methods are pure functions of their arguments plus the supplied
 * {@link ObjectMapper}. Extracted from {@code BriefingBestBetAdvisor} so the parse/classify/salvage
 * rules can be reasoned about and tested in isolation from the advisor's orchestration. Logs under
 * the {@link BriefingBestBetAdvisor} category so response-disposition diagnostics stay grouped with
 * the advisor's own output.
 */
public final class BestBetResponseParser {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingBestBetAdvisor.class);

    private BestBetResponseParser() {
    }

    /**
     * Parses the Claude JSON response into a list of {@link BestBet} records.
     *
     * <p>Thin convenience wrapper over {@link #classifyAndParse(String, ObjectMapper)} that discards
     * the outcome status. Retained for callers that only need the picks (e.g. the model-comparison
     * utility); {@code advise} uses {@code classifyAndParse} directly so it can report the status.
     *
     * @param raw          the raw Claude response text
     * @param objectMapper Jackson mapper for reading the response JSON
     * @return parsed picks, or empty list if parsing fails
     */
    public static List<BestBet> parseBestBets(String raw, ObjectMapper objectMapper) {
        return classifyAndParse(raw, objectMapper).picks();
    }

    /**
     * Parses the Claude JSON response and classifies the outcome into a {@link BestBetResult}.
     *
     * <p>Mapping:
     * <ul>
     *   <li>blank/missing content, a missing or non-array {@code picks} field, or an
     *       unparseable response from which nothing could be salvaged → {@code FAILED};</li>
     *   <li>a valid but empty {@code picks} array → {@code SUCCESS_NO_PICKS} (honest decline);</li>
     *   <li>one or more parsed picks, including picks salvaged from a truncated response →
     *       {@code SUCCESS_WITH_PICKS}.</li>
     * </ul>
     *
     * @param raw          the raw Claude response text
     * @param objectMapper Jackson mapper for reading the response JSON
     * @return the parse outcome (status + picks)
     */
    public static BestBetResult classifyAndParse(String raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            LOG.warn("Best-bet response was blank — FAILED");
            return BestBetResult.failed();
        }
        String cleaned = PromptUtils.stripCodeFences(raw);
        try {
            String extracted = PromptUtils.extractJsonObject(cleaned);
            JsonNode root = objectMapper.readTree(extracted);
            JsonNode picksNode = root.get("picks");
            if (picksNode == null || !picksNode.isArray()) {
                LOG.warn("Best-bet response missing 'picks' array — FAILED");
                return BestBetResult.failed();
            }
            if (picksNode.isEmpty()) {
                LOG.info("Best-bet response contained an empty 'picks' array (honest decline)");
                return BestBetResult.noPicks();
            }
            List<BestBet> picks = new ArrayList<>();
            for (JsonNode pick : picksNode) {
                picks.add(parsePickNode(pick));
            }
            LOG.info("Best-bet advisor returned {} pick(s)", picks.size());
            return BestBetResult.withPicks(picks);
        } catch (Exception e) {
            // Atomic parse failed — the response is structurally invalid, the classic
            // signature being a response truncated mid-pick when the model hit max_tokens.
            // Salvage any complete leading picks rather than discarding a valid rank-1
            // because a later pick was cut off (a single malformed pick must not zero out
            // a valid one). Salvaged picks are still subject to validateAndFilterPicks.
            List<BestBet> salvaged = salvagePicks(cleaned, objectMapper);
            if (!salvaged.isEmpty()) {
                LOG.warn("Best-bet response was not valid JSON (likely truncated mid-pick) — "
                        + "salvaged {} complete pick(s) from the partial response", salvaged.size());
                return BestBetResult.withPicks(salvaged);
            }
            String preview = raw.length() > 4000
                    ? raw.substring(0, 4000) + "...<truncated>"
                    : raw;
            LOG.warn("Failed to parse best-bet response and no picks could be salvaged — "
                    + "FAILED. Raw response was:\n{}", preview, e);
            return BestBetResult.failed();
        }
    }

    /**
     * Parses a single {@code pick} JSON node into a {@link BestBet}. Shared by the atomic
     * parse path and the {@link #salvagePicks} recovery path so both extract fields
     * identically. Missing fields degrade to nulls/defaults; the resulting pick is still
     * subject to downstream validation in {@code validateAndFilterPicks}.
     *
     * @param pick a single pick object node
     * @return the parsed pick (display fields left null — they are enriched later)
     */
    private static BestBet parsePickNode(JsonNode pick) {
        int rank = pick.path("rank").asInt(1);
        String headline = PromptUtils.sanitizeBrand(pick.path("headline").asText(null));
        String detail = PromptUtils.sanitizeBrand(pick.path("detail").asText(null));
        String event = pick.path("event").isNull()
                ? null : pick.path("event").asText(null);
        String region = pick.path("region").isNull()
                ? null : pick.path("region").asText(null);
        Confidence confidence = Confidence.fromString(
                pick.path("confidence").asText("medium"));
        Relationship relationship = Relationship.fromString(
                pick.path("relationship").asText(null));
        List<DiffersBy> differsBy = new ArrayList<>();
        JsonNode differsByNode = pick.get("differsBy");
        if (differsByNode != null && differsByNode.isArray()) {
            for (JsonNode d : differsByNode) {
                DiffersBy dim = DiffersBy.fromString(d.asText(null));
                if (dim != null) {
                    differsBy.add(dim);
                }
            }
        }
        return new BestBet(rank, headline, detail, event, region, confidence,
                null, null, null, null, relationship, differsBy);
    }

    /**
     * Recovers the complete leading pick objects from a structurally invalid best-bet
     * response (typically one truncated mid-pick when the model hit its token ceiling).
     *
     * <p>Locates the {@code "picks"} array and walks it element by element, extracting each
     * balanced {@code {...}} object and parsing it. Recovery stops at the first object that
     * is truncated (never closes) or fails to parse, so only the structurally-valid prefix is
     * returned. This restores the common case where rank 1 is complete but rank 2 was cut.
     *
     * <p>This is a pure mechanism: it recovers picks the model already emitted. It does not
     * relax validation — the survivors still pass through {@code validateAndFilterPicks}.
     *
     * @param cleaned      the code-fence-stripped raw response
     * @param objectMapper Jackson mapper for reading each recovered pick object
     * @return the complete leading picks (possibly empty)
     */
    private static List<BestBet> salvagePicks(String cleaned, ObjectMapper objectMapper) {
        if (cleaned == null) {
            return List.of();
        }
        int picksKeyIdx = cleaned.indexOf("\"picks\"");
        if (picksKeyIdx < 0) {
            return List.of();
        }
        int arrayStart = cleaned.indexOf('[', picksKeyIdx);
        if (arrayStart < 0) {
            return List.of();
        }
        List<BestBet> picks = new ArrayList<>();
        int i = arrayStart + 1;
        while (i < cleaned.length()) {
            while (i < cleaned.length()
                    && (Character.isWhitespace(cleaned.charAt(i)) || cleaned.charAt(i) == ',')) {
                i++;
            }
            if (i >= cleaned.length() || cleaned.charAt(i) != '{') {
                // End of array (']'), or a truncated/garbled element — stop salvaging.
                break;
            }
            String objStr = PromptUtils.balancedObjectAt(cleaned, i);
            if (objStr == null) {
                // Trailing object never closes (truncated) — keep what we have.
                break;
            }
            try {
                picks.add(parsePickNode(objectMapper.readTree(objStr)));
            } catch (Exception e) {
                break;
            }
            i += objStr.length();
        }
        return List.copyOf(picks);
    }
}
