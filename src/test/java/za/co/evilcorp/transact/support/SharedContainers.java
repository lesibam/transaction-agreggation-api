package za.co.evilcorp.transact.support;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-wide singleton containers so every @SpringBootTest subclass shares one
 * Postgres + one Kafka instead of starting a pair per test class.
 * Ryuk is disabled on this environment; a shutdown hook stops the containers.
 * Migrations are applied here once so the shared Postgres is ready before any
 * Spring context starts (spring-boot-starter-flyway also runs on boot).
 */
public final class SharedContainers {

    static {
        DockerTestEnvironment.apply();
    }

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static final KafkaContainer KAFKA =
        new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                KAFKA.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                POSTGRES.stop();
            } catch (RuntimeException ignored) {
            }
        }, "shared-containers-shutdown"));
    }

    private SharedContainers() {
    }

    public static PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    public static KafkaContainer kafka() {
        return KAFKA;
    }
}
