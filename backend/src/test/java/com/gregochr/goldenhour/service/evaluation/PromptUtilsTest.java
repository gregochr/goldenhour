package com.gregochr.goldenhour.service.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PromptUtils}.
 */
class PromptUtilsTest {

    // ── toCardinal ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("toCardinal")
    class ToCardinalTests {

        @Test
        void north() {
            assertThat(PromptUtils.toCardinal(0)).isEqualTo("N");
        }

        @Test
        void east() {
            assertThat(PromptUtils.toCardinal(90)).isEqualTo("E");
        }

        @Test
        void south() {
            assertThat(PromptUtils.toCardinal(180)).isEqualTo("S");
        }

        @Test
        void west() {
            assertThat(PromptUtils.toCardinal(270)).isEqualTo("W");
        }

        @Test
        void wrapsAt360() {
            assertThat(PromptUtils.toCardinal(360)).isEqualTo("N");
        }

        @Test
        void intermediateNne() {
            assertThat(PromptUtils.toCardinal(22)).isEqualTo("NNE");
        }

        @Test
        void intermediateSsw() {
            assertThat(PromptUtils.toCardinal(202)).isEqualTo("SSW");
        }
    }

    // ── truncateToWords ─────────────────────────────────────────────────

    @Nested
    @DisplayName("truncateToWords")
    class TruncateTests {

        @Test
        void truncatesOverLimit() {
            assertThat(PromptUtils.truncateToWords("one two three", 2))
                    .isEqualTo("one two");
        }

        @Test
        void returnsOriginalWithinLimit() {
            assertThat(PromptUtils.truncateToWords("short", 5)).isEqualTo("short");
        }

        @Test
        void returnsNullForNull() {
            assertThat(PromptUtils.truncateToWords(null, 5)).isNull();
        }

        @Test
        void returnsBlankForBlank() {
            assertThat(PromptUtils.truncateToWords("  ", 5)).isEqualTo("  ");
        }

        @Test
        void exactlyMaxWordsReturnsOriginal() {
            assertThat(PromptUtils.truncateToWords("one two three", 3))
                    .isEqualTo("one two three");
        }
    }

    // ── stripCodeFences ─────────────────────────────────────────────────

    @Nested
    @DisplayName("stripCodeFences")
    class StripCodeFencesTests {

        @Test
        void stripsJsonFence() {
            String input = "```json\n{\"key\": \"value\"}\n```";
            assertThat(PromptUtils.stripCodeFences(input)).isEqualTo("{\"key\": \"value\"}");
        }

        @Test
        void stripsPlainFence() {
            String input = "```\n{\"key\": \"value\"}\n```";
            assertThat(PromptUtils.stripCodeFences(input)).isEqualTo("{\"key\": \"value\"}");
        }

        @Test
        void noFenceReturnsOriginalTrimmed() {
            assertThat(PromptUtils.stripCodeFences("  hello  ")).isEqualTo("hello");
        }

        @Test
        void nullReturnsNull() {
            assertThat(PromptUtils.stripCodeFences(null)).isNull();
        }
    }

    // ── extractJsonObject ────────────────────────────────────────────────

    @Nested
    @DisplayName("extractJsonObject")
    class ExtractJsonObjectTests {

        @Test
        void pureJsonReturnsUnchanged() {
            String json = "{\"picks\":[{\"rank\":1}]}";
            assertThat(PromptUtils.extractJsonObject(json)).isEqualTo(json);
        }

        @Test
        void preamblePlusJson() {
            String input = "I'll analyze the conditions.\n\n{\"picks\":[]}";
            assertThat(PromptUtils.extractJsonObject(input)).isEqualTo("{\"picks\":[]}");
        }

        @Test
        void jsonPlusPostamble() {
            String input = "{\"picks\":[]}\n\nHope this helps!";
            assertThat(PromptUtils.extractJsonObject(input)).isEqualTo("{\"picks\":[]}");
        }

        @Test
        void preambleAndPostamble() {
            String input = "Here is my analysis:\n{\"picks\":[]}\nLet me know if you need more.";
            assertThat(PromptUtils.extractJsonObject(input)).isEqualTo("{\"picks\":[]}");
        }

        @Test
        void bracesInsideStringValues() {
            String json = "{\"detail\":\"winds {strong} today\",\"rank\":1}";
            assertThat(PromptUtils.extractJsonObject("Preamble " + json + " done"))
                    .isEqualTo(json);
        }

        @Test
        void escapedQuotesInsideStringValues() {
            String json = "{\"detail\":\"she said \\\"wow\\\"\",\"rank\":1}";
            assertThat(PromptUtils.extractJsonObject(json)).isEqualTo(json);
        }

        @Test
        void noBracesReturnsOriginal() {
            String input = "No JSON here at all";
            assertThat(PromptUtils.extractJsonObject(input)).isEqualTo(input);
        }

        @Test
        void unbalancedBracesReturnsOriginal() {
            String input = "{\"truncated\": true";
            assertThat(PromptUtils.extractJsonObject(input)).isEqualTo(input);
        }

        @Test
        void nullReturnsNull() {
            assertThat(PromptUtils.extractJsonObject(null)).isNull();
        }

        @Test
        void emptyStringReturnsEmpty() {
            assertThat(PromptUtils.extractJsonObject("")).isEqualTo("");
        }
    }

    // ── balancedObjectAt ─────────────────────────────────────────────────

    @Nested
    @DisplayName("balancedObjectAt")
    class BalancedObjectAtTests {

        @Test
        void returnsBalancedObjectFromStart() {
            String input = "{\"rank\":1}, {\"rank\":2}";
            assertThat(PromptUtils.balancedObjectAt(input, 0)).isEqualTo("{\"rank\":1}");
        }

        @Test
        void returnsBalancedObjectFromMidString() {
            String input = "[{\"rank\":1},{\"rank\":2}]";
            int second = input.lastIndexOf('{');
            assertThat(PromptUtils.balancedObjectAt(input, second)).isEqualTo("{\"rank\":2}");
        }

        @Test
        void truncatedObjectReturnsNull() {
            // The distinguishing behaviour: a never-closing object yields null, not the original.
            String input = "{\"rank\":2,\"differsBy";
            assertThat(PromptUtils.balancedObjectAt(input, 0)).isNull();
        }

        @Test
        void bracesInsideStringsDoNotCount() {
            String input = "{\"detail\":\"a } brace { inside\"}";
            assertThat(PromptUtils.balancedObjectAt(input, 0)).isEqualTo(input);
        }

        @Test
        void escapedQuoteInsideStringHandled() {
            String input = "{\"detail\":\"she said \\\"hi\\\"\"}";
            assertThat(PromptUtils.balancedObjectAt(input, 0)).isEqualTo(input);
        }

        @Test
        void nestedObjectsBalanced() {
            String input = "{\"a\":{\"b\":1}}trailing";
            assertThat(PromptUtils.balancedObjectAt(input, 0)).isEqualTo("{\"a\":{\"b\":1}}");
        }

        @Test
        void indexNotAtBraceReturnsNull() {
            assertThat(PromptUtils.balancedObjectAt("x{\"a\":1}", 0)).isNull();
        }

        @Test
        void nullOrOutOfRangeReturnsNull() {
            assertThat(PromptUtils.balancedObjectAt(null, 0)).isNull();
            assertThat(PromptUtils.balancedObjectAt("{}", -1)).isNull();
            assertThat(PromptUtils.balancedObjectAt("{}", 5)).isNull();
        }
    }

    // ── insertBeforeSuffix ──────────────────────────────────────────────

    @Nested
    @DisplayName("insertBeforeSuffix")
    class InsertBeforeSuffixTests {

        @Test
        void insertsBlockBeforeSuffix() {
            String base = "Cloud data here.\nRate 1-5.";
            String result = PromptUtils.insertBeforeSuffix(base, "Rate 1-5.", "TIDE: high\n");
            assertThat(result).isEqualTo("Cloud data here.\nTIDE: high\nRate 1-5.");
        }

        @Test
        void suffixNotFoundAppendsAtEnd() {
            String base = "Cloud data here.";
            String result = PromptUtils.insertBeforeSuffix(base, "MISSING", "TIDE: high\n");
            assertThat(result).isEqualTo("Cloud data here.\nTIDE: high\nMISSING");
        }

        @Test
        void suffixAppearsMultipleTimes_usesLastOccurrence() {
            String base = "Rate 1-5. More text. Rate 1-5.";
            String result = PromptUtils.insertBeforeSuffix(base, "Rate 1-5.", "INSERT\n");
            // Should insert before the LAST occurrence
            assertThat(result).isEqualTo("Rate 1-5. More text. INSERT\nRate 1-5.");
        }
    }

    // ── median ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("median")
    class MedianTests {

        @Test
        void oddLength() {
            assertThat(PromptUtils.median(new int[]{1, 2, 3})).isEqualTo(2);
        }

        @Test
        void evenLength() {
            assertThat(PromptUtils.median(new int[]{10, 20})).isEqualTo(20);
        }

        @Test
        void emptyArray() {
            assertThat(PromptUtils.median(new int[]{})).isEqualTo(0);
        }

        @Test
        void singleElement() {
            assertThat(PromptUtils.median(new int[]{42})).isEqualTo(42);
        }
    }

    // ── sanitizeBrand ───────────────────────────────────────────────────

    @Nested
    @DisplayName("sanitizeBrand")
    class SanitizeBrandTests {

        @Test
        @DisplayName("replaces standalone Claude with PhotoCast")
        void replacesStandaloneClaude() {
            assertThat(PromptUtils.sanitizeBrand("Claude rated this excellent"))
                    .isEqualTo("PhotoCast rated this excellent");
        }

        @Test
        @DisplayName("replaces hyphenated forms, preserving the suffix")
        void replacesHyphenatedForms() {
            assertThat(PromptUtils.sanitizeBrand("All ten locations Claude-rated excellent"))
                    .isEqualTo("All ten locations PhotoCast-rated excellent");
            assertThat(PromptUtils.sanitizeBrand("all eight Claude-evaluated coastal locations"))
                    .isEqualTo("all eight PhotoCast-evaluated coastal locations");
        }

        @Test
        @DisplayName("replaces Anthropic and possessive forms")
        void replacesAnthropicAndPossessive() {
            assertThat(PromptUtils.sanitizeBrand("Anthropic's model says")).isEqualTo("PhotoCast's model says");
            assertThat(PromptUtils.sanitizeBrand("Claude's verdict")).isEqualTo("PhotoCast's verdict");
        }

        @Test
        @DisplayName("is case-insensitive")
        void caseInsensitive() {
            assertThat(PromptUtils.sanitizeBrand("CLAUDE and claude")).isEqualTo("PhotoCast and PhotoCast");
        }

        @Test
        @DisplayName("does not touch substrings inside other words")
        void leavesSubstringsAlone() {
            assertThat(PromptUtils.sanitizeBrand("claudette clauded")).isEqualTo("claudette clauded");
        }

        @Test
        @DisplayName("returns null and empty unchanged")
        void handlesNullAndEmpty() {
            assertThat(PromptUtils.sanitizeBrand(null)).isNull();
            assertThat(PromptUtils.sanitizeBrand("")).isEmpty();
        }
    }
}
