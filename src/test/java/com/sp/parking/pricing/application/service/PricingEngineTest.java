package com.sp.parking.pricing.application.service;

import com.sp.parking.pricing.domain.model.PricingModel;
import com.sp.parking.pricing.domain.model.RatePlanRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class PricingEngineTest {
  private final PricingEngine engine = new PricingEngine(null);

  /** Primera hora $3.000, hora adicional $2.000 (cada minuto extra = $33,33), dia $18.000. */
  private RatePlanRule rule() {
    return RatePlanRule.builder()
      .pricingModel(PricingModel.PROGRESSIVE)
      .hourlyAmount(BigDecimal.valueOf(3000))
      .additionalHourAmount(BigDecimal.valueOf(2000))
      .dayAmount(BigDecimal.valueOf(18000))
      .rateIncreaseAmount(BigDecimal.ZERO)
      .build();
  }

  @ParameterizedTest(name = "{0} min = {1}")
  @CsvSource({
    "1, 3000",      // cualquier fraccion de la primera hora la cobra completa
    "60, 3000",
    "61, 3033",     // 1 min adicional = 2000/60, redondeado al peso
    "80, 3667",     // 1h20: 3000 + 20/60 * 2000
    "90, 4000",     // 1h30: 3000 + 1000
    "120, 5000",    // 2h
    "150, 6000",    // 2h30: 3000 + 1,5 * 2000
    "1440, 18000",  // 24 h: un dia
    "1450, 21000",  // 24h10: dia + primera hora
    "1530, 22000"   // 25h30: dia + primera hora + media hora adicional
  })
  void cobraPrimeraHoraYMinutosProporcionales(long minutes, int expected) {
    assertThat(engine.charge(rule(), minutes).amount()).isEqualByComparingTo(BigDecimal.valueOf(expected));
  }

  @Test
  void desgloseIndicaMinutosYValorAdicional() {
    PricingEngine.Charge charge = engine.charge(rule(), 150);
    assertThat(charge.firstHourCharged()).isTrue();
    assertThat(charge.additionalMinutes()).isEqualTo(90);
    assertThat(charge.additionalAmount()).isEqualByComparingTo("3000");
    assertThat(charge.days()).isZero();
  }

  @Test
  void topeDiarioLimitaElCobroPorHoras() {
    RatePlanRule capped = rule();
    capped.setApplyDailyCap(true);
    capped.setDayAmount(BigDecimal.valueOf(10000));
    // 10 h = 3000 + 9 * 2000 = 21000, con tope de 10000
    assertThat(engine.charge(capped, 600).amount()).isEqualByComparingTo("10000");
  }

  @Test
  void tarifaPlanaNoDependeDelTiempo() {
    RatePlanRule flat = rule();
    flat.setPricingModel(PricingModel.FLAT);
    flat.setFlatAmount(BigDecimal.valueOf(8000));
    assertThat(engine.charge(flat, 500).amount()).isEqualByComparingTo("8000");
  }
}
