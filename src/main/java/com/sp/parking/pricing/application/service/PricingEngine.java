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

@Service
@RequiredArgsConstructor
public class PricingEngine {
  private final RatePlanRuleRepository ruleRepository;

  @Transactional(readOnly = true)
  public PricingResponse calculate(UUID tenantId, PricingRequest request) {
    String normalizedVehicleType = request.vehicleType().toUpperCase();
    RatePlanRule rule = ruleRepository.findFirstByTenantIdAndVehicleTypeAndActiveTrue(tenantId, normalizedVehicleType)
      .orElseGet(() -> fallback(tenantId, normalizedVehicleType));
    normalize(rule);
    Instant in = request.checkedInAt() == null ? Instant.now() : request.checkedInAt();
    long minutes = Math.max(1, Duration.between(in, Instant.now()).toMinutes());
    BigDecimal subtotal = calculateTimeAmount(rule, minutes);
    BigDecimal surcharge = request.lostTicket() ? rule.getLostTicketFee() : BigDecimal.ZERO;
    BigDecimal total = subtotal.add(surcharge).setScale(0, RoundingMode.HALF_UP);
    return new PricingResponse(request.ticketUuid(), minutes, subtotal, surcharge, BigDecimal.ZERO, total);
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
      throw new ResponseStatusException(BAD_REQUEST, "Vehicle type in path and body must match");
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
    rule.setFractionAmount(scale(request.fractionAmount()));
    rule.setFractionMinutes(request.fractionMinutes() == null ? 30 : request.fractionMinutes());
    rule.setDayAmount(scale(request.dayAmount()));
    rule.setApplyDailyCap(request.applyDailyCap());
    rule.setRateIncreaseAfterHours(request.rateIncreaseAfterHours());
    rule.setRateIncreaseAmount(scale(request.rateIncreaseAmount()));
    rule.setLostTicketFee(scale(request.lostTicketFee()));
    rule.setActive(request.active() == null || request.active());
    normalize(rule);
    return toResponse(ruleRepository.save(rule));
  }

  private BigDecimal calculateTimeAmount(RatePlanRule rule, long minutes) {
    if (rule.getPricingModel() == PricingModel.FLAT) {
      return scale(rule.getFlatAmount());
    }
    if (minutes >= 24 * 60 && rule.getDayAmount() != null && rule.getDayAmount().compareTo(BigDecimal.ZERO) > 0) {
      long days = minutes / (24 * 60);
      long remaining = minutes % (24 * 60);
      return rule.getDayAmount().multiply(BigDecimal.valueOf(days)).add(calculateTimeAmount(rule, remaining == 0 ? 1 : remaining));
    }
    long fullHours = minutes / 60;
    long remainingMinutes = minutes % 60;
    if (fullHours == 0) {
      return applyCap(rule, rule.getHourlyAmount());
    }
    BigDecimal total = firstAndAdditionalHourTotal(rule, fullHours);
    if (remainingMinutes > 0) {
      long fractions = (long) Math.ceil(remainingMinutes / (double) Math.max(1, rule.getFractionMinutes()));
      total = total.add(rule.getFractionAmount().multiply(BigDecimal.valueOf(fractions)));
    }
    return applyCap(rule, total);
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
      case "MOTORCYCLE" -> rule(tenantId, type, "Moto", 1500, 800, 10_000);
      case "HEAVY" -> rule(tenantId, type, "Carga Pesada", 4500, 2500, 35_000);
      case "BICYCLE" -> rule(tenantId, type, "Bicicleta", 1000, 500, 5_000);
      default -> rule(tenantId, type, "Carro Normal", 2500, 1500, 18_000);
    };
  }

  private RatePlanRule rule(UUID tenantId, String type, String displayName, int hour, int fraction, int day) {
    return RatePlanRule.builder()
      .tenantId(tenantId)
      .vehicleType(type)
      .displayName(displayName)
      .pricingModel(PricingModel.PROGRESSIVE)
      .flatAmount(BigDecimal.valueOf(day))
      .hourlyAmount(BigDecimal.valueOf(hour))
      .additionalHourAmount(BigDecimal.valueOf(hour))
      .fractionAmount(BigDecimal.valueOf(fraction))
      .fractionMinutes(30)
      .dayAmount(BigDecimal.valueOf(day))
      .applyDailyCap(false)
      .rateIncreaseAfterHours(null)
      .rateIncreaseAmount(BigDecimal.ZERO)
      .lostTicketFee(BigDecimal.valueOf(20_000))
      .active(true)
      .build();
  }

  private BigDecimal firstAndAdditionalHourTotal(RatePlanRule rule, long fullHours) {
    BigDecimal total = rule.getHourlyAmount();
    if (fullHours <= 1) {
      return applyIncrease(rule, 1, total);
    }
    for (int hourIndex = 2; hourIndex <= fullHours; hourIndex++) {
      BigDecimal amount = rule.getAdditionalHourAmount();
      total = total.add(applyIncrease(rule, hourIndex, amount));
    }
    return total;
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
      throw new ResponseStatusException(BAD_REQUEST, "Flat amount is required for FLAT pricing");
    }
    if (request.pricingModel() == PricingModel.PROGRESSIVE && request.hourlyAmount() == null) {
      throw new ResponseStatusException(BAD_REQUEST, "Hourly amount is required for PROGRESSIVE pricing");
    }
    if (request.pricingModel() == PricingModel.PROGRESSIVE && request.additionalHourAmount() == null) {
      throw new ResponseStatusException(BAD_REQUEST, "Additional hour amount is required for PROGRESSIVE pricing");
    }
    if (request.pricingModel() == PricingModel.PROGRESSIVE && request.fractionAmount() == null) {
      throw new ResponseStatusException(BAD_REQUEST, "Fraction amount is required for PROGRESSIVE pricing");
    }
  }

  private BigDecimal scale(BigDecimal value) {
    return value == null ? null : value.setScale(0, RoundingMode.HALF_UP);
  }
}
