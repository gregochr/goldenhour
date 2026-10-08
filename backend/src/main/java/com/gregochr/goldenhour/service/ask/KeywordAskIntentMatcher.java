package com.gregochr.goldenhour.service.ask;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Ready intent match (plan §2.5 step 5, §4 #16): decides whether a typed question is exactly a
 * Ready question and, if so, hands back that Ready answer so it is served free as {@code kind:
 * ready}. Deterministic keyword rules ({@link ReadyIntentRules}), no embeddings.
 *
 * <p><b>Conservative on three counts.</b> The question must be made only of words the rules account
 * for (so a named place, a drive constraint, another day or a time of day the Ready answer ignores is
 * a reason to decline, not to match); it must not contain the name of any location or region in the
 * snapshot (a place whose name is made of ordinary words — a "Best Spot Cafe" — would otherwise slip
 * through the word rules); and the Ready answer must be <b>available and fresh right now</b>:
 * {@link AskReadyService#freshAnswers} is the very test a tap on the same question gets (stored for
 * the scope, every pick still eligible with the same rating and verdict, every event still live, the
 * text not gone stale, the BEST BET still the lead), judged against the live snapshot the request
 * already holds. A Ready id that is unavailable or stale is a miss, and the question goes on to the
 * cache and the engine.
 *
 * <p>The context window is deliberately <em>not</em> a reason to decline: a question that says "this
 * weekend" or "tonight" has fixed its own time, and the matcher only accepts such questions. A
 * question that names no time ("best spot?") is never matched, so the window chip still steers it.
 *
 * <p>The scope is the question's own: a single region's Ready set, otherwise the whole catalogue's
 * (plan §6 Q8), which is {@link AskScope#readyScope}.
 */
@Component
public class KeywordAskIntentMatcher implements AskIntentMatcher {

    /** More words than this is not a Ready question; also bounds the work done on a long question. */
    static final int MAX_WORDS = 14;

    private final AskReadyService readyService;

    /**
     * Creates the matcher.
     *
     * @param readyService serves the fresh Ready questions of a scope
     */
    public KeywordAskIntentMatcher(AskReadyService readyService) {
        this.readyService = readyService;
    }

    @Override
    public Optional<AskReadyResponse.Question> match(AskQuestion question, AskSnapshot snapshot) {
        if (question == null || question.normalised() == null || snapshot == null) {
            return Optional.empty();
        }
        List<String> words = PhraseAskPreFilter.words(question.normalised());
        if (words.isEmpty() || words.size() > MAX_WORDS || !ReadyIntentRules.couldMatchAny(words)
                || namesAPlace(question.normalised(), snapshot)) {
            return Optional.empty();
        }
        // Only now a read: the question is made of nothing but words a Ready question uses.
        for (AskReadyResponse.Question ready : readyService.freshAnswers(question.scope().readyScope(), snapshot)) {
            // The id is a ReadyQuestion name by construction (freshAnswers walks the catalogue).
            if (ReadyIntentRules.matches(ReadyQuestion.valueOf(ready.id()), words, ready)) {
                return Optional.of(ready);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the question contains the name of a location or a region in the snapshot, as whole
     * words. Both sides are normalised the same way, so the comparison cannot be defeated by case or
     * punctuation.
     */
    static boolean namesAPlace(String normalisedQuestion, AskSnapshot snapshot) {
        String padded = " " + normalisedQuestion + " ";
        for (String name : placeNames(snapshot)) {
            String normalised = AskQuestionSanitiser.normalise(name);
            if (!normalised.isBlank() && padded.contains(" " + normalised + " ")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> placeNames(AskSnapshot snapshot) {
        Set<String> names = new HashSet<>();
        for (AskSnapshot.Window window : snapshot.windows()) {
            for (AskSnapshot.Region region : window.regions()) {
                if (region.name() != null && !region.name().isBlank()) {
                    names.add(region.name());
                }
                for (AskSnapshot.Slot slot : region.slots()) {
                    if (slot.name() != null && !slot.name().isBlank()) {
                        names.add(slot.name());
                    }
                }
            }
        }
        return names;
    }
}
