package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SlotSignals;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Detects cloud inversion hot topics by reading the slot surface's unified inversion signal
 * ({@link SlotSignals#effectiveInversionScore()}).
 *
 * <p>A temperature inversion traps cloud below elevated viewpoints, creating a "sea of
 * clouds" at dawn. {@code InversionScoreCalculator} runs a deterministic 0–10 likelihood score
 * for every inversion-eligible (elevated / overlooks-water) candidate, inside
 * {@code ForecastService.fetchWeatherAndTriage} before either triage check — so
 * {@code SlotAtmosphereWriter} records it for every such candidate whose weather was fetched
 * this cycle, whatever the triage verdict or Gate 4 decision that follows. This detector fires
 * when any such row in the window reaches the STRONG band — score &ge;
 * {@value #STRONG_SCORE_INCLUSIVE}, mirroring
 * {@link PromptBuilder.InversionPotential#fromScore(int)} (9–10 = STRONG).
 *
 * <p>⚠️ <b>Phase 2 of "record conditions for every place" (owner decision 2026-09-30): this
 * detector's score always follows the calculator, with Claude's echo as a stand-in only for a
 * slot the calculator has not reached yet.</b> Through 2026-09-30 this class read
 * {@link SlotSignals.Scores#inversion()} — the {@code forecast_score} INVERSION component,
 * written only from a completed Claude evaluation — so it was silent for a triaged-out or
 * Gate-4-stood-down location exactly like the two-question rule (2026-09-29) says a hot topic must
 * not be. A second cut moved it onto {@link SlotSignals.Readings#inversionScore()} alone,
 * reasoning that a forward slot is upserted every cycle and therefore always current; a Codex
 * review of PR #948 (round 3) found that reasoning wrong — {@code BriefingCandidateCollector}
 * skips a region with a fresh {@code cached_evaluation} entry ({@code SKIPPED_CACHED}, around
 * lines 204–227) BEFORE {@code fetchWeatherAndTriage} ever runs, and
 * {@code FreshnessProperties.settledHours} (36, uncapped at T+2 and beyond) lets that skip hold for
 * up to 36 hours on a SETTLED region, so a forward slot can carry a null calculator reading for a
 * day and a half while Claude's own echo already exists. This detector now reads
 * {@link SlotSignals#effectiveInversionScore()} instead — the ONE shared rule every reader of
 * this signal uses (also read by {@code ComingUpConditionsBuilder.buildInversion}'s two loops):
 * the calculator's reading when present, else Claude's echo. When the calculator HAS scored a
 * slot, the calculator decides, full stop — the reading always wins when both exist. The echo is
 * never anything more than a stand-in for what the calculator has not reached yet, and it is the
 * same 0–10 scale with the same STRONG cut, so this can never make the topic fire on a slot the
 * calculator itself would have refused.
 *
 * <p>⚠️ <b>Round 4: "the calculator has not reached this slot yet" and "the calculator reached it
 * and found nothing to report" are different facts, and only {@code inversion_scored} tells them
 * apart.</b> {@link SlotSignals#effectiveInversionScore()} used to read a null
 * {@link SlotSignals.Readings#inversionScore()} as reason enough to fall back to Claude's echo
 * — but a null reading is also exactly what a FRESH write produces: {@code
 * InversionScoreCalculator.calculate} itself returns null for an eligible location when the
 * required weather inputs (dew point, surface temperature) are missing, and {@code
 * ForecastDataAugmentor.augmentWithInversionScore} returns the base {@code AtmosphericData}
 * unchanged — the score staying null — for that case AND for an ineligible location alike. Since
 * {@code ForecastScoreWriter} only ever upserts the INVERSION component when the current
 * evaluation carries a non-null score, any earlier {@code forecast_score} row is left in place
 * forever whenever a later cycle scores nothing — so falling back on every null reading could
 * revive a STRONG rating from days ago that the current data no longer supports. V158's second
 * column, {@code inversion_scored}, fixes this: {@code SlotAtmosphereWriter} sets it {@code
 * true} on every write it makes, with a score or with a null one alike, so a row from a writer
 * that ran this cycle is authoritative — null included — and only a PRE-COLUMN row (written before
 * this flag existed, defaulting {@code false}) or an entirely absent key still falls back to the
 * echo.
 *
 * <p>⚠️ <b>Two surfaces, two questions, and they may disagree — deliberately.</b> The map popup's
 * inversion badge ({@code ForecastDtoMapper} → {@code forecast_evaluation.inversion_score}) stays
 * on Claude's echo alone and is unaffected by this change: it answers "is this place worth going
 * to", exactly the second question the two-question rule reserves for a completed evaluation, and
 * Claude has narrow discretion to disagree with the calculator on the measured reversal. This hot
 * topic answers "what is happening" and always follows the effective score above. So a location
 * can show a strong-inversion chip here while its own map badge reads a different band, or vice
 * versa on a location Claude never evaluated at all — that is the intended split, not a bug to
 * reconcile.
 *
 * <p>Makes no external API calls.
 *
 * <p><b>Advance notice, every morning.</b> An inversion "sea of clouds" is a dawn phenomenon —
 * it is only useful as night-before planning, because once sunrise has passed you can no longer
 * get to the viewpoint in time. This detector drops any row whose sunrise has already passed
 * ({@link SolarEventFreshness}) and lists <em>every</em> remaining strong-inversion morning in
 * the window, so a multi-day setup is surfaced in full rather than collapsed to the earliest day.
 *
 * <p><b>Sunrise rows only.</b> The writer records an inversion score for whichever event a
 * location was fetched for — including SUNSET, since the calculator gates only on
 * elevation/overlooks-water, not event type. But a sea of clouds is a dawn event, so a SUNSET
 * inversion row is physically meaningless <em>and</em> harmful: its freshness is judged against
 * the (still-future) sunset, so this morning's already-burned-off inversion would linger on the
 * board all evening labelled "today". Restricting to SUNRISE rows drops that noise and lets the
 * freshness filter retire a morning the instant its sunrise passes.
 */
@Component
public class InversionHotTopicStrategy implements HotTopicStrategy {

    private static final String INVERSION_DESCRIPTION =
            "A temperature inversion traps cloud below elevated viewpoints, creating a"
                    + " 'sea of clouds' effect. Best seen from high ground overlooking"
                    + " water at dawn.";

    /** Topic priority — act-on-it, sorts above the calendar heads-up topics. */
    private static final int PRIORITY = 2;

    /**
     * Inclusive lower bound of the STRONG inversion band on the calculator's 0–10 score. Matches
     * {@link PromptBuilder.InversionPotential#fromScore(int)} (score &ge; 9 = STRONG); MODERATE
     * (7–8) and below never fire the topic.
     */
    public static final int STRONG_SCORE_INCLUSIVE = 9;

    /** The italic "how to use it" cue on the enriched fact line. */
    private static final String INVERSION_NOTE =
            "climb above it — the valleys fill with cloud, burning off after sunrise";

    private final SlotSignalReader slotSignalReader;
    private final SolarEventFreshness freshness;

    /**
     * Constructs an {@code InversionHotTopicStrategy}.
     *
     * @param slotSignalReader the unified slot read model (inversion scores)
     * @param freshness            shared filter dropping strong-inversion mornings already past
     */
    public InversionHotTopicStrategy(SlotSignalReader slotSignalReader,
            SolarEventFreshness freshness) {
        this.slotSignalReader = slotSignalReader;
        this.freshness = freshness;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits one topic per non-expired strong-inversion morning in the window — each dated to
     * that morning and carrying only its regions — so a multi-day setup surfaces as an adjacent
     * run of day cards rather than a single collapsed pill. Returns empty when no strong-inversion
     * row remains after dropping mornings already past.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        List<SlotSignals> strong = slotSignalReader.read(fromDate, toDate).stream()
                .filter(s -> s.eventType() == TargetType.SUNRISE)
                .filter(s -> s.effectiveInversionScore() != null
                        && s.effectiveInversionScore() >= STRONG_SCORE_INCLUSIVE)
                .filter(s -> freshness.isAhead(s.location(), s.date(), s.eventType()))
                .sorted(Comparator.comparing(SlotSignals::date))
                .toList();
        if (strong.isEmpty()) {
            return List.of();
        }

        return PerDateHotTopicBuilder.perDate(
                strong,
                "INVERSION",
                "Cloud inversion",
                "Strong inversion likely at elevated locations",
                PRIORITY,
                INVERSION_DESCRIPTION,
                this::attachFacts);
    }

    /**
     * Attaches the inversion fact line — the likelihood score and band of the strongest of the
     * day's qualifying rows. The inversion-layer <em>altitude</em> is deliberately omitted: the
     * calculator scores inversion likelihood (0–10) but never computes a layer height, so a
     * metres figure would be fabricated. The score band is the honest headline.
     *
     * <p>The band is derived from the score, not read off a stored classification — the
     * calculator gives no NONE/MODERATE/STRONG string of its own, unlike Claude's echo. It uses
     * the SAME mapping {@link PromptBuilder.InversionPotential#fromScore(int)} applies, so the
     * fact line can never disagree with the threshold that gated it firing at all.
     *
     * @param topic   the day's base topic
     * @param dayRows that day's strong-inversion rows
     * @return the topic enriched with the strength fact (unchanged if no row carries a score)
     */
    private HotTopic attachFacts(HotTopic topic, List<SlotSignals> dayRows) {
        SlotSignals top = dayRows.stream()
                .filter(s -> s.effectiveInversionScore() != null)
                .max(Comparator.comparingDouble(SlotSignals::effectiveInversionScore))
                .orElse(null);
        if (top == null) {
            return topic;
        }
        // Round, never truncate — see PromptBuilder's own comment on the same conversion. The
        // calculator's components are all whole-number doubles (or exact 6.0/8.0 gate ceilings)
        // and Claude's echo is already a whole-number Integer widened to double, so this never
        // actually changes a value; it exists so the display rule can't drift from the one the
        // prompt already applies.
        int reported = (int) Math.round(top.effectiveInversionScore());
        String value = reported + "/10 · " + bandLabel(reported);
        return topic.withScience(
                List.of(HotTopicFact.metric("inversion", value)), INVERSION_NOTE);
    }

    /**
     * Derives the band label for the fact line from the score, lower-cased to sit inside the
     * running text — the same NONE/MODERATE/STRONG mapping the prompt uses, so this fact line and
     * Claude's own inversion forecast can never assign different words to the identical score.
     *
     * @param score the calculator's rounded 0–10 score
     * @return the lower-cased band label (e.g. {@code "strong"})
     */
    static String bandLabel(int score) {
        return PromptBuilder.InversionPotential.fromScore(score).name().toLowerCase(Locale.ROOT);
    }
}
