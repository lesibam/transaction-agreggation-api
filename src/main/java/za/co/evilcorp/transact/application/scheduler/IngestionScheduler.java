package za.co.evilcorp.transact.application.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.evilcorp.transact.application.service.IngestionService;

@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionScheduler {

    private final IngestionService ingestionService;

    @Scheduled(fixedRateString = "${app.ingestion.interval-ms:60000}") // Default 60s (matches application.yml)
    @SchedulerLock(name = "ingestAllSources", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void scheduleIngestion() {
        log.info("Triggering scheduled ingestion cycle...");
        ingestionService.ingestAllSources();
    }
}
