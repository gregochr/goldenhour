package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and parses Anthropic Batch API custom IDs used across the four forecast
 * evaluation paths (scheduled, JFDI, force-submit, aurora).
 *
 * <p>Formats:
 * <ul>
 *   <li>Forecast (scheduled): {@code fc-{locationId}-{date}-{targetType}}, optionally with a
 *       trailing {@code -r{evalRowId}} segment (prompted-row persistence plan, R3) carrying the
 *       primary key of the {@code PENDING} {@code forecast_evaluation} row this submission is
 *       the carrier for, and optionally a further trailing {@code -f} segment marking the task
 *       as a {@code ForceEvalHeadlineSelector} force-evaluation (verdict-minimum-sample rule's
 *       force-evaluation exemption — see {@link EvaluationTask.Forecast#forced}'s javadoc)</li>
 *   <li>Bluebell (scheduled): {@code bb-{locationId}-{date}-{targetType}}, optionally with the
 *       same trailing {@code -f} marker — a WOODLAND-exposure or OPEN_FELL-paired bluebell
 *       candidate can be force-evaluated exactly like a sky one, since {@code
 *       ForecastTaskCollector} decides eligibility before it routes to a lane</li>
 *   <li>Woodland (scheduled): {@code wd-{locationId}-{date}-{targetType}}, optionally with the
 *       same trailing {@code -f} marker, for the identical reason</li>
 *   <li>JFDI: {@code jfdi-{locationId}-{date}-{targetType}}</li>
 *   <li>Force-submit: {@code force-{sanitisedRegion}-{locationId}-{date}-{targetType}}</li>
 *   <li>Aurora: {@code au-{alertLevel}-{date}}</li>
 * </ul>
 *
 * <p>The optional forecast {@code -f} and {@code -r{evalRowId}} suffixes are stripped BEFORE the
 * shared {@code -{date}-{targetType}} tail parser runs (that parser takes the segment after the
 * last hyphen as the target type — a naïve append would misparse either suffix as one), in that
 * order: {@code -f} (outermost, since it is appended last by {@link #forForecast(Long, LocalDate,
 * TargetType, Long, boolean)}) THEN {@code -r{evalRowId}}. Backward compatibility is mandatory:
 * batches submitted by the previous binary are in flight at deploy — the Anthropic Batch API can
 * take up to 24 hours to complete — so an id with neither suffix is not an error; it parses with a
 * {@code null} {@code evalRowId} and {@code forced = false}, identically to before either suffix
 * existed. A malformed {@code -r} suffix (non-digit, empty) is not silently treated as absent
 * either — it falls through to the tail parser unstripped, where the malformed segment fails
 * {@code TargetType} resolution and the whole id is rejected as malformed.
 *
 * <p>⚠️ <b>The reverse direction is NOT safe, and is not fixed here.</b> If a deploy that submits
 * {@code -f}-suffixed ids is rolled back while one of those batches is still in flight, the
 * PREVIOUS binary's parser (identical to this class's {@code parseForecast} minus the {@code -f}
 * handling) strips only a trailing {@code -r\d+}, leaving a batch's {@code -f} suffix attached; its
 * tail parser then reads {@code "f"} as the segment after the last hyphen and calls {@code
 * TargetType.valueOf("f")}, which throws — {@code BatchResultProcessor} logs {@code "malformed
 * customId"} and counts the response as errored. A rolled-back deploy therefore loses (never
 * corrupts) the results of any in-flight force-evaluated task, at the cost of one wasted Claude
 * call per such task — the same fail-safe direction every other malformed-id case in this class
 * already takes. This is a narrow, low-probability window (only the capped handful of force-
 * evaluated tasks a cycle submits, and only during the hours a rollback overlaps their batch still
 * being in flight) and is accepted rather than patched, because patching would mean shipping a fix
 * to a binary that is, by definition, already superseded.
 *
 * <p>Parsing dispatches by prefix rather than hyphen count — the previous implementation
 * in {@code BatchResultProcessor} counted parts after {@code split("-")} (fc/jfdi = 6,
 * force = 7), a brittle scheme that would silently misparse any future format with a
 * different hyphen count. Prefix dispatch plus bounded length extraction (date is always
 * 10 chars) removes that fragility.
 *
 * <p>Region sanitisation for force-submit IDs strips every non-ASCII-alphanumeric
 * character — the exact rule previously used at {@code ForceSubmitBatchService.forceSubmit},
 * preserved verbatim because {@link BatchResultProcessor}'s forward-compatible parser
 * relies on the region segment containing zero hyphens.
 */
public final class CustomIdFactory {

    private static final int MAX_LENGTH = 64;
    private static final Pattern ANTHROPIC_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Pattern REGION_STRIP = Pattern.compile("[^a-zA-Z0-9]");

    /** Trailing {@code -r{evalRowId}} suffix on a forecast custom ID (R3). */
    private static final Pattern ROW_ID_SUFFIX = Pattern.compile("-r(\\d+)$");

    /**
     * Trailing {@code -f} suffix marking a forecast custom ID as a
     * {@code ForceEvalHeadlineSelector} force-evaluation. Stripped before {@link #ROW_ID_SUFFIX}
     * — see the class javadoc for the exact order.
     */
    private static final Pattern FORCED_SUFFIX = Pattern.compile("-f$");

    private static final String PREFIX_FORECAST = "fc-";
    private static final String PREFIX_BLUEBELL = "bb-";
    private static final String PREFIX_WOODLAND = "wd-";
    private static final String PREFIX_JFDI = "jfdi-";
    private static final String PREFIX_FORCE = "force-";
    private static final String PREFIX_AURORA = "au-";

    /** Fixed length of the ISO date segment (YYYY-MM-DD). */
    private static final int DATE_LEN = 10;

    private CustomIdFactory() {
    }

    /**
     * Builds a forecast custom ID for the scheduled batch path.
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @return an ID of the form {@code "fc-{locationId}-{date}-{targetType}"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forForecast(Long locationId, LocalDate date, TargetType targetType) {
        return forForecast(locationId, date, targetType, null, false);
    }

    /**
     * Builds a forecast custom ID for the scheduled batch path, carrying the primary key of the
     * {@code PENDING} {@code forecast_evaluation} row this submission is the carrier for
     * (prompted-row persistence plan, R3).
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @param evalRowId  primary key of the pending row, or {@code null} to omit the suffix
     *                   (matches the three-arg {@link #forForecast(Long, LocalDate, TargetType)})
     * @return an ID of the form {@code "fc-{locationId}-{date}-{targetType}[-r{evalRowId}]"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forForecast(Long locationId, LocalDate date, TargetType targetType,
            Long evalRowId) {
        return forForecast(locationId, date, targetType, evalRowId, false);
    }

    /**
     * Builds a forecast custom ID for the scheduled batch path, carrying both the optional
     * pending-row primary key (R3) and whether this task is a {@code ForceEvalHeadlineSelector}
     * force-evaluation — the verdict-minimum-sample rule's force-evaluation exemption fact,
     * carried across the async Batch API round trip because neither {@code forecast_evaluation}
     * nor a disposition row can otherwise tell the result side which specific submission
     * produced the eventual rating (see {@link EvaluationTask.Forecast#forced}'s javadoc).
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @param evalRowId  primary key of the pending row, or {@code null} to omit the {@code -r}
     *                   segment
     * @param forced     whether to append the {@code -f} force-evaluation marker
     * @return an ID of the form
     *         {@code "fc-{locationId}-{date}-{targetType}[-r{evalRowId}][-f]"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forForecast(Long locationId, LocalDate date, TargetType targetType,
            Long evalRowId, boolean forced) {
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(targetType, "targetType");
        String base = PREFIX_FORECAST + locationId + "-" + date + "-" + targetType.name();
        if (evalRowId != null) {
            base += "-r" + evalRowId;
        }
        if (forced) {
            base += "-f";
        }
        return validate(base);
    }

    /**
     * Builds a bluebell custom ID for the scheduled bluebell mini-batch path.
     *
     * <p>Distinct from the {@code fc-} forecast prefix so the result side knows the response
     * was produced by the dedicated bluebell prompt and must be parsed/combined via the
     * bluebell path rather than the colour path.
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @return an ID of the form {@code "bb-{locationId}-{date}-{targetType}"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forBluebell(Long locationId, LocalDate date, TargetType targetType) {
        return forBluebell(locationId, date, targetType, false);
    }

    /**
     * Builds a bluebell custom ID, optionally carrying the {@code ForceEvalHeadlineSelector}
     * force-evaluation marker — a bluebell-only (WOODLAND exposure) or OPEN_FELL-paired candidate
     * can be force-evaluated exactly like a sky one, since {@code ForecastTaskCollector} decides
     * eligibility before it routes to a lane (see {@code EvaluationTask.Forecast#forced}'s
     * javadoc).
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @param forced     whether to append the {@code -f} force-evaluation marker
     * @return an ID of the form {@code "bb-{locationId}-{date}-{targetType}[-f]"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forBluebell(Long locationId, LocalDate date, TargetType targetType,
            boolean forced) {
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(targetType, "targetType");
        String base = PREFIX_BLUEBELL + locationId + "-" + date + "-" + targetType.name();
        return validate(forced ? base + "-f" : base);
    }

    /**
     * Builds a woodland custom ID, signalling that the response was produced by the year-round
     * woodland prompt and must be parsed/combined via the woodland path rather than the colour
     * path. Same length class as the bluebell prefix, so the 64-char Anthropic limit is no
     * tighter here than it already was.
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @return an ID of the form {@code "wd-{locationId}-{date}-{targetType}"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forWoodland(Long locationId, LocalDate date, TargetType targetType) {
        return forWoodland(locationId, date, targetType, false);
    }

    /**
     * Builds a woodland custom ID, optionally carrying the {@code ForceEvalHeadlineSelector}
     * force-evaluation marker — see {@link #forBluebell(Long, LocalDate, TargetType, boolean)}'s
     * javadoc for why a canopy candidate needs the identical treatment.
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @param forced     whether to append the {@code -f} force-evaluation marker
     * @return an ID of the form {@code "wd-{locationId}-{date}-{targetType}[-f]"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forWoodland(Long locationId, LocalDate date, TargetType targetType,
            boolean forced) {
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(targetType, "targetType");
        String base = PREFIX_WOODLAND + locationId + "-" + date + "-" + targetType.name();
        return validate(forced ? base + "-f" : base);
    }

    /**
     * Builds a JFDI custom ID.
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @return an ID of the form {@code "jfdi-{locationId}-{date}-{targetType}"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forJfdi(Long locationId, LocalDate date, TargetType targetType) {
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(targetType, "targetType");
        return validate(PREFIX_JFDI + locationId + "-" + date + "-" + targetType.name());
    }

    /**
     * Builds a force-submit custom ID.
     *
     * <p>The region name is sanitised by stripping every non-ASCII-alphanumeric character
     * (e.g. {@code "The North York Moors"} → {@code "TheNorthYorkMoors"}). This matches
     * the rule previously inlined in {@code ForceSubmitBatchService.forceSubmit} verbatim
     * and is required for the result-side parser to work: the region segment must contain
     * zero hyphens so the trailing date and target-type segments remain unambiguous.
     *
     * @param regionName region name; will be sanitised
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @return an ID of the form {@code "force-{sanitisedRegion}-{locationId}-{date}-{targetType}"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forForceSubmit(String regionName, Long locationId,
            LocalDate date, TargetType targetType) {
        Objects.requireNonNull(regionName, "regionName");
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(targetType, "targetType");
        String sanitised = sanitiseRegionName(regionName);
        if (sanitised.isEmpty()) {
            throw new IllegalArgumentException("Region name has no alphanumeric characters: "
                    + regionName);
        }
        return validate(PREFIX_FORCE + sanitised + "-" + locationId + "-" + date
                + "-" + targetType.name());
    }

    /**
     * Builds an aurora custom ID.
     *
     * @param alertLevel current alert level
     * @param date       forecast date
     * @return an ID of the form {@code "au-{alertLevel}-{date}"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forAurora(AlertLevel alertLevel, LocalDate date) {
        Objects.requireNonNull(alertLevel, "alertLevel");
        Objects.requireNonNull(date, "date");
        return validate(PREFIX_AURORA + alertLevel.name() + "-" + date);
    }

    /**
     * Applies the force-submit region sanitisation rule: strip every non-ASCII-alphanumeric
     * character. Exposed package-private for test reuse and explicit documentation.
     */
    static String sanitiseRegionName(String regionName) {
        return REGION_STRIP.matcher(regionName).replaceAll("");
    }

    /**
     * Parses any custom ID produced by this factory, dispatching by prefix.
     *
     * @param customId the ID to parse
     * @return a structured record matching the ID's format
     * @throws IllegalArgumentException if the ID does not match any known prefix or is
     *                                  otherwise malformed
     */
    public static ParsedCustomId parse(String customId) {
        Objects.requireNonNull(customId, "customId");
        if (customId.startsWith(PREFIX_BLUEBELL)) {
            return parseBluebell(customId);
        }
        if (customId.startsWith(PREFIX_WOODLAND)) {
            return parseWoodland(customId);
        }
        if (customId.startsWith(PREFIX_FORECAST)) {
            return parseForecast(customId);
        }
        if (customId.startsWith(PREFIX_JFDI)) {
            return parseJfdi(customId);
        }
        if (customId.startsWith(PREFIX_FORCE)) {
            return parseForceSubmit(customId);
        }
        if (customId.startsWith(PREFIX_AURORA)) {
            return parseAurora(customId);
        }
        throw new IllegalArgumentException("Unknown custom ID prefix: " + customId);
    }

    private static ParsedCustomId.Forecast parseForecast(String customId) {
        String body = customId;
        boolean forced = false;
        Matcher forcedMatch = FORCED_SUFFIX.matcher(body);
        if (forcedMatch.find()) {
            forced = true;
            body = body.substring(0, forcedMatch.start());
        }
        Long evalRowId = null;
        Matcher rowIdMatch = ROW_ID_SUFFIX.matcher(body);
        if (rowIdMatch.find()) {
            evalRowId = parseEvalRowId(rowIdMatch.group(1), customId);
            body = body.substring(0, rowIdMatch.start());
        }
        TailParts tail = extractDateAndTarget(body, PREFIX_FORECAST);
        Long locationId = parseLocationId(tail.before(), body);
        return new ParsedCustomId.Forecast(
                locationId, tail.date(), tail.targetType(), evalRowId, forced);
    }

    /**
     * Parses the {@code -r{evalRowId}} suffix's digit run. {@link #ROW_ID_SUFFIX} guarantees the
     * captured group is all digits, but does not guarantee it fits in a {@code long} — an
     * overflowing run (unreachable from a real IDENTITY-generated row id, but not from an
     * adversarial or corrupted custom id) must still be rejected as malformed rather than
     * propagate a bare {@link NumberFormatException} with a JDK-generic message, for the same
     * reason {@link #parseLocationId} wraps its own {@code Long.parseLong}.
     */
    private static Long parseEvalRowId(String digits, String customId) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Malformed custom ID (row id overflow): " + customId, e);
        }
    }

    private static ParsedCustomId.Woodland parseWoodland(String customId) {
        String body = customId;
        boolean forced = false;
        Matcher forcedMatch = FORCED_SUFFIX.matcher(body);
        if (forcedMatch.find()) {
            forced = true;
            body = body.substring(0, forcedMatch.start());
        }
        TailParts tail = extractDateAndTarget(body, PREFIX_WOODLAND);
        Long locationId = parseLocationId(tail.before(), body);
        return new ParsedCustomId.Woodland(locationId, tail.date(), tail.targetType(), forced);
    }

    private static ParsedCustomId.Bluebell parseBluebell(String customId) {
        String body = customId;
        boolean forced = false;
        Matcher forcedMatch = FORCED_SUFFIX.matcher(body);
        if (forcedMatch.find()) {
            forced = true;
            body = body.substring(0, forcedMatch.start());
        }
        TailParts tail = extractDateAndTarget(body, PREFIX_BLUEBELL);
        Long locationId = parseLocationId(tail.before(), body);
        return new ParsedCustomId.Bluebell(locationId, tail.date(), tail.targetType(), forced);
    }

    private static ParsedCustomId.Jfdi parseJfdi(String customId) {
        TailParts tail = extractDateAndTarget(customId, PREFIX_JFDI);
        Long locationId = parseLocationId(tail.before(), customId);
        return new ParsedCustomId.Jfdi(locationId, tail.date(), tail.targetType());
    }

    private static ParsedCustomId.ForceSubmit parseForceSubmit(String customId) {
        TailParts tail = extractDateAndTarget(customId, PREFIX_FORCE);
        // Before is "{sanitisedRegion}-{locationId}"; split at last hyphen.
        int locationSep = tail.before().lastIndexOf('-');
        if (locationSep < 1 || locationSep >= tail.before().length() - 1) {
            throw new IllegalArgumentException("Malformed force-submit custom ID: " + customId);
        }
        String sanitisedRegion = tail.before().substring(0, locationSep);
        Long locationId = parseLocationId(
                tail.before().substring(locationSep + 1), customId);
        return new ParsedCustomId.ForceSubmit(sanitisedRegion, locationId,
                tail.date(), tail.targetType());
    }

    private static ParsedCustomId.Aurora parseAurora(String customId) {
        String body = customId.substring(PREFIX_AURORA.length());
        if (body.length() < DATE_LEN + 2) {
            throw new IllegalArgumentException("Malformed aurora custom ID: " + customId);
        }
        String dateStr = body.substring(body.length() - DATE_LEN);
        // Expect a separating hyphen before the date.
        if (body.charAt(body.length() - DATE_LEN - 1) != '-') {
            throw new IllegalArgumentException("Malformed aurora custom ID: " + customId);
        }
        String alertName = body.substring(0, body.length() - DATE_LEN - 1);
        try {
            LocalDate date = LocalDate.parse(dateStr);
            AlertLevel alertLevel = AlertLevel.valueOf(alertName);
            return new ParsedCustomId.Aurora(alertLevel, date);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed aurora custom ID: " + customId, e);
        }
    }

    /**
     * Extracts the trailing {@code -{date}-{targetType}} suffix from {@code customId}
     * after stripping {@code prefix}, and returns the remaining "before" body.
     */
    private static TailParts extractDateAndTarget(String customId, String prefix) {
        String body = customId.substring(prefix.length());
        int targetSep = body.lastIndexOf('-');
        if (targetSep < DATE_LEN + 1) {
            throw new IllegalArgumentException("Malformed custom ID: " + customId);
        }
        String targetName = body.substring(targetSep + 1);
        String beforeTarget = body.substring(0, targetSep);
        if (beforeTarget.length() < DATE_LEN + 2) {
            throw new IllegalArgumentException("Malformed custom ID: " + customId);
        }
        String dateStr = beforeTarget.substring(beforeTarget.length() - DATE_LEN);
        if (beforeTarget.charAt(beforeTarget.length() - DATE_LEN - 1) != '-') {
            throw new IllegalArgumentException("Malformed custom ID: " + customId);
        }
        String before = beforeTarget.substring(0, beforeTarget.length() - DATE_LEN - 1);
        try {
            LocalDate date = LocalDate.parse(dateStr);
            TargetType target = TargetType.valueOf(targetName);
            return new TailParts(before, date, target);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed custom ID: " + customId, e);
        }
    }

    private static Long parseLocationId(String value, String customId) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed custom ID (non-numeric locationId): "
                    + customId, e);
        }
    }

    private static String validate(String id) {
        if (id.length() > MAX_LENGTH || !ANTHROPIC_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Custom ID exceeds " + MAX_LENGTH + " chars or contains invalid characters: "
                            + id);
        }
        return id;
    }

    private record TailParts(String before, LocalDate date, TargetType targetType) {
    }
}
