package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CustomIdFactoryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 16);
    private static final Pattern ANTHROPIC = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    @Test
    void forForecastProducesExpectedFormat() {
        assertThat(CustomIdFactory.forForecast(42L, DATE, TargetType.SUNRISE))
                .isEqualTo("fc-42-2026-04-16-SUNRISE");
    }

    @Test
    void forJfdiProducesExpectedFormat() {
        assertThat(CustomIdFactory.forJfdi(42L, DATE, TargetType.SUNSET))
                .isEqualTo("jfdi-42-2026-04-16-SUNSET");
    }

    @Test
    void forForceSubmitSanitisesRegionName() {
        String id = CustomIdFactory.forForceSubmit(
                "The North York Moors", 93L, DATE, TargetType.SUNSET);
        assertThat(id).isEqualTo("force-TheNorthYorkMoors-93-2026-04-16-SUNSET");
    }

    @Test
    void forForceSubmitStripsPunctuationAndUnderscores() {
        String id = CustomIdFactory.forForceSubmit(
                "Lake-District_(Wet)!", 1L, DATE, TargetType.SUNRISE);
        assertThat(id).isEqualTo("force-LakeDistrictWet-1-2026-04-16-SUNRISE");
    }

    @Test
    void forAuroraProducesExpectedFormat() {
        assertThat(CustomIdFactory.forAurora(AlertLevel.MODERATE, DATE))
                .isEqualTo("au-MODERATE-2026-04-16");
    }

    @Test
    void allBuildersProduceAnthropicCompatibleIds() {
        assertThat(CustomIdFactory.forForecast(42L, DATE, TargetType.SUNRISE))
                .matches(ANTHROPIC);
        assertThat(CustomIdFactory.forJfdi(42L, DATE, TargetType.SUNRISE))
                .matches(ANTHROPIC);
        assertThat(CustomIdFactory.forForceSubmit("Lake District", 42L, DATE, TargetType.SUNRISE))
                .matches(ANTHROPIC);
        assertThat(CustomIdFactory.forAurora(AlertLevel.STRONG, DATE))
                .matches(ANTHROPIC);
    }

    @Test
    void validationRejectsIdExceeding64Chars() {
        String longRegion = "A".repeat(60);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CustomIdFactory.forForceSubmit(
                        longRegion, 999L, DATE, TargetType.SUNRISE))
                .withMessageContaining("64 chars");
    }

    @Test
    void buildersRejectNullArguments() {
        assertThatNullPointerException()
                .isThrownBy(() -> CustomIdFactory.forForecast(null, DATE, TargetType.SUNRISE));
        assertThatNullPointerException()
                .isThrownBy(() -> CustomIdFactory.forForecast(42L, null, TargetType.SUNRISE));
        assertThatNullPointerException()
                .isThrownBy(() -> CustomIdFactory.forForecast(42L, DATE, null));
        assertThatNullPointerException()
                .isThrownBy(() -> CustomIdFactory.forAurora(null, DATE));
    }

    @Test
    void parseForecastReturnsStructuredRecord() {
        ParsedCustomId parsed = CustomIdFactory.parse("fc-42-2026-04-16-SUNRISE");
        assertThat(parsed).isInstanceOf(ParsedCustomId.Forecast.class);
        ParsedCustomId.Forecast f = (ParsedCustomId.Forecast) parsed;
        assertThat(f.locationId()).isEqualTo(42L);
        assertThat(f.date()).isEqualTo(DATE);
        assertThat(f.targetType()).isEqualTo(TargetType.SUNRISE);
    }

    @Test
    void parseJfdiReturnsStructuredRecord() {
        ParsedCustomId parsed = CustomIdFactory.parse("jfdi-7-2026-04-16-SUNSET");
        assertThat(parsed).isInstanceOf(ParsedCustomId.Jfdi.class);
        ParsedCustomId.Jfdi j = (ParsedCustomId.Jfdi) parsed;
        assertThat(j.locationId()).isEqualTo(7L);
        assertThat(j.date()).isEqualTo(DATE);
        assertThat(j.targetType()).isEqualTo(TargetType.SUNSET);
    }

    @Test
    void parseForceSubmitReturnsStructuredRecord() {
        ParsedCustomId parsed = CustomIdFactory.parse(
                "force-TheNorthYorkMoors-93-2026-04-16-SUNSET");
        assertThat(parsed).isInstanceOf(ParsedCustomId.ForceSubmit.class);
        ParsedCustomId.ForceSubmit fs = (ParsedCustomId.ForceSubmit) parsed;
        assertThat(fs.sanitisedRegion()).isEqualTo("TheNorthYorkMoors");
        assertThat(fs.locationId()).isEqualTo(93L);
        assertThat(fs.date()).isEqualTo(DATE);
        assertThat(fs.targetType()).isEqualTo(TargetType.SUNSET);
    }

    @Test
    void parseAuroraReturnsStructuredRecord() {
        ParsedCustomId parsed = CustomIdFactory.parse("au-MODERATE-2026-04-16");
        assertThat(parsed).isInstanceOf(ParsedCustomId.Aurora.class);
        ParsedCustomId.Aurora a = (ParsedCustomId.Aurora) parsed;
        assertThat(a.alertLevel()).isEqualTo(AlertLevel.MODERATE);
        assertThat(a.date()).isEqualTo(DATE);
    }

    @Test
    void forecastRoundTripsThroughParse() {
        String id = CustomIdFactory.forForecast(42L, DATE, TargetType.SUNRISE);
        assertThat(CustomIdFactory.parse(id))
                .isEqualTo(new ParsedCustomId.Forecast(42L, DATE, TargetType.SUNRISE));
    }

    @Test
    void jfdiRoundTripsThroughParse() {
        String id = CustomIdFactory.forJfdi(7L, DATE, TargetType.SUNSET);
        assertThat(CustomIdFactory.parse(id))
                .isEqualTo(new ParsedCustomId.Jfdi(7L, DATE, TargetType.SUNSET));
    }

    @Test
    void forceSubmitRoundTripsThroughParse() {
        String id = CustomIdFactory.forForceSubmit(
                "The North York Moors", 93L, DATE, TargetType.SUNSET);
        assertThat(CustomIdFactory.parse(id))
                .isEqualTo(new ParsedCustomId.ForceSubmit(
                        "TheNorthYorkMoors", 93L, DATE, TargetType.SUNSET));
    }

    @Test
    void auroraRoundTripsThroughParse() {
        String id = CustomIdFactory.forAurora(AlertLevel.STRONG, DATE);
        assertThat(CustomIdFactory.parse(id))
                .isEqualTo(new ParsedCustomId.Aurora(AlertLevel.STRONG, DATE));
    }

    @Test
    void parseRejectsUnknownPrefix() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CustomIdFactory.parse("unknown-42-2026-04-16-SUNRISE"))
                .withMessageContaining("Unknown custom ID prefix");
    }

    @Test
    void parseRejectsNonNumericLocationIdInForecast() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CustomIdFactory.parse("fc-not-a-number-2026-04-16-SUNRISE"))
                .withMessageContaining("Malformed custom ID");
    }

    @Test
    void parseRejectsInvalidDateInForecast() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CustomIdFactory.parse("fc-42-not-a-date-SUNRISE"))
                .withMessageContaining("Malformed custom ID");
    }

    @Test
    void parseRejectsUnknownTargetTypeInForecast() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CustomIdFactory.parse("fc-42-2026-04-16-NOTATYPE"))
                .withMessageContaining("Malformed custom ID");
    }

    @Test
    void parseRejectsInvalidAlertLevelInAurora() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CustomIdFactory.parse("au-WILD-2026-04-16"))
                .withMessageContaining("Malformed aurora");
    }

    @Test
    void parseRejectsTruncatedForecastId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CustomIdFactory.parse("fc-42-SUNRISE"));
    }

    @Test
    void parseRejectsNullInput() {
        assertThatNullPointerException()
                .isThrownBy(() -> CustomIdFactory.parse(null));
    }

    @Test
    void forForceSubmitRejectsRegionThatSanitisesToEmpty() {
        // "!!!", "—", "***" all sanitise to "" and would produce "force--{id}-{date}-{type}".
        // The parse-side cannot recover the empty region segment (it requires at least
        // one char before the locationId hyphen), so build must fail-fast rather than
        // emit an ID that round-trips to MALFORMED_ID at result time.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CustomIdFactory.forForceSubmit(
                        "!!!", 1L, DATE, TargetType.SUNRISE))
                .withMessageContaining("no alphanumeric");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CustomIdFactory.forForceSubmit(
                        "", 1L, DATE, TargetType.SUNRISE))
                .withMessageContaining("no alphanumeric");
    }

    @Test
    void forForceSubmitAcceptsIdAtExactly64Chars() {
        // "force-" (6) + region (38) + "-" (1) + "1" (1) + "-" (1)
        //   + "2026-04-16" (10) + "-" (1) + "SUNSET" (6) = 64
        String region = "A".repeat(38);
        String id = CustomIdFactory.forForceSubmit(region, 1L, DATE, TargetType.SUNSET);
        assertThat(id).hasSize(64);
        // Round-trips cleanly — boundary value is genuinely valid, not just accidentally
        // accepted by an off-by-one mutation.
        assertThat(CustomIdFactory.parse(id))
                .isEqualTo(new ParsedCustomId.ForceSubmit(region, 1L, DATE, TargetType.SUNSET));
    }

    @Test
    void forForceSubmitRejectsIdAtExactly65Chars() {
        // 39 chars of A — one over the boundary tested above. Mutation guard for `> 64`
        // vs `>= 64` boundary in CustomIdFactory.validate.
        String region = "A".repeat(39);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CustomIdFactory.forForceSubmit(
                        region, 1L, DATE, TargetType.SUNSET))
                .withMessageContaining("64 chars");
    }

    @Test
    void forForecastAcceptsZeroLocationId() {
        // Defensive: locationId=0 is unusual but technically valid (Long.parseLong("0")
        // succeeds). Asserts the factory does not silently reject non-positive IDs.
        String id = CustomIdFactory.forForecast(0L, DATE, TargetType.SUNRISE);
        assertThat(id).isEqualTo("fc-0-2026-04-16-SUNRISE");
        assertThat(CustomIdFactory.parse(id))
                .isEqualTo(new ParsedCustomId.Forecast(0L, DATE, TargetType.SUNRISE));
    }

    @Test
    void forForecastRoundTripsLongMaxValueLocationId() {
        // 19-digit max long. ID is 41 chars total — comfortably under the 64-char limit
        // even at the largest possible locationId, confirming the boundary holds.
        String id = CustomIdFactory.forForecast(Long.MAX_VALUE, DATE, TargetType.SUNRISE);
        assertThat(CustomIdFactory.parse(id))
                .isEqualTo(new ParsedCustomId.Forecast(
                        Long.MAX_VALUE, DATE, TargetType.SUNRISE));
    }

    @Test
    void sanitiseRegionPreservesExistingBehaviour() {
        // Rule preserved verbatim from ForceSubmitBatchService:276-278
        assertThat(CustomIdFactory.sanitiseRegionName("North York Moors"))
                .isEqualTo("NorthYorkMoors");
        assertThat(CustomIdFactory.sanitiseRegionName("Lake-District_1")).isEqualTo("LakeDistrict1");
        assertThat(CustomIdFactory.sanitiseRegionName("")).isEmpty();
        assertThat(CustomIdFactory.sanitiseRegionName("!!!")).isEmpty();
    }
}
