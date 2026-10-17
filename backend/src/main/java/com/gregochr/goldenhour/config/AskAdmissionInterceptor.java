package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.service.ask.AskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@code POST /api/ask}'s per-user rate limit <b>before the request body is converted</b>
 * (plan §2.5 step 1).
 *
 * <p>An interceptor's {@code preHandle} runs before Spring reads the {@code @RequestBody}, which a
 * limiter inside the service cannot: a body that fails conversion (malformed JSON, a wrongly typed
 * field) is answered by the exception handler before the service is ever called, so a client could
 * send such bodies without limit and burn request-parsing capacity. Here every authenticated POST to
 * the endpoint is counted first. A refusal is an {@code AskRefusal}, which the application-wide
 * handler renders as 429 {@code {"error","code":"RATE_LIMITED"}}.
 *
 * <p><b>Exactly one count per request.</b> The count lives in {@link AskService#admit} and nowhere
 * else. This interceptor calls it once and passes the admitted user to the controller on
 * {@link #ADMITTED_USER_ATTRIBUTE}; the controller calls {@code admit} itself only when that
 * attribute is absent (this interceptor did not run), and {@code AskService.ask} takes the admitted
 * user and counts nothing. The asker is resolved once, here, and not again.
 *
 * <p>Not counted, on purpose: an anonymous request (Spring Security answers 401 before any
 * interceptor, and this refuses to count one if it ever got here), and any request while Ask is
 * switched off. This class does not read the flag itself: {@link AskFlagInterceptor} is registered
 * ahead of it ({@code AskWebConfig}) and answers 404 first, so no limiter state is touched and the
 * flag lives in one place.
 */
public class AskAdmissionInterceptor implements HandlerInterceptor {

    /** The request attribute carrying the admitted {@code AppUserEntity} to the controller. */
    public static final String ADMITTED_USER_ATTRIBUTE = AskAdmissionInterceptor.class.getName() + ".user";

    private final AskService askService;

    /**
     * Creates the interceptor.
     *
     * @param askService the service that resolves and counts the asker
     */
    public AskAdmissionInterceptor(AskService askService) {
        this.askService = askService;
    }

    /**
     * Counts the request against the asker's window, or refuses it.
     *
     * @param request  the request
     * @param response the response (unused)
     * @param handler  the chosen handler (unused)
     * @return always true: a refusal is an exception, not a false return
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return true;
        }
        request.setAttribute(ADMITTED_USER_ATTRIBUTE, askService.admit(auth));
        return true;
    }
}
