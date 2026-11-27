package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.client.GitHubActionsClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;

/**
 * Builds the {@link GitHubActionsClient} for {@code ci_spend_poll} (docs/engineering/ci-spend-plan.md, C2).
 *
 * <p>The client sits on its <b>own request factory that never follows redirects</b>. {@code AppConfig}'s shared
 * factory is a {@code SimpleClientHttpRequestFactory}, which follows GET redirects and would carry the bearer token
 * to the signed blob host the artifact-zip call redirects to. Timeouts match the app's other outbound clients.
 */
@Configuration
public class GitHubActionsConfig {

    /** The GitHub REST API version header value. */
    static final String API_VERSION = "2022-11-28";

    /**
     * A JDK request factory with {@link HttpClient.Redirect#NEVER} and the app's connect and read timeouts.
     *
     * @return the factory
     */
    public static JdkClientHttpRequestFactory noRedirectRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(AppConfig.CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(AppConfig.READ_TIMEOUT);
        return factory;
    }

    /**
     * Builds the typed client: base URL, {@code Authorization: Bearer}, {@code Accept} and
     * {@code X-GitHub-Api-Version} on every request, redirects never followed. The token is read once, here.
     *
     * @param baseUrl the GitHub API root (a trailing slash is dropped)
     * @param token   the GitHub token, sent only as the Bearer credential
     * @return a typed proxy implementing {@link GitHubActionsClient}
     */
    public static GitHubActionsClient create(String baseUrl, String token) {
        RestClient client = RestClient.builder()
                .baseUrl(baseUrl.trim().replaceAll("/+$", ""))
                .requestFactory(noRedirectRequestFactory())
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", API_VERSION)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                .build().createClient(GitHubActionsClient.class);
    }

    /**
     * The Spring bean. With no token set the client is still built (the job is gated off and never calls it), but
     * carries a placeholder credential that no request will use.
     *
     * @param properties the CI-spend configuration
     * @return the GitHub Actions client
     */
    @Bean
    GitHubActionsClient gitHubActionsClient(CiSpendProperties properties) {
        return create(properties.getApiBaseUrl(), properties.hasToken() ? properties.getGithubToken() : "unset");
    }
}
