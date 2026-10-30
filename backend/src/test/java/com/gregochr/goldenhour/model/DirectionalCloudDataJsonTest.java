package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson round-trip tests for {@link DirectionalCloudData}. The mapper is deliberately a strict
 * default one (plain {@code ObjectMapper} plus the JavaTime module, unknown properties fail), not
 * a lenient replay mapper: it is the stronger check for the round trip.
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

    @Test
    @DisplayName("an 18-component record survives serialise then deserialise")
    void eighteenComponents_roundTrip() throws Exception {
        DirectionalCloudData original = new DirectionalCloudData(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18);

        DirectionalCloudData read = mapper.readValue(mapper.writeValueAsString(original),
                DirectionalCloudData.class);

        assertThat(read).isEqualTo(original);
        assertThat(read.farSolarCloud850hPa()).isEqualTo(13);
        assertThat(read.farSolarCloud300hPa()).isEqualTo(18);
    }

    @Test
    @DisplayName("old 7-key and 12-key JSON deserialise with the six pressure-level values null")
    void oldJson_deserialisesWithPressureLevelsNull() throws Exception {
        String sevenKeys = "{\"solarLowCloudPercent\":1,\"solarMidCloudPercent\":2,"
                + "\"solarHighCloudPercent\":3,\"antisolarLowCloudPercent\":4,"
                + "\"antisolarMidCloudPercent\":5,\"antisolarHighCloudPercent\":6,"
                + "\"farSolarLowCloudPercent\":7}";
        String twelveKeys = mapper.writeValueAsString(new DirectionalCloudData(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))
                .replaceAll(",\"farSolarCloud\\d+hPa\":null", "");
        assertThat(twelveKeys).doesNotContain("hPa");

        for (String json : new String[] {sevenKeys, twelveKeys}) {
            DirectionalCloudData read = mapper.readValue(json, DirectionalCloudData.class);
            assertThat(read.farSolarLowCloudPercent()).isEqualTo(7);
            assertThat(read.farSolarCloud850hPa()).isNull();
            assertThat(read.farSolarCloud700hPa()).isNull();
            assertThat(read.farSolarCloud600hPa()).isNull();
            assertThat(read.farSolarCloud500hPa()).isNull();
            assertThat(read.farSolarCloud400hPa()).isNull();
            assertThat(read.farSolarCloud300hPa()).isNull();
        }
    }

    @Test
    @DisplayName("the 12-argument constructor leaves the six pressure-level values null")
    void twelveArgConstructor_leavesPressureLevelsNull() {
        DirectionalCloudData data = new DirectionalCloudData(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

        assertThat(data.farSolarCloud850hPa()).isNull();
        assertThat(data.farSolarCloud700hPa()).isNull();
        assertThat(data.farSolarCloud600hPa()).isNull();
        assertThat(data.farSolarCloud500hPa()).isNull();
        assertThat(data.farSolarCloud400hPa()).isNull();
        assertThat(data.farSolarCloud300hPa()).isNull();
    }
}
