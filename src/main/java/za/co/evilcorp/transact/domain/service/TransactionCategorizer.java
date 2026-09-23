package za.co.evilcorp.transact.domain.service;

import za.co.evilcorp.transact.domain.model.CanonicalTransaction;
import za.co.evilcorp.transact.domain.model.Category;

public interface TransactionCategorizer {
    Category categorize(CanonicalTransaction transaction);
}
