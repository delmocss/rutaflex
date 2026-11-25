-- Esquema inicial. Coordenadas como geography(Point, 4326): las distancias salen en metros
-- directamente con ST_Distance, sin proyectar.
-- CREATE EXTENSION necesita un usuario con permiso para ello; en docker-compose lo es.
CREATE EXTENSION IF NOT EXISTS postgis;

-- Punto de salida y de vuelta de las furgonetas.
CREATE TABLE warehouse (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text                  NOT NULL,
    address    text                  NOT NULL,
    location   geography(Point, 4326) NOT NULL,
    created_at timestamptz           NOT NULL DEFAULT now()
);

-- Capacidad en bultos y jornada. shift_start/shift_end acotan cuando sale y cuando debe volver.
CREATE TABLE vehicle (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id     uuid        NOT NULL REFERENCES warehouse (id) ON DELETE CASCADE,
    name             text        NOT NULL,
    capacity_parcels integer     NOT NULL CHECK (capacity_parcels > 0),
    shift_start      time        NOT NULL,
    shift_end        time        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT vehicle_shift_order CHECK (shift_start < shift_end),
    CONSTRAINT vehicle_name_per_warehouse UNIQUE (warehouse_id, name)
);

-- Parada de reparto de un dia. La franja horaria es opcional, pero va completa o no va.
CREATE TABLE delivery_stop (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_date date                   NOT NULL,
    address       text                   NOT NULL,
    location      geography(Point, 4326) NOT NULL,
    parcels       integer                NOT NULL CHECK (parcels > 0),
    window_start  time,
    window_end    time,
    created_at    timestamptz            NOT NULL DEFAULT now(),
    CONSTRAINT stop_window_complete CHECK ((window_start IS NULL) = (window_end IS NULL)),
    CONSTRAINT stop_window_order CHECK (window_start IS NULL OR window_start < window_end)
);
CREATE INDEX idx_delivery_stop_date ON delivery_stop (delivery_date);
CREATE INDEX idx_delivery_stop_location ON delivery_stop USING gist (location);

-- Trabajo de optimizacion: estado y resultado. El calculo se hace en segundo plano.
CREATE TABLE optimization_job (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id   uuid          NOT NULL REFERENCES warehouse (id),
    delivery_date  date          NOT NULL,
    status         text          NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    failure_reason text,
    result         jsonb,
    total_km       numeric(10, 2),
    baseline_km    numeric(10, 2),
    requested_at   timestamptz   NOT NULL DEFAULT now(),
    started_at     timestamptz,
    finished_at    timestamptz,
    CONSTRAINT job_failure_has_reason CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL)
);
CREATE INDEX idx_optimization_job_status ON optimization_job (status);
