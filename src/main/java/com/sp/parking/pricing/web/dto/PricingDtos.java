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
  /**
   * Resultado del cobro. Desglose: dias completos cobrados con el valor dia, si se cobro la primera hora,
   * minutos despues de la primera hora (cobrados proporcionalmente al valor de la hora adicional) y su valor.
   */
  public record PricingResponse(
    UUID ticketUuid,
    long billableMinutes,
    BigDecimal subtotal,
    BigDecimal surcharges,
    BigDecimal discounts,
    BigDecimal total,
    long days,
    boolean firstHourCharged,
    long additionalMinutes,
    BigDecimal additionalAmount
  ) {}
  public record RatePlanRuleRequest(
    @NotBlank String vehicleType,
    @NotBlank String displayName,
    @NotNull PricingModel pricingModel,
    @DecimalMin(value = "0.0", message = "La tarifa plana no puede ser negativa") BigDecimal flatAmount,
    @DecimalMin(value = "0.0", message = "El valor de la primera hora no puede ser negativo") BigDecimal hourlyAmount,
    @DecimalMin(value = "0.0", message = "El valor de la hora adicional no puede ser negativo") BigDecimal additionalHourAmount,
    @DecimalMin("0.0") BigDecimal fractionAmount,
    @Min(1) Integer fractionMinutes,
    @DecimalMin(value = "0.0", message = "El valor del día no puede ser negativo") BigDecimal dayAmount,
    boolean applyDailyCap,
    @Min(1) Integer rateIncreaseAfterHours,
    @DecimalMin("0.0") BigDecimal rateIncreaseAmount,
    @DecimalMin(value = "0.0", message = "El valor del ticket perdido no puede ser negativo") BigDecimal lostTicketFee,
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
