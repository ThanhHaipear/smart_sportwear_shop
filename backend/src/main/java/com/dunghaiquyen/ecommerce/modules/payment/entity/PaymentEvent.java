package com.dunghaiquyen.ecommerce.modules.payment.entity;

import com.dunghaiquyen.ecommerce.common.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "payment_events")
public class PaymentEvent extends AbstractEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProvider provider;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "provider_event_key", nullable = false, length = 64, updatable = false)
    private String providerEventKey;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false)
    private boolean verified;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_redacted", columnDefinition = "jsonb")
    private Map<String, Object> payloadRedacted;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();
}
