import { HttpErrorResponse } from '@angular/common/http';

export interface ApiProblem {
  /** Mensaje principal, apto para mostrar al usuario. */
  message: string;
  /** Detalles adicionales (por ejemplo, un error por linea de un CSV). */
  details: string[];
}

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
  [
    /^A resource with the same unique values already exists$/,
    'Ya existe un registro con esos mismos valores únicos.',
  ],
];

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

/**
 * Traduce un error HTTP a algo mostrable. La API responde application/problem+json con `detail` y,
 * segun el caso, `errors` (lista de lineas del CSV, o mapa campo -> mensaje).
 */
export function describeError(error: unknown): ApiProblem {
  if (!(error instanceof HttpErrorResponse)) {
    return { message: 'Ha ocurrido un error inesperado.', details: [] };
  }
  if (error.status === 0) {
    return {
      message:
        'No se puede conectar con la API. Comprueba que route-api está en marcha (puerto 8080).',
      details: [],
    };
  }

  const body = error.error as { detail?: unknown; errors?: unknown } | null;
  const details = Array.isArray(body?.errors)
    ? body.errors.map((item) => translateApiText(String(item)))
    : body?.errors && typeof body.errors === 'object'
      ? Object.entries(body.errors as Record<string, unknown>).map(
          ([field, text]) => `${field}: ${String(text)}`,
        )
      : [];

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
