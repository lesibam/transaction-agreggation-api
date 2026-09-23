package za.co.evilcorp.transact.application.service;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeysetCursorTest {

    private final OffsetDateTime date = OffsetDateTime.parse("2026-03-15T10:30:45.123+02:00");
    private final UUID id = UUID.fromString("6f9619ff-8b86-4d01-b42d-00cf4fc964ff");

    @Test
    void encodeDecodeRoundTripPreservesDateAndId() {
        String encoded = KeysetCursor.encode(date, id);

        assertThat(encoded).isNotBlank();
        assertThat(encoded).doesNotContain("=");
        assertThat(encoded).matches("[A-Za-z0-9_-]+");

        KeysetCursor.Parts parts = KeysetCursor.decode(encoded);
        assertThat(parts.transactionDate()).isEqualTo(date);
        assertThat(parts.id()).isEqualTo(id);
    }

    @Test
    void decodeRejectsNonBase64Input() {
        assertThatThrownBy(() -> KeysetCursor.decode("not base64!!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid cursor");
    }

    @Test
    void decodeRejectsBase64WithoutPayloadStructure() {
        String garbage = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("hello".getBytes());

        assertThatThrownBy(() -> KeysetCursor.decode(garbage))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid cursor");
    }

    @Test
    void decodeRejectsPayloadWithInvalidUuid() {
        String payload = date + "|not-a-uuid";
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes());

        assertThatThrownBy(() -> KeysetCursor.decode(encoded))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid cursor");
    }

    @Test
    void decodeRejectsPayloadWithInvalidDate() {
        String payload = "yesterday|" + id;
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes());

        assertThatThrownBy(() -> KeysetCursor.decode(encoded))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid cursor");
    }

    @Test
    void decodeRejectsBlankParts() {
        String payload = "|" + id;
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes());

        assertThatThrownBy(() -> KeysetCursor.decode(encoded))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid cursor");
    }
}
