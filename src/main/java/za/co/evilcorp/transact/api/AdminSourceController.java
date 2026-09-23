package za.co.evilcorp.transact.api;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.evilcorp.transact.api.dto.SourceHealthDto;
import za.co.evilcorp.transact.api.dto.SourceStatusResponse;
import za.co.evilcorp.transact.application.service.TransactionQueryService;

import java.util.List;

@RestController
@RequestMapping("/v1/admin")
@Validated
public class AdminSourceController {

    private final TransactionQueryService queryService;

    public AdminSourceController(TransactionQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/sources")
    public ResponseEntity<SourceStatusResponse> getSources() {
        List<SourceHealthDto> sources = queryService.getSourceStatuses().stream()
                .map(s -> new SourceHealthDto(
                        s.id(),
                        s.name(),
                        s.status(),
                        s.lastSuccessfulSync(),
                        s.lastAttempt(),
                        s.failureCount(),
                        s.freshnessSeconds(),
                        s.freshnessStatus()))
                .toList();
        return ResponseEntity.ok(new SourceStatusResponse(sources));
    }
}
