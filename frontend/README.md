# RutaFlex UI

Interfaz de RutaFlex: portada, planificación (optimizar un día y ver las rutas sobre el mapa), historial de resultados y gestión de los datos. Angular 21 (componentes standalone, signals y rutas con carga diferida) + Leaflet sobre teselas de OpenStreetMap.

## Ejecutarla

Necesita la API (`localhost:8080`) y, para optimizar, el optimizador (ver el README de la raíz). En desarrollo, `proxy.conf.json` reenvía `/api` a la API, así que no hace falta CORS.

```bash
npm install
npm start          # http://localhost:4200
```

## Scripts

| Comando | Qué hace |
|---|---|
| `npm start` | Servidor de desarrollo con proxy a la API |
| `npm run build` | Build de producción en `dist/` |
| `npm test` | Tests unitarios (Vitest) |
| `npm run format:check` | Comprueba el formato (Prettier) |

## Estructura

```
src/app/
├── core/          # modelos, cliente HTTP, estados (signals), lógica de dominio y traducción de errores
│   ├── plan.store.ts   # almacén, flota, días y paradas del día elegido
│   └── jobs.store.ts   # optimizaciones: lanzar, seguir hasta que terminan, historial
├── features/
│   ├── map/       # mapa Leaflet: paradas, rutas por color, selección y encuadre
│   └── sidebar/   # resumen, flota, lista de paradas e importación CSV
├── pages/         # una carpeta por vista (landing, planning, results, data)
└── app.*          # armazón: navegación y salida de la vista activa
```

## Decisiones

- **Estados con control de respuestas obsoletas.** Cada operación que cambia lo que se mira lleva un número de orden; si cuando llega una respuesta ya se ha pedido otra cosa (otro día, otro trabajo), se descarta. Los tests lo cubren y se comprobó por mutación que quitar cualquiera de esas protecciones hace fallar alguno.
- **Seguimiento de un trabajo.** Mientras está en cola o calculando se consulta cada 1,5 s; se detiene solo al terminar, al cambiar de pantalla o al mirar otro trabajo.
- **Resultado desactualizado.** Un resultado solo lleva ids; si las paradas del día cambian después de calcularlo, la pantalla lo dice y no dibuja rutas que ya no cuadran.
- **Los trazados son líneas rectas** entre paradas: no hay red viaria, así que no son los recorridos reales por calle.

## Notas

- Las teselas son las públicas de OpenStreetMap (con su atribución): valen para desarrollo y demo, no para mucho tráfico.
- Los mensajes de la API están en inglés; `core/api-error.ts` traduce los conocidos (incluidos los motivos de un cálculo fallido) y muestra el resto tal cual.
- Se usa Angular 21 porque Angular 22 exige Node 24.15 o superior.
