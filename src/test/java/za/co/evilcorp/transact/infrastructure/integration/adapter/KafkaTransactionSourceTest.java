package za.co.evilcorp.transact.infrastructure.integration.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.config.SourceDescriptor;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.integration.normalizer.SourceANormalizer;
import za.co.evilcorp.transact.support.SharedContainers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test against the shared JVM-wide Kafka container. The topic is
 * created with a single partition so the "partition:offset" cursor is
 * deterministic. Garbage record values must surface as null entries that
 * IngestionService quarantines.
 */
class KafkaTransactionSourceTest {

    private static final String K1_JSON =
        "{\"id\":\"K1\",\"amount\":-10,\"currency\":\"ZAR\",\"description\":\"d\","
            + "\"merchantName\":\"Woolworths\",\"date\":\"2026-09-23T10:00:00Z\"}";
    private static final String K2_JSON =
        "{\"id\":\"K2\",\"amount\":20,\"currency\":\"ZAR\",\"description\":\"d2\","
            + "\"merchantName\":\"Checkers\",\"date\":\"2026-09-23T10:00:00Z\"}";
    private static final String GARBAGE = "not-json{{";

    private static KafkaTransactionSource source;

    @BeforeAll
    static void setUpSource() throws Exception {
        KafkaContainer kafka = SharedContainers.kafka();

        String topic = "raw-source-test-" + System.nanoTime();
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"))) {
            producer.send(new ProducerRecord<>(topic, "tx", K1_JSON)).get();
            producer.send(new ProducerRecord<>(topic, "tx", K2_JSON)).get();
            producer.send(new ProducerRecord<>(topic, "tx", GARBAGE)).get();
        }

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

        SourceDescriptor descriptor = new SourceDescriptor(
            "SOURCE_K", null, true, SourceDescriptor.Type.KAFKA, "source-a-v1", null,
            new SourceDescriptor.Kafka(kafka.getBootstrapServers(), topic, null, "earliest", 2000, 500),
            null, null);

        source = new KafkaTransactionSource(descriptor, new SourceANormalizer(), objectMapper);
    }

    @Test
    void firstFetchFromEarliestNormalizesRecordsAndQuarantinesGarbage() {
        TransactionSource.SourceResponse response = source.fetchTransactions(Optional.empty());

        assertThat(response.transactions()).hasSize(3);

        CanonicalTransaction debit = response.transactions().get(0);
        assertThat(debit.getSourceId()).isEqualTo("SOURCE_K");
        assertThat(debit.getSourceTransactionId()).isEqualTo("K1");
        assertThat(debit.getAmount()).isEqualByComparingTo("10");
        assertThat(debit.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(debit.getCurrency()).isEqualTo("ZAR");
        assertThat(debit.getMerchantName()).isEqualTo("Woolworths");

        CanonicalTransaction credit = response.transactions().get(1);
        assertThat(credit.getSourceTransactionId()).isEqualTo("K2");
        assertThat(credit.getAmount()).isEqualByComparingTo("20");
        assertThat(credit.getDirection()).isEqualTo(TransactionDirection.CREDIT);

        assertThat(response.transactions().get(2)).isNull();
        assertThat(response.nextCursor()).isEqualTo("0:3");
    }

    @Test
    void refetchFromReturnedCursorYieldsNothingNew() {
        TransactionSource.SourceResponse response = source.fetchTransactions(Optional.of("0:3"));

        assertThat(response.transactions()).isEmpty();
        assertThat(response.nextCursor()).isEqualTo("0:3");
    }

    @Test
    void malformedCursorFailsLoudly() {
        assertThatThrownBy(() -> source.fetchTransactions(Optional.of("abc")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SOURCE_K");
    }
}
