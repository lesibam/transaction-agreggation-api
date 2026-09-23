package za.co.evilcorp.transact.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.TransactionDirection;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * jackson-datatype-jsr310 and ParameterNamesModule are not on the classpath
 * (reported). Minimal ISO-8601 serializers for java.time plus a builder-based
 * deserializer for Lombok @Value CanonicalTransaction (no default ctor /
 * @JsonCreator — production gap reported).
 */
final class JavaTimeJacksonSupport {

    private JavaTimeJacksonSupport() {
    }

    static Module javaTimeModule() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(OffsetDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(OffsetDateTime.class, new JsonDeserializer<>() {
            @Override
            public OffsetDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return OffsetDateTime.parse(p.getValueAsString());
            }
        });
        module.addSerializer(ZonedDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(ZonedDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(ZonedDateTime.class, new JsonDeserializer<>() {
            @Override
            public ZonedDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return ZonedDateTime.parse(p.getValueAsString());
            }
        });
        module.addSerializer(LocalDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(LocalDateTime.class, new JsonDeserializer<>() {
            @Override
            public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return LocalDateTime.parse(p.getValueAsString());
            }
        });
        module.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override
            public void serialize(LocalDate value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override
            public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return LocalDate.parse(p.getValueAsString());
            }
        });
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return Instant.parse(p.getValueAsString());
            }
        });
        module.addDeserializer(CanonicalTransaction.class, new JsonDeserializer<>() {
            @Override
            public CanonicalTransaction deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                JsonNode n = p.readValueAsTree();
                CanonicalTransaction.CanonicalTransactionBuilder b = CanonicalTransaction.builder();
                if (n.hasNonNull("id")) {
                    b.id(UUID.fromString(n.get("id").asText()));
                }
                if (n.hasNonNull("accountId")) {
                    b.accountId(UUID.fromString(n.get("accountId").asText()));
                }
                if (n.hasNonNull("amount")) {
                    b.amount(new BigDecimal(n.get("amount").asText()));
                }
                if (n.hasNonNull("currency")) {
                    b.currency(n.get("currency").asText());
                }
                if (n.hasNonNull("direction")) {
                    b.direction(TransactionDirection.valueOf(n.get("direction").asText()));
                }
                if (n.hasNonNull("transactionDate")) {
                    b.transactionDate(OffsetDateTime.parse(n.get("transactionDate").asText()));
                }
                if (n.hasNonNull("postedAt")) {
                    b.postedAt(OffsetDateTime.parse(n.get("postedAt").asText()));
                }
                if (n.hasNonNull("description")) {
                    b.description(n.get("description").asText());
                }
                if (n.hasNonNull("merchantName")) {
                    b.merchantName(n.get("merchantName").asText());
                }
                if (n.hasNonNull("categoryCode")) {
                    b.categoryCode(n.get("categoryCode").asText());
                }
                if (n.hasNonNull("categoryVersion")) {
                    b.categoryVersion(n.get("categoryVersion").asInt());
                }
                if (n.hasNonNull("ruleId")) {
                    b.ruleId(n.get("ruleId").asText());
                }
                if (n.hasNonNull("normalizationVersion")) {
                    b.normalizationVersion(n.get("normalizationVersion").asText());
                }
                if (n.hasNonNull("sourceId")) {
                    b.sourceId(n.get("sourceId").asText());
                }
                if (n.hasNonNull("sourceTransactionId")) {
                    b.sourceTransactionId(n.get("sourceTransactionId").asText());
                }
                if (n.hasNonNull("ingestedAt")) {
                    b.ingestedAt(OffsetDateTime.parse(n.get("ingestedAt").asText()));
                }
                return b.build();
            }
        });
        return module;
    }
}
