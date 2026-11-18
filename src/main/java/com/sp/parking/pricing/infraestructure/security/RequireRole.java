package com.sp.parking.pricing.infraestructure.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Limita un endpoint a los roles indicados (OWNER, ADMIN, CASHIER) dentro del parqueadero de X-Tenant-Id. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
  String[] value();
}
