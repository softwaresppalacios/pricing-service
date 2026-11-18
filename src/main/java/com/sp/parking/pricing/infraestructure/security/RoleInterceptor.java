package com.sp.parking.pricing.infraestructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.server.ResponseStatusException;
import java.util.Arrays;
import static org.springframework.http.HttpStatus.FORBIDDEN;

/** Aplica @RequireRole con el rol que dejo TenantAccessFilter en la peticion. */
@Component
public class RoleInterceptor implements HandlerInterceptor {
  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) return true;
    RequireRole required = method.getMethodAnnotation(RequireRole.class);
    if (required == null) return true;
    Object role = request.getAttribute(TenantAccessFilter.ROLE_ATTRIBUTE);
    if (role == null || Arrays.stream(required.value()).noneMatch(role::equals)) {
      throw new ResponseStatusException(FORBIDDEN, "Tu rol no permite realizar esta acción");
    }
    return true;
  }
}
