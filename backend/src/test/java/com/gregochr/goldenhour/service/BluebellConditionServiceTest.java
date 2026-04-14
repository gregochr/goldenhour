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
}
