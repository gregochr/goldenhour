package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AppBuild}. */
class AppBuildTest {

    @Test
    @DisplayName("a release tag is reported as given")
    void releaseTagIsReportedAsGiven() {
        assertThat(new AppBuild("v2.28.0").value()).isEqualTo("v2.28.0");
    }

    @Test
    @DisplayName("a blank or missing version reads dev, so a local run still stamps something")
    void blankReadsDev() {
        assertThat(new AppBuild("").value()).isEqualTo(AppBuild.UNSET);
        assertThat(new AppBuild("   ").value()).isEqualTo(AppBuild.UNSET);
        assertThat(new AppBuild(null).value()).isEqualTo("dev");
    }

    @Test
    @DisplayName("surrounding whitespace is stripped")
    void whitespaceIsStripped() {
        assertThat(new AppBuild("  v2.28.0\n").value()).isEqualTo("v2.28.0");
    }

    @Test
    @DisplayName("a value longer than the column is cut to its width, so provenance can never fail a write")
    void longValueIsCutToTheColumnWidth() {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef";

        assertThat(new AppBuild(sha).value()).hasSize(AppBuild.MAX_LENGTH)
                .isEqualTo(sha.substring(0, AppBuild.MAX_LENGTH));
        assertThat(new AppBuild("x".repeat(AppBuild.MAX_LENGTH)).value()).hasSize(AppBuild.MAX_LENGTH);
    }
}
