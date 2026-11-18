package com.sp.parking.pricing.infraestructure.config;

import com.sp.parking.pricing.domain.model.PricingModel;
import com.sp.parking.pricing.domain.model.RatePlanRule;
import com.sp.parking.pricing.domain.repository.RatePlanRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

/** Crea las tarifas del parqueadero demo solo si no existen; nunca sobrescribe las que el usuario configuro. */
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
  private static final UUID DEMO_TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private final RatePlanRuleRepository repository;

  @Override
  @Transactional
  public void run(String... args) {
    seed("CAR", "Carro Normal", 2500, 18_000);
    seed("HEAVY", "Carga Pesada", 4500, 35_000);
    seed("MOTORCYCLE", "Moto", 1500, 10_000);
    seed("BICYCLE", "Bicicleta", 1000, 5_000);
  }

  private void seed(String type, String displayName, int hour, int day) {
    if (repository.findFirstByTenantIdAndVehicleTypeAndActiveTrue(DEMO_TENANT_ID, type).isPresent()) return;
    repository.save(RatePlanRule.builder()
      .tenantId(DEMO_TENANT_ID)
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
      .rateIncreaseAmount(BigDecimal.ZERO)
      .lostTicketFee(BigDecimal.valueOf(20_000))
      .active(true)
      .build());
  }
}
