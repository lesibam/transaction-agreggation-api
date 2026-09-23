package za.co.evilcorp.transact.infrastructure.integration.adapter;

import java.util.Optional;

/**
 * Deterministic page cursor helper: inbound "cursor-{n}" (or empty) produces
 * outbound "cursor-{n+1}". A stable format keeps incremental sync meaningful —
 * unlike random UUIDs which can never be resumed from.
 */
final class SyncCursor {

    private static final String PREFIX = "cursor-";

    private SyncCursor() {
    }

    static String next(Optional<String> current) {
        int page = current
            .map(c -> c.startsWith(PREFIX) ? c.substring(PREFIX.length()) : c)
            .filter(s -> s.matches("\\d+"))
            .map(Integer::parseInt)
            .orElse(0);
        return PREFIX + (page + 1);
    }
}
