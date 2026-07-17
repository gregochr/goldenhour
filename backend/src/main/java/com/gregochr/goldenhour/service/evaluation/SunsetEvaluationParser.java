package com.gregochr.goldenhour.service.evaluation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.model.BluebellEvaluation;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Claude's JSON responses into evaluation records.
 *
 * <p>Owns the sky-colour contract ({@code rating}, {@code fiery_sky}, {@code golden_hour},
 * {@code summary} and friends) and the separate bluebell contract, each with a strict-JSON
 * path and a regex fallback for structurally-invalid responses (e.g. unescaped quotes in a
 * summary).
 *
 * <p>Extracted from {@code ClaudeEvaluationStrategy}, which now delegates here. Parsing is a
 * distinct responsibility from prompt-build → API-call, and the batch paths need it without
 * making a Claude call at all: {@code ForecastResultHandler} and {@code SkyRatingEvalBatchService}
 * parse raw text returned by the Batch API. Both previously reached into the
 * {@code Map<EvaluationModel, EvaluationStrategy>} and downcast the HAIKU entry to borrow this
 * logic; they now inject this component directly.
 *
 * <p>Stateless and thread-safe — the compiled patterns are immutable and the mapper is a
 * parameter, so callers keep using their own configured {@link ObjectMapper}.
 */
@Component
public class SunsetEvaluationParser {

    /**
     * Outcome of {@link #parseEvaluationWithMetadata(String, ObjectMapper)}: the parsed evaluation plus whether the
     * greedy regex fallback was used (i.e. strict JSON parsing failed). The fallback can
     * over-capture adjacent fields and then SUCCEED silently, so callers persist the raw
     * response for diagnosis when this flag is set (the Bug B capture).
     *
     * @param evaluation        the parsed evaluation
     * @param usedRegexFallback {@code true} when strict {@code readTree} failed and the regex
     *                          fallback produced the result
     */
    public record ParseResult(SunsetEvaluation evaluation, boolean usedRegexFallback) {
    }

    /**
     * Extracts the summary text — bounded so it CANNOT over-capture into a following field
     * (Bug B). The capture {@code [^"\\]*(?:\\.[^"\\]*)*} matches any run of non-quote / escaped
     * characters and stops at the first genuine (unescaped) closing quote, so it physically cannot
     * swallow {@code ","headline":"..."}. If the value itself contains unescaped quotes this finds
     * no match; {@link #SUMMARY_PATTERN_SALVAGE} then salvages the field so the fallback never
     * loses the rating.
     *
     * <p>The "unrolled loop" form ({@code [^"\\]*(?:\\.[^"\\]*)*}) is used deliberately instead of
     * the equivalent {@code (?:[^"\\]|\\.)*}: Java compiles an alternation-inside-a-star to a matcher
     * that recurses once per character, so a long response reaching this fallback overflowed the
     * stack ({@code StackOverflowError}). The character-class star {@code [^"\\]*} matches
     * iteratively, so recursion depth is bounded by the number of escape sequences (≈0), not the
     * input length.
     */
    static final Pattern SUMMARY_PATTERN =
            Pattern.compile("\"summary\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"\\s*[,}]");

    /**
     * Legacy greedy summary extractor, used ONLY when {@link #SUMMARY_PATTERN} (bounded) finds no
     * match — i.e. the summary value itself contains unescaped quotes. It can over-capture, but it
     * keeps a (mangled) summary and the correct rating rather than failing the whole fallback.
     */
    static final Pattern SUMMARY_PATTERN_SALVAGE =
            Pattern.compile("(?s)\"summary\"\\s*:\\s*\"(.*)\"\\s*[,}]");

    /** Extracts the integer rating from Claude's response. */
    static final Pattern RATING_PATTERN = Pattern.compile("\"rating\"\\s*:\\s*(\\d{1,2})");

    /** Extracts the Fiery Sky Potential (0-100) from Claude's response. */
    static final Pattern FIERY_SKY_PATTERN = Pattern.compile("\"fiery_sky\"\\s*:\\s*(\\d{1,3})");

    /** Extracts the Golden Hour Potential (0-100) from Claude's response. */
    static final Pattern GOLDEN_HOUR_PATTERN = Pattern.compile("\"golden_hour\"\\s*:\\s*(\\d{1,3})");

    /** Extracts the basic Fiery Sky Potential (0-100) from Claude's response. */
    static final Pattern BASIC_FIERY_SKY_PATTERN =
            Pattern.compile("\"basic_fiery_sky\"\\s*:\\s*(\\d{1,3})");

    /** Extracts the basic Golden Hour Potential (0-100) from Claude's response. */
    static final Pattern BASIC_GOLDEN_HOUR_PATTERN =
            Pattern.compile("\"basic_golden_hour\"\\s*:\\s*(\\d{1,3})");

    /**
     * Extracts the basic summary — bounded against over-capture, same as {@link #SUMMARY_PATTERN}
     * (including the stack-safe unrolled-loop form that avoids {@code StackOverflowError} on long
     * responses).
     */
    static final Pattern BASIC_SUMMARY_PATTERN =
            Pattern.compile("\"basic_summary\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"\\s*[,}]");

    /** Greedy salvage for basic summary, used only when {@link #BASIC_SUMMARY_PATTERN} finds no match. */
    static final Pattern BASIC_SUMMARY_PATTERN_SALVAGE =
            Pattern.compile("(?s)\"basic_summary\"\\s*:\\s*\"(.*)\"\\s*[,}]");

    /** Extracts the cloud inversion score (0-10) from Claude's response. */
    static final Pattern INVERSION_SCORE_PATTERN =
            Pattern.compile("\"inversion_score\"\\s*:\\s*(\\d{1,2})");

    /** Extracts the cloud inversion potential classification from Claude's response. */
    static final Pattern INVERSION_POTENTIAL_PATTERN =
            Pattern.compile("\"inversion_potential\"\\s*:\\s*\"(\\w+)\"");

    /** Extracts the Claude-authored card headline (Gate 2 redesign). */
    static final Pattern HEADLINE_PATTERN =
            Pattern.compile("\"headline\"\\s*:\\s*\"([^\"]+)\"");
    /**
     * Parses Claude's JSON response into a {@link SunsetEvaluation}.
     *
     * <p>Expects JSON with {@code rating} (optional), {@code fiery_sky}, {@code golden_hour},
     * and {@code summary}. First attempts strict JSON parsing; falls back to regex if the
     * summary contains unescaped quote characters.
     *
     * @param text   the raw text returned by Claude
     * @param mapper Jackson mapper for JSON parsing
     * @return the parsed evaluation
     * @throws IllegalArgumentException if the response cannot be parsed
     */
    public SunsetEvaluation parseEvaluation(String text, ObjectMapper mapper) {
        return parseEvaluationWithMetadata(text, mapper).evaluation();
    }

    /**
     * Parses the dedicated bluebell prompt's response into a {@link BluebellEvaluation}.
     *
     * <p>The bluebell contract is small: a {@code rating} (1-5), a {@code summary}, and an
     * optional {@code headline} — no sky sub-scores. Attempts strict JSON first; on failure
     * (e.g. an unescaped quote in the summary) falls back to the same bounded/salvage regex
     * extraction the sky parser uses, so a malformed-but-recoverable response still yields a
     * rating and summary rather than dropping the slot. Introduced in Pass 3.
     *
     * @param text   the raw text returned by Claude for a bluebell evaluation
     * @param mapper Jackson mapper for JSON parsing
     * @return the parsed bluebell evaluation
     * @throws IllegalArgumentException if neither strict parsing nor the regex fallback recovers
     *                                  a rating and summary
     */
    public BluebellEvaluation parseBluebellEvaluation(String text, ObjectMapper mapper) {
        String cleaned = text.trim()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .trim();
        try {
            JsonNode node = mapper.readTree(cleaned);
            Integer rating = node.has("rating") ? node.get("rating").asInt() : null;
            String summary = node.get("summary").stringValue();
            String headline = node.has("headline") ? node.get("headline").stringValue() : null;
            return new BluebellEvaluation(rating, summary, headline);
        } catch (Exception jsonException) {
            Matcher ratingMatcher = RATING_PATTERN.matcher(text);
            String summary = extractField(SUMMARY_PATTERN, SUMMARY_PATTERN_SALVAGE, text);
            if (summary == null) {
                throw new IllegalArgumentException(
                        "Failed to parse bluebell evaluation response: " + text, jsonException);
            }
            Integer rating = ratingMatcher.find() ? Integer.parseInt(ratingMatcher.group(1)) : null;
            Matcher headlineMatcher = HEADLINE_PATTERN.matcher(text);
            String headline = headlineMatcher.find() ? headlineMatcher.group(1) : null;
            return new BluebellEvaluation(rating, summary, headline);
        }
    }

    /**
     * Parses Claude's JSON response, reporting whether the regex fallback was used.
     *
     * <p>Attempts strict JSON parsing first; on failure, falls back to regex extraction and
     * sets {@link ParseResult#usedRegexFallback()}. The fallback can over-capture adjacent
     * fields (e.g. swallow {@code headline} into {@code summary}) and still SUCCEED, so the flag
     * lets the batch result handler persist the raw response for diagnosis (the Bug B capture)
     * without changing any parsing behaviour. Parsing itself is identical to
     * {@link #parseEvaluation}.
     *
     * @param text   the raw text returned by Claude
     * @param mapper Jackson mapper for JSON parsing
     * @return the parsed evaluation and whether the regex fallback was used
     * @throws IllegalArgumentException if neither strict parsing nor the regex fallback succeeds
     */
    public ParseResult parseEvaluationWithMetadata(String text, ObjectMapper mapper) {
        String cleaned = text.trim()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .trim();
        try {
            JsonNode node = mapper.readTree(cleaned);
            Integer rating = node.has("rating") ? node.get("rating").asInt() : null;
            int fierySky = node.get("fiery_sky").asInt();
            int goldenHour = node.get("golden_hour").asInt();
            String summary = node.get("summary").stringValue();
            Integer basicFierySky = node.has("basic_fiery_sky")
                    ? node.get("basic_fiery_sky").asInt() : null;
            Integer basicGoldenHour = node.has("basic_golden_hour")
                    ? node.get("basic_golden_hour").asInt() : null;
            String basicSummary = node.has("basic_summary")
                    ? node.get("basic_summary").stringValue() : null;
            Integer inversionScore = node.has("inversion_score")
                    ? node.get("inversion_score").asInt() : null;
            String inversionPotential = node.has("inversion_potential")
                    ? sanitiseInversionPotential(node.get("inversion_potential").stringValue())
                    : null;
            String headline = node.has("headline")
                    ? node.get("headline").stringValue() : null;
            // Bluebell left the standard prompt in Pass 3 — it now has its own prompt/parser
            // (parseBluebellEvaluation), so the colour evaluation carries no bluebell score.
            return new ParseResult(new SunsetEvaluation(rating, fierySky, goldenHour, summary,
                    basicFierySky, basicGoldenHour, basicSummary,
                    inversionScore, inversionPotential,
                    headline), false);
        } catch (Exception jsonException) {
            // Strict JSON parse failed — fall through to the greedy regex fallback and flag it so
            // the caller can persist the raw response for diagnosis (Bug B). Diagnostic only:
            // parsing behaviour is unchanged.
            return new ParseResult(parseWithRegexFallback(text, jsonException), true);
        }
    }
    /**
     * Fallback parser when the JSON is structurally invalid (e.g. unescaped quotes in summary).
     *
     * @param text  the raw response text
     * @param cause the original JSON parse exception, re-thrown if regex also fails
     * @return the parsed evaluation
     * @throws IllegalArgumentException if scores and summary cannot be extracted
     */
    private SunsetEvaluation parseWithRegexFallback(String text, Exception cause) {
        Matcher ratingMatcher = RATING_PATTERN.matcher(text);
        Matcher fierySkyMatcher = FIERY_SKY_PATTERN.matcher(text);
        Matcher goldenHourMatcher = GOLDEN_HOUR_PATTERN.matcher(text);
        // Bounded first (no over-capture), greedy salvage only if the value has unescaped quotes.
        String summary = extractField(SUMMARY_PATTERN, SUMMARY_PATTERN_SALVAGE, text);

        Integer rating = ratingMatcher.find() ? Integer.parseInt(ratingMatcher.group(1)) : null;
        if (fierySkyMatcher.find() && goldenHourMatcher.find() && summary != null) {
            int fierySky = Integer.parseInt(fierySkyMatcher.group(1));
            int goldenHour = Integer.parseInt(goldenHourMatcher.group(1));

            Matcher basicFierySkyMatcher = BASIC_FIERY_SKY_PATTERN.matcher(text);
            Matcher basicGoldenHourMatcher = BASIC_GOLDEN_HOUR_PATTERN.matcher(text);
            Integer basicFierySky = basicFierySkyMatcher.find()
                    ? Integer.parseInt(basicFierySkyMatcher.group(1)) : null;
            Integer basicGoldenHour = basicGoldenHourMatcher.find()
                    ? Integer.parseInt(basicGoldenHourMatcher.group(1)) : null;
            String basicSummary = extractField(
                    BASIC_SUMMARY_PATTERN, BASIC_SUMMARY_PATTERN_SALVAGE, text);

            Matcher inversionScoreMatcher = INVERSION_SCORE_PATTERN.matcher(text);
            Matcher inversionPotentialMatcher = INVERSION_POTENTIAL_PATTERN.matcher(text);
            Integer inversionScore = inversionScoreMatcher.find()
                    ? Integer.parseInt(inversionScoreMatcher.group(1)) : null;
            String inversionPotential = inversionPotentialMatcher.find()
                    ? sanitiseInversionPotential(inversionPotentialMatcher.group(1)) : null;

            Matcher headlineMatcher = HEADLINE_PATTERN.matcher(text);
            String headline = headlineMatcher.find() ? headlineMatcher.group(1) : null;

            // Bluebell fields left the standard prompt in Pass 3 (own prompt/parser).
            return new SunsetEvaluation(rating, fierySky, goldenHour, summary,
                    basicFierySky, basicGoldenHour, basicSummary,
                    inversionScore, inversionPotential,
                    headline);
        }

        throw new IllegalArgumentException(
                "Failed to parse evaluation response: " + text, cause);
    }

    /**
     * Extracts a string field from malformed JSON, preferring a bounded pattern that cannot
     * over-capture into the following field (the Bug B fix), and only falling back to the legacy
     * greedy pattern when the bounded one finds no match — i.e. the value itself contains unescaped
     * quotes. The greedy salvage keeps the fallback resilient: it never loses the rating just
     * because a summary is internally mangled. Returns {@code null} if neither pattern matches.
     *
     * @param bounded the bounded (non-over-capturing) pattern, group 1 = value
     * @param salvage the greedy fallback pattern, group 1 = value
     * @param text    the raw response text
     * @return the extracted field value, or null if neither pattern matches
     */
    private static String extractField(Pattern bounded, Pattern salvage, String text) {
        Matcher boundedMatcher = bounded.matcher(text);
        if (boundedMatcher.find()) {
            return boundedMatcher.group(1);
        }
        Matcher salvageMatcher = salvage.matcher(text);
        return salvageMatcher.find() ? salvageMatcher.group(1) : null;
    }

    /**
     * Normalises the inversion potential value from Claude's response to a known enum name.
     * Maps any value containing "moderate" to MODERATE, "strong" to STRONG, otherwise NONE.
     * Prevents VARCHAR(10) overflow in the database.
     *
     * @param raw the raw string from Claude's JSON response
     * @return NONE, MODERATE, or STRONG
     */
    static String sanitiseInversionPotential(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.toUpperCase();
        if (upper.contains("STRONG")) {
            return "STRONG";
        }
        if (upper.contains("MODERATE")) {
            return "MODERATE";
        }
        return "NONE";
    }
}
