package com.example.paymentapi.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Projection interface for paginated payment list queries.
 * Only the columns actually shown in the list view are fetched — avoids
 * loading description, metadata, and audit fields for every row.
 */
public interface PaymentSummary {
    String getId();
    String getSourceAccount();
    String getDestinationAccount();
    BigDecimal getAmount();
    String getCurrency();
    String getStatus();
    LocalDateTime getCreatedAt();
    LocalDateTime getUpdatedAt();
}
