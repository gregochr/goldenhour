package com.gregochr.goldenhour.service.ask;

import java.time.LocalDate;
import java.util.Locale;

/**
 * The one way Ask compares an event's type.
 *
 * <p>A type is spelt two ways in served data: the almanac writes {@code lunar-eclipse}, the hot
 * topic {@code LUNAR_ECLIPSE}. They are one type, so identity is {@link #key}: stripped,
 * upper-cased and with {@code -} read as {@code _}. Every place that asks "is this the same
 * type" (the {@code get_coming_up} timeline's dedupe, the validator's evidence match and its
 * offered-events count, the Ready freshness re-find, a Ready question's admitted types, the stub's
 * de-duplication) goes through here, so they cannot disagree.
 *
 * <p>The type an event <em>card</em> carries on the wire and in a stored answer is the {@link #key}
 * too (an almanac {@code lunar-eclipse} is served as {@code LUNAR_ECLIPSE}, which is what the client
 * keys its kicker and colour channel on). {@link #served} is the other spelling: the one the tools
 * show the model (upper-cased, otherwise as served), so the evidence it reads is unchanged. An answer
 * stored before the card type was folded still carries the dash; every comparison folds both sides,
 * and the freshness re-join folds it on the way out.
 */
final class AskEventType {

    private AskEventType() {
    }

    /**
     * The identity of a type: stripped, upper-cased, dashes read as underscores.
     *
     * @param type a served type in any case or spelling; may be null
     * @return the key, or an empty string for a null type
     */
    static String key(String type) {
        return type == null ? "" : type.strip().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * The identity of one event for "has this been offered or listed already": its type's key and
     * its date.
     *
     * @param type a served type
     * @param date the event's date; may be null
     * @return {@code KEY|date}
     */
    static String offerKey(String type, LocalDate date) {
        return key(type) + "|" + date;
    }

    /**
     * Whether two served types are one type.
     *
     * @param first  a served type
     * @param second another served type
     * @return true when neither is null and their keys are equal
     */
    static boolean same(String first, String second) {
        return first != null && second != null && key(first).equals(key(second));
    }

    /**
     * A type as an event card carries it: stripped and upper-cased, the spelling otherwise as served.
     *
     * @param type a served type; may be null
     * @return the type to show and store, or null for a null type
     */
    static String served(String type) {
        return type == null ? null : type.strip().toUpperCase(Locale.ROOT);
    }
}
