package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.SeenTag;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The {@code seen} field of {@code PUT /api/outcome/ratings}, wrapped so the request can tell three
 * things apart that a bare {@link SeenTag} cannot: the property is <em>absent</em> (the holder is
 * {@code null}: "leave the earlier tag as it is"), <em>explicitly null</em> (a choice whose {@code
 * tag} is null: "clear the tag"), or a value (a choice whose {@code tag} is set).
 *
 * @param tag the tag to store, or {@code null} to clear it
 */
@JsonDeserialize(using = SeenChoiceDeserializer.class)
public record SeenChoice(SeenTag tag) {

    /** The choice that clears the tag. */
    public static final SeenChoice CLEAR = new SeenChoice(null);
}
