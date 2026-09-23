package za.co.evilcorp.transact;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class TransactApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactApplication.class, args);
    }
}
