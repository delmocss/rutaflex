import { HttpErrorResponse } from '@angular/common/http';

export interface ApiProblem {
  /** Mensaje principal, apto para mostrar al usuario. */
  message: string;
  /** Detalles adicionales (por ejemplo, un error por linea de un CSV). */
  details: string[];
}

const UNREACHABLE =
  'No se puede conectar con la API. Comprueba que route-api está en marcha (puerto 8080).';

/** Mensajes conocidos de la API (en ingles, fijos) y su version en espanol. Lo no reconocido se muestra tal cual. */
const MESSAGES: [RegExp, string][] = [
  [/^The CSV is not valid, nothing was imported$/, 'El CSV no es válido: no se ha importado nada.'],
  [/^The file has no rows$/, 'El fichero no tiene filas.'],
  [/^The file has more than (\d+) rows$/, 'El fichero tiene más de $1 filas.'],
  [/^Missing required column\(s\): (.+)$/, 'Faltan columnas obligatorias: $1.'],
  [/^Expected header: (.+)$/, 'Cabecera esperada: $1'],
  [/^The file is not valid UTF-8.*$/, 'El fichero no está en UTF-8: guárdalo como «CSV UTF-8».'],
  [
    /^The file is not a well-formed CSV.*$/,
    'El fichero no es un CSV válido (revisa comillas y separadores).',
  ],
  [
    /^The header is not valid: column names must be unique$/,
    'La cabecera no es válida: los nombres de columna deben ser únicos.',
  ],
  [/^Validation failed$/, 'Hay datos no válidos.'],
  [/^\.\.\. and (\d+) more$/, '… y $1 más'],
  [
    /^A resource with the same unique values already exists$/,
    'Ya existe un registro con esos mismos valores únicos.',
  ],
  // ---- optimizaciones ----
  [
    /^An optimization for this warehouse and day is already in progress$/,
    'Ya hay un cálculo en curso para este día. Espera a que termine.',
  ],
  [/^There are no stops for (.+)$/, 'No hay paradas para el día $1.'],
  [
    /^The warehouse has no vehicles to plan with$/,
    'El almacén no tiene furgonetas con las que planificar.',
  ],
  [/^There is no warehouse yet$/, 'Todavía no hay ningún almacén.'],
  [
    /^warehouseId is required when there is more than one warehouse$/,
    'Hay varios almacenes: elige uno.',
  ],
  [
    /^The optimization could not be queued, try again shortly$/,
    'No se pudo poner en cola la optimización. Inténtalo de nuevo en unos segundos.',
  ],
];

/**
 * Motivos de fallo de un trabajo (los escribe el optimizador o la API, en ingles) en espanol. Se muestran al
 * usuario en la ficha del trabajo; lo no reconocido se deja tal cual.
 */
const FAILURE_REASONS: [RegExp, string][] = [
  [
    /^No feasible plan found.*$/,
    'No existe un reparto que cumpla todas las restricciones: revisa la capacidad de las furgonetas, las franjas horarias y la jornada.',
  ],
  [
    /^There are (\d+) parcels but the vehicles only carry (\d+)$/,
    'Hay $1 bultos pero las furgonetas solo pueden llevar $2.',
  ],
  [
    /^Stop '(.+)' has (\d+) parcels, more than any single vehicle carries \((\d+)\)$/,
    'La parada «$1» tiene $2 bultos, más de lo que cabe en cualquier furgoneta ($3).',
  ],
  [/^There are no vehicles to plan with$/, 'No hay furgonetas con las que planificar.'],
  [/^There are no stops to plan$/, 'No hay paradas que planificar.'],
  [
    /^The job did not finish within (.+): its result never arrived$/,
    'El cálculo no terminó en $1: el resultado nunca llegó. Puedes volver a lanzarlo.',
  ],
  [
    /^The job could not be queued.*$/,
    'No se pudo poner en cola el cálculo. Puedes volver a lanzarlo.',
  ],
  [
    /^The optimization request could not be read by the optimizer$/,
    'El optimizador no pudo leer la petición.',
  ],
  [
    /^The optimizer could not process the request after (\d+) attempts.*$/,
    'El optimizador no pudo procesar la petición tras $1 intentos.',
  ],
  [/^The optimization failed$/, 'La optimización falló.'],
];

/** El motivo de fallo de un trabajo, en espanol. */
export function translateFailureReason(reason: string | null | undefined): string {
  return reason ? translateWith(FAILURE_REASONS, reason) : '';
}

/** Parte final de un error por linea del CSV ("line 3: <columna> <aqui>"). */
const LINE_REASONS: [RegExp, string][] = [
  [/^must be greater than or equal to (.+)$/, 'debe ser mayor o igual que $1'],
  [/^must be less than or equal to (.+)$/, 'debe ser menor o igual que $1'],
  [
    /^is not a number \(use a dot as the decimal separator\)$/,
    'no es un número (usa punto como separador decimal)',
  ],
  [/^is not a whole number$/, 'no es un número entero'],
  [/^must be a time as HH:mm$/, 'debe ser una hora con formato HH:mm'],
  [/^must not be blank$/, 'no puede estar vacío'],
  [
    /^windowStart and windowEnd must be both set or both empty, and windowStart must be before windowEnd$/,
    'debe tener inicio y fin, y el inicio debe ser anterior al fin',
  ],
];

function translateWith(rules: [RegExp, string][], text: string): string {
  for (const [pattern, replacement] of rules) {
    if (pattern.test(text)) {
      return text.replace(pattern, replacement);
    }
  }
  return text;
}

/** "line 3: parcels must be ..." -> "Línea 3 · parcels: debe ser ...". */
export function translateApiText(text: string): string {
  const line = /^line (\d+): ([\w/]+) (.+)$/.exec(text);
  if (line) {
    return `Línea ${line[1]} · ${line[2]}: ${translateWith(LINE_REASONS, line[3])}`;
  }
  return translateWith(MESSAGES, text);
}

/** Un valor cualquiera como texto, sin caer en "[object Object]". */
function text(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value);
}

/**
 * Traduce un error HTTP a algo mostrable. La API responde application/problem+json con `detail` y,
 * segun el caso, `errors` (lista de lineas del CSV, o mapa campo -> mensaje).
 */
export function describeError(error: unknown): ApiProblem {
  if (!(error instanceof HttpErrorResponse)) {
    return { message: 'Ha ocurrido un error inesperado.', details: [] };
  }
  if (error.status === 0) {
    return { message: UNREACHABLE, details: [] };
  }

  const body = error.error as { detail?: unknown; errors?: unknown } | null;
  const details = Array.isArray(body?.errors)
    ? body.errors.map((item) => translateApiText(text(item)))
    : body?.errors && typeof body.errors === 'object'
      ? Object.entries(body.errors as Record<string, unknown>).map(
          ([field, reason]) => `${field}: ${translateWith(LINE_REASONS, text(reason))}`,
        )
      : [];

  // 502/504: el proxy de desarrollo no llega a la API. Un 500 sin cuerpo problem+json tambien es del
  // proxy (la API siempre responde con `detail`).
  const proxyFailure =
    error.status === 502 ||
    error.status === 504 ||
    (error.status === 500 && typeof body?.detail !== 'string');
  if (proxyFailure) {
    return { message: UNREACHABLE, details: [] };
  }
  if (error.status === 503) {
    return {
      message: 'El servicio no está disponible temporalmente. Inténtalo de nuevo.',
      details: [],
    };
  }
  if (error.status === 413) {
    return { message: 'El fichero supera el límite de 1 MB.', details };
  }
  if (error.status >= 500) {
    return { message: 'La API ha fallado. Inténtalo de nuevo en unos instantes.', details: [] };
  }
  const detail = typeof body?.detail === 'string' ? body.detail : '';
  return {
    message: detail
      ? translateApiText(detail)
      : `La API ha rechazado la petición (${error.status}).`,
    details,
  };
}
