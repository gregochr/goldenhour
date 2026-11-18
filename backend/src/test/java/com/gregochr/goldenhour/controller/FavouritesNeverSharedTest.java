package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.model.AreaDirectoryEntry;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.service.ask.AskReadyResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A favourite never rides an ETag-shared payload (find-and-favourites-plan F3; CLAUDE.md "Personal data never
 * rides an ETag-shared payload"). Two proofs: the shared response types (the briefing, the almanac, Ask's
 * Ready answers and the areas directory) carry no favourite-shaped field anywhere in their record graphs, so
 * a favourite cannot be serialised into them; and the directory read, with a favourite held by the very
 * reader asking, neither consults the favourites service nor mentions a favourite in its body.
 */
class FavouritesNeverSharedTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static void collectComponentNames(Class<?> type, Set<Class<?>> seen, List<String> names) {
        if (!type.isRecord() || !seen.add(type)) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            names.add(type.getSimpleName() + "." + component.getName());
            collectComponentNames(component.getType(), seen, names);
            if (component.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterized) {
                for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                    if (argument instanceof Class<?> argumentClass) {
                        collectComponentNames(argumentClass, seen, names);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the briefing, almanac, Ask-ready and directory response types hold no favourite-shaped field")
    void sharedResponseTypesCarryNoFavourite() {
        List<String> names = new ArrayList<>();
        for (Class<?> shared : List.of(DailyBriefingResponse.class, ComingUpResponse.class,
                AskReadyResponse.class, AreaDirectoryEntry.class)) {
            collectComponentNames(shared, new HashSet<>(), names);
        }

        assertThat(names).as("the walk reached real fields").hasSizeGreaterThan(20);
        assertThat(names).noneMatch(name -> name.toLowerCase().contains("favourite")
                || name.toLowerCase().contains("favorite") || name.toLowerCase().contains("starred"));
    }

    @Test
    @DisplayName("the areas directory body has no favourite and the favourites service is never consulted")
    void directoryBodyCarriesNoFavourite() throws Exception {
        AreaDirectoryEntry wall = new AreaDirectoryEntry(7L, "Hadrian's Wall", AreaKind.NAMED, List.of(), null,
                null, List.of(1L));
        when(areaDirectoryService.directoryFor(argThat(auth -> auth != null && "pat".equals(auth.getName()))))
                .thenReturn(List.of(wall));

        String body = mockMvc.perform(get("/api/areas/directory").with(user("pat").roles("PRO_USER")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Hadrian's Wall").doesNotContainIgnoringCase("favourite")
                .doesNotContainIgnoringCase("starred");
        verifyNoInteractions(userFavouritesService);
    }
}
