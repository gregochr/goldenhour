package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.model.comingup.ComingUpCoincidenceLine;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpFact;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Shapes the served almanac feed for a Lite reader at the HTTP edge.
 *
 * <p>The tide relative to the light is Pro and Admin only (docs/engineering/tide-light-pro-plan.md
 * §3 B2). {@link #forLite} removes the two things that state where high water falls against
 * sunrise or sunset: the {@link ComingUpFact.Kind#EVENT_WATER} fact rows, and the alignment clock
 * in a coincidence line's label. Every other fact, card and date stays. An entry that actually lost
 * something is marked {@code lightRowsWithheld} so the client can say so.
 *
 * <p>Pure: the input (the service's cached, role-free feed) is never mutated, and the carriers the
 * decision rests on are in-memory only. A missing carrier fails closed: a coincidence line with no
 * {@code liteFactsLabel} is served as its span alone, never as the Pro label.
 */
@Component
public class ComingUpRoleShaper {

    /** The separator the assembler puts between a coincidence label's clauses. */
    private static final String SEPARATOR = " · ";

    /**
     * Returns the feed as a Lite reader may see it.
     *
     * @param response the full, role-free feed (may be null)
     * @return a shaped copy, or null if the input was null
     */
    public ComingUpResponse forLite(ComingUpResponse response) {
        if (response == null) {
            return null;
        }
        return response.withEntries(
                response.entries().stream().map(ComingUpRoleShaper::liteEntry).toList());
    }

    private static ComingUpEntry liteEntry(ComingUpEntry entry) {
        List<ComingUpFact> facts = entry.facts().stream()
                .filter(fact -> fact.kind() != ComingUpFact.Kind.EVENT_WATER).toList();
        boolean withheld = facts.size() != entry.facts().size();

        List<ComingUpCoincidenceLine> lines = entry.coincidence().stream()
                .map(ComingUpRoleShaper::liteLine).toList();
        for (int i = 0; i < lines.size(); i++) {
            withheld |= !Objects.equals(lines.get(i).factsLabel(),
                    entry.coincidence().get(i).factsLabel());
        }
        if (!withheld) {
            return entry;
        }
        return entry.withFacts(facts).withCoincidence(lines).withLightRowsWithheld(true);
    }

    private static ComingUpCoincidenceLine liteLine(ComingUpCoincidenceLine line) {
        String label = line.liteFactsLabel() != null ? line.liteFactsLabel() : spanOnly(line.factsLabel());
        return new ComingUpCoincidenceLine(line.family(), line.name(), label, line.type(), label);
    }

    /** The first clause of a label: the span, which carries no clock. */
    private static String spanOnly(String label) {
        if (label == null) {
            return null;
        }
        int cut = label.indexOf(SEPARATOR);
        return cut < 0 ? label : label.substring(0, cut);
    }
}
