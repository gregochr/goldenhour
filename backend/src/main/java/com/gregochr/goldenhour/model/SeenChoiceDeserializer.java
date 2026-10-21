package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.SeenTag;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads {@link SeenChoice}: a JSON tag name becomes a choice holding it, and a JSON {@code null}
 * becomes {@link SeenChoice#CLEAR} (Jackson asks {@link #getNullValue} for it) - which is how an
 * explicit {@code "seen": null} stays distinguishable from a property that was never sent, where the
 * record component is simply {@code null} ({@link #getAbsentValue} says so explicitly, because the
 * default would read an absent property as the null value). An unknown tag name fails the enum read
 * and answers 400.
 */
public class SeenChoiceDeserializer extends ValueDeserializer<SeenChoice> {

    @Override
    public SeenChoice deserialize(JsonParser parser, DeserializationContext context) {
        return new SeenChoice(context.readValue(parser, SeenTag.class));
    }

    @Override
    public SeenChoice getNullValue(DeserializationContext context) {
        return SeenChoice.CLEAR;
    }

    /**
     * What a creator property that never appeared reads as. Jackson's default is the null value, which
     * would turn "not sent" into "clear" - the one distinction this type exists to keep - so absent is
     * a plain {@code null}.
     */
    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return null;
    }
}
