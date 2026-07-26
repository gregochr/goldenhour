package com.gregochr.goldenhour.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the PIT mutation run against exclusion drift.
 *
 * <p>Prompt-regression tests make real Claude API calls and throw outright when
 * {@code ANTHROPIC_API_KEY} is absent, as it is on the scheduled PIT runner. PIT does its own test
 * discovery and — with {@code parseSurefireConfig=false} — cannot see surefire's
 * {@code excludedGroups}, so if it picks one of these tests up the coverage phase goes red and PIT
 * aborts the entire run with "Mutation testing requires a green suite".
 *
 * <p>That is not hypothetical. {@code SkyRatingEvalTest} was added on 2026-06-27 carrying the tag
 * but not an entry in the hand-maintained {@code <excludedTestClasses>} list, and the weekly
 * mutation run was red for four consecutive Mondays from 2026-06-29 — four weeks with no mutation
 * score at all (CHANGELOG, 2026-06-29). The pom now carries two overlapping mechanisms: PIT's own
 * {@code <excludedGroups>} (tag-based, so a new tagged test is covered automatically) and the
 * explicit class list as a belt-and-braces fallback.
 *
 * <p>This test is the ratchet on both. It fails the day a fourth {@code @Tag("prompt-regression")}
 * class is added without being listed, and the day the tag-level exclusion is removed. It asserts
 * against the text <em>inside</em> the relevant pom elements rather than the whole file, so a class
 * name mentioned only in a comment does not satisfy it.
 */
class PitExclusionDriftTest {

    /** The pitest plugin coordinate, used to scope pom parsing to that plugin's configuration. */
    private static final String PITEST_ARTIFACT_ID = "<artifactId>pitest-maven</artifactId>";

    /** The JUnit tag that marks a test as requiring a live Anthropic API key. */
    private static final String PROMPT_REGRESSION_TAG = "prompt-regression";

    /** Strips XML comments, so element names mentioned in prose cannot satisfy an assertion. */
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** Matches the tag annotation itself, tolerating whitespace inside the parentheses. */
    private static final Pattern TAG_ANNOTATION =
            Pattern.compile("@Tag\\s*\\(\\s*\"" + PROMPT_REGRESSION_TAG + "\"\\s*\\)");

    /** Surefire runs with the module directory as the working directory, so these are relative. */
    private static final Path POM = Path.of("pom.xml");

    private static final Path TEST_SOURCE_ROOT = Path.of("src", "test", "java");

    /**
     * Verifies the working-directory assumption instead of relying on it.
     *
     * <p>Surefire defaults its {@code workingDirectory} to {@code ${basedir}}, so both paths above
     * resolve inside {@code backend/}. If that ever changes, the file walk would find nothing and
     * this whole class would silently degrade into a test that always passes — the exact failure
     * mode it exists to prevent. Fail loudly and early instead.
     */
    @BeforeEach
    void verifyWorkingDirectoryIsTheBackendModule() throws IOException {
        assertThat(POM)
                .as("expected the working directory (%s) to be the backend module root, "
                        + "because these paths are resolved relative to it",
                        Path.of("").toAbsolutePath())
                .isRegularFile();
        assertThat(Files.readString(POM, StandardCharsets.UTF_8))
                .as("pom.xml in the working directory must be the backend module pom, not the "
                        + "repository root or another module")
                .contains("<artifactId>golden-hour</artifactId>");
        assertThat(TEST_SOURCE_ROOT)
                .as("the test-source walk root must exist, otherwise this ratchet is vacuous")
                .isDirectory();
    }

    @Test
    void everyPromptRegressionTaggedClassIsListedInPitExcludedTestClasses() throws IOException {
        List<String> excludedPatterns = paramsOf("excludedTestClasses");
        List<String> tagged = taggedTestClasses();

        assertThat(tagged)
                .as("no @Tag(\"%s\") class was found under %s. This ratchet only has teeth if the "
                        + "walk actually finds the tagged classes; an empty result would make every "
                        + "assertion below vacuously true and the test would pass forever while PIT "
                        + "drifts. If the tag was renamed or moved, fix the walk — do not delete "
                        + "this assertion.", PROMPT_REGRESSION_TAG, TEST_SOURCE_ROOT)
                .isNotEmpty();

        assertThat(tagged).allSatisfy(className -> assertThat(excludedPatterns)
                .as("add <param>%s</param> to <excludedTestClasses> in the pitest-maven "
                        + "configuration in backend/pom.xml. PIT does its own test discovery, this "
                        + "class throws without ANTHROPIC_API_KEY, and PIT aborts the whole run on "
                        + "a red suite — that is how the weekly mutation run went red for four "
                        + "consecutive Mondays from 2026-06-29 (CHANGELOG). Currently listed: %s",
                        className, excludedPatterns)
                .anyMatch(pattern -> matchesPitGlob(pattern, className)));
    }

    @Test
    void pitExcludesThePromptRegressionTagItself() throws IOException {
        assertThat(paramsOf("excludedGroups"))
                .as("PIT's own <excludedGroups> must keep listing the \"%s\" tag. It is the "
                        + "mechanism that covers a newly tagged test automatically; the explicit "
                        + "class list is only a fallback, and it was the class list alone that "
                        + "drifted and cost four weeks of mutation scores (CHANGELOG 2026-06-29).",
                        PROMPT_REGRESSION_TAG)
                .contains(PROMPT_REGRESSION_TAG);
    }

    /**
     * Reads the {@code <param>} values of one element inside the pitest plugin's configuration.
     *
     * @param elementName the wrapping element, e.g. {@code excludedTestClasses}
     * @return the declared parameter values, in pom order
     * @throws IOException if the pom cannot be read
     */
    private static List<String> paramsOf(String elementName) throws IOException {
        String pitestPlugin = pitestPluginBlock(Files.readString(POM, StandardCharsets.UTF_8));
        Matcher element = Pattern.compile(
                        "<" + elementName + ">(.*?)</" + elementName + ">", Pattern.DOTALL)
                .matcher(pitestPlugin);
        assertThat(element.find())
                .as("the pitest-maven configuration in backend/pom.xml must declare <%s>",
                        elementName)
                .isTrue();

        Matcher params = Pattern.compile("<param>\\s*(.*?)\\s*</param>", Pattern.DOTALL)
                .matcher(element.group(1));
        return params.results().map(result -> result.group(1)).toList();
    }

    /**
     * Narrows the pom text to the pitest plugin declaration.
     *
     * <p>Scoping matters twice over: {@code <excludedGroups>} also appears in the surefire plugin,
     * and the pitest configuration is preceded by a long comment that names both elements and the
     * excluded classes. Asserting against the whole file, or against text that still carries those
     * comments, would let prose satisfy the ratchet — so comments are stripped first.
     *
     * @param pom the full pom text
     * @return the text of the pitest {@code <plugin>} element, comments removed
     */
    private static String pitestPluginBlock(String pom) {
        String uncommented = XML_COMMENT.matcher(pom).replaceAll("");
        int coordinate = uncommented.indexOf(PITEST_ARTIFACT_ID);
        assertThat(coordinate)
                .as("backend/pom.xml must still declare the pitest-maven plugin")
                .isNotNegative();
        int end = uncommented.indexOf("</plugin>", coordinate);
        assertThat(end)
                .as("the pitest-maven <plugin> element must be closed")
                .isNotNegative();
        return uncommented.substring(coordinate, end);
    }

    /**
     * Walks the test sources and returns the fully-qualified names of tagged classes.
     *
     * @return sorted fully-qualified class names carrying the prompt-regression tag
     * @throws IOException if the source tree cannot be walked
     */
    private static List<String> taggedTestClasses() throws IOException {
        try (Stream<Path> sources = Files.walk(TEST_SOURCE_ROOT)) {
            return sources
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(PitExclusionDriftTest::declaresPromptRegressionTag)
                    .map(PitExclusionDriftTest::toFullyQualifiedName)
                    .sorted()
                    .toList();
        }
    }

    /**
     * Decides whether a source file carries the tag as a real annotation.
     *
     * <p>Comment lines are skipped: three of these classes discuss the tag in their Javadoc, and a
     * mention in prose is not an exclusion-worthy declaration.
     *
     * @param source the java source file
     * @return true if the file declares {@code @Tag("prompt-regression")} outside a comment
     */
    private static boolean declaresPromptRegressionTag(Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.startsWith("*") && !line.startsWith("/"))
                    .anyMatch(line -> TAG_ANNOTATION.matcher(line).find());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read test source " + source, e);
        }
    }

    /**
     * Converts a path under {@code src/test/java} to a fully-qualified class name.
     *
     * @param source the java source file
     * @return the fully-qualified class name
     */
    private static String toFullyQualifiedName(Path source) {
        Path relative = TEST_SOURCE_ROOT.toAbsolutePath().relativize(source.toAbsolutePath());
        String withSlashes = relative.toString().replace(".java", "");
        return withSlashes.replace(relative.getFileSystem().getSeparator(), ".");
    }

    /**
     * Matches a class name against one PIT exclusion pattern.
     *
     * <p>PIT's exclusion entries are globs, so {@code com.gregochr.goldenhour.controller.*} really
     * does exclude a tagged class in that package. Treating them as literals would fail the build
     * for a class PIT already skips.
     *
     * @param pattern the {@code <param>} value from the pom
     * @param className the fully-qualified class name
     * @return true if PIT would exclude that class under this pattern
     */
    private static boolean matchesPitGlob(String pattern, String className) {
        StringBuilder regex = new StringBuilder();
        boolean first = true;
        for (String literal : pattern.split("\\*", -1)) {
            if (!first) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
            first = false;
        }
        return className.matches(regex.toString());
    }
}
