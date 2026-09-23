package za.co.evilcorp.transact.application.dto;

import java.util.List;

public record TransactionListResult(List<TransactionView> data, MetaInfo meta) {
}
