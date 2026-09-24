package za.co.evilcorp.transact.infrastructure.integration.normalizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalizer strategy unit tests — the semantic contract that used to live in
 * the adapters (ADR-006), now exercised directly through the strategy
 * interface selected by the registry's `normalizer` key (ADR-011).
 */
class SourceNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sourceAKey() {
        assertThat(new SourceANormalizer().key()).isEqualTo("source-a-v1");
        assertThat(new SourceBNormalizer().key()).isEqualTo("source-b-v1");
        assertThat(new SourceCNormalizer().key()).isEqualTo("source-c-v1");
    }

    @Test
    void sourceA_negativeAmountBecomesAbsoluteDebit() {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("id", "A100");
        raw.put("amount", -150);
        raw.put("currency", "ZAR");
        raw.put("description", "Grocery Store A");
        raw.put("merchantName", "Woolworths");
        raw.put("date", "2026-09-22T10:00:00Z");

        CanonicalTransaction t = new SourceANormalizer().normalize(raw, "SOURCE_A");

        assertThat(t.getSourceId()).isEqualTo("SOURCE_A");
        assertThat(t.getSourceTransactionId()).isEqualTo("A100");
        assertThat(t.getAmount()).isEqualByComparingTo("150");
        assertThat(t.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(t.getCurrency()).isEqualTo("ZAR");
        assertThat(t.getMerchantName()).isEqualTo("Woolworths");
        assertThat(t.getTransactionDate()).isEqualTo(OffsetDateTime.parse("2026-09-22T10:00:00Z"));
        assertNormalizationContract(t);
    }

    @Test
    void sourceA_positiveAmountBecomesCreditWithNullMerchant() {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("id", "A-POS");
        raw.put("amount", 99.99);
        raw.put("currency", "ZAR");
        raw.putNull("merchantName");
        raw.put("date", "2026-09-22T10:00:00Z");

        CanonicalTransaction t = new SourceANormalizer().normalize(raw, "SOURCE_A");

        assertThat(t.getDirection()).isEqualTo(TransactionDirection.CREDIT);
        assertThat(t.getAmount()).isEqualByComparingTo("99.99");
        assertThat(t.getMerchantName()).isNull();
        assertNormalizationContract(t);
    }

    @Test
    void sourceA_nullAmountDoesNotThrow() {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("id", "A-NULL");
        raw.putNull("amount");
        raw.put("currency", "ZAR");
        raw.put("date", "2026-09-22T10:00:00Z");

        CanonicalTransaction t = new SourceANormalizer().normalize(raw, "SOURCE_A");

        assertThat(t.getAmount()).isNull();
        assertThat(t.getDirection()).isNull();
        assertThat(t.getNormalizationVersion()).isEqualTo("1");
    }

    @Test
    void sourceA_invalidDateBecomesNullAndIsQuarantinedDownstream() {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("id", "A-BAD-DATE");
        raw.put("amount", 10);
        raw.put("currency", "ZAR");
        raw.put("date", "not-a-date");

        CanonicalTransaction t = new SourceANormalizer().normalize(raw, "SOURCE_A");

        assertThat(t.getTransactionDate()).isNull();
        assertThat(t.getAmount()).isEqualByComparingTo("10");
    }

    @Test
    void sourceB_mapsFieldNamesAndSignSemantics() {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("transactionReference", "B-REF-001");
        raw.put("value", -0.01);
        raw.put("currencyCode", "USD");
        raw.put("merchantInfo", "Coffee Shop B");
        raw.put("merchantName", "STARBUCKS");
        raw.put("timestamp", "2026-09-23T06:00:00Z");

        CanonicalTransaction t = new SourceBNormalizer().normalize(raw, "SOURCE_B");

        assertThat(t.getSourceId()).isEqualTo("SOURCE_B");
        assertThat(t.getSourceTransactionId()).isEqualTo("B-REF-001");
        assertThat(t.getAmount()).isEqualByComparingTo("0.01");
        assertThat(t.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(t.getCurrency()).isEqualTo("USD");
        assertThat(t.getDescription()).isEqualTo("Coffee Shop B");
        assertThat(t.getMerchantName()).isEqualTo("STARBUCKS");
        assertNormalizationContract(t);
    }

    @Test
    void sourceB_nullValueDoesNotThrow() {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("transactionReference", "B-NULL");
        raw.putNull("value");
        raw.put("currencyCode", "ZAR");
        raw.put("timestamp", "2026-09-23T06:00:00Z");

        CanonicalTransaction t = new SourceBNormalizer().normalize(raw, "SOURCE_B");

        assertThat(t.getAmount()).isNull();
        assertThat(t.getDirection()).isNull();
        assertThat(t.getNormalizationVersion()).isEqualTo("1");
    }

    @Test
    void sourceC_mapsSnakeCaseFields() {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("tx_id", "C-999");
        raw.put("tx_amount", -12.5);
        raw.put("tx_currency", "ZAR");
        raw.put("tx_desc", "Online Store C");
        raw.put("merchantName", "Netflix");
        raw.put("tx_date", "2026-09-23T10:30:00Z");

        CanonicalTransaction t = new SourceCNormalizer().normalize(raw, "SOURCE_C");

        assertThat(t.getSourceId()).isEqualTo("SOURCE_C");
        assertThat(t.getSourceTransactionId()).isEqualTo("C-999");
        assertThat(t.getAmount()).isEqualByComparingTo("12.5");
        assertThat(t.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(t.getDescription()).isEqualTo("Online Store C");
        assertThat(t.getMerchantName()).isEqualTo("Netflix");
        assertNormalizationContract(t);
    }

    @Test
    void sourceC_nullAmountDoesNotThrow() {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("tx_id", "C-NULL");
        raw.putNull("tx_amount");
        raw.put("tx_currency", "ZAR");
        raw.put("tx_date", "2026-09-23T10:30:00Z");

        CanonicalTransaction t = new SourceCNormalizer().normalize(raw, "SOURCE_C");

        assertThat(t.getAmount()).isNull();
        assertThat(t.getDirection()).isNull();
        assertThat(t.getNormalizationVersion()).isEqualTo("1");
    }

    @Test
    void missingFieldsYieldNullsNotExceptions() {
        CanonicalTransaction t = new SourceANormalizer().normalize(mapper.createObjectNode(), "SOURCE_A");

        assertThat(t.getSourceTransactionId()).isNull();
        assertThat(t.getAmount()).isNull();
        assertThat(t.getCurrency()).isNull();
        assertThat(t.getDirection()).isNull();
        assertThat(t.getTransactionDate()).isNull();
        assertThat(t.getNormalizationVersion()).isEqualTo("1");
    }

    private static void assertNormalizationContract(CanonicalTransaction t) {
        assertThat(t.getNormalizationVersion()).isEqualTo("1");
        assertThat(t.getIngestedAt()).isNotNull();
        assertThat(t.getAmount()).isNotNull();
        assertThat(t.getAmount()).isPositive();
        assertThat(t.getTransactionDate()).isNotNull();
    }
}
