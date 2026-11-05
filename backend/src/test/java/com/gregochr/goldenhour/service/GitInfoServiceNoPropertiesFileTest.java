package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GitInfoService#init()} reads {@code git.properties} through its own class loader, and the build
 * always puts that file on the test classpath, so the "file is absent" and "file cannot be read" paths are
 * unreachable in an ordinary test. These tests load a private copy of the service (and a tiny probe in the
 * same package) in a class loader that hides or breaks {@code git.properties}, and run the real
 * {@code init()} there. Nothing is reflected into: the probe calls the package-private constructor and
 * {@code init()} directly.
 */
class GitInfoServiceNoPropertiesFileTest {

    /** Runs inside the isolated loader, where it shares a runtime package with that loader's service copy. */
    public static class Probe implements Callable<List<String>> {

        private final Map<String, String> environment;

        /**
         * Creates the probe.
         *
         * @param environment the fake process environment the service falls back to
         */
        public Probe(Map<String, String> environment) {
            this.environment = environment;
        }

        @Override
        public List<String> call() {
            GitInfoService service = new GitInfoService(environment::get);
            service.init();
            return List.of(service.getCommitHash(), String.valueOf(service.getCommitMessage()),
                    String.valueOf(service.getCommitDate()), service.getBranch());
        }
    }

    /** Defines its own copy of the service and probe, and answers {@code git.properties} as told. */
    private static final class GitPropertiesLoader extends ClassLoader {

        private final Supplier<InputStream> gitProperties;

        GitPropertiesLoader(Supplier<InputStream> gitProperties) {
            super(GitInfoServiceNoPropertiesFileTest.class.getClassLoader());
            this.gitProperties = gitProperties;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(GitInfoService.class.getName()) && !name.equals(Probe.class.getName())) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = readClassBytes(name);
                    // The original's protection domain keeps the copy's code source, so JaCoCo still
                    // instruments (and so counts) the lines the copy runs.
                    Class<?> original = name.equals(Probe.class.getName()) ? Probe.class : GitInfoService.class;
                    loaded = defineClass(name, bytes, 0, bytes.length, original.getProtectionDomain());
                }
                return loaded;
            }
        }

        private byte[] readClassBytes(String name) throws ClassNotFoundException {
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if ("git.properties".equals(name)) {
                return gitProperties == null ? null : gitProperties.get();
            }
            return super.getResourceAsStream(name);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> initIn(Supplier<InputStream> gitProperties, Map<String, String> environment)
            throws Exception {
        GitPropertiesLoader loader = new GitPropertiesLoader(gitProperties);
        Class<?> probe = loader.loadClass(Probe.class.getName());
        assertThat(probe.getClassLoader()).as("the probe runs in the isolated loader").isSameAs(loader);
        Callable<List<String>> instance = (Callable<List<String>>) probe
                .getConstructor(Map.class).newInstance(environment);
        return instance.call();
    }

    @Test
    @DisplayName("with no git.properties on the classpath, init takes the commit from the GIT_COMMIT_* environment")
    void init_missingFile_usesEnvironment() throws Exception {
        List<String> read = initIn(null, Map.of(
                "GIT_COMMIT_SHA", "a91b0e2c4d5e6f708192a3b4c5d6e7f801234567",
                "GIT_COMMIT_TIME", "2031-11-02T10:30:00+01:00",
                "GIT_COMMIT_MESSAGE", "Prompt: stricter cirrus penalty"));

        assertThat(read).containsExactly(
                "a91b0e2c4d5e6f708192a3b4c5d6e7f801234567",
                "Prompt: stricter cirrus penalty",
                "2031-11-02T09:30",
                "");
    }

    @Test
    @DisplayName("with no git.properties and no environment, git info stays unknown")
    void init_missingFileAndNoEnvironment_staysUnknown() throws Exception {
        List<String> read = initIn(null, Map.of());

        assertThat(read).containsExactly("", "null", "null", "");
    }

    @Test
    @DisplayName("a git.properties that cannot be read falls back to the environment instead of failing startup")
    void init_unreadableFile_usesEnvironment() throws Exception {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk gone");
            }
        };

        List<String> read = initIn(() -> broken, Map.of("GIT_COMMIT_SHA", "feedface00000"));

        assertThat(read).containsExactly("feedface00000", "null", "null", "");
    }
}
