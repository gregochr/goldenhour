package com.gregochr.goldenhour.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * The Jackson mapper for replaying stored {@code AtmosphericData} JSON in the prompt-test and
 * model-test harnesses.
 *
 * <p>Stored rows outlive the build that wrote them. A row written by a newer build can carry
 * properties an older one does not know: for example the far-point cloud fields recorded since
 * far-cloud-lid-plan P2. With Jackson's default, such a row fails to deserialise, and its replay
 * is recorded as failed. This mapper skips an unknown property instead, so a later rollback can
 * still replay rows written after it.
 *
 * <p>It is not silent. Each skipped property is logged once at WARN, naming it. A field that was
 * renamed, rather than added, then shows up in the log, instead of quietly replaying a different
 * prompt.
 */
public final class ReplayJson {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayJson.class);

    private ReplayJson() {
    }

    /**
     * Builds a mapper that reads Java time types, skips unknown properties and logs each one.
     *
     * @return a new mapper, safe to share once built
     */
    public static ObjectMapper replayMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .addHandler(new LoggingUnknownPropertyHandler());
    }

    /** Logs an unknown property at WARN and skips its value. */
    private static final class LoggingUnknownPropertyHandler extends DeserializationProblemHandler {

        @Override
        public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                JsonDeserializer<?> deserializer, Object beanOrClass, String propertyName)
                throws IOException {
            String type = beanOrClass instanceof Class<?> c
                    ? c.getSimpleName() : beanOrClass.getClass().getSimpleName();
            LOG.warn("Replay JSON: skipped unknown property '{}' on {}", propertyName, type);
            p.skipChildren();
            return true;
        }
    }
}
