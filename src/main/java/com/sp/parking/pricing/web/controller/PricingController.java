package com.sp.parking.pricing.web.controller;

import com.sp.parking.pricing.infraestructure.client.AuditClient;
import com.sp.parking.pricing.application.service.PricingEngine;
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
