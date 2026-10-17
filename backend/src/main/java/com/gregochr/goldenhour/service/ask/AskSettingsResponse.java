package com.gregochr.goldenhour.service.ask;

/**
 * The body of {@code GET /api/user/settings/ask} (plan §2.9): what the empty state needs before any
 * question is asked. Always a 200; with Ask off it is {@code enabled: false} and zeros.
 *
 * @param enabled        whether Ask is switched on
 * @param used           typed questions charged today
 * @param limit          the asker's daily allowance
 * @param left           {@code limit - used}, never negative
 * @param typedAvailable false when the flag is off, today's spend cap is reached, or a paid model
 *                       call's cost could not be recorded: the client then offers Ready questions only
 */
public record AskSettingsResponse(boolean enabled, int used, int limit, int left,
        boolean typedAvailable) {

    /**
     * The response while Ask is switched off.
     *
     * @return {@code enabled: false}, zeros, typed unavailable
     */
    public static AskSettingsResponse off() {
        return new AskSettingsResponse(false, 0, 0, 0, false);
    }
}
