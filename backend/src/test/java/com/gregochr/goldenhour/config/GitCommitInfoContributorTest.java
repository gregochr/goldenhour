package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.service.GitInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link GitCommitInfoContributor}: the commit it adds, what it leaves out, and that Spring's own git
 * output wins when a {@link GitProperties} bean exists.
 */
class GitCommitInfoContributorTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private GitInfoService gitInfo;

    @BeforeEach
    void setUp() {
        gitInfo = mock(GitInfoService.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GitProperties> springGit(GitProperties value) {
        ObjectProvider<GitProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static Map<String, Object> contribute(GitCommitInfoContributor contributor) {
        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);
        return builder.build().getDetails();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> commitOf(Map<String, Object> details) {
        return (Map<String, Object>) ((Map<String, Object>) details.get("git")).get("commit");
    }

    @Test
    @DisplayName("adds git.commit.id (full) and git.commit.time when both are known")
    void addsIdAndTime() {
        when(gitInfo.getCommitHash()).thenReturn(SHA);
        when(gitInfo.getCommitDate()).thenReturn(LocalDateTime.of(2026, 11, 8, 9, 30, 15));

        Map<String, Object> details = contribute(new GitCommitInfoContributor(gitInfo, springGit(null)));

        assertThat(commitOf(details)).containsEntry("id", SHA).containsEntry("time", "2026-11-08T09:30:15Z")
                .hasSize(2);
    }

    @Test
    @DisplayName("adds only the id when the commit time is unknown")
    void addsIdWithoutTime() {
        when(gitInfo.getCommitHash()).thenReturn(SHA);
        when(gitInfo.getCommitDate()).thenReturn(null);

        Map<String, Object> details = contribute(new GitCommitInfoContributor(gitInfo, springGit(null)));

        assertThat(commitOf(details)).containsOnlyKeys("id");
    }

    @Test
    @DisplayName("adds nothing when there is no commit hash (the probe stays warn-only)")
    void emptyHash_addsNothing() {
        when(gitInfo.getCommitHash()).thenReturn("");

        assertThat(contribute(new GitCommitInfoContributor(gitInfo, springGit(null)))).isEmpty();
    }

    @Test
    @DisplayName("never publishes the commit message or the branch")
    void omitsMessageAndBranch() {
        when(gitInfo.getCommitHash()).thenReturn(SHA);
        when(gitInfo.getCommitMessage()).thenReturn("secret subject");
        when(gitInfo.getBranch()).thenReturn("feature/secret");

        Map<String, Object> details = contribute(new GitCommitInfoContributor(gitInfo, springGit(null)));

        assertThat(details.toString()).doesNotContain("secret").doesNotContain("message").doesNotContain("branch");
    }

    @Test
    @DisplayName("adds nothing when Spring's own GitProperties exists")
    void springGitPresent_addsNothing() {
        when(gitInfo.getCommitHash()).thenReturn(SHA);
        GitProperties spring = new GitProperties(new Properties());

        assertThat(contribute(new GitCommitInfoContributor(gitInfo, springGit(spring)))).isEmpty();
    }

    @Test
    @DisplayName("env-var fallback end to end: GIT_COMMIT_SHA and GIT_COMMIT_TIME become git.commit")
    void environmentFallback_endToEnd() throws java.io.IOException {
        Map<String, String> env = Map.of(
                "GIT_COMMIT_SHA", SHA,
                "GIT_COMMIT_TIME", "2026-11-08T09:30:15+00:00",
                "GIT_COMMIT_MESSAGE", "feat: secret");
        GitInfoService real = new GitInfoService(env::get);
        real.load(new java.io.ByteArrayInputStream(new byte[0]));

        Map<String, Object> details = contribute(new GitCommitInfoContributor(real, springGit(null)));

        assertThat(details).containsOnlyKeys("git");
        assertThat(commitOf(details)).containsEntry("id", SHA).containsEntry("time", "2026-11-08T09:30:15Z");
    }

    @Test
    @DisplayName("in a real context the GitProperties bean decides: absent contributes, present stays silent")
    void realContext_condition() throws java.io.IOException {
        GitInfoService service = new GitInfoService(name -> "GIT_COMMIT_SHA".equals(name) ? SHA : null);
        service.load(new java.io.ByteArrayInputStream(new byte[0]));

        try (AnnotationConfigApplicationContext without = new AnnotationConfigApplicationContext()) {
            without.registerBean(GitInfoService.class, () -> service);
            without.register(GitCommitInfoContributor.class);
            without.refresh();
            assertThat(contribute(without.getBean(GitCommitInfoContributor.class))).containsKey("git");
        }
        try (AnnotationConfigApplicationContext with = new AnnotationConfigApplicationContext()) {
            with.registerBean(GitInfoService.class, () -> service);
            with.registerBean(GitProperties.class, () -> new GitProperties(new Properties()));
            with.register(GitCommitInfoContributor.class);
            with.refresh();
            assertThat(contribute(with.getBean(GitCommitInfoContributor.class))).isEmpty();
        }
    }
}
