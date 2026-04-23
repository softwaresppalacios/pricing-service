package com.sp.parking.pricing.domain.repository;

import com.sp.parking.pricing.domain.model.RatePlanRule;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RatePlanRuleRepository extends JpaRepository<RatePlanRule, Long> {
  Optional<RatePlanRule> findFirstByTenantIdAndVehicleTypeAndActiveTrue(UUID tenantId, String vehicleType);
  List<RatePlanRule> findByTenantIdAndActiveTrueOrderByVehicleTypeAsc(UUID tenantId);
}
