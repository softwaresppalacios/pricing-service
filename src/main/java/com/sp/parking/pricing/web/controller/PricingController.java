package com.sp.parking.pricing.web.controller;

import com.sp.parking.pricing.infraestructure.client.AuditClient;
import com.sp.parking.pricing.infraestructure.client.OperationClient;
import com.sp.parking.pricing.application.service.PricingEngine;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.sp.parking.pricing.infraestructure.security.RequireRole;
import com.sp.parking.pricing.web.dto.PricingDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/pricing")
@RequiredArgsConstructor
public class PricingController {
  private final PricingEngine pricingEngine;
  private final AuditClient auditClient;
  private final OperationClient operationClient;

  /** Nuevo tipo de vehiculo ("como carro" o "como moto") con su tarifa. */
  @PostMapping("/rules")
  @RequireRole({"OWNER", "ADMIN"})
  public RatePlanRuleResponse createType(@RequestHeader("X-Tenant-Id") UUID tenantId, @Valid @RequestBody VehicleTypeCreateRequest request) {
    RatePlanRuleResponse created = pricingEngine.createType(tenantId, request);
    auditClient.record(tenantId, "VEHICLE_TYPE_CREATE", "TARIFF", created.displayName(),
      "Tipo nuevo (" + ("MOTORCYCLE".equals(created.category()) ? "como moto" : "como carro") + ") · "
        + (created.pricingModel() == com.sp.parking.pricing.domain.model.PricingModel.FLAT
          ? "Tarifa plana " + AuditClient.money(created.flatAmount())
          : "1a hora " + AuditClient.money(created.hourlyAmount()) + " · hora adicional " + AuditClient.money(created.additionalHourAmount())));
    return created;
  }

  /**
   * Elimina (desactiva) un tipo de vehiculo. No se permite si hay vehiculos de ese tipo adentro o
   * mensualidades vigentes; el historial conserva su nombre.
   */
  @DeleteMapping("/rules/{vehicleType}")
  @RequireRole({"OWNER", "ADMIN"})
  public RatePlanRuleResponse deleteType(@RequestHeader("X-Tenant-Id") UUID tenantId, @PathVariable("vehicleType") String vehicleType) {
    OperationClient.VehicleTypeUsage usage = operationClient.usage(tenantId, vehicleType.toUpperCase());
    if (usage.inUse()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede eliminar: hay "
        + (usage.vehiclesInside() > 0 ? usage.vehiclesInside() + (usage.vehiclesInside() == 1 ? " vehículo adentro" : " vehículos adentro") : "")
        + (usage.vehiclesInside() > 0 && usage.activeMonthly() > 0 ? " y " : "")
        + (usage.activeMonthly() > 0 ? usage.activeMonthly() + (usage.activeMonthly() == 1 ? " mensualidad vigente" : " mensualidades vigentes") : "")
        + " de este tipo");
    }
    RatePlanRuleResponse removed = pricingEngine.deactivateType(tenantId, vehicleType);
    auditClient.record(tenantId, "VEHICLE_TYPE_DELETE", "TARIFF", removed.displayName(), "Tipo de vehículo eliminado (el historial se conserva)");
    return removed;
  }

  @PostMapping("/calculate")
  public PricingResponse calculate(@RequestHeader("X-Tenant-Id") UUID tenantId, @Valid @RequestBody PricingRequest request) {
    return pricingEngine.calculate(tenantId, request);
  }

  @GetMapping("/rules")
  public RatePlanCatalogResponse rules(@RequestHeader("X-Tenant-Id") UUID tenantId) {
    return pricingEngine.getRules(tenantId);
  }

  @PutMapping("/rules/{vehicleType}")
  @RequireRole({"OWNER", "ADMIN"})
  public RatePlanRuleResponse upsertRule(
    @RequestHeader("X-Tenant-Id") UUID tenantId,
    @PathVariable("vehicleType") String vehicleType,
    @Valid @RequestBody RatePlanRuleRequest request
  ) {
    RatePlanRuleResponse saved = pricingEngine.upsertRule(tenantId, vehicleType, request);
    String values = saved.pricingModel() == com.sp.parking.pricing.domain.model.PricingModel.FLAT
      ? "Tarifa plana " + AuditClient.money(saved.flatAmount())
      : "1a hora " + AuditClient.money(saved.hourlyAmount()) + " · hora adicional " + AuditClient.money(saved.additionalHourAmount());
    auditClient.record(tenantId, "TARIFF_UPDATE", "TARIFF", saved.displayName(), values + " · día " + AuditClient.money(saved.dayAmount()) + " · ticket perdido " + AuditClient.money(saved.lostTicketFee()));
    return saved;
  }
}
