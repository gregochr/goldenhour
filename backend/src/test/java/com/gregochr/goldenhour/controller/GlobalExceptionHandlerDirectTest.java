package com.gregochr.goldenhour.controller;

import java.util.Collection;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler} called directly for the shapes of exception the MVC stack does not readily
 * produce: a 405 that knows no supported verbs, and a client disconnecting mid-response.
 */
class GlobalExceptionHandlerDirectTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("a 405 lists the verbs the path does accept in its Allow header")
    void methodNotSupported_withSupportedVerbs_setsAllow() {
        ResponseEntity<?> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("GET", List.of("PUT", "DELETE")));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.PUT, HttpMethod.DELETE);
    }

    @Test
    @DisplayName("a 405 that knows no supported verbs carries no Allow header")
    void methodNotSupported_withoutSupportedVerbs_hasNoAllow() {
        ResponseEntity<?> unknown = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("GET", (Collection<String>) null));
        ResponseEntity<?> empty = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("GET", List.of()));

        assertThat(unknown.getStatusCode().value()).isEqualTo(405);
        assertThat(unknown.getHeaders().containsHeader(HttpHeaders.ALLOW)).isFalse();
        assertThat(empty.getStatusCode().value()).isEqualTo(405);
        assertThat(empty.getHeaders().containsHeader(HttpHeaders.ALLOW)).isFalse();
    }

    @Test
    @DisplayName("a client disconnect is logged at debug with its reason and produces no error log")
    void clientDisconnect_isLoggedAtDebugOnly() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            handler.handleClientDisconnect(new AsyncRequestNotUsableException("Response not usable"));

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("Client disconnected during async response: Response not usable");
            assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsOnly(Level.DEBUG);
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
        }
    }
}
