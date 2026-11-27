package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.config.GitHubActionsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.InputStream;
import java.net.URI;

/**
 * Fetches a GitHub Actions artifact from the signed blob URL that {@link GitHubActionsClient#getArtifactZip}
 * returned in its {@code Location} header.
 *
 * <p>Deliberately not the GitHub client: it carries <b>no Authorization header</b> (the signed URL is its own
 * credential, and the token must never reach the blob host), never follows redirects and requires {@code https}.
 * The stream is capped at the caller's limit whatever {@code Content-Length} says. Logs name the blob host only,
 * never the URL, because the query string is a signature.
 */
@Component
public class GitHubArtifactDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubArtifactDownloader.class);

    private final RestClient restClient;
    private final boolean httpsOnly;

    /** Builds the production downloader: a bare client with redirects off, https only. */
    public GitHubArtifactDownloader() {
        this(RestClient.builder().requestFactory(GitHubActionsConfig.noRedirectRequestFactory()).build(), true);
    }

    /**
     * Builds a downloader around a given client. Tests pass {@code httpsOnly=false} to reach a local server.
     *
     * @param restClient a client with no credentials and redirects off
     * @param httpsOnly  whether a non-https location is refused
     */
    GitHubArtifactDownloader(RestClient restClient, boolean httpsOnly) {
        this.restClient = restClient;
        this.httpsOnly = httpsOnly;
    }

    /**
     * Downloads the bytes at a signed URL, reading at most {@code maxBytes}.
     *
     * @param location the {@code Location} header value
     * @param maxBytes the most bytes accepted
     * @return the body
     * @throws ArtifactDownloadException when the URL is unusable or the body is larger than {@code maxBytes}
     * @throws RestClientResponseException when the blob host answers anything but 200
     * @throws org.springframework.web.client.ResourceAccessException on an I/O failure
     */
    public byte[] download(String location, int maxBytes) throws ArtifactDownloadException {
        URI uri = parse(location);
        LOG.info("Downloading CI usage artifact from blob host {}", uri.getHost());
        byte[] body = restClient.get().uri(uri).exchange((request, response) -> {
            int status = response.getStatusCode().value();
            if (status != 200) {
                throw new RestClientResponseException("Artifact blob request failed", status, "", new HttpHeaders(),
                        null, null);
            }
            try (InputStream in = response.getBody()) {
                return in.readNBytes(maxBytes + 1);
            }
        });
        if (body.length > maxBytes) {
            throw new ArtifactDownloadException("download exceeds " + maxBytes + " bytes");
        }
        return body;
    }

    private URI parse(String location) throws ArtifactDownloadException {
        URI uri;
        try {
            uri = URI.create(location == null ? "" : location.trim());
        } catch (IllegalArgumentException e) {
            throw new ArtifactDownloadException("location is not a valid URI");
        }
        if (uri.getHost() == null) {
            throw new ArtifactDownloadException("location has no host");
        }
        if (httpsOnly && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ArtifactDownloadException("location is not https");
        }
        return uri;
    }
}
