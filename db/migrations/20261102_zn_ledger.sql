-- 锌耗日结台账（液位法）落库
-- 应用：docker exec -i report-hub-pg psql -U reporthub -d reporthub < db/migrations/20261102_zn_ledger.sql

CREATE TABLE IF NOT EXISTS zn_ledger (
  id                BIGSERIAL PRIMARY KEY,
  work_date         DATE NOT NULL,
  pot_length        NUMERIC(10,3) NOT NULL,          -- 锅长 m
  pot_width         NUMERIC(10,3) NOT NULL,          -- 锅宽 m
  zinc_density      NUMERIC(10,4) NOT NULL,          -- 锌液密度 g/cm3
  h0                NUMERIC(10,2) NOT NULL,          -- 期初读数 mm（从上沿往下量）
  h1                NUMERIC(10,2) NOT NULL,          -- 期末读数 mm
  level_drop_mm     NUMERIC(10,2) NOT NULL,          -- 液面变化 mm（h1-h0，正=下降）
  k_kg_per_mm       NUMERIC(12,3) NOT NULL,          -- 量程 L*W*rho
  add_zinc_kg       NUMERIC(12,2) NOT NULL DEFAULT 0,-- 加锌合计 kg
  level_zinc_kg     NUMERIC(12,2) NOT NULL,          -- 液位折算锌耗 kg
  day_zinc_kg       NUMERIC(12,2) NOT NULL,          -- 当日锌耗 kg
  adds_json         JSONB NOT NULL DEFAULT '[]',     -- 加锌明细 [{t,w}]
  note              TEXT NOT NULL DEFAULT '',
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_zn_ledger_date ON zn_ledger (work_date);
CREATE INDEX IF NOT EXISTS ix_zn_ledger_date ON zn_ledger (work_date DESC);
