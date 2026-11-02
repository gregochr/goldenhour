package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ServiceName;

/**
 * One distinct error message of a service's failed calls with how often it occurred, the message cut to 120
 * characters by SQL so no call row (and no response body) is loaded.
 *
 * @param service the service called
 * @param batch   true for Anthropic Message Batches rows
 * @param message the error message, at most 120 characters
 * @param count   how many calls failed with it
 */
public record ApiCallErrorGroup(ServiceName service, Boolean batch, String message, Long count) {
}
