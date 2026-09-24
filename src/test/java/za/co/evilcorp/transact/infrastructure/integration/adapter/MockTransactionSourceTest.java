package za.co.evilcorp.transact.infrastructure.integration.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import za.co.evilcorp.transact.application.port.TransactionSource.SourceResponse;
import za.co.evilcorp.transact.config.SourceDescriptor;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.integration.normalizer.SourceANormalizer;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockTransactionSourceTest {

    private final MockTransactionSource source = new MockTransactionSource(
        mockDescriptor("classpath:mock/source-a.json"),
        new SourceANormalizer(),
        objectMapper());

    @Test
    void firstFetchWithoutCursorReturnsAllRecordsAndExhaustedCursor() {
        SourceResponse response = source.fetchTransactions(Optional.empty());

        assertThat(response.transactions()).hasSize(2);
        assertThat(response.nextCursor()).isEqualTo("MOCK_EXHAUSTED");

        CanonicalTransaction debit = response.transactions().get(0);
        assertThat(debit.getSourceId()).isEqualTo("source-a");
        assertThat(debit.getSourceTransactionId()).isEqualTo("A100");
        assertThat(debit.getAmount()).isEqualByComparingTo("150");
        assertThat(debit.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(debit.getCurrency()).isEqualTo("ZAR");
        assertThat(debit.getMerchantName()).isEqualTo("Woolworths");

        CanonicalTransaction credit = response.transactions().get(1);
        assertThat(credit.getSourceTransactionId()).isEqualTo("A101");
        assertThat(credit.getAmount()).isEqualByComparingTo("2000");
        assertThat(credit.getDirection()).isEqualTo(TransactionDirection.CREDIT);
        assertThat(credit.getMerchantName()).isNull();
    }

    @Test
    void secondFetchWithExhaustedCursorReturnsNoRecordsAndSameCursor() {
        SourceResponse response = source.fetchTransactions(Optional.of("MOCK_EXHAUSTED"));

        assertThat(response.transactions()).isEmpty();
        assertThat(response.nextCursor()).isEqualTo("MOCK_EXHAUSTED");
    }

    @Test
    void nonObjectRecordYieldsNullEntryInsteadOfThrowing() {
        MockTransactionSource badRecordSource = new MockTransactionSource(
            mockDescriptor("classpath:mock/bad-record.json"),
            new SourceANormalizer(),
            objectMapper());

        SourceResponse response = badRecordSource.fetchTransactions(Optional.empty());

        assertThat(response.transactions()).hasSize(2);
        assertThat(response.transactions().get(0)).isNotNull();
        assertThat(response.transactions().get(0).getSourceTransactionId()).isEqualTo("A100");
        assertThat(response.transactions().get(1)).isNull();
        assertThat(response.nextCursor()).isEqualTo("MOCK_EXHAUSTED");
    }

    @Test
    void missingMockConfigurationFailsLoudlyNamingTheSource() {
        MockTransactionSource unconfigured = new MockTransactionSource(
            new SourceDescriptor("source-a", "Source A", true, SourceDescriptor.Type.MOCK,
                SourceANormalizer.KEY, null, null, null, null),
            new SourceANormalizer(),
            objectMapper());

        assertThatThrownBy(() -> unconfigured.fetchTransactions(Optional.empty()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("source-a");
    }

    @Test
    void unreadableDataLocationFailsLoudlyNamingSourceAndLocation() {
        MockTransactionSource missingFile = new MockTransactionSource(
            mockDescriptor("classpath:mock/does-not-exist.json"),
            new SourceANormalizer(),
            objectMapper());

        assertThatThrownBy(() -> missingFile.fetchTransactions(Optional.empty()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("source-a")
            .hasMessageContaining("classpath:mock/does-not-exist.json");
    }

    private static SourceDescriptor mockDescriptor(String dataLocation) {
        return new SourceDescriptor(
            "source-a", "Source A", true, SourceDescriptor.Type.MOCK, SourceANormalizer.KEY,
            new SourceDescriptor.Mock(dataLocation), null, null, null);
    }

    private static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        return mapper;
    }
}
