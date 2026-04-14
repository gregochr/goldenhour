package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BluebellConditionService}.
 */
class BluebellConditionServiceTest {

    private BluebellConditionService service;

    @BeforeEach
    void setUp() {
        service = new BluebellConditionService();
    }

    @Test
    @DisplayName("WOODLAND — mist + calm + soft light scores >= 7")
    void score_woodland_mistyAndCalmAndSoftLight_highScore() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(1500)          // < 2000m → misty
                .windSpeed(new BigDecimal("1.50"))   // 5.4 km/h → calm
                .lowCloud(70)
                .midCloud(80)
                .highCloud(60)             // avg 70 → softLight
                .precipitation(BigDecimal.ZERO)
                .build();

        BluebellConditionScore score = service.score(data, BluebellExposure.WOODLAND);

        assertThat(score.misty()).isTrue();
        assertThat(score.calm()).isTrue();
        assertThat(score.softLight()).isTrue();
        assertThat(score.overall()).isGreaterThanOrEqualTo(7);
        assertThat(score.exposure()).isEqualTo(BluebellExposure.WOODLAND);
    }

    @Test
    @DisplayName("OPEN_FELL — mist + calm + golden light scores >= 8")
    void score_openFell_mistyAndCalmAndGoldenLight_highScore() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(1800)          // < 2000m → misty
                .windSpeed(new BigDecimal("1.20"))   // 4.3 km/h → calm
                .lowCloud(10)
                .midCloud(20)
                .highCloud(15)             // avg 15 → goldenHourLight
                .precipitation(BigDecimal.ZERO)
                .build();

        BluebellConditionScore score = service.score(data, BluebellExposure.OPEN_FELL);

        assertThat(score.misty()).isTrue();
        assertThat(score.calm()).isTrue();
        assertThat(score.goldenHourLight()).isTrue();
        assertThat(score.overall()).isGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("WOODLAND — high wind + rain produces low score")
    void score_woodland_highWindAndRain_lowScore() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(10000)
                .windSpeed(new BigDecimal("6.00"))   // 21.6 km/h → not calm
                .lowCloud(0)
                .midCloud(5)
                .highCloud(0)
                .precipitation(new BigDecimal("2.0"))  // raining
                .build();

        BluebellConditionScore score = service.score(data, BluebellExposure.WOODLAND);

        assertThat(score.calm()).isFalse();
        assertThat(score.dryNow()).isFalse();
        assertThat(score.overall()).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("OPEN_FELL — high wind scores <= 4 (wind is critical)")
    void score_openFell_highWind_lowScore() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(10000)
                .windSpeed(new BigDecimal("5.00"))   // 18 km/h → not calm
                .lowCloud(5)
                .midCloud(10)
                .highCloud(0)
                .precipitation(BigDecimal.ZERO)
                .build();

        BluebellConditionScore score = service.score(data, BluebellExposure.OPEN_FELL);

        assertThat(score.calm()).isFalse();
        assertThat(score.overall()).isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("misty flag — dewpoint spread < 2°C triggers mist even with good visibility")
    void score_dewPointSpreadNarrow_mistyTrue() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(5000)          // > 2000m
                .temperature(8.0)
                .dewPoint(7.0)             // spread = 1°C → misty
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(60)
                .midCloud(70)
                .highCloud(70)
                .precipitation(BigDecimal.ZERO)
                .build();

        BluebellConditionScore score = service.score(data, BluebellExposure.WOODLAND);

        assertThat(score.misty()).isTrue();
    }

    @Test
    @DisplayName("postRain flag — precipitation >= 0.5mm triggers postRain")
    void score_precipitationAtThreshold_postRainTrue() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(8000)
                .windSpeed(new BigDecimal("1.5"))
                .lowCloud(40)
                .midCloud(50)
                .highCloud(60)
                .precipitation(new BigDecimal("0.5"))
                .build();

        BluebellConditionScore score = service.score(data, BluebellExposure.WOODLAND);

        assertThat(score.postRain()).isTrue();
        assertThat(score.dryNow()).isFalse();
    }

    @Test
    @DisplayName("dryNow flag — precipitation < 0.2mm")
    void score_lowPrecipitation_dryNowTrue() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(8000)
                .windSpeed(new BigDecimal("1.5"))
                .lowCloud(30)
                .midCloud(40)
                .highCloud(50)
                .precipitation(new BigDecimal("0.1"))
                .build();

        BluebellConditionScore score = service.score(data, BluebellExposure.WOODLAND);

        assertThat(score.dryNow()).isTrue();
        assertThat(score.postRain()).isFalse();
    }

    @Test
    @DisplayName("isGood returns true when score >= 6")
    void isGood_scoreAtLeast6_returnsTrue() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(1500)
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(70)
                .midCloud(80)
                .highCloud(60)
                .precipitation(BigDecimal.ZERO)
                .build();

        BluebellConditionScore score = service.score(data, BluebellExposure.WOODLAND);

        assertThat(score.isGood()).isTrue();
    }

    @Test
    @DisplayName("qualityLabel returns Excellent for score >= 8")
    void qualityLabel_excellent() {
        BluebellConditionScore score = new BluebellConditionScore(
                8, false, false, false, false, false, true, BluebellExposure.WOODLAND, "ok");
        assertThat(score.qualityLabel()).isEqualTo("Excellent");
    }

    @Test
    @DisplayName("qualityLabel returns Good for score 6-7")
    void qualityLabel_good() {
        BluebellConditionScore score = new BluebellConditionScore(
                6, false, false, false, false, false, true, BluebellExposure.WOODLAND, "ok");
        assertThat(score.qualityLabel()).isEqualTo("Good");
    }

    @Test
    @DisplayName("qualityLabel returns Fair for score 4-5")
    void qualityLabel_fair() {
        BluebellConditionScore score = new BluebellConditionScore(
                4, false, false, false, false, false, true, BluebellExposure.WOODLAND, "ok");
        assertThat(score.qualityLabel()).isEqualTo("Fair");
    }

    @Test
    @DisplayName("qualityLabel returns Poor for score < 4")
    void qualityLabel_poor() {
        BluebellConditionScore score = new BluebellConditionScore(
                3, false, false, false, false, false, true, BluebellExposure.WOODLAND, "ok");
        assertThat(score.qualityLabel()).isEqualTo("Poor");
    }

    @Test
    @DisplayName("isExcellent returns true for score >= 8")
    void isExcellent_highScore_returnsTrue() {
        BluebellConditionScore score = new BluebellConditionScore(
                9, true, true, false, true, false, true, BluebellExposure.OPEN_FELL, "excellent");
        assertThat(score.isExcellent()).isTrue();
    }

    @Test
    @DisplayName("isExcellent returns false for score < 8")
    void isExcellent_lowScore_returnsFalse() {
        BluebellConditionScore score = new BluebellConditionScore(
                7, false, true, true, false, false, true, BluebellExposure.WOODLAND, "good");
        assertThat(score.isExcellent()).isFalse();
    }

    @Test
    @DisplayName("summary — misty and calm returns perfect conditions message")
    void buildSummary_mistyAndCalm_returnsPerfectMessage() {
        String summary = service.buildSummary(true, true, false, false, false, false,
                BluebellExposure.WOODLAND);

        assertThat(summary).isEqualTo("Misty and still — perfect conditions");
    }

    @Test
    @DisplayName("summary — breezy includes blur warning")
    void buildSummary_notCalm_includesBlurWarning() {
        String summary = service.buildSummary(false, false, true, false, false, true,
                BluebellExposure.WOODLAND);

        assertThat(summary).contains("breezy");
    }

    // ── Threshold boundary tests ─────────────────────────────────────────────

    @Test
    @DisplayName("calm — wind just below 8 km/h (2.21 m/s = 7.956 km/h) is calm")
    void calm_windJustBelow8kmh_isCalm() {
        AtmosphericData data = TestAtmosphericData.builder()
                .windSpeed(new BigDecimal("2.21"))   // 2.21 * 3.6 = 7.956 km/h
                .visibility(10000)
                .lowCloud(30).midCloud(30).highCloud(30)
                .precipitation(BigDecimal.ZERO)
                .build();

        assertThat(service.score(data, BluebellExposure.WOODLAND).calm()).isTrue();
    }

    @Test
    @DisplayName("calm — wind just above 8 km/h (2.23 m/s = 8.028 km/h) is not calm")
    void calm_windJustAbove8kmh_isNotCalm() {
        AtmosphericData data = TestAtmosphericData.builder()
                .windSpeed(new BigDecimal("2.23"))   // 2.23 * 3.6 = 8.028 km/h
                .visibility(10000)
                .lowCloud(30).midCloud(30).highCloud(30)
                .precipitation(BigDecimal.ZERO)
                .build();

        assertThat(service.score(data, BluebellExposure.WOODLAND).calm()).isFalse();
    }

    @Test
    @DisplayName("misty — visibility at exactly 2000m is NOT misty (operator is <, not <=)")
    void misty_visibilityExactly2000m_notMisty() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(2000)
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(30).midCloud(30).highCloud(30)
                .precipitation(BigDecimal.ZERO)
                .build();

        assertThat(service.score(data, BluebellExposure.WOODLAND).misty()).isFalse();
    }

    @Test
    @DisplayName("misty — visibility at 1999m is misty")
    void misty_visibility1999m_isMisty() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(1999)
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(30).midCloud(30).highCloud(30)
                .precipitation(BigDecimal.ZERO)
                .build();

        assertThat(service.score(data, BluebellExposure.WOODLAND).misty()).isTrue();
    }

    @Test
    @DisplayName("misty via dew point — spread exactly 2.0°C is NOT misty (operator is <, not <=)")
    void misty_dewpointSpreadExactly2Celsius_notMisty() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(10000)
                .temperature(8.0)
                .dewPoint(6.0)             // spread = 2.0°C exactly
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(30).midCloud(30).highCloud(30)
                .precipitation(BigDecimal.ZERO)
                .build();

        assertThat(service.score(data, BluebellExposure.WOODLAND).misty()).isFalse();
    }

    @Test
    @DisplayName("misty via dew point — spread 1.99°C is misty")
    void misty_dewpointSpread1p99_isMisty() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(10000)
                .temperature(8.0)
                .dewPoint(6.01)            // spread = 1.99°C
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(30).midCloud(30).highCloud(30)
                .precipitation(BigDecimal.ZERO)
                .build();

        assertThat(service.score(data, BluebellExposure.WOODLAND).misty()).isTrue();
    }

    @Test
    @DisplayName("softLight — avgCloud exactly 60% is NOT soft light (operator is >, not >=)")
    void softLight_avgCloud60Percent_notSoftLight() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(10000)
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(60).midCloud(60).highCloud(60)  // avg = 60.0 exactly
                .precipitation(BigDecimal.ZERO)
                .build();

        assertThat(service.score(data, BluebellExposure.WOODLAND).softLight()).isFalse();
    }

    @Test
    @DisplayName("softLight — avgCloud above 60% is soft light")
    void softLight_avgCloudAbove60_isSoftLight() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(10000)
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(61).midCloud(61).highCloud(61)  // avg = 61.0
                .precipitation(BigDecimal.ZERO)
                .build();

        assertThat(service.score(data, BluebellExposure.WOODLAND).softLight()).isTrue();
    }

    @Test
    @DisplayName("goldenHourLight — avgCloud exactly 40% is NOT golden hour (operator is <, not <=)")
    void goldenHourLight_avgCloud40Percent_notGoldenHour() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(10000)
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(40).midCloud(40).highCloud(40)  // avg = 40.0 exactly
                .precipitation(BigDecimal.ZERO)
                .build();

        assertThat(service.score(data, BluebellExposure.OPEN_FELL).goldenHourLight()).isFalse();
    }

    @Test
    @DisplayName("goldenHourLight — avgCloud below 40% is golden hour")
    void goldenHourLight_avgCloudBelow40_isGoldenHour() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(10000)
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(39).midCloud(39).highCloud(39)  // avg = 39.0
                .precipitation(BigDecimal.ZERO)
                .build();

        assertThat(service.score(data, BluebellExposure.OPEN_FELL).goldenHourLight()).isTrue();
    }

    @Test
    @DisplayName("postRain — 0.49mm is NOT post-rain (operator is >=, not >)")
    void postRain_precipAt0p49mm_notPostRain() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(10000)
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(30).midCloud(30).highCloud(30)
                .precipitation(new BigDecimal("0.49"))
                .build();

        assertThat(service.score(data, BluebellExposure.WOODLAND).postRain()).isFalse();
    }

    @Test
    @DisplayName("dryNow — exactly 0.2mm is NOT dry now (operator is <, not <=)")
    void dryNow_precipExactly0p2mm_notDryNow() {
        AtmosphericData data = TestAtmosphericData.builder()
                .visibility(10000)
                .windSpeed(new BigDecimal("1.0"))
                .lowCloud(30).midCloud(30).highCloud(30)
                .precipitation(new BigDecimal("0.2"))
                .build();

        assertThat(service.score(data, BluebellExposure.WOODLAND).dryNow()).isFalse();
    }

    // ── Exact score tests (kill score-increment mutants) ─────────────────────

    @Test
    @DisplayName("WOODLAND — misty only scores exactly 3")
    void calculateScore_woodland_mistyOnly_exactScore3() {
        int score = service.calculateScore(true, false, false, false, false, false,
                BluebellExposure.WOODLAND);
        assertThat(score).isEqualTo(3);
    }

    @Test
    @DisplayName("WOODLAND — calm only scores exactly 2")
    void calculateScore_woodland_calmOnly_exactScore2() {
        int score = service.calculateScore(false, true, false, false, false, false,
                BluebellExposure.WOODLAND);
        assertThat(score).isEqualTo(2);
    }

    @Test
    @DisplayName("WOODLAND — softLight only scores exactly 3 (2.5 rounds up)")
    void calculateScore_woodland_softLightOnly_exactScore3() {
        int score = service.calculateScore(false, false, true, false, false, false,
                BluebellExposure.WOODLAND);
        assertThat(score).isEqualTo(3);
    }

    @Test
    @DisplayName("WOODLAND — postRain only scores exactly 2 (1.5 rounds up)")
    void calculateScore_woodland_postRainOnly_exactScore2() {
        int score = service.calculateScore(false, false, false, false, true, false,
                BluebellExposure.WOODLAND);
        assertThat(score).isEqualTo(2);
    }

    @Test
    @DisplayName("WOODLAND — dryNow only scores exactly 1")
    void calculateScore_woodland_dryNowOnly_exactScore1() {
        int score = service.calculateScore(false, false, false, false, false, true,
                BluebellExposure.WOODLAND);
        assertThat(score).isEqualTo(1);
    }

    @Test
    @DisplayName("OPEN_FELL — calm only scores exactly 3")
    void calculateScore_openFell_calmOnly_exactScore3() {
        int score = service.calculateScore(false, true, false, false, false, false,
                BluebellExposure.OPEN_FELL);
        assertThat(score).isEqualTo(3);
    }

    @Test
    @DisplayName("OPEN_FELL — goldenHourLight only scores exactly 2")
    void calculateScore_openFell_goldenHourLightOnly_exactScore2() {
        int score = service.calculateScore(false, false, false, true, false, false,
                BluebellExposure.OPEN_FELL);
        assertThat(score).isEqualTo(2);
    }

    @Test
    @DisplayName("OPEN_FELL — misty + calm + golden = exactly 8")
    void calculateScore_openFell_mistyAndCalmAndGolden_exactScore8() {
        int score = service.calculateScore(true, true, false, true, false, false,
                BluebellExposure.OPEN_FELL);
        assertThat(score).isEqualTo(8);
    }

    // ── buildSummary additional branches ─────────────────────────────────────

    @Test
    @DisplayName("summary — WOODLAND + softLight adds 'soft diffused light'")
    void buildSummary_woodlandSoftLight_includesDiffusedLight() {
        String summary = service.buildSummary(false, false, true, false, false, true,
                BluebellExposure.WOODLAND);

        assertThat(summary).contains("soft diffused light");
    }

    @Test
    @DisplayName("summary — OPEN_FELL + goldenHourLight adds 'golden hour light'")
    void buildSummary_openFellGoldenHour_includesGoldenHourLight() {
        String summary = service.buildSummary(false, false, false, true, false, true,
                BluebellExposure.OPEN_FELL);

        assertThat(summary).contains("golden hour light");
    }

    @Test
    @DisplayName("summary — WOODLAND + softLight not added for OPEN_FELL exposure")
    void buildSummary_woodlandSoftLightNotAddedForOpenFell() {
        String summary = service.buildSummary(false, false, true, false, false, true,
                BluebellExposure.OPEN_FELL);

        assertThat(summary).doesNotContain("soft diffused light");
    }

    @Test
    @DisplayName("summary — misty but not calm includes 'Misty morning' and 'breezy'")
    void buildSummary_mistyNotCalm_includesMistyMorningAndBreezy() {
        String summary = service.buildSummary(true, false, false, false, false, true,
                BluebellExposure.WOODLAND);

        assertThat(summary).contains("Misty morning");
        assertThat(summary).contains("breezy");
    }

    @Test
    @DisplayName("summary — postRain and dryNow together adds 'post-rain freshness'")
    void buildSummary_postRainAndDryNow_includesFreshness() {
        String summary = service.buildSummary(false, true, false, false, true, true,
                BluebellExposure.WOODLAND);

        assertThat(summary).contains("post-rain freshness");
    }

    @Test
    @DisplayName("summary — still raining (not dryNow) adds rain-expected warning")
    void buildSummary_notDryNow_includesRainExpected() {
        String summary = service.buildSummary(false, true, false, false, false, false,
                BluebellExposure.WOODLAND);

        assertThat(summary).contains("rain expected during shooting window");
    }
}
