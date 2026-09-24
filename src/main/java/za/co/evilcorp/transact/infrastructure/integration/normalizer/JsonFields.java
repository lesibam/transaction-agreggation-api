package za.co.evilcorp.transact.infrastructure.integration.normalizer;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Shared null-safe field extraction for JSON-based normalizers. Missing or
 * invalid values return null so IngestionService's validation quarantines
 * the record instead of the adapter throwing.
 */
final class JsonFields {

    private JsonFields() {
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.decimalValue();
    }

    static OffsetDateTime dateTime(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
