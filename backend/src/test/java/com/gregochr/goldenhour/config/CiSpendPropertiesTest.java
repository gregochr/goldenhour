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
    @DisplayName("defaults: off, no token, 14 days, 300 calls, the documented repository, workflow and API")
    void defaults() {
        CiSpendProperties p = new CiSpendProperties();

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.hasToken()).isFalse();
        assertThat(p.getLookbackDays()).isEqualTo(14);
        assertThat(p.getMaxCallsPerPoll()).isEqualTo(300);
        assertThat(p.getRepository()).isEqualTo("gregochr/goldenhour");
        assertThat(p.getWorkflowFile()).isEqualTo("prompt-regression.yml");
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

        p.setGithubToken("a-token");
        assertThat(p.isActive()).isTrue();

        p.setEnabled(false);
        assertThat(p.isActive()).isFalse();
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
