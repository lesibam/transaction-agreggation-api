package za.co.evilcorp.transact.application.port;

import za.co.evilcorp.transact.domain.model.CanonicalTransaction;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable event published once a source record has been validated and normalized.
 * Topic: transactions.ingested
 */
public record TransactionIngestedEvent(
    UUID eventId,
    OffsetDateTime occurredAt,
    String source,
    CanonicalTransaction transaction
) {}
