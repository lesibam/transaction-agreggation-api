package za.co.evilcorp.transact.infrastructure.integration.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import za.co.evilcorp.transact.application.port.TransactionSource;
import za.co.evilcorp.transact.config.SourceDescriptor;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.infrastructure.integration.normalizer.SourceNormalizer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * type: KAFKA transport adapter. Not a Spring bean: the source factory builds
 * one instance per configured SourceDescriptor.
 *
 * Cursor protocol (at-least-once): CSV of "partition:offset" entries, e.g.
 * "0:42,1:7". The ingestion pipeline persists the returned cursor in
 * source_sync_state only after a successful cycle, so offsets advance exactly
 * like every other source cursor. Partitions use manual assignment (no group
 * coordination) and auto-commit stays off; autoOffsetReset therefore only
 * applies to partitions that have no cursor entry yet.
 *
 * Offsets are strictly derived from consumer.position() after polling: every
 * record that was consumed is returned (garbage payloads as null entries for
 * quarantine), so advancing the cursor can never skip data.
 */
@Slf4j
public class KafkaTransactionSource implements TransactionSource {

    private static final String EARLIEST = "earliest";
    private static final String LATEST = "latest";
    private static final int DEFAULT_POLL_TIMEOUT_MS = 2000;
    private static final int DEFAULT_MAX_POLL_RECORDS = 500;

    private final SourceDescriptor descriptor;
    private final SourceNormalizer normalizer;
    private final ObjectMapper objectMapper;

    public KafkaTransactionSource(SourceDescriptor descriptor, SourceNormalizer normalizer, ObjectMapper objectMapper) {
        if (descriptor.kafka() == null) {
            throw new IllegalStateException("Source " + descriptor.id() + " has no kafka configuration");
        }
        if (isBlank(descriptor.kafka().bootstrapServers())) {
            throw new IllegalStateException("Source " + descriptor.id() + " has a blank kafka bootstrapServers");
        }
        if (isBlank(descriptor.kafka().topic())) {
            throw new IllegalStateException("Source " + descriptor.id() + " has a blank kafka topic");
        }
        this.descriptor = descriptor;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getSourceId() {
        return descriptor.id();
    }

    @Override
    public SourceResponse fetchTransactions(Optional<String> cursor) {
        SourceDescriptor.Kafka kafka = descriptor.kafka();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps(kafka))) {

            List<PartitionInfo> partitionInfos = consumer.partitionsFor(kafka.topic());
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                log.info("Kafka source {} has no partitions for topic {} yet, nextCursor=null",
                    descriptor.id(), kafka.topic());
                return new SourceResponse(List.of(), null);
            }

            List<TopicPartition> assigned = partitionInfos.stream()
                .map(info -> new TopicPartition(kafka.topic(), info.partition()))
                .sorted(Comparator.comparingInt(TopicPartition::partition))
                .toList();

            consumer.assign(assigned);
            Map<Integer, Long> cursorOffsets = parseCursor(cursor.orElse(null), assigned);
            seek(consumer, assigned, cursorOffsets, kafka.autoOffsetReset());

            List<CanonicalTransaction> transactions = poll(consumer, kafka);

            String nextCursor = buildCursor(consumer, assigned);

            log.info("Kafka source {} fetched {} record(s) from {} partition(s), nextCursor={}",
                descriptor.id(), transactions.size(), assigned.size(), nextCursor);

            return new SourceResponse(transactions, nextCursor);
        }
    }

    private Properties consumerProps(SourceDescriptor.Kafka kafka) {
        int maxPollRecords = kafka.maxPollRecords() == null ? DEFAULT_MAX_POLL_RECORDS : kafka.maxPollRecords();
        Properties props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, isBlank(kafka.groupId())
            ? "transact-source-" + descriptor.id()
            : kafka.groupId());
        props.setProperty(ConsumerConfig.CLIENT_ID_CONFIG, "transact-source-" + descriptor.id());
        props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            isBlank(kafka.autoOffsetReset()) ? EARLIEST : kafka.autoOffsetReset());
        props.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(maxPollRecords));
        return props;
    }

    private void seek(KafkaConsumer<String, String> consumer, List<TopicPartition> assigned,
                      Map<Integer, Long> cursorOffsets, String autoOffsetReset) {
        List<TopicPartition> withoutCursorEntry = new ArrayList<>();
        for (TopicPartition partition : assigned) {
            Long offset = cursorOffsets.get(partition.partition());
            if (offset != null) {
                consumer.seek(partition, offset);
            } else {
                withoutCursorEntry.add(partition);
            }
        }
        if (withoutCursorEntry.isEmpty()) {
            return;
        }
        String reset = isBlank(autoOffsetReset) ? EARLIEST : autoOffsetReset;
        if (EARLIEST.equals(reset)) {
            consumer.seekToBeginning(withoutCursorEntry);
        } else if (LATEST.equals(reset)) {
            consumer.seekToEnd(withoutCursorEntry);
        } else {
            throw new IllegalStateException(
                "Source " + descriptor.id() + " has unsupported autoOffsetReset '" + reset + "'");
        }
    }

    private List<CanonicalTransaction> poll(KafkaConsumer<String, String> consumer, SourceDescriptor.Kafka kafka) {
        int maxPollRecords = kafka.maxPollRecords() == null ? DEFAULT_MAX_POLL_RECORDS : kafka.maxPollRecords();
        int pollTimeoutMs = kafka.pollTimeoutMs() == null ? DEFAULT_POLL_TIMEOUT_MS : kafka.pollTimeoutMs();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pollTimeoutMs);
        List<CanonicalTransaction> transactions = new ArrayList<>();
        while (transactions.size() < maxPollRecords) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) {
                break;
            }
            ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(remainingMs));
            for (ConsumerRecord<String, String> record : batch) {
                transactions.add(toCanonical(record.value()));
            }
        }
        return transactions;
    }

    private CanonicalTransaction toCanonical(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) {
                return null;
            }
            return normalizer.normalize(node, descriptor.id());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String buildCursor(KafkaConsumer<String, String> consumer, List<TopicPartition> assigned) {
        StringJoiner cursor = new StringJoiner(",");
        for (TopicPartition partition : assigned) {
            cursor.add(partition.partition() + ":" + consumer.position(partition));
        }
        return cursor.toString();
    }

    private Map<Integer, Long> parseCursor(String cursor, List<TopicPartition> assigned) {
        if (isBlank(cursor)) {
            return Map.of();
        }
        Map<Integer, Long> offsets = new LinkedHashMap<>();
        for (String entry : cursor.split(",", -1)) {
            String[] parts = entry.split(":", -1);
            if (parts.length != 2) {
                throw malformedCursor(cursor);
            }
            int partition;
            long offset;
            try {
                partition = Integer.parseInt(parts[0]);
                offset = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                throw malformedCursor(cursor);
            }
            if (partition < 0 || offset < 0 || offsets.containsKey(partition)) {
                throw malformedCursor(cursor);
            }
            if (assigned.stream().noneMatch(tp -> tp.partition() == partition)) {
                throw new IllegalStateException("Source " + descriptor.id()
                    + " cursor references unknown partition " + partition
                    + " for topic " + descriptor.kafka().topic() + " (cursor: '" + cursor + "')");
            }
            offsets.put(partition, offset);
        }
        return offsets;
    }

    private IllegalStateException malformedCursor(String cursor) {
        return new IllegalStateException("Source " + descriptor.id()
            + " has a malformed cursor '" + cursor
            + "' (expected 'partition:offset[,partition:offset...]')");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
