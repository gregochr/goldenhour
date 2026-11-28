package com.gregochr.goldenhour.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CiSpendProperties}: the active rule, the secret-free {@code toString}, the repository split
 * and the validation ranges.
 */
class CiSpendPropertiesTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("defaults: off, no token, 14 days, 300 calls, the documented repository and API")
    void defaults() {
        CiSpendProperties p = new CiSpendProperties();

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.hasToken()).isFalse();
        assertThat(p.getLookbackDays()).isEqualTo(14);
        assertThat(p.getMaxCallsPerPoll()).isEqualTo(300);
        assertThat(p.getRepository()).isEqualTo("gregochr/goldenhour");
        assertThat(p.getApiBaseUrl()).isEqualTo("https://api.github.com");
        assertThat(VALIDATOR.validate(p)).isEmpty();
    }

    @Test
    @DisplayName("isActive needs both the switch and a non-blank token")
    void isActive_needsSwitchAndToken() {
        CiSpendProperties p = new CiSpendProperties();
        assertThat(p.isActive()).isFalse();

        p.setEnabled(true);
        assertThat(p.isActive()).isFalse();

        p.setGithubToken("   ");
        assertThat(p.isActive()).isFalse();

        p.setGithubToken(null);
        assertThat(p.hasToken()).isFalse();

        p.setGithubToken("a_token");
        assertThat(p.isActive()).isTrue();

        p.setEnabled(false);
        assertThat(p.isActive()).isFalse();
    }

    @Test
    @DisplayName("the token is trimmed on bind, and only letters, digits and underscores count as a token")
    void tokenShape() {
        CiSpendProperties p = new CiSpendProperties();
        p.setEnabled(true);

        p.setGithubToken("  github_pat_ABC123  ");
        assertThat(p.getGithubToken()).isEqualTo("github_pat_ABC123");
        assertThat(p.isActive()).isTrue();

        for (String bad : new String[] {"abc\ndef", "abc def", "abc\r\nX-Evil: 1", "tok-en", "tok.en", "to\tken"}) {
            p.setGithubToken(bad);
            assertThat(p.hasToken()).as("bad token").isFalse();
            assertThat(p.isActive()).isFalse();
            assertThat(p.toString()).doesNotContain("Evil").contains("tokenConfigured=false");
        }
    }

    @Test
    @DisplayName("repository and API URL reject dot segments, non-https and userinfo")
    void shapeValidation() {
        for (String bad : new String[] {"./x", "../x", "x/.", "x/..", "a/b/c", "/x", "x/", "a b/c"}) {
            CiSpendProperties p = new CiSpendProperties();
            p.setRepository(bad);
            assertThat(VALIDATOR.validate(p)).as(bad).extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("repository");
        }
        String[] badUrls = {"http://api.github.com", "https://", "https://u:p@api.github.com", "https://a@b.test",
                "ftp://x"};
        for (String bad : badUrls) {
            CiSpendProperties p = new CiSpendProperties();
            p.setApiBaseUrl(bad);
            assertThat(VALIDATOR.validate(p)).as(bad).extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("apiBaseUrl");
        }
        CiSpendProperties ok = new CiSpendProperties();
        ok.setRepository("my-org/my.repo_1");
        ok.setApiBaseUrl("https://github.example.com/api/v3");
        assertThat(VALIDATOR.validate(ok)).isEmpty();
        ok.setRepository(".github/.x");
        assertThat(VALIDATOR.validate(ok)).isEmpty();
    }

    @Test
    @DisplayName("toString never contains the token")
    void toString_omitsToken() {
        CiSpendProperties p = new CiSpendProperties();
        p.setEnabled(true);
        p.setGithubToken("github_pat_SUPERSECRETVALUE");

        assertThat(p.toString()).doesNotContain("SUPERSECRET").contains("tokenConfigured=true")
                .contains("gregochr/goldenhour");
    }

    @Test
    @DisplayName("owner and repoName split the repository at the slash")
    void ownerAndName() {
        CiSpendProperties p = new CiSpendProperties();
        p.setRepository("acme/widgets");

        assertThat(p.owner()).isEqualTo("acme");
        assertThat(p.repoName()).isEqualTo("widgets");
    }

    @Test
    @DisplayName("validation rejects a repository that is not owner/name and out-of-range numbers")
    void validation() {
        CiSpendProperties p = new CiSpendProperties();
        p.setRepository("not-a-repo");
        p.setLookbackDays(91);
        p.setMaxCallsPerPoll(0);

        assertThat(VALIDATOR.validate(p)).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("repository", "lookbackDays", "maxCallsPerPoll");

        p.setRepository("owner/name");
        p.setLookbackDays(0);
        p.setMaxCallsPerPoll(1);
        assertThat(VALIDATOR.validate(p)).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("lookbackDays");
    }
}
