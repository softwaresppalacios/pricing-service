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

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
  private static final UUID DEMO_TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private final RatePlanRuleRepository repository;

  @Override
  @Transactional
  public void run(String... args) {
    seed("CAR", "Carro Normal", 2500, 1500, 18_000);
    seed("HEAVY", "Carga Pesada", 4500, 2500, 35_000);
    seed("MOTORCYCLE", "Moto", 1500, 800, 10_000);
    seed("BICYCLE", "Bicicleta", 1000, 500, 5_000);
  }

  private void seed(String type, String displayName, int hour, int fraction, int day) {
    RatePlanRule rule = repository.findFirstByTenantIdAndVehicleTypeAndActiveTrue(DEMO_TENANT_ID, type).orElseGet(() ->
      RatePlanRule.builder()
        .tenantId(DEMO_TENANT_ID)
        .vehicleType(type)
        .active(true)
        .build()
    );
    rule.setDisplayName(displayName);
    rule.setPricingModel(PricingModel.PROGRESSIVE);
    rule.setFlatAmount(BigDecimal.valueOf(day));
    rule.setHourlyAmount(BigDecimal.valueOf(hour));
    rule.setAdditionalHourAmount(BigDecimal.valueOf(hour));
    rule.setFractionAmount(BigDecimal.valueOf(fraction));
    rule.setFractionMinutes(30);
    rule.setDayAmount(BigDecimal.valueOf(day));
    rule.setApplyDailyCap(false);
    rule.setRateIncreaseAfterHours(null);
    rule.setRateIncreaseAmount(BigDecimal.ZERO);
    rule.setLostTicketFee(BigDecimal.valueOf(20_000));
    repository.save(rule);
  }
}
