CREATE SCHEMA IF NOT EXISTS pricing_service;

CREATE TABLE IF NOT EXISTS pricing_service.rate_plan_rule (
  id BIGSERIAL PRIMARY KEY,
  tenant_id UUID NOT NULL,
  vehicle_type VARCHAR(255) NOT NULL,
  display_name VARCHAR(255),
  pricing_model VARCHAR(255),
  flat_amount NUMERIC(19,2),
  hourly_amount NUMERIC(19,2),
  additional_hour_amount NUMERIC(19,2),
  fraction_amount NUMERIC(19,2),
  fraction_minutes INTEGER,
  day_amount NUMERIC(19,2),
  apply_daily_cap BOOLEAN DEFAULT FALSE,
  rate_increase_after_hours INTEGER,
  rate_increase_amount NUMERIC(19,2),
  lost_ticket_fee NUMERIC(19,2),
  active BOOLEAN DEFAULT TRUE
);

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS apply_daily_cap BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS display_name VARCHAR(255);

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS pricing_model VARCHAR(255);

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS flat_amount NUMERIC(19,2);

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS hourly_amount NUMERIC(19,2);

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS additional_hour_amount NUMERIC(19,2);

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS fraction_amount NUMERIC(19,2);

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS fraction_minutes INTEGER;

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS day_amount NUMERIC(19,2);

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS rate_increase_after_hours INTEGER;

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS rate_increase_amount NUMERIC(19,2);

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS lost_ticket_fee NUMERIC(19,2);

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT TRUE;

UPDATE pricing_service.rate_plan_rule
SET
  display_name = COALESCE(display_name, vehicle_type),
  pricing_model = COALESCE(pricing_model, 'PROGRESSIVE'),
  fraction_minutes = COALESCE(fraction_minutes, 30),
  apply_daily_cap = COALESCE(apply_daily_cap, FALSE),
  active = COALESCE(active, TRUE)
WHERE
  display_name IS NULL
  OR pricing_model IS NULL
  OR fraction_minutes IS NULL
  OR apply_daily_cap IS NULL
  OR active IS NULL;

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ALTER COLUMN display_name SET NOT NULL;

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ALTER COLUMN pricing_model SET NOT NULL;

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ALTER COLUMN fraction_minutes SET NOT NULL;

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ALTER COLUMN apply_daily_cap SET DEFAULT FALSE;

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ALTER COLUMN apply_daily_cap SET NOT NULL;

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ALTER COLUMN active SET DEFAULT TRUE;

ALTER TABLE IF EXISTS pricing_service.rate_plan_rule
  ALTER COLUMN active SET NOT NULL;
