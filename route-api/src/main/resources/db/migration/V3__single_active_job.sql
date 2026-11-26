-- Como mucho un trabajo activo (en cola o calculando) por almacen y dia: pedir otro mientras el primero
-- no termina es un conflicto. Al ser un indice, lo garantiza la base de datos aunque dos peticiones lleguen a la vez.
CREATE UNIQUE INDEX uq_optimization_job_active
    ON optimization_job (warehouse_id, delivery_date)
    WHERE status IN ('QUEUED', 'RUNNING');
