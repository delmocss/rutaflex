-- Orden de llegada de las paradas. created_at no sirve: una importacion inserta todas las filas en
-- una transaccion y now() es el inicio de la transaccion, asi que todas tendrian el mismo valor y el
-- desempate quedaria en manos del id aleatorio. La columna identidad se asigna en orden de insercion,
-- que es el orden del CSV; es la base de la linea "sin optimizar" con la que se medira el ahorro de km.
ALTER TABLE delivery_stop ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY;
CREATE INDEX idx_delivery_stop_date_seq ON delivery_stop (delivery_date, seq);
