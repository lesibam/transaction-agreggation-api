package za.co.evilcorp.transact.domain.model;

import lombok.*;

@Value
@Builder
public class Category {
    String code;
    Integer version;
    String ruleId;
}
