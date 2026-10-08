package com.gregochr.goldenhour.service.ask;

import java.util.ArrayList;
import java.util.List;

/** Test-only scopes naming regions by name, with throwaway ids (the tests never look an id up). */
final class TestScopes {

    private TestScopes() {
    }

    /**
     * A scope of the named regions.
     *
     * @param names the region names; none means every region
     * @return the scope
     */
    static AskScope of(String... names) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            ids.add(i + 1L);
        }
        return AskScope.of(ids, List.of(names));
    }
}
