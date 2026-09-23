package za.co.evilcorp.transact.api.dto;

import java.util.List;

public record TransactionResponseDto(List<TransactionDto> data, MetaDto meta) {
}
