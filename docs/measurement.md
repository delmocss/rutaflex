# Medición: km ahorrados

Este fichero lo genera `MeasurementReport` (ver «Cómo repetirlo»). No se edita a mano.

## Qué se mide

Los km totales de las rutas de un día de reparto: 80 paradas (28 con franja horaria) y 205 bultos, con 4 furgonetas de capacidad total 240 bultos, saliendo de un único almacén. Es el conjunto de ejemplo de Madrid sur (`route-api/src/main/resources/sample/madrid-sur-stops.csv`), sintético y generado con semilla fija.

Las distancias son **estimadas**: línea recta (haversine) × 1,3 de factor de rodeo urbano, sin red viaria real. Se aplican igual a la solución y a las referencias, así que la comparación entre ellas es justa aunque la cifra absoluta no sea la de un recorrido por calle.

## Las referencias

| Referencia | Km | Paradas que llegarían tarde | Furgonetas fuera de jornada | Furgonetas sobrecargadas |
|---|---:|---:|---:|---:|
| Orden de llegada (el «sin optimizar» del plan) | 793.8 | 20 | 3 | 0 |
| Barrido por ángulo + vecino más cercano | 131.5 | 6 | 0 | 0 |

- **Orden de llegada**: las paradas tal como llegan, cada una a la primera furgoneta en la que quepa. Es la referencia más ingenua posible.
- **Barrido + vecino más cercano**: lo que haría un despachante con sentido común. Ordena las paradas por el ángulo alrededor del almacén (cortando el círculo por el sur), las reparte por sectores mientras quepan y, dentro de cada furgoneta, va siempre a la parada pendiente más cercana. Es mucho más exigente. Si no cabe todo en orden, el sobrante va a la furgoneta más vacía y se cuenta en «sobrecargadas» (con estos datos, ninguna).
- Ninguna mira las franjas horarias ni la jornada, y por eso la tabla dice cuántas romperían. El optimizador **sí** las respeta: si no encuentra un reparto que las cumpla, falla en vez de devolver una solución inválida. Comparar sus km con los de una referencia inviable **probablemente subestima** el ahorro frente a una referencia que las respetase, pero eso es un argumento, no una medición: la medición es la comparación «sin franjas» de más abajo.

### El barrido depende de dónde se corte el círculo

El corte por el sur es una elección arbitraria, y las paradas que quedan a ambos lados del corte van a furgonetas distintas aunque estén juntas. Probando 72 cortes equiespaciados el barrido da entre **130.2** y **163.3** km (media 136.7); el corte «de serie» (131.5 km) queda por debajo de la media, es decir, favorece a la referencia.

Frente al **mejor** corte posible (130.2 km, que nadie elegiría sin probarlos todos) el optimizador ahorra de media **10.0 %** de los km.

## El resultado

El optimizador se detiene tras un número fijo de pasos de búsqueda (**60000**), no por tiempo: así el resultado no depende de lo rápida que sea la máquina. Se repite con 3 semillas distintas para ver cuánto varía (con tan pocas, es un rango, no una prueba de estabilidad). **La aplicación limita el cálculo por tiempo** (30 s, o 8 s sin mejora), no por pasos: en una máquina lenta llegará a menos pasos y su resultado será el de la fila correspondiente de la curva de convergencia, que puede ser peor.

| Semilla | Km optimizados | Ahorro frente al orden de llegada | Ahorro frente al barrido |
|---:|---:|---:|---:|
| 1 | 117.4 | 85.2 % | 10.7 % |
| 2 | 116.8 | 85.3 % | 11.2 % |
| 3 | 117.4 | 85.2 % | 10.7 % |
| **media** | **117.2** | **85.2 %** | **10.9 %** |
| mejor / peor | 116.8 / 117.4 | 85.3 % / 85.2 % | 11.2 % / 10.7 % |

## Qué cuestan las franjas horarias

La referencia de barrido no mira las franjas, y la solución sí. Para comparar distancia contra distancia, se repite todo **sin franjas horarias** (mismo problema, mismas semillas):

| | Km |
|---|---:|
| Barrido + vecino más cercano, sin franjas | 131.5 |
| Optimizador, sin franjas (media de 3 semillas) | 112.6 |
| Optimizador, **con** franjas (media) | 117.2 |

Respetar las 28 franjas horarias cuesta, de media, 4.6 km más (4.1 %) que ignorarlas.

## Convergencia

Cuántos pasos de búsqueda hacen falta (semilla 1). Con pocos pasos la solución es peor; la mejora se aplana hacia el final:

| Pasos | Km optimizados |
|---:|---:|
| 10000 | 183.8 |
| 20000 | 157.7 |
| 30000 | 140.3 |
| 40000 | 128.7 |
| 50000 | 121.7 |
| 60000 | 117.4 |
| 80000 | 116.3 |

En la aplicación el cálculo se limita por tiempo (30 s por defecto), no por pasos; el número de pasos que da en ese tiempo depende de la máquina.

## Cómo leerlo

- Frente al reparto **sin ordenar** (el «sin optimizar» del plan) el optimizador ahorra **85.2 %** de los km de media. Es la comparación del plan, la más favorable y la menos exigente.
- Frente a una referencia **con sentido común** (barrido + vecino más cercano) ahorra **10.9 %** de los km de media, pero esa referencia es inviable (6 paradas llegarían tarde): la solución cumple todas las franjas y ella no.
- La comparación de distancia contra distancia, **sin franjas** en ambos lados, es la más justa: el optimizador ahorra **14.3 %** de los km frente a la misma referencia.
- Las semillas dan resultados cercanos entre sí (mira «mejor / peor»): la cifra no es un golpe de suerte de una ejecución concreta.
- Es un conjunto sintético de una sola zona y un solo día. Sirve para demostrar el método, no para prometer un porcentaje en otro negocio.

## Cómo repetirlo

```bash
mvn -pl optimizer-service -am test -Dtest=MeasurementReport -Drutaflex.measure=true
```

Opciones: `-Drutaflex.measure.steps=60000` (pasos de búsqueda) y `-Drutaflex.measure.seeds=3` (semillas). Con los mismos valores y el mismo entorno sale el mismo resultado en km; `ReproducibilityTest` comprueba en cada `mvn verify` que la misma semilla y los mismos pasos dan exactamente el mismo resultado, y fija el valor de una ejecución corta sobre este dataset (se ejecuta en Windows y en Linux). No se ha comprobado a más largo plazo ni en otras plataformas.

Generado con JDK 21.0.11 en Windows 11.
