package za.co.evilcorp.transact.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;
import za.co.evilcorp.transact.application.port.TransactionIngestedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer wiring for the ingestion pipeline: JSON deserialization of
 * TransactionIngestedEvent, bounded retries with exponential backoff,
 * then dead-letter to transactions.dlq.
 */
@Configuration
public class KafkaConsumerConfig {

    static final String DLQ_TOPIC = "transactions.dlq";

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionIngestedEvent>
            transactionIngestedContainerFactory(
                    @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
                    ObjectMapper objectMapper,
                    KafkaTemplate<String, TransactionIngestedEvent> kafkaTemplate) {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "transact-ingestion");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "za.co.evilcorp.transact");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionIngestedEvent.class.getName());

        // Jackson2Config's ObjectMapper carries JavaTimeModule for OffsetDateTime
        // on the event payload.
        ConsumerFactory<String, TransactionIngestedEvent> consumerFactory =
            new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                new JsonDeserializer<>(TransactionIngestedEvent.class, objectMapper));

        ConcurrentKafkaListenerContainerFactory<String, TransactionIngestedEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0d);
        backOff.setMaxAttempts(3);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) -> new TopicPartition(DLQ_TOPIC, record.partition()));

        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, backOff));
        return factory;
    }
}
