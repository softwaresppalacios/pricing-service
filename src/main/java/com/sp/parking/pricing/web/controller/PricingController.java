package com.sp.parking.pricing.web.controller;

import com.sp.parking.pricing.application.service.PricingEngine;
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

  @PostMapping("/calculate")
  public PricingResponse calculate(@RequestHeader("X-Tenant-Id") UUID tenantId, @Valid @RequestBody PricingRequest request) {
    return pricingEngine.calculate(tenantId, request);
  }

  @GetMapping("/rules")
  public RatePlanCatalogResponse rules(@RequestHeader("X-Tenant-Id") UUID tenantId) {
    return pricingEngine.getRules(tenantId);
  }

  @PutMapping("/rules/{vehicleType}")
  public RatePlanRuleResponse upsertRule(
    @RequestHeader("X-Tenant-Id") UUID tenantId,
    @PathVariable("vehicleType") String vehicleType,
    @Valid @RequestBody RatePlanRuleRequest request
  ) {
    return pricingEngine.upsertRule(tenantId, vehicleType, request);
  }
}
