package za.co.evilcorp.transact.application.port;

import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import java.util.List;
import java.util.Optional;

/**
 * Application-owned port for pulling normalized transactions from an external source.
 * Implemented by source adapters under infrastructure.integration.
 */
public interface TransactionSource {
    String getSourceId();

    /**
     * Fetches transactions from the source.
     * @param cursor The cursor for incremental sync.
     * @return A list of normalized transactions and the next cursor.
     */
    SourceResponse fetchTransactions(Optional<String> cursor);

    record SourceResponse(
        List<CanonicalTransaction> transactions,
        String nextCursor
    ) {}
}
