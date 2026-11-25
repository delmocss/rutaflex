# RutaFlex UI

Interfaz de solo lectura de RutaFlex: muestra sobre un mapa de Madrid sur el almacén, la flota y las paradas de un día de reparto, y permite importar paradas desde un CSV. Angular 21 (componentes standalone y signals) + Leaflet sobre teselas de OpenStreetMap.

## Ejecutarla

Necesita la API en marcha en `localhost:8080` (ver el README de la raíz). En desarrollo, `proxy.conf.json` reenvía `/api` a ella, así que no hace falta CORS.

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
├── core/        # modelos, cliente HTTP, estado (signals), lógica de dominio y traducción de errores
├── features/
│   ├── map/     # mapa Leaflet: marcadores, selección y encuadre
│   └── sidebar/ # resumen, flota, lista de paradas e importación CSV
└── app.*        # pantalla principal
```

## Notas

- Las teselas son las públicas de OpenStreetMap (con su atribución): valen para desarrollo y demo, no para mucho tráfico.
- Los mensajes de la API están en inglés; `core/api-error.ts` traduce los conocidos y muestra el resto tal cual.
- Se usa Angular 21 porque Angular 22 exige Node 24.15 o superior.
