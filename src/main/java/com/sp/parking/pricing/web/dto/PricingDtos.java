package com.sp.parking.pricing.web.dto;

import com.sp.parking.pricing.domain.model.PricingModel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PricingDtos {
  private PricingDtos() {}
  public record PricingRequest(UUID ticketUuid, @NotBlank String vehicleType, Instant checkedInAt, boolean lostTicket) {}
  public record PricingResponse(UUID ticketUuid, long billableMinutes, BigDecimal subtotal, BigDecimal surcharges, BigDecimal discounts, BigDecimal total) {}
  public record RatePlanRuleRequest(
    @NotBlank String vehicleType,
    @NotBlank String displayName,
    @NotNull PricingModel pricingModel,
    @DecimalMin("0.0") BigDecimal flatAmount,
    @DecimalMin("0.0") BigDecimal hourlyAmount,
    @DecimalMin("0.0") BigDecimal additionalHourAmount,
    @DecimalMin("0.0") BigDecimal fractionAmount,
    @Min(1) Integer fractionMinutes,
    @DecimalMin("0.0") BigDecimal dayAmount,
    boolean applyDailyCap,
    @Min(1) Integer rateIncreaseAfterHours,
    @DecimalMin("0.0") BigDecimal rateIncreaseAmount,
    @DecimalMin("0.0") BigDecimal lostTicketFee,
    Boolean active
  ) {}
  public record RatePlanRuleResponse(
    Long id,
    UUID tenantId,
    String vehicleType,
    String displayName,
    PricingModel pricingModel,
    BigDecimal flatAmount,
    BigDecimal hourlyAmount,
    BigDecimal additionalHourAmount,
    BigDecimal fractionAmount,
    int fractionMinutes,
    BigDecimal dayAmount,
    boolean applyDailyCap,
    Integer rateIncreaseAfterHours,
    BigDecimal rateIncreaseAmount,
    BigDecimal lostTicketFee,
    boolean active
  ) {}
  public record RatePlanCatalogResponse(List<RatePlanRuleResponse> rules) {}
}
