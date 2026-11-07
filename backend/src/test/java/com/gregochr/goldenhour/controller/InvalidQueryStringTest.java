package com.gregochr.goldenhour.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves, through the real security filter chain, that a query string Tomcat cannot parse (an empty parameter
 * name such as {@code ?=}) is answered with 400 and a fixed body, never 500.
 *
 * <p>MockMvc's mock request never fails to parse a query string, so {@link TomcatParseFailureFilter} ahead
 * of the chain reproduces what Tomcat does: every {@code getParameter*} call throws
 * {@link InvalidParameterException} once the query string holds a chunk with no name.
 */
class InvalidQueryStringTest extends AbstractControllerTest {

    private static final String FIXED_BODY = "{\"error\":\"Invalid request parameter\"}";

    @Autowired
    private WebApplicationContext context;

    private MockMvc tomcatLikeMvc;

    @BeforeEach
    void setUp() {
        tomcatLikeMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .addFilters(new TomcatParseFailureFilter())
                .build();
    }

    @ParameterizedTest(name = "GET {0}?{1} is 400 with the fixed body")
    @CsvSource(delimiter = '#', value = {
            "/api/almanac # =",
            "/api/almanac # days=3&=",
            "/api/aurora/locations # =&minStars=1",
            "/api/aurora/locations # maxBortle=4&=",
            "/api/briefing/digest # =",
    })
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("an empty parameter name on a read endpoint is 400, never 500")
    void emptyParameterName_isBadRequest(String path, String query) throws Exception {
        tomcatLikeMvc.perform(withQuery(path, query))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(FIXED_BODY));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("the controller is never reached, and the value after the empty name is not echoed")
    void emptyParameterName_neverReachesTheService() throws Exception {
        tomcatLikeMvc.perform(withQuery("/api/almanac", "=secret-value"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(FIXED_BODY));

        verifyNoInteractions(almanacService);
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a GET to a PUT-only path with an empty parameter name is 400, not 500")
    void emptyParameterName_onUnmappedVerb_isBadRequest() throws Exception {
        tomcatLikeMvc.perform(withQuery("/api/user/settings/home", "="))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(FIXED_BODY));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("a query string with only named parameters is unaffected")
    void namedParameters_unaffected() throws Exception {
        tomcatLikeMvc.perform(get("/api/almanac?days=3"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the handler returns the fixed body")
    void handler_returnsFixedBody() {
        GlobalExceptionHandler.ErrorResponse body = new GlobalExceptionHandler().handleInvalidQueryString();

        assertEquals(new GlobalExceptionHandler.ErrorResponse(
                "Invalid request parameter"), body);
    }

    /** MockMvc drops an empty-named chunk when building a request, so the raw query is set directly. */
    private static MockHttpServletRequestBuilder withQuery(String path, String query) {
        return get(path).with(request -> {
            request.setQueryString(query);
            return request;
        });
    }

    /** Mirrors Tomcat: a chunk with no name poisons every later parameter read on the request. */
    private static final class TomcatParseFailureFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String query = request.getQueryString();
            boolean unparseable = query != null
                    && Arrays.stream(query.split("&")).anyMatch(chunk -> chunk.startsWith("="));
            chain.doFilter(unparseable ? new FailingParameterRequest(request) : request, response);
        }
    }

    /** A request whose parameter accessors throw, as Tomcat's do after a parse failure. */
    private static final class FailingParameterRequest extends HttpServletRequestWrapper {

        FailingParameterRequest(HttpServletRequest request) {
            super(request);
        }

        private static InvalidParameterException failure() {
            return new InvalidParameterException("Invalid chunk starting at byte [0] and ending at byte [1]");
        }

        @Override
        public String getParameter(String name) {
            throw failure();
        }

        @Override
        public String[] getParameterValues(String name) {
            throw failure();
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            throw failure();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            throw failure();
        }
    }
}
