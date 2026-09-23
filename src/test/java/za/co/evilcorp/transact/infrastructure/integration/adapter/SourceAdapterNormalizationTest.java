package za.co.evilcorp.transact.infrastructure.integration.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.integration.dto.SourceADto;
import za.co.evilcorp.transact.infrastructure.integration.dto.SourceBDto;
import za.co.evilcorp.transact.infrastructure.integration.dto.SourceCDto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SourceAdapterNormalizationTest {

    @Test
    void sourceA_normalizesSignedAmountToAbsoluteWithDirectionFromSign() {
        TransactionSource.SourceResponse response =
            new SourceAAdapter().fetchTransactions(Optional.empty());

        assertThat(response.transactions()).hasSize(2);
        assertThat(response.nextCursor()).isEqualTo("cursor-1");

        CanonicalTransaction debit = response.transactions().get(0);
        assertThat(debit.getSourceId()).isEqualTo("SOURCE_A");
        assertThat(debit.getSourceTransactionId()).isEqualTo("A100");
        assertThat(debit.getAmount()).isEqualByComparingTo("150.00");
        assertThat(debit.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(debit.getCurrency()).isEqualTo("ZAR");
        assertThat(debit.getMerchantName()).isEqualTo("Woolworths");
        assertNormalizationContract(debit);
    }

    private static void assertNormalizationContract(CanonicalTransaction t) {
        assertThat(t.getNormalizationVersion()).isEqualTo("1");
        assertThat(t.getIngestedAt()).isNotNull();
        assertThat(t.getTransactionDate()).isNotNull();
        assertThat(t.getAmount()).isNotNull();
        assertThat(t.getAmount()).isPositive();
    }

    @Test
    void sourceA_creditRecordAndNullMerchant() {
        TransactionSource.SourceResponse response =
            new SourceAAdapter().fetchTransactions(Optional.empty());
        CanonicalTransaction credit = response.transactions().get(1);

        assertThat(credit.getAmount()).isEqualByComparingTo("2000.00");
        assertThat(credit.getDirection()).isEqualTo(TransactionDirection.CREDIT);
        assertThat(credit.getMerchantName()).isNull();
        assertThat(credit.getDescription()).isEqualTo("Salary Deposit");
        assertNormalizationContract(credit);
    }

    @Test
    void sourceB_normalizesValueSignToAbsoluteWithDirection() {
        TransactionSource.SourceResponse response =
            new SourceBAdapter().fetchTransactions(Optional.empty());

        assertThat(response.transactions()).hasSize(2);
        assertThat(response.nextCursor()).isEqualTo("cursor-1");

        CanonicalTransaction first = response.transactions().get(0);
        assertThat(first.getSourceId()).isEqualTo("SOURCE_B");
        assertThat(first.getSourceTransactionId()).isEqualTo("B-REF-001");
        assertThat(first.getAmount()).isEqualByComparingTo("45.00");
        assertThat(first.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(first.getCurrency()).isEqualTo("ZAR");
        assertThat(first.getMerchantName()).isEqualTo("STARBUCKS");
        assertNormalizationContract(first);

        CanonicalTransaction second = response.transactions().get(1);
        assertThat(second.getAmount()).isEqualByComparingTo("120.50");
        assertThat(second.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(second.getMerchantName()).isEqualTo("UBER EATS");
        assertNormalizationContract(second);
    }

    @Test
    void sourceC_normalizesAmountCurrencyAndMerchant() {
        TransactionSource.SourceResponse response =
            new SourceCAdapter().fetchTransactions(Optional.empty());

        assertThat(response.transactions()).hasSize(1);
        assertThat(response.nextCursor()).isEqualTo("cursor-1");

        CanonicalTransaction tx = response.transactions().get(0);
        assertThat(tx.getSourceId()).isEqualTo("SOURCE_C");
        assertThat(tx.getSourceTransactionId()).isEqualTo("C-999");
        assertThat(tx.getAmount()).isEqualByComparingTo("12.50");
        assertThat(tx.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(tx.getCurrency()).isEqualTo("ZAR");
        assertThat(tx.getMerchantName()).isEqualTo("Netflix");
        assertNormalizationContract(tx);
    }

    @Test
    void sourceA_normalizeWithNullAmountDoesNotThrow() {
        SourceADto dto = new SourceADto();
        dto.setId("A-NULL");
        dto.setAmount(null);
        dto.setCurrency("ZAR");
        dto.setDescription("broken payload");
        dto.setDate(OffsetDateTime.now());

        CanonicalTransaction normalized =
            ReflectionTestUtils.invokeMethod(new SourceAAdapter(), "normalize", dto);

        assertThat(normalized).isNotNull();
        assertThat(normalized.getAmount()).isNull();
        assertThat(normalized.getDirection()).isNull();
        assertThat(normalized.getNormalizationVersion()).isEqualTo("1");
    }

    @Test
    void sourceB_normalizeWithNullValueDoesNotThrow() {
        SourceBDto dto = new SourceBDto();
        dto.setTransactionReference("B-NULL");
        dto.setValue(null);
        dto.setCurrencyCode("ZAR");
        dto.setTimestamp(OffsetDateTime.now());

        CanonicalTransaction normalized =
            ReflectionTestUtils.invokeMethod(new SourceBAdapter(), "normalize", dto);

        assertThat(normalized).isNotNull();
        assertThat(normalized.getAmount()).isNull();
        assertThat(normalized.getDirection()).isNull();
        assertThat(normalized.getNormalizationVersion()).isEqualTo("1");
    }

    @Test
    void sourceC_normalizeWithNullAmountDoesNotThrow() {
        SourceCDto dto = new SourceCDto();
        dto.setTx_id("C-NULL");
        dto.setTx_amount(null);
        dto.setTx_currency("ZAR");
        dto.setTx_date(OffsetDateTime.now());

        CanonicalTransaction normalized =
            ReflectionTestUtils.invokeMethod(new SourceCAdapter(), "normalize", dto);

        assertThat(normalized).isNotNull();
        assertThat(normalized.getAmount()).isNull();
        assertThat(normalized.getDirection()).isNull();
        assertThat(normalized.getNormalizationVersion()).isEqualTo("1");
    }

    @Test
    void allAdaptersMarkNormalizationVersionOne() {
        List<TransactionSource.SourceResponse> responses = List.of(
            new SourceAAdapter().fetchTransactions(Optional.empty()),
            new SourceBAdapter().fetchTransactions(Optional.empty()),
            new SourceCAdapter().fetchTransactions(Optional.empty()));

        assertThat(responses)
            .flatExtracting(TransactionSource.SourceResponse::transactions)
            .allSatisfy(t -> assertThat(t.getNormalizationVersion()).isEqualTo("1"));
    }

    @Test
    void positiveAmountNormalizesToCredit() {
        SourceADto dto = new SourceADto();
        dto.setId("A-POS");
        dto.setAmount(new BigDecimal("99.99"));
        dto.setCurrency("ZAR");
        dto.setDate(OffsetDateTime.now());

        CanonicalTransaction normalized =
            ReflectionTestUtils.invokeMethod(new SourceAAdapter(), "normalize", dto);

        assertThat(normalized.getDirection()).isEqualTo(TransactionDirection.CREDIT);
        assertThat(normalized.getAmount()).isEqualByComparingTo("99.99");
    }

    @Test
    void negativeAmountNormalizesToDebitWithAbsoluteValue() {
        SourceBDto dto = new SourceBDto();
        dto.setTransactionReference("B-NEG");
        dto.setValue(new BigDecimal("-0.01"));
        dto.setCurrencyCode("USD");
        dto.setTimestamp(OffsetDateTime.now());

        CanonicalTransaction normalized =
            ReflectionTestUtils.invokeMethod(new SourceBAdapter(), "normalize", dto);

        assertThat(normalized.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(normalized.getAmount()).isEqualByComparingTo("0.01");
        assertThat(normalized.getCurrency()).isEqualTo("USD");
    }
}
