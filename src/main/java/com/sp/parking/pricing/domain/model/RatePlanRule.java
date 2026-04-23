package com.sp.parking.pricing.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RatePlanRule {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(nullable = false)
  private UUID tenantId;
  @Column(nullable = false)
  private String vehicleType;
  @Column(nullable = false)
  private String displayName;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PricingModel pricingModel;
  private BigDecimal flatAmount;
  private BigDecimal hourlyAmount;
  private BigDecimal additionalHourAmount;
  private BigDecimal fractionAmount;
  private int fractionMinutes;
  private BigDecimal dayAmount;
  private boolean applyDailyCap;
  private Integer rateIncreaseAfterHours;
  private BigDecimal rateIncreaseAmount;
  private BigDecimal lostTicketFee;
  private boolean active;
}
