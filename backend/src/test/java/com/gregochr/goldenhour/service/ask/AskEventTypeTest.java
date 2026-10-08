package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AskEventType}: the one identity every Ask site compares an event type by. */
class AskEventTypeTest {

    @ParameterizedTest
    @CsvSource({
        "LUNAR_ECLIPSE, LUNAR_ECLIPSE",
        "lunar-eclipse, LUNAR_ECLIPSE",
        "Lunar-Eclipse, LUNAR_ECLIPSE",
        "LUNAR-ECLIPSE, LUNAR_ECLIPSE",
        "'  lunar-eclipse  ', LUNAR_ECLIPSE",
        "ECLIPSE, ECLIPSE",
        "eclipse, ECLIPSE",
        "spring-tide, SPRING_TIDE",
        "snow_tops, SNOW_TOPS",
        "a-b-c, A_B_C",
    })
    @DisplayName("the key strips, upper-cases and reads a dash as an underscore")
    void key(String type, String expected) {
        assertThat(AskEventType.key(type)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a missing type has the empty key, so it equals nothing a tool returned")
    void nullKey() {
        assertThat(AskEventType.key(null)).isEmpty();
        assertThat(AskEventType.key("   ")).isEmpty();
    }

    @Test
    @DisplayName("two spellings of one type are the same type; different types, and a missing one, are not")
    void same() {
        assertThat(AskEventType.same("lunar-eclipse", "LUNAR_ECLIPSE")).isTrue();
        assertThat(AskEventType.same(" Aurora ", "AURORA")).isTrue();
        assertThat(AskEventType.same("ECLIPSE", "LUNAR_ECLIPSE")).isFalse();
        assertThat(AskEventType.same(null, "AURORA")).isFalse();
        assertThat(AskEventType.same("AURORA", null)).isFalse();
        assertThat(AskEventType.same(null, null)).as("nothing equals nothing").isFalse();
    }

    @Test
    @DisplayName("the offer key is the type's key and the date, so one event reads the same from a topic and an entry")
    void offerKey() {
        LocalDate date = LocalDate.of(2026, 10, 14);

        assertThat(AskEventType.offerKey("lunar-eclipse", date)).isEqualTo("LUNAR_ECLIPSE|2026-10-14");
        assertThat(AskEventType.offerKey("LUNAR_ECLIPSE", date))
                .isEqualTo(AskEventType.offerKey("lunar-eclipse", date));
        assertThat(AskEventType.offerKey("LUNAR_ECLIPSE", date.plusDays(1)))
                .isNotEqualTo(AskEventType.offerKey("LUNAR_ECLIPSE", date));
        assertThat(AskEventType.offerKey("AURORA", null)).isEqualTo("AURORA|null");
    }

    @Test
    @DisplayName("the served type is upper-cased and stripped but keeps its dash: it is what a card carries, "
            + "not an identity")
    void served() {
        assertThat(AskEventType.served(" lunar-eclipse ")).isEqualTo("LUNAR-ECLIPSE");
        assertThat(AskEventType.served("LUNAR_ECLIPSE")).isEqualTo("LUNAR_ECLIPSE");
        assertThat(AskEventType.served(null)).isNull();
    }
}
