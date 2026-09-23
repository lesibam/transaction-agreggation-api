package za.co.evilcorp.transact.application.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

final class KeysetCursor {

    private KeysetCursor() {
    }

    static String encode(OffsetDateTime transactionDate, UUID id) {
        String payload = transactionDate + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    static Parts decode(String raw) {
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
        String payload = new String(decoded, StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Invalid cursor");
        }
        try {
            return new Parts(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
    }

    record Parts(OffsetDateTime transactionDate, UUID id) {
    }
}
