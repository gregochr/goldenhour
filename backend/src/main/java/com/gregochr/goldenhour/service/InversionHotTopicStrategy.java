package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SurvivorSignals;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Detects cloud inversion hot topics by reading the survivor surface's readings half
 * ({@code survivor_atmosphere}).
 *
 * <p>A temperature inversion traps cloud below elevated viewpoints, creating a "sea of
 * clouds" at dawn. {@code InversionScoreCalculator} runs a deterministic 0–10 likelihood score
 * for every inversion-eligible (elevated / overlooks-water) candidate, inside
 * {@code ForecastService.fetchWeatherAndTriage} before either triage check — so
 * {@code SurvivorAtmosphereWriter} records it for every such candidate whose weather was fetched
 * this cycle, whatever the triage verdict or Gate 4 decision that follows. This detector fires
 * when any such row in the window reaches the STRONG band — score &ge;
 * {@value #STRONG_SCORE_INCLUSIVE}, mirroring
 * {@link PromptBuilder.InversionPotential#fromScore(int)} (9–10 = STRONG).
 *
 * <p>⚠️ <b>Phase 2 of "record conditions for every place" (owner decision 2026-09-30): this
 * detector moved off Claude's echo onto the calculator's own score.</b> Through 2026-09-30 this
 * class read {@link SurvivorSignals.Scores#inversion()} — the {@code forecast_score} INVERSION
 * component, written only from a completed Claude evaluation — so it was silent for a triaged-out
 * or Gate-4-stood-down location exactly like the two-question rule (2026-09-29) says a hot topic
 * must not be. It now reads {@link SurvivorSignals.Readings#inversionScore()} instead — the
 * deterministic calculator's own score, from {@code survivor_atmosphere} (V158) — so a stood-down
 * location still shows its inversion likelihood. {@code Scores#inversion()} is left entirely
 * unread here now; it is still read by {@code ComingUpConditionsBuilder}'s trailing-history
 * display and by {@code TopicDailyLogJob} (both documented on their own classes).
 *
 * <p>⚠️ <b>Two surfaces, two questions, and they may disagree — deliberately.</b> The map popup's
 * inversion badge ({@code ForecastDtoMapper} → {@code forecast_evaluation.inversion_score}) stays
 * on Claude's echo and is unaffected by this change: it answers "is this place worth going to",
 * exactly the second question the two-question rule reserves for a completed evaluation, and
 * Claude has narrow discretion to disagree with the calculator on the measured reversal. This hot
 * topic answers "what is happening" and always follows the calculator. So a location can show a
 * strong-inversion chip here while its own map badge reads a different band, or vice versa on a
 * location Claude never evaluated at all — that is the intended split, not a bug to reconcile.
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

    private final SurvivorSignalReader survivorSignalReader;
    private final SolarEventFreshness freshness;

    /**
     * Constructs an {@code InversionHotTopicStrategy}.
     *
     * @param survivorSignalReader the unified survivor read model (inversion scores)
     * @param freshness            shared filter dropping strong-inversion mornings already past
     */
    public InversionHotTopicStrategy(SurvivorSignalReader survivorSignalReader,
            SolarEventFreshness freshness) {
        this.survivorSignalReader = survivorSignalReader;
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
        List<SurvivorSignals> strong = survivorSignalReader.read(fromDate, toDate).stream()
                .filter(s -> s.eventType() == TargetType.SUNRISE)
                .filter(s -> s.readings().inversionScore() != null
                        && s.readings().inversionScore() >= STRONG_SCORE_INCLUSIVE)
                .filter(s -> freshness.isAhead(s.location(), s.date(), s.eventType()))
                .sorted(Comparator.comparing(SurvivorSignals::date))
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
    private HotTopic attachFacts(HotTopic topic, List<SurvivorSignals> dayRows) {
        SurvivorSignals top = dayRows.stream()
                .filter(s -> s.readings().inversionScore() != null)
                .max(Comparator.comparingDouble((SurvivorSignals s) -> s.readings().inversionScore()))
                .orElse(null);
        if (top == null) {
            return topic;
        }
        // Round, never truncate — see PromptBuilder's own comment on the same conversion. The
        // calculator's components are all whole-number doubles (or exact 6.0/8.0 gate ceilings),
        // so this never actually changes a value; it exists so the display rule can't drift from
        // the one the prompt already applies.
        int reported = (int) Math.round(top.readings().inversionScore());
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
