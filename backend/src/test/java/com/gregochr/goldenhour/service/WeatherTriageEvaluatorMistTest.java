package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.TriageResult;
import com.gregochr.goldenhour.model.TriageRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the valley-mist triage routing (valley-mist-plan M2): rule 3 (visibility under 5 km) is NOT
 * CONSULTED for a slot whose band is exactly LIKELY, rules 1 and 2 still apply, and the two log lines
 * fire only where they are meant to.
 */
class WeatherTriageEvaluatorMistTest {

    private static final LocalDateTime SUNRISE = LocalDateTime.of(2026, 10, 22, 6, 41);

    private final WeatherTriageEvaluator evaluator = new WeatherTriageEvaluator();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(WeatherTriageEvaluator.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static TestAtmosphericData slot(int visibility, MistPotential band) {
        return TestAtmosphericData.builder()
                .locationName("River Brathay")
                .solarEventTime(SUNRISE)
                .targetType(TargetType.SUNRISE)
                .lowCloud(10)
                .visibility(visibility)
                .mistScore(band == null ? null : 7.0)
                .mistPotential(band);
    }

    private List<String> infoMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("LIKELY at 3,000 m visibility is not stood down: rule 3 is not consulted")
    void likelyBelowFiveKm_proceeds() {
        Optional<TriageResult> result = evaluator.evaluate(slot(3000, MistPotential.LIKELY).build());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("the exemption logs one [MIST CANDIDATE] line naming the site, date, event and visibility")
    void likelyBelowFiveKm_logsCandidateLine() {
        evaluator.evaluate(slot(3000, MistPotential.LIKELY).build());

        assertThat(infoMessages()).containsExactly(
                "[MIST CANDIDATE] River Brathay 2026-10-22 SUNRISE: visibility 3000 m, mist LIKELY"
                        + " — rule 3 not consulted");
    }

    @ParameterizedTest(name = "{0} still stands the slot down on rule 3")
    @EnumSource(value = MistPotential.class, names = {"POSSIBLE", "FOGBOUND", "NONE"})
    @DisplayName("only LIKELY is exempt: POSSIBLE, FOGBOUND and NONE keep rule 3")
    void otherBands_keepRuleThree(MistPotential band) {
        Optional<TriageResult> result = evaluator.evaluate(slot(3000, band).build());

        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.LOW_VISIBILITY);
        assertThat(infoMessages()).isEmpty();
    }

    @Test
    @DisplayName("an unflagged site (no band) keeps rule 3 unchanged")
    void unflaggedSite_keepsRuleThree() {
        Optional<TriageResult> result = evaluator.evaluate(slot(3000, null).build());

        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.LOW_VISIBILITY);
        assertThat(infoMessages()).isEmpty();
    }

    @Test
    @DisplayName("a LIKELY slot with visibility above 5 km passes quietly: no candidate line")
    void likelyAboveFiveKm_noLine() {
        assertThat(evaluator.evaluate(slot(9000, MistPotential.LIKELY).build())).isEmpty();
        assertThat(infoMessages()).isEmpty();
    }

    @Test
    @DisplayName("rule 1 still stands a LIKELY slot down, and logs one [BATCH DIAG] line naming the rule")
    void likelyWithBlockedSolarHorizon_isStillRefused() {
        AtmosphericData data = slot(3000, MistPotential.LIKELY).lowCloud(90).build();

        Optional<TriageResult> result = evaluator.evaluate(data);

        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.HIGH_CLOUD);
        assertThat(infoMessages()).hasSize(1);
        assertThat(infoMessages().getFirst())
                .startsWith("[BATCH DIAG] MIST CANDIDATE River Brathay | date=2026-10-22 event=SUNRISE")
                .contains("refused by rule 1 (HIGH_CLOUD)")
                .contains("90%");
    }

    @Test
    @DisplayName("rule 2 still stands a LIKELY slot down, and logs one [BATCH DIAG] line naming the rule")
    void likelyInRain_isStillRefused() {
        AtmosphericData data = slot(3000, MistPotential.LIKELY)
                .precipitation(new BigDecimal("3.0")).build();

        Optional<TriageResult> result = evaluator.evaluate(data);

        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.PRECIPITATION);
        assertThat(infoMessages()).hasSize(1);
        assertThat(infoMessages().getFirst()).contains("refused by rule 2 (PRECIPITATION)");
    }

    @Test
    @DisplayName("a refused slot that is not a LIKELY mist candidate logs nothing")
    void ordinaryRefusal_logsNothing() {
        evaluator.evaluate(slot(30000, null).lowCloud(90).build());

        assertThat(infoMessages()).isEmpty();
    }

    @Test
    @DisplayName("the rule 3 reason text is untouched, so the 'Fog forecast' wording still recognises it")
    void ruleThreeReason_roundTripsThroughTheRecogniser() {
        TriageResult result = evaluator.evaluate(slot(3000, MistPotential.FOGBOUND).build()).orElseThrow();

        assertThat(WeatherTriageEvaluator.isLowVisibilityReason(result.reason())).isTrue();
    }
}
