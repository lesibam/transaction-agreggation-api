package za.co.evilcorp.transact.infrastructure.integration.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.config.SourceDescriptor;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;
import za.co.evilcorp.transact.infrastructure.integration.normalizer.SourceCNormalizer;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test against a per-class MinIO container. Batches use the
 * Source C payload shape while the descriptor id is SOURCE_S3, proving the
 * transport is decoupled from the payload shape via SourceCNormalizer.
 */
class S3TransactionSourceTest {

    private static final String BATCH_001 =
        "[{\"tx_id\":\"D1\",\"tx_amount\":-5,\"tx_currency\":\"ZAR\",\"tx_desc\":\"x\","
            + "\"merchantName\":\"Netflix\",\"tx_date\":\"2026-09-23T10:00:00Z\"}]";
    private static final String BATCH_002 =
        "[{\"tx_id\":\"D2\",\"tx_amount\":7,\"tx_currency\":\"ZAR\",\"tx_desc\":\"y\","
            + "\"merchantName\":\"Spotify\",\"tx_date\":\"2026-09-23T11:00:00Z\"}]";

    private static MinIOContainer minio;
    private static S3Client adminClient;
    private static String bucket;
    private static S3TransactionSource source;

    @BeforeAll
    static void setUpSource() {
        minio = new MinIOContainer(DockerImageName.parse("minio/minio:latest"));
        minio.start();
        String endpoint = minio.getS3URL();

        adminClient = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .forcePathStyle(true)
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
            .build();

        bucket = "source-d-test-" + System.nanoTime();
        adminClient.createBucket(request -> request.bucket(bucket));

        put("source-d/batch-001.json", BATCH_001);
        put("source-d/batch-002.json", BATCH_002);

        SourceDescriptor descriptor = new SourceDescriptor(
            "SOURCE_S3", null, true, SourceDescriptor.Type.S3, "source-c-v1", null, null,
            new SourceDescriptor.S3(endpoint, "us-east-1", bucket, "source-d/", "minioadmin", "minioadmin", 10),
            null);

        source = new S3TransactionSource(descriptor, new SourceCNormalizer(), new ObjectMapper());
    }

    @AfterAll
    static void tearDown() {
        if (adminClient != null) {
            adminClient.close();
        }
        if (minio != null) {
            minio.stop();
        }
    }

    @Test
    void firstFetchListsAllBatchesUnderPrefix() {
        TransactionSource.SourceResponse response = source.fetchTransactions(Optional.empty());

        assertThat(response.transactions()).hasSize(2);

        CanonicalTransaction debit = response.transactions().get(0);
        assertThat(debit.getSourceId()).isEqualTo("SOURCE_S3");
        assertThat(debit.getSourceTransactionId()).isEqualTo("D1");
        assertThat(debit.getAmount()).isEqualByComparingTo("5");
        assertThat(debit.getDirection()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(debit.getCurrency()).isEqualTo("ZAR");
        assertThat(debit.getMerchantName()).isEqualTo("Netflix");

        CanonicalTransaction credit = response.transactions().get(1);
        assertThat(credit.getSourceTransactionId()).isEqualTo("D2");
        assertThat(credit.getAmount()).isEqualByComparingTo("7");
        assertThat(credit.getDirection()).isEqualTo(TransactionDirection.CREDIT);
        assertThat(credit.getMerchantName()).isEqualTo("Spotify");

        assertThat(response.nextCursor()).isEqualTo("source-d/batch-002.json");
    }

    @Test
    void fetchFromLastKeyCursorIsExhausted() {
        TransactionSource.SourceResponse response =
            source.fetchTransactions(Optional.of("source-d/batch-002.json"));

        assertThat(response.transactions()).isEmpty();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void arrayElementThatIsNotAnObjectBecomesQuarantineNull() {
        put("source-e/batch-001.json", "[42]");

        SourceDescriptor scalarDescriptor = new SourceDescriptor(
            "SOURCE_S3", null, true, SourceDescriptor.Type.S3, "source-c-v1", null, null,
            new SourceDescriptor.S3(minio.getS3URL(), "us-east-1", bucket, "source-e/",
                "minioadmin", "minioadmin", 10),
            null);
        S3TransactionSource scalarSource =
            new S3TransactionSource(scalarDescriptor, new SourceCNormalizer(), new ObjectMapper());

        TransactionSource.SourceResponse response = scalarSource.fetchTransactions(Optional.empty());

        assertThat(response.transactions()).hasSize(1);
        assertThat(response.transactions().get(0)).isNull();
        assertThat(response.nextCursor()).isEqualTo("source-e/batch-001.json");
    }

    private static void put(String key, String content) {
        adminClient.putObject(
            request -> request.bucket(bucket).key(key),
            RequestBody.fromString(content));
    }
}
