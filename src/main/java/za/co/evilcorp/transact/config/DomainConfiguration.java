package za.co.evilcorp.transact.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import za.co.evilcorp.transact.domain.service.RuleBasedCategorizer;
import za.co.evilcorp.transact.domain.service.TransactionCategorizer;

@Configuration
public class DomainConfiguration {

    @Bean
    public TransactionCategorizer ruleBasedCategorizer() {
        return new RuleBasedCategorizer();
    }
}
