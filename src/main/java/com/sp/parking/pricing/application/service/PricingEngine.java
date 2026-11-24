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
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

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
    // Tambien cobra tipos eliminados: un ticket abierto antes de eliminarlo conserva su tarifa.
    RatePlanRule rule = latest(tenantId, normalizedVehicleType)
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

  /**
   * Tipos de vehiculo del parqueadero con su tarifa: los 4 basicos (salvo los eliminados) y despues los
   * que agrego el parqueadero, en el orden en que se crearon.
   */
  @Transactional(readOnly = true)
  public RatePlanCatalogResponse getRules(UUID tenantId) {
    Map<String, RatePlanRule> latest = latestByType(tenantId);
    List<RatePlanRule> catalog = new ArrayList<>();
    for (String base : BASE_TYPES) {
      RatePlanRule saved = latest.get(base);
      if (saved == null) catalog.add(fallback(tenantId, base));
      else if (saved.isActive()) catalog.add(saved);
    }
    latest.values().stream()
      .filter(rule -> rule.isActive() && !BASE_TYPES.contains(rule.getVehicleType()))
      .sorted(Comparator.comparing(RatePlanRule::getId))
      .forEach(catalog::add);
    return new RatePlanCatalogResponse(catalog.stream().map(this::toResponse).toList());
  }

  /** Agrega un tipo de vehiculo propio del parqueadero ("Camioneta", "Patineta"...) con su tarifa. */
  @Transactional
  public RatePlanRuleResponse createType(UUID tenantId, VehicleTypeCreateRequest request) {
    String name = request.displayName().trim();
    String category = category(request.category());
    boolean duplicated = getRules(tenantId).rules().stream().anyMatch(rule -> rule.displayName().equalsIgnoreCase(name));
    if (duplicated) throw new ResponseStatusException(CONFLICT, "Ya existe un tipo de vehículo llamado \"" + name + "\"");
    validateAmounts(request.pricingModel(), request.flatAmount(), request.hourlyAmount(), request.additionalHourAmount());
    RatePlanRule rule = RatePlanRule.builder()
      .tenantId(tenantId)
      .vehicleType(uniqueCode(tenantId, name))
      .displayName(name)
      .category(category)
      .pricingModel(request.pricingModel())
      .flatAmount(scale(request.flatAmount()))
      .hourlyAmount(scale(request.hourlyAmount()))
      .additionalHourAmount(scale(request.additionalHourAmount() == null ? request.hourlyAmount() : request.additionalHourAmount()))
      .fractionAmount(BigDecimal.ZERO)
      .fractionMinutes(30)
      .dayAmount(scale(request.dayAmount()))
      .applyDailyCap(request.applyDailyCap())
      .rateIncreaseAmount(BigDecimal.ZERO)
      .lostTicketFee(scale(request.lostTicketFee()))
      .active(true)
      .build();
    normalize(rule);
    return toResponse(ruleRepository.save(rule));
  }

  /**
   * Elimina (desactiva) un tipo de vehiculo. Los tickets, cobros y reportes anteriores conservan su nombre.
   * Debe quedar al menos un tipo, y no puede estar en uso: eso lo verifica quien llama con parking-operation-service.
   */
  @Transactional
  public RatePlanRuleResponse deactivateType(UUID tenantId, String vehicleType) {
    String code = vehicleType.toUpperCase();
    List<RatePlanRuleResponse> active = getRules(tenantId).rules();
    if (active.stream().noneMatch(rule -> rule.vehicleType().equals(code))) {
      throw new ResponseStatusException(NOT_FOUND, "Ese tipo de vehículo no existe o ya fue eliminado");
    }
    if (active.size() <= 1) {
      throw new ResponseStatusException(CONFLICT, "Debe quedar al menos un tipo de vehículo");
    }
    RatePlanRule rule = latest(tenantId, code).orElseGet(() -> fallback(tenantId, code));
    normalize(rule);
    rule.setActive(false);
    return toResponse(ruleRepository.save(rule));
  }

  @Transactional
  public RatePlanRuleResponse upsertRule(UUID tenantId, String vehicleType, RatePlanRuleRequest request) {
    if (!vehicleType.equalsIgnoreCase(request.vehicleType())) {
      throw new ResponseStatusException(BAD_REQUEST, "El tipo de vehículo de la ruta y del cuerpo no coinciden");
    }
    validateRule(request);
    String normalizedVehicleType = vehicleType.toUpperCase();
    RatePlanRule rule = latest(tenantId, normalizedVehicleType)
      .orElseGet(() -> RatePlanRule.builder().tenantId(tenantId).vehicleType(normalizedVehicleType).build());
    if (rule.getId() != null && !rule.isActive()) {
      throw new ResponseStatusException(CONFLICT, "Este tipo de vehículo fue eliminado; créalo de nuevo si lo necesitas");
    }
    if (rule.getId() == null && !BASE_TYPES.contains(normalizedVehicleType)) {
      throw new ResponseStatusException(NOT_FOUND, "Ese tipo de vehículo no existe");
    }
    // La categoria solo se puede cambiar en los tipos agregados; los basicos la tienen fija.
    if (!BASE_TYPES.contains(normalizedVehicleType) && request.category() != null && !request.category().isBlank()) {
      rule.setCategory(category(request.category()));
    }
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
    // Eliminar un tipo se hace con deactivateType (que verifica que no este en uso), no editando la tarifa.
    rule.setActive(true);
    normalize(rule);
    return toResponse(ruleRepository.save(rule));
  }

  /** Los 4 tipos con los que nace todo parqueadero. */
  static final List<String> BASE_TYPES = List.of("CAR", "MOTORCYCLE", "HEAVY", "BICYCLE");

  /** Categoria de los tipos basicos: moto y bicicleta usan espacios de moto; carro y carga, de carro. */
  static String baseCategory(String vehicleType) {
    return "MOTORCYCLE".equals(vehicleType) || "BICYCLE".equals(vehicleType) ? "MOTORCYCLE" : "CAR";
  }

  private static String category(String value) {
    String category = value == null ? "" : value.trim().toUpperCase();
    if (!category.equals("CAR") && !category.equals("MOTORCYCLE")) {
      throw new ResponseStatusException(BAD_REQUEST, "Indica si el tipo se parquea como carro o como moto");
    }
    return category;
  }

  private Optional<RatePlanRule> latest(UUID tenantId, String vehicleType) {
    return Optional.ofNullable(latestByType(tenantId).get(vehicleType));
  }

  /** La regla mas reciente de cada tipo (por si quedaron varias de versiones anteriores). */
  private Map<String, RatePlanRule> latestByType(UUID tenantId) {
    Map<String, RatePlanRule> latest = new LinkedHashMap<>();
    for (RatePlanRule rule : ruleRepository.findByTenantIdOrderByIdAsc(tenantId)) {
      latest.put(rule.getVehicleType().toUpperCase(), rule);
    }
    return latest;
  }

  /** Codigo interno a partir del nombre: "Camioneta 4x4" -> CAMIONETA_4X4 (unico, incluso frente a tipos eliminados). */
  private String uniqueCode(UUID tenantId, String displayName) {
    String base = Normalizer.normalize(displayName, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
      .toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    if (base.isEmpty()) base = "TIPO";
    base = base.substring(0, Math.min(24, base.length()));
    Set<String> used = new HashSet<>(BASE_TYPES);
    ruleRepository.findByTenantIdOrderByIdAsc(tenantId).forEach(rule -> used.add(rule.getVehicleType().toUpperCase()));
    String code = base;
    for (int i = 2; used.contains(code); i++) code = base + "_" + i;
    return code;
  }

  private void validateAmounts(PricingModel model, BigDecimal flat, BigDecimal hourly, BigDecimal additional) {
    if (model == PricingModel.FLAT && (flat == null || flat.signum() <= 0)) {
      throw new ResponseStatusException(BAD_REQUEST, "Ingresa el valor de la tarifa plana");
    }
    if (model == PricingModel.PROGRESSIVE && (hourly == null || hourly.signum() <= 0)) {
      throw new ResponseStatusException(BAD_REQUEST, "Ingresa el valor de la primera hora");
    }
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
      rule.isActive(),
      rule.getCategory() != null ? rule.getCategory() : baseCategory(rule.getVehicleType()),
      !BASE_TYPES.contains(rule.getVehicleType())
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
