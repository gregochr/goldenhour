package com.gregochr.goldenhour.service.ask;

import java.util.Map;

/**
 * The names of the enabled locations by id, for the one thing the live snapshot cannot say: what an
 * earlier pick of a thread was called once its slot has left the forecast. A seam over the
 * repository so the thread's prompt lines can be tested without a database; read lazily, once per
 * conversation, and only when a thread carries a pick the snapshot no longer holds.
 */
@FunctionalInterface
interface AskLocationNames {

    /** Knows no names: every dropped pick reads as {@code a place no longer listed}. */
    AskLocationNames NONE = Map::of;

    /**
     * Every enabled location's name, by id.
     *
     * @return the names
     */
    Map<Long, String> byId();
}
