package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;

import java.time.LocalDate;
import java.util.List;

/**
 * Supplies entries for the 90-day "Coming up" feed over an arbitrary date range.
 *
 * <p><strong>Why this is not {@code HotTopicStrategy}.</strong> That interface has the same shape —
 * {@code detect(from, to)} — and it is a trap. Of its thirteen implementations only three actually
 * honour the range they are handed: two tide strategies read a five-day briefing cache, NLC reads a
 * clarity cache built during the briefing run, six read survivor signals the batch pipeline only
 * ever writes out to T+3, and aurora inspects {@code fromDate} and {@code fromDate + 1} and nothing
 * else. Asking that interface for ninety days returns a four-day answer wearing a ninety-day
 * signature.
 *
 * <p>So this is a separate contract with one rule its sibling cannot state: <strong>an
 * implementation must answer for the whole range it is given, or not exist.</strong> Every source
 * here derives its dates from ephemeris, which has no horizon. Where a source wants figures that do
 * have a horizon — tide heights, say — it emits the entry with its dates and omits the figures,
 * never a shortened range and never a synthesised number.
 *
 * <p>Implementations are Spring beans, collected by {@code AlmanacService}. They must be free of
 * external API calls: the feed is rebuilt daily for every user and a source that reaches the
 * network turns a cheap read into a fan-out.
 */
public interface AlmanacSource {

    /**
     * Returns every entry this source can derive that overlaps the given range.
     *
     * <p>An entry may start before {@code from} or end after {@code to} — a season or a tide run
     * that straddles the edge is reported with its true span, not clipped, because a reader needs
     * to know an eleven-week season began a fortnight ago rather than that it starts today.
     *
     * @param from first day of interest, inclusive
     * @param to   last day of interest, inclusive
     * @return entries in any order, never null; empty when nothing falls in the range
     */
    List<AlmanacEvent> events(LocalDate from, LocalDate to);
}
