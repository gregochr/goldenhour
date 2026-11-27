package com.gregochr.goldenhour.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecretRedactor}.
 */
class SecretRedactorTest {

    @Test
    @DisplayName("null stays null")
    void nullInput() {
        assertThat(SecretRedactor.redactKeys(null)).isNull();
    }

    @Test
    @DisplayName("text without a key parameter is unchanged")
    void noKey() {
        assertThat(SecretRedactor.redactKeys("connection reset")).isEqualTo("connection reset");
        assertThat(SecretRedactor.redactKeys("https://x.test/a?lat=1&lon=2"))
                .isEqualTo("https://x.test/a?lat=1&lon=2");
    }

    @Test
    @DisplayName("a bare key at the start of the text is masked")
    void plainKey() {
        assertThat(SecretRedactor.redactKeys("key=abc123")).isEqualTo("key=***");
    }

    @Test
    @DisplayName("a key in the middle of a query is masked and its neighbours are kept")
    void keyMidQuery() {
        assertThat(SecretRedactor.redactKeys("https://x.test/a?lat=1&key=abc123&lon=2"))
                .isEqualTo("https://x.test/a?lat=1&key=***&lon=2");
    }

    @Test
    @DisplayName("a key before a closing quote, as in Spring's I/O error message, stops at the quote")
    void keyBeforeClosingQuote() {
        assertThat(SecretRedactor.redactKeys(
                "I/O error on GET request for \"https://x.test/api?extremes&key=SECRET\": timeout"))
                .isEqualTo("I/O error on GET request for \"https://x.test/api?extremes&key=***\": timeout");
    }

    @Test
    @DisplayName("a key at the end of the text, or before whitespace or a single quote, is masked")
    void keyAtEndOrBeforeSeparators() {
        assertThat(SecretRedactor.redactKeys("https://x.test/a?key=SECRET")).isEqualTo("https://x.test/a?key=***");
        assertThat(SecretRedactor.redactKeys("GET https://x.test/a?key=SECRET failed"))
                .isEqualTo("GET https://x.test/a?key=*** failed");
        assertThat(SecretRedactor.redactKeys("'https://x.test/a?key=SECRET'"))
                .isEqualTo("'https://x.test/a?key=***'");
    }

    @Test
    @DisplayName("apikey and api_key parameters are masked, whatever their case")
    void variantsAndCase() {
        assertThat(SecretRedactor.redactKeys("?apikey=a&api_key=b&KEY=c&ApiKey=d&API_KEY=e"))
                .isEqualTo("?apikey=***&api_key=***&KEY=***&ApiKey=***&API_KEY=***");
    }

    @Test
    @DisplayName("parameters that merely end in 'key' are untouched")
    void doesNotTouchMonkey() {
        assertThat(SecretRedactor.redactKeys("https://x.test/a?monkey=1&turkey=2"))
                .isEqualTo("https://x.test/a?monkey=1&turkey=2");
    }

    @Test
    @DisplayName("an Authorization Bearer header and a bare bearer token are masked, in any case")
    void masksBearer() {
        assertThat(SecretRedactor.redactKeys("Authorization: Bearer abc123.DEF-456_xyz"))
                .isEqualTo("Authorization: Bearer ***");
        assertThat(SecretRedactor.redactKeys("sent bearer TOKENVALUE99 to the host"))
                .isEqualTo("sent bearer *** to the host");
    }

    @Test
    @DisplayName("the word bearer in prose is left alone")
    void leavesProseBearer() {
        assertThat(SecretRedactor.redactKeys("the bearer of bad news")).isEqualTo("the bearer of bad news");
    }

    @Test
    @DisplayName("GitHub fine-grained and classic token shapes are masked wherever they appear")
    void masksGithubTokens() {
        assertThat(SecretRedactor.redactKeys("token github_pat_11ABCDEFG0abcdefghij_klmnopqrst failed"))
                .isEqualTo("token *** failed");
        assertThat(SecretRedactor.redactKeys("ghs_abcdefghijklmnopqrstuvwx and ghp_0123456789abcdefghij"))
                .isEqualTo("*** and ***");
    }

    @Test
    @DisplayName("a signed blob URL has its sig, se, skoid, sktid and sv values masked")
    void masksSignedUrlParameters() {
        String url = "I/O error on GET request for \"https://blob.test/a/b?sv=2024-01-01&se=2026-11-27T10%3A00Z"
                + "&skoid=11111111&sktid=22222222&sig=AbCd%2Fef%3D&rscd=attachment\": timed out";

        String masked = SecretRedactor.redactKeys(url);

        assertThat(masked).isEqualTo("I/O error on GET request for \"https://blob.test/a/b?sv=***&se=***"
                + "&skoid=***&sktid=***&sig=***&rscd=attachment\": timed out");
        assertThat(masked).doesNotContain("AbCd");
    }

    @Test
    @DisplayName("parameters that merely contain sig or se are untouched")
    void leavesSimilarParameters() {
        assertThat(SecretRedactor.redactKeys("https://x.test/a?design=1&user=2"))
                .isEqualTo("https://x.test/a?design=1&user=2");
    }
}
