package com.sp.parking.pricing.application.service;

import com.sp.parking.pricing.domain.model.PricingModel;
import com.sp.parking.pricing.domain.model.RatePlanRule;
import com.sp.parking.pricing.domain.repository.RatePlanRuleRepository;
import com.sp.parking.pricing.web.dto.PricingDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
import java.time.*;
import java.util.List;
import java.util.UUID;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Cobro por tiempo:
 * - La primera hora (o cualquier fraccion de ella) se cobra completa con el valor de primera hora.
 * - Despues de la primera hora, cada minuto se cobra proporcional al valor de la hora adicional
 *   (valor hora adicional / 60 por minuto), redondeado al peso.
 * - Con valor dia, cada bloque de 24 h se cobra con ese valor y el resto se calcula igual que arriba.
 */
@Service
@RequiredArgsConstructor
public class PricingEngine {
  private static final long MINUTES_PER_DAY = 24L * 60;
  private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

  private final RatePlanRuleRepository ruleRepository;

  /** Valor del tiempo y su desglose: minutos despues de la primera hora y lo que se cobro por ellos. */
  record Charge(BigDecimal amount, long days, boolean firstHourCharged, long additionalMinutes, BigDecimal additionalAmount) {}

  @Transactional(readOnly = true)
  public PricingResponse calculate(UUID tenantId, PricingRequest request) {
    String normalizedVehicleType = request.vehicleType().toUpperCase();
    RatePlanRule rule = ruleRepository.findFirstByTenantIdAndVehicleTypeAndActiveTrue(tenantId, normalizedVehicleType)
      .orElseGet(() -> fallback(tenantId, normalizedVehicleType));
    normalize(rule);
    Instant in = request.checkedInAt() == null ? Instant.now() : request.checkedInAt();
    long minutes = Math.max(1, Duration.between(in, Instant.now()).toMinutes());
    Charge charge = charge(rule, minutes);
    BigDecimal subtotal = charge.amount().setScale(0, RoundingMode.HALF_UP);
    BigDecimal surcharge = request.lostTicket() ? rule.getLostTicketFee() : BigDecimal.ZERO;
    BigDecimal total = subtotal.add(surcharge).setScale(0, RoundingMode.HALF_UP);
    return new PricingResponse(
      request.ticketUuid(), minutes, subtotal, surcharge, BigDecimal.ZERO, total,
      charge.days(), charge.firstHourCharged(), charge.additionalMinutes(), charge.additionalAmount()
    );
  }

  @Transactional(readOnly = true)
  public RatePlanCatalogResponse getRules(UUID tenantId) {
    List<RatePlanRule> savedRules = ruleRepository.findByTenantIdAndActiveTrueOrderByVehicleTypeAsc(tenantId);
    return new RatePlanCatalogResponse(List.of("CAR", "MOTORCYCLE", "HEAVY", "BICYCLE").stream()
      .map(vehicleType -> savedRules.stream()
        .filter(rule -> vehicleType.equalsIgnoreCase(rule.getVehicleType()))
        .findFirst()
        .orElseGet(() -> fallback(tenantId, vehicleType)))
      .map(this::toResponse)
      .toList());
  }

  @Transactional
  public RatePlanRuleResponse upsertRule(UUID tenantId, String vehicleType, RatePlanRuleRequest request) {
    if (!vehicleType.equalsIgnoreCase(request.vehicleType())) {
      throw new ResponseStatusException(BAD_REQUEST, "El tipo de vehículo de la ruta y del cuerpo no coinciden");
    }
    validateRule(request);
    String normalizedVehicleType = vehicleType.toUpperCase();
    RatePlanRule rule = ruleRepository.findFirstByTenantIdAndVehicleTypeAndActiveTrue(tenantId, normalizedVehicleType)
      .orElseGet(() -> RatePlanRule.builder().tenantId(tenantId).vehicleType(normalizedVehicleType).build());
    rule.setVehicleType(normalizedVehicleType);
    rule.setDisplayName(request.displayName());
    rule.setPricingModel(request.pricingModel());
    rule.setFlatAmount(scale(request.flatAmount()));
    rule.setHourlyAmount(scale(request.hourlyAmount()));
    rule.setAdditionalHourAmount(scale(request.additionalHourAmount()));
    if (request.fractionAmount() != null) rule.setFractionAmount(scale(request.fractionAmount()));
    if (request.fractionMinutes() != null) rule.setFractionMinutes(request.fractionMinutes());
    rule.setDayAmount(scale(request.dayAmount()));
    rule.setApplyDailyCap(request.applyDailyCap());
    rule.setRateIncreaseAfterHours(request.rateIncreaseAfterHours());
    rule.setRateIncreaseAmount(scale(request.rateIncreaseAmount()));
    rule.setLostTicketFee(scale(request.lostTicketFee()));
    rule.setActive(request.active() == null || request.active());
    normalize(rule);
    return toResponse(ruleRepository.save(rule));
  }

  Charge charge(RatePlanRule rule, long minutes) {
    if (rule.getPricingModel() == PricingModel.FLAT) {
      return new Charge(scale(rule.getFlatAmount()), 0, false, 0, BigDecimal.ZERO);
    }
    long days = 0;
    BigDecimal dayTotal = BigDecimal.ZERO;
    long rest = minutes;
    BigDecimal dayAmount = rule.getDayAmount();
    if (minutes >= MINUTES_PER_DAY && dayAmount != null && dayAmount.signum() > 0) {
      days = minutes / MINUTES_PER_DAY;
      rest = minutes % MINUTES_PER_DAY;
      dayTotal = dayAmount.multiply(BigDecimal.valueOf(days));
      if (rest == 0) return new Charge(dayTotal, days, false, 0, BigDecimal.ZERO);
    }
    long additionalMinutes = Math.max(0, rest - 60);
    BigDecimal additionalAmount = proportionalAmount(rule, additionalMinutes);
    BigDecimal hours = applyCap(rule, applyIncrease(rule, 1, rule.getHourlyAmount()).add(additionalAmount));
    return new Charge(dayTotal.add(hours), days, true, additionalMinutes, additionalAmount);
  }

  /**
   * Valor de los minutos despues de la primera hora: cada hora completa con el valor de hora adicional
   * y los minutos sueltos proporcionalmente (valor / 60 por minuto). Se redondea al peso al final.
   */
  private BigDecimal proportionalAmount(RatePlanRule rule, long additionalMinutes) {
    if (additionalMinutes <= 0) return BigDecimal.ZERO;
    long fullHours = additionalMinutes / 60;
    long looseMinutes = additionalMinutes % 60;
    BigDecimal amount = BigDecimal.ZERO;
    for (long hourNumber = 2; hourNumber <= fullHours + 1; hourNumber++) {
      amount = amount.add(applyIncrease(rule, hourNumber, rule.getAdditionalHourAmount()));
    }
    if (looseMinutes > 0) {
      BigDecimal hourRate = applyIncrease(rule, fullHours + 2, rule.getAdditionalHourAmount());
      amount = amount.add(hourRate.multiply(BigDecimal.valueOf(looseMinutes)).divide(SIXTY, 4, RoundingMode.HALF_UP));
    }
    return amount.setScale(0, RoundingMode.HALF_UP);
  }

  private void normalize(RatePlanRule rule) {
    RatePlanRule defaults = fallback(rule.getTenantId(), rule.getVehicleType());
    if (rule.getDisplayName() == null || rule.getDisplayName().isBlank()) rule.setDisplayName(defaults.getDisplayName());
    if (rule.getPricingModel() == null) rule.setPricingModel(defaults.getPricingModel());
    if (rule.getFlatAmount() == null) rule.setFlatAmount(defaults.getFlatAmount());
    if (rule.getHourlyAmount() == null) rule.setHourlyAmount(defaults.getHourlyAmount());
    if (rule.getAdditionalHourAmount() == null) rule.setAdditionalHourAmount(rule.getHourlyAmount());
    if (rule.getFractionAmount() == null) rule.setFractionAmount(defaults.getFractionAmount());
    if (rule.getFractionMinutes() <= 0) rule.setFractionMinutes(30);
    if (rule.getDayAmount() == null) rule.setDayAmount(defaults.getDayAmount());
    if (rule.getRateIncreaseAmount() == null) rule.setRateIncreaseAmount(BigDecimal.ZERO);
    if (rule.getLostTicketFee() == null) rule.setLostTicketFee(defaults.getLostTicketFee());
  }

  private RatePlanRule fallback(UUID tenantId, String type) {
    return switch (type) {
      case "MOTORCYCLE" -> rule(tenantId, type, "Moto", 1500, 10_000);
      case "HEAVY" -> rule(tenantId, type, "Carga Pesada", 4500, 35_000);
      case "BICYCLE" -> rule(tenantId, type, "Bicicleta", 1000, 5_000);
      default -> rule(tenantId, type, "Carro Normal", 2500, 18_000);
    };
  }

  private RatePlanRule rule(UUID tenantId, String type, String displayName, int hour, int day) {
    return RatePlanRule.builder()
      .tenantId(tenantId)
      .vehicleType(type)
      .displayName(displayName)
      .pricingModel(PricingModel.PROGRESSIVE)
      .flatAmount(BigDecimal.valueOf(day))
      .hourlyAmount(BigDecimal.valueOf(hour))
      .additionalHourAmount(BigDecimal.valueOf(hour))
      .fractionAmount(BigDecimal.ZERO)
      .fractionMinutes(30)
      .dayAmount(BigDecimal.valueOf(day))
      .applyDailyCap(false)
      .rateIncreaseAfterHours(null)
      .rateIncreaseAmount(BigDecimal.ZERO)
      .lostTicketFee(BigDecimal.valueOf(20_000))
      .active(true)
      .build();
  }

  private BigDecimal applyIncrease(RatePlanRule rule, long hourNumber, BigDecimal baseAmount) {
    if (rule.getRateIncreaseAfterHours() == null || rule.getRateIncreaseAfterHours() <= 0) {
      return baseAmount;
    }
    if (hourNumber <= rule.getRateIncreaseAfterHours()) {
      return baseAmount;
    }
    return baseAmount.add(rule.getRateIncreaseAmount());
  }

  private BigDecimal applyCap(RatePlanRule rule, BigDecimal total) {
    if (rule.isApplyDailyCap() && rule.getDayAmount() != null && rule.getDayAmount().compareTo(BigDecimal.ZERO) > 0) {
      return total.min(rule.getDayAmount());
    }
    return total;
  }

  private RatePlanRuleResponse toResponse(RatePlanRule rule) {
    normalize(rule);
    return new RatePlanRuleResponse(
      rule.getId(),
      rule.getTenantId(),
      rule.getVehicleType(),
      rule.getDisplayName(),
      rule.getPricingModel(),
      scale(rule.getFlatAmount()),
      scale(rule.getHourlyAmount()),
      scale(rule.getAdditionalHourAmount()),
      scale(rule.getFractionAmount()),
      rule.getFractionMinutes(),
      scale(rule.getDayAmount()),
      rule.isApplyDailyCap(),
      rule.getRateIncreaseAfterHours(),
      scale(rule.getRateIncreaseAmount()),
      scale(rule.getLostTicketFee()),
      rule.isActive()
    );
  }

  private void validateRule(RatePlanRuleRequest request) {
    if (request.pricingModel() == PricingModel.FLAT && request.flatAmount() == null) {
      throw new ResponseStatusException(BAD_REQUEST, "Ingresa el valor de la tarifa plana");
    }
    if (request.pricingModel() == PricingModel.PROGRESSIVE && request.hourlyAmount() == null) {
      throw new ResponseStatusException(BAD_REQUEST, "Ingresa el valor de la primera hora");
    }
    if (request.pricingModel() == PricingModel.PROGRESSIVE && request.additionalHourAmount() == null) {
      throw new ResponseStatusException(BAD_REQUEST, "Ingresa el valor de la hora adicional");
    }
  }

  private BigDecimal scale(BigDecimal value) {
    return value == null ? null : value.setScale(0, RoundingMode.HALF_UP);
  }
}
