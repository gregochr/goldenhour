package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins D5: the reference point defaults to Durham and no configuration value can stop startup.
 */
class SolarReferencePointTest {

    @Test
    @DisplayName("nothing configured -> Durham")
    void unset_isDurham() {
        SolarReferencePoint point = new SolarReferencePoint(new MockEnvironment());

        assertThat(point.lat()).isEqualTo(54.776);
        assertThat(point.lon()).isEqualTo(-1.575);
    }

    @Test
    @DisplayName("both keys set -> read through the Spring environment, whitespace tolerated")
    void bothSet_isUsed() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("photocast.scheduler.solar-reference.lat", " 60.15 ")
                .withProperty("photocast.scheduler.solar-reference.lon", "-1.15");

        SolarReferencePoint point = new SolarReferencePoint(env);

        assertThat(point.lat()).isEqualTo(60.15);
        assertThat(point.lon()).isEqualTo(-1.15);
    }

    @Test
    @DisplayName("an unparseable, non-finite or out-of-range value falls back to Durham for BOTH "
            + "coordinates, without throwing")
    void invalidValues_fallBackToDurham() {
        String[][] cases = {
            {"north", "-1.5"}, {"54.0", "west"}, {"NaN", "-1.5"}, {"54.0", "Infinity"},
            {"91", "0"}, {"-90.5", "0"}, {"54", "180.5"}, {"54", "-181"}, {"", "-1.5"},
            {"54.0", ""}
        };
        for (String[] c : cases) {
            SolarReferencePoint point = new SolarReferencePoint(c[0], c[1]);

            assertThat(point.lat()).as("lat for " + c[0] + "," + c[1]).isEqualTo(54.776);
            assertThat(point.lon()).as("lon for " + c[0] + "," + c[1]).isEqualTo(-1.575);
        }
    }

    @Test
    @DisplayName("Spring can construct the bean (two constructors exist, so the Environment one "
            + "must be the one it picks) and reads the properties from its environment")
    void springWiring_picksTheEnvironmentConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("photocast.scheduler.solar-reference.lat", "60.15",
                            "photocast.scheduler.solar-reference.lon", "-1.15")));
            context.register(SolarReferencePoint.class);
            context.refresh();

            SolarReferencePoint point = context.getBean(SolarReferencePoint.class);

            assertThat(point.lat()).isEqualTo(60.15);
            assertThat(point.lon()).isEqualTo(-1.15);
        }
    }

    @Test
    @DisplayName("only one of the two set -> Durham for both (a half-configured point names nowhere)")
    void onlyOneSet_isDurham() {
        assertThat(new SolarReferencePoint("60.15", null).lat()).isEqualTo(54.776);
        assertThat(new SolarReferencePoint(null, "-1.15").lon()).isEqualTo(-1.575);
    }

    @Test
    @DisplayName("the range limits themselves are accepted")
    void limits_areAccepted() {
        SolarReferencePoint point = new SolarReferencePoint("-90", "180");

        assertThat(point.lat()).isEqualTo(-90.0);
        assertThat(point.lon()).isEqualTo(180.0);
    }
}
