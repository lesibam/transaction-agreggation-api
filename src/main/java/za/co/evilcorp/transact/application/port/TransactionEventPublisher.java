package za.co.evilcorp.transact.application.port;

/**
 * Messaging port owned by the application layer.
 * Infrastructure provides the Kafka implementation; the application never
 * depends on the broker directly.
 */
public interface TransactionEventPublisher {

    /**
     * Publishes the event. Implementations must surface infrastructure failures
     * as runtime exceptions so the ingestion pipeline can retry and record
     * the failure in source sync state.
     */
    void publish(TransactionIngestedEvent event);
}
