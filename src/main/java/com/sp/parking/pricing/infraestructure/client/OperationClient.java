package com.sp.parking.pricing.infraestructure.client;

import com.sp.parking.pricing.infraestructure.security.TenantAccessFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Consulta a parking-operation-service si un tipo de vehiculo esta en uso (vehiculos adentro o
 * mensualidades vigentes) antes de eliminarlo. Si no responde, no se elimina: es mas seguro.
 */
@Component
public class OperationClient {
  private final RestClient restClient;

  public OperationClient(@Value("${services.operation.base-url:http://localhost:8082/api}") String baseUrl) {
    SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
    timeouts.setConnectTimeout(3_000);
    timeouts.setReadTimeout(5_000);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts).build();
  }

  public record VehicleTypeUsage(long vehiclesInside, long activeMonthly) {
    public boolean inUse() { return vehiclesInside > 0 || activeMonthly > 0; }
  }

  public VehicleTypeUsage usage(UUID tenantId, String vehicleType) {
    try {
      VehicleTypeUsage usage = restClient.get()
        .uri("/vehicle-types/{type}/usage", vehicleType)
        .header("X-Tenant-Id", tenantId.toString())
        .header(HttpHeaders.AUTHORIZATION, TenantAccessFilter.currentAuthorization())
        .retrieve()
        .body(VehicleTypeUsage.class);
      return usage == null ? new VehicleTypeUsage(0, 0) : usage;
    } catch (RestClientException ex) {
      throw new ResponseStatusException(SERVICE_UNAVAILABLE, "No se pudo verificar si el tipo de vehículo está en uso. Intenta de nuevo en un momento");
    }
  }
}
