package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson round-trip tests for {@link DirectionalCloudData}. The mapper is deliberately a strict
 * default one (plain {@code ObjectMapper} plus the JavaTime module, unknown properties fail), not
 * the replay services' lenient {@code ReplayJson} mapper: it is the stronger check for the round trip.
 */
class DirectionalCloudDataJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("a 12-component record survives serialise then deserialise")
    void twelveComponents_roundTrip() throws Exception {
        DirectionalCloudData original = new DirectionalCloudData(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

        DirectionalCloudData read = mapper.readValue(mapper.writeValueAsString(original),
                DirectionalCloudData.class);

        assertThat(read).isEqualTo(original);
        assertThat(read.farSolarHighCloudPercentDarkHour()).isEqualTo(12);
    }

    @Test
    @DisplayName("JSON holding only the 7 old keys deserialises with the five new values null")
    void sevenOldKeys_deserialiseWithNewValuesNull() throws Exception {
        String old = "{\"solarLowCloudPercent\":1,\"solarMidCloudPercent\":2,"
                + "\"solarHighCloudPercent\":3,\"antisolarLowCloudPercent\":4,"
                + "\"antisolarMidCloudPercent\":5,\"antisolarHighCloudPercent\":6,"
                + "\"farSolarLowCloudPercent\":7}";

        DirectionalCloudData read = mapper.readValue(old, DirectionalCloudData.class);

        assertThat(read.farSolarLowCloudPercent()).isEqualTo(7);
        assertThat(read.farSolarMidCloudPercent()).isNull();
        assertThat(read.farSolarHighCloudPercent()).isNull();
        assertThat(read.farSolarLowCloudPercentDarkHour()).isNull();
        assertThat(read.farSolarMidCloudPercentDarkHour()).isNull();
        assertThat(read.farSolarHighCloudPercentDarkHour()).isNull();
    }

    @Test
    @DisplayName("the 7-argument constructor leaves the five recorded values null")
    void sevenArgConstructor_leavesNewValuesNull() {
        DirectionalCloudData data = new DirectionalCloudData(1, 2, 3, 4, 5, 6, 7);

        assertThat(data.farSolarMidCloudPercent()).isNull();
        assertThat(data.farSolarHighCloudPercentDarkHour()).isNull();
    }
}
