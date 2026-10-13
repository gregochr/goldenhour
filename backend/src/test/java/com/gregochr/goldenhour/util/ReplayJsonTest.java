package com.gregochr.goldenhour.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replay mapper skips a property it does not know, logs each skipped occurrence at WARN, and
 * still reads every property it does know.
 */
class ReplayJsonTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(ReplayJson.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("an unknown property is skipped and the known ones are still read")
    void unknownPropertySkipped_knownPropertiesRead() throws Exception {
        String json = "{\"solarLowCloudPercent\":10,\"solarMidCloudPercent\":20,"
                + "\"solarHighCloudPercent\":30,\"antisolarLowCloudPercent\":40,"
                + "\"antisolarMidCloudPercent\":50,\"antisolarHighCloudPercent\":60,"
                + "\"farSolarLowCloudPercent\":70,\"farSolarMidCloudPercent\":80,"
                + "\"aFieldFromANewerBuild\":{\"nested\":[1,2,3]}}";

        DirectionalCloudData dc = ReplayJson.replayMapper().readValue(json, DirectionalCloudData.class);

        assertThat(dc.solarLowCloudPercent()).isEqualTo(10);
        assertThat(dc.antisolarHighCloudPercent()).isEqualTo(60);
        assertThat(dc.farSolarLowCloudPercent()).isEqualTo(70);
        assertThat(dc.farSolarMidCloudPercent()).isEqualTo(80);
        assertThat(dc.farSolarHighCloudPercent()).isNull();
        assertThat(appender.list)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("aFieldFromANewerBuild");
                });
    }

    @Test
    @DisplayName("each skipped property is logged at WARN by name")
    void skippedPropertyIsLogged() throws Exception {
        ReplayJson.replayMapper().readValue(
                "{\"solarLowCloudPercent\":1,\"renamedField\":5}", DirectionalCloudData.class);

        assertThat(appender.list)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage())
                            .contains("renamedField")
                            .contains("DirectionalCloudData");
                });
    }

    @Test
    @DisplayName("JSON with no unknown property logs nothing")
    void cleanJsonLogsNothing() throws Exception {
        ReplayJson.replayMapper().readValue("{\"solarLowCloudPercent\":1}", DirectionalCloudData.class);

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("Java time types still deserialise")
    void readsJavaTime() throws Exception {
        ObjectMapper mapper = ReplayJson.replayMapper();

        LocalDateTime t = mapper.readValue("\"2026-10-13T17:14:00\"", LocalDateTime.class);

        assertThat(t).isEqualTo(LocalDateTime.of(2026, 10, 13, 17, 14));
    }
}
