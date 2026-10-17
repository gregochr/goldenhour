package com.gregochr.goldenhour.service.evaluation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * The Anthropic message ids of the current cycle's cache-primer responses, keyed by the cache prefix
 * each primed ({@link BatchRequestFactory#cachePrefixKey}).
 *
 * <p>Exists for one reason: the API's cache diagnostics compare a request with a <em>previous</em>
 * message, and the only previous message a scheduled batch request has is the primer that warmed
 * its prefix. {@code BatchCachePrimer} publishes the ids it read back from its primers' results, and
 * {@link BatchRequestFactory} names the matching one as {@code diagnostics.previous_message_id} on a
 * warmed request, so the response says where that request diverged from the primer (system, tools or
 * messages) - the explanation the 2026-10-04 "a standard call's cache is not reliably picked up by a
 * batch" measurement lacked. Held in memory only and replaced wholesale each cycle: a restart, a
 * failed primer or a cycle without one leaves it empty, and an empty registry means no request
 * carries a previous message id.
 */
@Component
public class PrimerMessageIds {

    private volatile Map<String, String> byPrefix = Map.of();

    /**
     * Replaces the registry with the ids of a cycle's primers (an empty map clears it).
     *
     * @param idsByPrefix primer message id by cache prefix key
     */
    public void replace(Map<String, String> idsByPrefix) {
        this.byPrefix = Map.copyOf(idsByPrefix);
    }

    /**
     * Returns the primer message id for a cache prefix.
     *
     * @param prefixKey the cache prefix key
     * @return the id of the response that primed it this cycle, if one was read
     */
    public Optional<String> forPrefix(String prefixKey) {
        return Optional.ofNullable(byPrefix.get(prefixKey));
    }
}
