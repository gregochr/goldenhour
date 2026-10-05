package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Exactly one {@link AskEngine} bean is active, whichever way {@code photocast.ask.stub} is written.
 * Both engine classes are always on the classpath and always registered here; only the condition
 * decides, which is the whole of what is under test.
 */
class AskEngineSelectionTest {

    /**
     * The Claude engine's dependencies are registered only when asked, so a stub context provably
     * needs none of them: no Anthropic client, no job-run service.
     */
    private ApplicationContextRunner runner(boolean withClaudeDependencies) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(AskAnswerValidator.class, AskAnswerValidator::new)
                .withBean(DriveTimeResolver.class, () -> mock(DriveTimeResolver.class))
                .withBean(RegionRepository.class, () -> mock(RegionRepository.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(ClaudeAskEngine.class, StubAskEngine.class);
        if (withClaudeDependencies) {
            runner = runner
                    .withBean(AnthropicApiClient.class, () -> mock(AnthropicApiClient.class))
                    .withBean(AskProperties.class, AskProperties::new)
                    .withBean(AskJobRunService.class, () -> mock(AskJobRunService.class))
                    .withBean(AskPromptBuilder.class, AskPromptBuilder::new)
                    .withBean(Clock.class, Clock::systemUTC);
        }
        return runner;
    }

    @Test
    @DisplayName("with the property absent the Claude engine is the engine, and the stub is not a bean")
    void defaultIsClaude() {
        runner(true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(AskEngine.class)).hasSize(1);
            assertThat(context).hasSingleBean(ClaudeAskEngine.class).doesNotHaveBean(StubAskEngine.class);
        });
    }

    @Test
    @DisplayName("stub=false is the Claude engine")
    void falseIsClaude() {
        runner(true).withPropertyValues("photocast.ask.stub=false").run(context -> {
            assertThat(context.getBeansOfType(AskEngine.class)).hasSize(1);
            assertThat(context).hasSingleBean(ClaudeAskEngine.class).doesNotHaveBean(StubAskEngine.class);
        });
    }

    @Test
    @DisplayName("stub=true is the stub engine alone, and a context with no Anthropic client at all starts: "
            + "the stub has no way to make a call or write a cost row")
    void trueIsTheStub() {
        runner(false).withPropertyValues("photocast.ask.stub=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(AskEngine.class)).hasSize(1);
            assertThat(context).hasSingleBean(StubAskEngine.class).doesNotHaveBean(ClaudeAskEngine.class);
            assertThat(context).doesNotHaveBean(AnthropicApiClient.class).doesNotHaveBean(AskJobRunService.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "True", "yes", "on", "1"})
    @DisplayName("every spelling the property binder reads as true selects the stub: one engine, never zero")
    void binderTrueSpellingsSelectTheStub(String spelling) {
        runner(true).withPropertyValues("photocast.ask.stub=" + spelling).run(context -> {
            assertThat(context.getBeansOfType(AskEngine.class)).hasSize(1);
            assertThat(context).hasSingleBean(StubAskEngine.class).doesNotHaveBean(ClaudeAskEngine.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"FALSE", "no", "off", "0", ""})
    @DisplayName("every other spelling that reads as false, or as nothing, selects Claude: one engine, never zero")
    void binderFalseSpellingsSelectClaude(String spelling) {
        runner(true).withPropertyValues("photocast.ask.stub=" + spelling).run(context -> {
            assertThat(context.getBeansOfType(AskEngine.class)).hasSize(1);
            assertThat(context).hasSingleBean(ClaudeAskEngine.class).doesNotHaveBean(StubAskEngine.class);
        });
    }

    @Test
    @DisplayName("stub=true under the prod profile fails startup with a reason: neither templates for readers "
            + "nor a silent fall-back to the engine that bills the real key")
    void stubIsRefusedInProduction() {
        runner(true).withPropertyValues("spring.profiles.active=prod", "photocast.ask.stub=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("refused under the prod profile");
                });
    }

    @Test
    @DisplayName("prod with the stub off is the Claude engine as always; local and dev may use the stub")
    void productionStillGetsClaudeAndOtherProfilesGetTheStub() {
        runner(true).withPropertyValues("spring.profiles.active=prod").run(context ->
                assertThat(context).hasSingleBean(ClaudeAskEngine.class).doesNotHaveBean(StubAskEngine.class));
        runner(true).withPropertyValues("spring.profiles.active=local", "photocast.ask.stub=true").run(context ->
                assertThat(context).hasSingleBean(StubAskEngine.class).doesNotHaveBean(ClaudeAskEngine.class));
        runner(true).withPropertyValues("spring.profiles.active=dev", "photocast.ask.stub=true").run(context ->
                assertThat(context).hasSingleBean(StubAskEngine.class).doesNotHaveBean(ClaudeAskEngine.class));
    }

    @Test
    @DisplayName("a value that is not a boolean fails startup rather than picking an engine")
    void garbageFailsStartup() {
        runner(true).withPropertyValues("photocast.ask.stub=banana")
                .run(context -> assertThat(context).hasFailed());
    }
}
