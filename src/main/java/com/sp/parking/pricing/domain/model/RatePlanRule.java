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
  /** Valor de la primera hora (o fraccion de ella). */
  private BigDecimal hourlyAmount;
  /** Valor por hora del tiempo despues de la primera hora; los minutos se cobran proporcionalmente. */
  private BigDecimal additionalHourAmount;
  /** Fraccion: ya no interviene en el calculo; se conserva por compatibilidad con reglas guardadas. */
  private BigDecimal fractionAmount;
  private int fractionMinutes;
  private BigDecimal dayAmount;
  private boolean applyDailyCap;
  private Integer rateIncreaseAfterHours;
  private BigDecimal rateIncreaseAmount;
  private BigDecimal lostTicketFee;
  /** false = tipo de vehiculo eliminado (se conserva para el historial y para cobrar tickets anteriores). */
  private boolean active;
  /**
   * Como se comporta en la entrada: CAR (espacios de carro y tablero de llaves) o MOTORCYCLE
   * (espacios de moto y casilleros de casco). Null en reglas antiguas: se deduce del tipo base.
   */
  @Column(length = 20)
  private String category;
}
