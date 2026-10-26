package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BigSeasDay;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.LocationTideFact;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Shapes the served briefing for the reader's role at the HTTP edge.
 *
 * <p>The tide curve and the per-location tide match are Pro features
 * (docs/engineering/tide-pro-gate-plan.md §7), as are the Fiery Sky and Golden Hour scores and the
 * per-location written why (docs/engineering/pro-features-gate-plan.md §6). {@link #forLite}
 * removes the tide data from the window tide block, the window's tide facts and the slot flags, and
 * the scores and the written why from every slot; every free fact stays (the star, the verdict, the
 * region prose). The tide at the light is Pro as well (docs/engineering/tide-light-pro-plan.md):
 * {@link #forLite} also removes how far the nearest extreme sits from the light, the slot's and the
 * per-location "tide on the light" facts and the tide-mismatch stand-down reason, the spring and
 * king window badges' light-bearing detail and clock, and (via {@link HotTopic#forLite()}) the
 * spring and king hot topics' alignment sentence, run row, resolved light and aligned counts, and
 * the big-seas gap and aligned flags. Every topic, size fact and clock stays (the two-question
 * rule). The best-bet picks are Pro too
 * (docs/engineering/pro-features-gate-plan.md §8): {@link #forLite} also removes each window's
 * pick, empties {@code bestBets} and nulls the best-bet model, status and withdrawn flag.
 *
 * <p>Rebuilds only through the records' own withers and canonical constructors, so a component
 * added later is never defaulted away silently. Never used on {@code getCachedBriefingForApi}'s
 * internal callers (the digest, Ask), which keep the full data. The input is never mutated.
 */
@Component
public class BriefingRoleShaper {

    /** The slot flags that state the per-location match; Pro only. */
    static final Set<String> MATCH_FLAGS = Set.of("Tide aligned", "Tide not aligned");

    /**
     * Returns the briefing as a LITE reader may see it.
     *
     * @param response the full briefing (may be null)
     * @return a stripped copy, or null if the input was null
     */
    public DailyBriefingResponse forLite(DailyBriefingResponse response) {
        if (response == null) {
            return null;
        }
        // Each spring/king window badge is served the SAME light-free sentence as its topic, so a
        // badge and the topic list in one response never disagree. A topic with no liteDetail
        // (a reloaded or simulated briefing) leaves its badge with no detail: fail closed.
        List<HotTopic> liteTopics = response.hotTopics().stream().map(HotTopic::forLite).toList();
        List<BriefingDay> days = response.days().stream()
                .map(day -> liteDay(day, liteTopics)).toList();
        // The best-bet picks are Pro (pro-features-gate-plan.md §8). bestBets stays present and
        // empty; bestBetsWithdrawn is null so LITE is never told a pick was withdrawn.
        List<BigSeasDay> bigSeas = response.bigSeas() == null ? null
                : response.bigSeas().stream().map(BigSeasDay::forLite).toList();
        return response.withDays(days)
                .withLiveOverlays(response.auroraTonight(), response.auroraTomorrow(), liteTopics)
                .withBigSeas(bigSeas)
                .withBestBets(List.of(), null)
                .withBestBetModel(null)
                .withBestBetStatus(null);
    }

    private static BriefingDay liteDay(BriefingDay day, List<HotTopic> liteTopics) {
        return day.withEventSummaries(day.eventSummaries().stream()
                .map(es -> liteEvent(es, day.date(), liteTopics)).toList());
    }

    private static BriefingEventSummary liteEvent(BriefingEventSummary es, LocalDate date,
            List<HotTopic> liteTopics) {
        List<BriefingRegion> regions = es.regions().stream()
                .map(region -> region.withSlots(liteSlots(region.slots())))
                .toList();
        BriefingEventSummary shaped = es.withRegions(regions)
                .withUnregioned(liteSlots(es.unregioned()));
        return shaped.window() == null ? shaped : shaped.withWindow(liteWindow(shaped.window(), date, liteTopics));
    }

    private static List<BriefingSlot> liteSlots(List<BriefingSlot> slots) {
        return slots == null ? null : slots.stream().map(BriefingRoleShaper::liteSlot).toList();
    }

    private static BriefingSlot liteSlot(BriefingSlot slot) {
        // The scores and the written why go from EVERY slot, whether or not it carries a tide flag.
        BriefingSlot stripped = slot.forLite();
        if (stripped.flags().stream().noneMatch(MATCH_FLAGS::contains)) {
            return stripped;
        }
        return stripped.withFlags(stripped.flags().stream().filter(f -> !MATCH_FLAGS.contains(f)).toList());
    }

    private static BriefingWindow liteWindow(BriefingWindow window, LocalDate date,
            List<HotTopic> liteTopics) {
        BriefingWindow shaped = window.withPick(null)
                .withBadges(window.badges().stream().map(b -> liteBadge(b, date, liteTopics)).toList());
        if (window.tide() != null) {
            shaped = shaped.withTide(window.tide().forLite());
        }
        if (window.tideFacts() != null) {
            shaped = shaped.withTideFacts(
                    window.tideFacts().stream().map(LocationTideFact::forLite).toList());
        }
        return shaped;
    }

    private static boolean isTideType(String type) {
        return "SPRING_TIDE".equals(type) || "KING_TIDE".equals(type);
    }

    /**
     * A spring or king badge's detail is the light-free sentence of its own topic (same type, same
     * date), and its clock, which names the aligned window, goes. Any other badge is unchanged.
     */
    private static BriefingWindow.Badge liteBadge(BriefingWindow.Badge badge, LocalDate date,
            List<HotTopic> liteTopics) {
        if (!isTideType(badge.type())) {
            return badge;
        }
        String detail = liteTopics.stream()
                .filter(t -> Objects.equals(t.type(), badge.type()) && Objects.equals(t.date(), date))
                .map(HotTopic::detail)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new BriefingWindow.Badge(badge.type(), badge.label(), detail, badge.facts(), null,
                badge.rarityRank(), badge.note(), badge.rarityNote(), badge.safetyNote());
    }
}
