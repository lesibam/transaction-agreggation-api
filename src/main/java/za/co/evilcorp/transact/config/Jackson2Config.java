package za.co.evilcorp.transact.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4 auto-configures Jackson 3 (tools.jackson). Production code still
 * injects Jackson 2 (com.fasterxml) in SecurityConfig and KafkaConsumerConfig,
 * so an explicit Jackson 2 mapper with java.time support is required.
 */
@Configuration
public class Jackson2Config {

    @Bean
    public ObjectMapper jackson2ObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // JSON numbers parse to BigDecimal (not double) so monetary fields
        // survive normalization without binary rounding artifacts.
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        return mapper;
    }
}
