package za.co.evilcorp.transact.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import za.co.evilcorp.transact.application.port.TransactionIngestedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Producer wiring for TransactionIngestedEvent: JSON without type headers,
 * java.time support (OffsetDateTime), keyed by source for partition ordering.
 * Overrides Boot auto-config so the event serializer is explicit.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, TransactionIngestedEvent> transactionEventProducerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Shared Jackson2Config bean (java.time support) - the same instance
        // KafkaConsumerConfig and SecurityConfig use, so all three Jackson 2
        // call sites in this Boot-4-with-Jackson-3-by-default app agree on one
        // configuration instead of drifting independently. See ADR-012.
        JsonSerializer<TransactionIngestedEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);

        return new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, TransactionIngestedEvent> kafkaTemplate(
            ProducerFactory<String, TransactionIngestedEvent> transactionEventProducerFactory) {
        return new KafkaTemplate<>(transactionEventProducerFactory);
    }
}
