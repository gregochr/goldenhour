package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.service.GitInfoService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes the running build's commit under {@code git.commit} on {@code /actuator/info} when Spring Boot's own
 * git contributor has nothing to say.
 *
 * <p>Boot's {@code GitInfoContributor} needs a {@link GitProperties} bean, which needs a {@code git.properties}
 * file. The production image has none (no {@code .git} in the build context), so {@link GitInfoService} reads the
 * {@code GIT_COMMIT_*} environment variables instead, and this contributor surfaces that commit so the post-deploy
 * smoke can confirm the new build is the one running.
 *
 * <p>The "only when Spring's git info is absent" rule is a runtime check of the {@link GitProperties} provider, not
 * {@code @ConditionalOnMissingBean}: conditions on a component-scanned class are evaluated while scanning, before
 * auto-configuration registers {@code GitProperties}, so they would wrongly see the bean as missing and duplicate
 * Spring's output. Only the full commit id and the commit time are published; the message and branch stay out
 * because the endpoint is public.
 */
@Component
public class GitCommitInfoContributor implements InfoContributor {

    private final GitInfoService gitInfoService;
    private final ObjectProvider<GitProperties> springGitProperties;

    /**
     * Constructs the contributor.
     *
     * @param gitInfoService      source of the commit (git.properties or the GIT_COMMIT_* environment)
     * @param springGitProperties Boot's own git properties, when a {@code git.properties} file produced them
     */
    public GitCommitInfoContributor(GitInfoService gitInfoService,
                                    ObjectProvider<GitProperties> springGitProperties) {
        this.gitInfoService = gitInfoService;
        this.springGitProperties = springGitProperties;
    }

    @Override
    public void contribute(Info.Builder builder) {
        if (springGitProperties.getIfAvailable() != null) {
            return;
        }
        String id = gitInfoService.getCommitHash();
        if (id.isBlank()) {
            return;
        }
        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("id", id);
        LocalDateTime time = gitInfoService.getCommitDate();
        if (time != null) {
            commit.put("time", time.toInstant(ZoneOffset.UTC).toString());
        }
        builder.withDetail("git", Map.of("commit", commit));
    }
}
