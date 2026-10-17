package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.service.ask.AskAnswerParser.Parsed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AskAnswerParser}: shape errors are errors, never exceptions. */
class AskAnswerParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Parsed parse(String json) throws Exception {
        return AskAnswerParser.parse(mapper.readTree(json));
    }

    @Test
    @DisplayName("a full answer is read: picks, events, the missing phrase and the date")
    void fullAnswer() throws Exception {
        Parsed p = parse("""
                {"answerable": true, "summary": "Go to Bamburgh.",
                 "picks": [{"locationId": 12, "windowId": "2026-10-05_sunset", "why": "Clear."}],
                 "events": [{"type": "AURORA", "date": "2026-10-06", "why": "Kp 6."}],
                 "missing": null}""");

        assertThat(p.ok()).isTrue();
        assertThat(p.raw().answerable()).isTrue();
        assertThat(p.raw().summary()).isEqualTo("Go to Bamburgh.");
        assertThat(p.raw().picks()).singleElement().satisfies(pick -> {
            assertThat(pick.locationId()).isEqualTo(12L);
            assertThat(pick.windowId()).isEqualTo("2026-10-05_sunset");
            assertThat(pick.why()).isEqualTo("Clear.");
        });
        assertThat(p.raw().events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("AURORA");
            assertThat(event.date()).isEqualTo(LocalDate.of(2026, 10, 6));
        });
        assertThat(p.raw().missing()).isNull();
    }

    @Test
    @DisplayName("only answerable and summary are required; picks, events and missing may be absent or null")
    void minimalAnswer() throws Exception {
        Parsed p = parse("{\"answerable\": false, \"summary\": \"Can't tell.\", \"picks\": null}");

        assertThat(p.ok()).isTrue();
        assertThat(p.raw().picks()).isEmpty();
        assertThat(p.raw().events()).isEmpty();
    }

    @Test
    @DisplayName("extra fields are ignored: nothing reads them, and the model has no field for a "
            + "safety note or a 'try' suggestion")
    void extraFieldsAreIgnored() throws Exception {
        Parsed p = parse("""
                {"answerable": true, "summary": "x", "try": ["Best tonight?"], "safetyNote": "none",
                 "picks": [{"locationId": 1, "windowId": "w", "why": "y", "rank": 9, "extra": true}]}""");

        assertThat(p.ok()).isTrue();
        assertThat(p.raw().picks()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "null",
            "[]",
            "\"submit\"",
            "42",
            "{}",
            "{\"summary\": \"x\"}",
            "{\"answerable\": true}",
            "{\"answerable\": \"true\", \"summary\": \"x\"}",
            "{\"answerable\": 1, \"summary\": \"x\"}",
            "{\"answerable\": true, \"summary\": 5}",
            "{\"answerable\": true, \"summary\": null}",
            "{\"answerable\": true, \"summary\": \"x\", \"missing\": 3}",
            "{\"answerable\": true, \"summary\": \"x\", \"picks\": \"none\"}",
            "{\"answerable\": true, \"summary\": \"x\", \"picks\": [\"a\"]}",
            "{\"answerable\": true, \"summary\": \"x\", \"picks\": [{\"windowId\": \"w\"}]}",
            "{\"answerable\": true, \"summary\": \"x\", \"picks\": [{\"locationId\": \"12\", \"windowId\": \"w\"}]}",
            "{\"answerable\": true, \"summary\": \"x\", \"picks\": [{\"locationId\": 12.5, \"windowId\": \"w\"}]}",
            "{\"answerable\": true, \"summary\": \"x\", \"picks\": [{\"locationId\": 12.0, \"windowId\": \"w\"}]}",
            "{\"answerable\": true, \"summary\": \"x\", "
                    + "\"picks\": [{\"locationId\": 99999999999999999999, \"windowId\": \"w\"}]}",
            "{\"answerable\": true, \"summary\": \"x\", \"picks\": [{\"locationId\": 1}]}",
            "{\"answerable\": true, \"summary\": \"x\", \"picks\": [{\"locationId\": 1, \"windowId\": 7}]}",
            "{\"answerable\": true, \"summary\": \"x\", "
                    + "\"picks\": [{\"locationId\": 1, \"windowId\": \"w\", \"why\": 4}]}",
            "{\"answerable\": true, \"summary\": \"x\", \"events\": {\"type\": \"AURORA\"}}",
            "{\"answerable\": true, \"summary\": \"x\", \"events\": [\"AURORA\"]}",
            "{\"answerable\": true, \"summary\": \"x\", \"events\": [{\"why\": \"y\"}]}",
            "{\"answerable\": true, \"summary\": \"x\", \"events\": [{\"type\": \"A\", \"date\": \"6 Oct\"}]}",
            "{\"answerable\": true, \"summary\": \"x\", \"events\": [{\"type\": \"A\", \"date\": 20261006}]}"
    })
    @DisplayName("every malformed shape is an error result, never an exception")
    void malformedInputIsAnError(String json) throws Exception {
        Parsed p = parse(json);

        assertThat(p.ok()).isFalse();
        assertThat(p.raw()).isNull();
        assertThat(p.error()).isNotBlank();
    }

    @Test
    @DisplayName("a null input (no JSON at all) is an error")
    void nullInput() {
        Parsed p = AskAnswerParser.parse((JsonNode) null);

        assertThat(p.ok()).isFalse();
        assertThat(p.error()).contains("not a JSON object");
    }

    @Test
    @DisplayName("a blank date is no date: the validator then takes the served one")
    void blankDateIsAbsent() throws Exception {
        Parsed p = parse("{\"answerable\": true, \"summary\": \"x\", "
                + "\"events\": [{\"type\": \"AURORA\", \"date\": \"  \"}]}");

        assertThat(p.ok()).isTrue();
        assertThat(p.raw().events().getFirst().date()).isNull();
    }

    @Test
    @DisplayName("at most ten picks and ten events are read; the rest are never looked at "
            + "(so a malformed eleventh item cannot fail an otherwise good answer)")
    void itemCapIsTen() throws Exception {
        StringBuilder picks = new StringBuilder();
        for (int i = 1; i <= AskAnswerParser.MAX_ITEMS; i++) {
            picks.append("{\"locationId\": ").append(i).append(", \"windowId\": \"w\"},");
        }
        picks.append("\"not an object\"");

        Parsed p = parse("{\"answerable\": true, \"summary\": \"x\", \"picks\": [" + picks + "]}");

        assertThat(p.ok()).isTrue();
        assertThat(p.raw().picks()).hasSize(AskAnswerParser.MAX_ITEMS);
    }

    @Test
    @DisplayName("the largest whole number that fits a long is read; one more is malformed")
    void locationIdBoundary() throws Exception {
        Parsed atLimit = parse("{\"answerable\": true, \"summary\": \"x\", \"picks\": "
                + "[{\"locationId\": 9223372036854775807, \"windowId\": \"w\"}]}");
        Parsed over = parse("{\"answerable\": true, \"summary\": \"x\", \"picks\": "
                + "[{\"locationId\": 9223372036854775808, \"windowId\": \"w\"}]}");

        assertThat(atLimit.ok()).isTrue();
        assertThat(atLimit.raw().picks().getFirst().locationId()).isEqualTo(Long.MAX_VALUE);
        assertThat(over.ok()).isFalse();
    }
}
