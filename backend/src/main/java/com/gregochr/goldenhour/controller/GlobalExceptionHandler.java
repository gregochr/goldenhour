package com.gregochr.goldenhour.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.NoSuchElementException;

/**
 * Translates well-known exception types to JSON error responses.
 *
 * <p>All error responses carry an {@link ErrorResponse} body with a human-readable
 * {@code error} message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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
     * Maps {@link WebClientResponseException} to HTTP 502 Bad Gateway.
     *
     * <p>Wraps upstream API failures (Open-Meteo, NOAA, etc.) so callers receive a
     * consistent error shape instead of a raw 500. The upstream status code is included
     * in the message to aid diagnostics.
     *
     * @param ex the exception thrown by WebClient
     * @return a 502 response with a structured error body
     */
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamError(WebClientResponseException ex) {
        String message = "Upstream API error: " + ex.getStatusCode().value() + " " + ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(message));
    }
}
