package za.co.evilcorp.transact.support;

import java.lang.reflect.Field;
import java.util.Map;

final class DockerTestEnvironment {

    private static boolean applied;

    private DockerTestEnvironment() {
    }

    static synchronized void apply() {
        if (applied) {
            return;
        }
        applied = true;

        System.setProperty("api.version", "1.44");

        if (System.getProperty("docker.host") == null && System.getenv("DOCKER_HOST") == null) {
            String fromUserProps = UserTestcontainersProperties.get("docker.host");
            if (fromUserProps != null && !fromUserProps.isBlank()) {
                System.setProperty("docker.host", fromUserProps);
            }
        }

        setEnv("TESTCONTAINERS_RYUK_DISABLED", "true");
        setEnv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock");
    }

    private static void setEnv(String key, String value) {
        if (value.equals(System.getenv(key))) {
            return;
        }
        try {
            Class<?> processEnvironment = Class.forName("java.lang.ProcessEnvironment");
            putIntoEnvMap(processEnvironment, "theEnvironment", key, value);
            putIntoEnvMap(processEnvironment, "theCaseInsensitiveEnvironment", key, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.setProperty(toPropertyKey(key), value);
        }
    }

    private static String toPropertyKey(String envKey) {
        return envKey.toLowerCase().replace('_', '.');
    }

    @SuppressWarnings("unchecked")
    private static void putIntoEnvMap(Class<?> processEnvironment, String fieldName, String key, String value)
            throws ReflectiveOperationException {
        Field field = processEnvironment.getDeclaredField(fieldName);
        field.setAccessible(true);
        Map<String, String> map = (Map<String, String>) field.get(null);
        map.put(key, value);
    }

    private static final class UserTestcontainersProperties {
        private static final Map<String, String> VALUES = load();

        private static Map<String, String> load() {
            java.util.Properties props = new java.util.Properties();
            java.nio.file.Path path = java.nio.file.Path.of(
                    System.getProperty("user.home"), ".testcontainers.properties");
            try (var reader = java.nio.file.Files.newBufferedReader(path)) {
                props.load(reader);
            } catch (Exception e) {
                return java.util.Map.of();
            }
            java.util.Map<String, String> out = new java.util.HashMap<>();
            for (String name : props.stringPropertyNames()) {
                out.put(name, props.getProperty(name));
            }
            return java.util.Map.copyOf(out);
        }

        private static String get(String key) {
            return VALUES.get(key);
        }
    }
}
