package com.gregochr.goldenhour.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

/**
 * Translates well-known exception types to JSON error responses.
 *
 * <p>All error responses carry an {@link ErrorResponse} body with a human-readable
 * {@code error} message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * JSON body returned for all error responses.
     *
     * @param error human-readable description of the error
     */
    public record ErrorResponse(String error) {
    }

    /**
     * Maps {@link NoSuchElementException} to HTTP 404.
     *
     * @param ex the exception
     * @return an error response body
     */
    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NoSuchElementException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    /**
     * Maps {@link IllegalArgumentException} to HTTP 400.
     *
     * @param ex the exception
     * @return an error response body
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(IllegalArgumentException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    /**
     * Maps {@link MissingServletRequestParameterException} to HTTP 400.
     *
     * <p>Without this handler the catch-all below would intercept it and return 500.
     *
     * @param ex the exception
     * @return a 400 response with the missing parameter name
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleMissingParam(MissingServletRequestParameterException ex) {
        return new ErrorResponse("Missing required parameter: " + ex.getParameterName());
    }

    /**
     * Catch-all for unhandled runtime exceptions — logs the full stack trace and returns 500.
     *
     * <p>{@link AccessDeniedException} is re-thrown so Spring Security's filter chain
     * can convert it to a 403 response.
     *
     * @param ex the exception
     * @return a 500 response with a structured error body
     * @throws AccessDeniedException if the exception is a security authorisation failure
     */
    /**
     * Silently ignores client-disconnect notifications on async/SSE responses.
     *
     * <p>These fire when the browser closes the connection before the response is complete
     * (tab closed, navigation, network drop). There is no meaningful action to take and
     * no HTTP response can be sent, so logging at ERROR would be misleading noise.
     *
     * @param ex the disconnect exception
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        LOG.debug("Client disconnected during async response: {}", ex.getMessage());
    }

    /**
     * Preserves the HTTP status from {@link ResponseStatusException}.
     *
     * @param ex the exception
     * @return a response with the exception's status code and reason
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ErrorResponse(ex.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) throws AccessDeniedException {
        if (ex instanceof AccessDeniedException ade) {
            throw ade;
        }
        LOG.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal error: " + ex.getMessage()));
    }

    /**
     * Maps {@link RestClientResponseException} to HTTP 502 Bad Gateway.
     *
     * <p>Wraps upstream API failures (Open-Meteo, WorldTides, etc.) so callers receive a
     * consistent error shape instead of a raw 500. The upstream status code is included
     * in the message to aid diagnostics.
     *
     * @param ex the exception thrown by RestClient
     * @return a 502 response with a structured error body
     */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamError(RestClientResponseException ex) {
        String message = "Upstream API error: " + ex.getStatusCode().value() + " " + ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(message));
    }
}
