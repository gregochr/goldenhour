package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.service.ask.AskErrorCode;
import com.gregochr.goldenhour.service.ask.AskRefusal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Turns the security chain's 403 on {@code /api/ask/**} into Ask's one refusal shape,
 * {@code {"error","code":"NOT_ENTITLED"}}, instead of Boot's {@code /error} body. Registered in
 * {@code SecurityConfig} for the Ask paths only; every other path keeps the default handler.
 */
public class AskAccessDeniedHandler implements AccessDeniedHandler {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * Writes the {@code NOT_ENTITLED} refusal as the 403 response body.
     *
     * @param request               the refused request
     * @param response              the response to write
     * @param accessDeniedException why access was denied (unused: the reason is always the role)
     * @throws IOException if the body cannot be written
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        AskRefusal refusal = new AskRefusal(AskErrorCode.NOT_ENTITLED);
        response.setStatus(refusal.code().status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(MAPPER.writeValueAsString(refusal.body()));
    }
}
