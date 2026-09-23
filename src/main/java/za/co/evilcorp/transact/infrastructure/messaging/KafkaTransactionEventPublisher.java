package za.co.evilcorp.transact.infrastructure.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import za.co.evilcorp.transact.application.port.TransactionEventPublisher;
import za.co.evilcorp.transact.application.port.TransactionIngestedEvent;

import java.util.concurrent.TimeUnit;

/**
 * Kafka implementation of the application-owned messaging port.
 * Keyed by source so records for one source stay ordered on a single partition.
 * Producer serializers (StringSerializer key / JsonSerializer value) come from
 * spring.kafka.producer.* configuration — not set here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaTransactionEventPublisher implements TransactionEventPublisher {

    static final String TOPIC = "transactions.ingested";
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, TransactionIngestedEvent> kafkaTemplate;

    /**
     * Sends synchronously with a bounded wait so the ingestion pipeline can retry
     * infrastructure failures and reflect them in source sync state.
     */
    @Override
    public void publish(TransactionIngestedEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.source(), event)
                .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while publishing event " + event.eventId() + " to topic " + TOPIC, e);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to publish event " + event.eventId() + " to topic " + TOPIC + ": " + e.getMessage(), e);
        }
    }
}
