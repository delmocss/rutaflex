import { HttpErrorResponse } from '@angular/common/http';
import { describeError, translateApiText } from './api-error';

const http = (status: number, error: unknown) => new HttpErrorResponse({ status, error });

describe('describeError', () => {
  it('sin conexion explica que falta la API', () => {
    const problem = describeError(http(0, null));
    expect(problem.message).toContain('No se puede conectar');
    expect(problem.details).toEqual([]);
  });

  it('un CSV invalido muestra el detalle y cada linea con su error', () => {
    const problem = describeError(
      http(400, {
        detail: 'The CSV is not valid, nothing was imported',
        errors: [
          'line 3: parcels must be greater than or equal to 1',
          'line 4: lat must be less than or equal to 90.0',
        ],
      }),
    );
    expect(problem.message).toBe('El CSV no es válido: no se ha importado nada.');
    expect(problem.details).toEqual([
      'Línea 3 · parcels: debe ser mayor o igual que 1',
      'Línea 4 · lat: debe ser menor o igual que 90.0',
    ]);
  });

  it('errores de validacion como mapa campo -> mensaje, traducidos', () => {
    const problem = describeError(
      http(400, { detail: 'Validation failed', errors: { name: 'must not be blank' } }),
    );
    expect(problem.message).toBe('Hay datos no válidos.');
    expect(problem.details).toEqual(['name: no puede estar vacío']);
  });

  it('un elemento que no es texto no se muestra como [object Object]', () => {
    const problem = describeError(http(400, { detail: 'x', errors: [{ field: 'a' }] }));
    expect(problem.details).toEqual(['{"field":"a"}']);
  });

  it('el aviso de truncado de la API se traduce', () => {
    const problem = describeError(http(400, { detail: 'x', errors: ['... and 20 more'] }));
    expect(problem.details).toEqual(['… y 20 más']);
  });

  it('un 502/504 o un 500 sin cuerpo de la API (proxy de desarrollo) es "no se puede conectar"', () => {
    for (const status of [502, 504]) {
      expect(describeError(http(status, 'Bad gateway')).message).toContain('No se puede conectar');
    }
    expect(describeError(http(500, 'Error occurred while trying to proxy')).message).toContain(
      'No se puede conectar',
    );
  });

  it('un 503 de la API (base de datos caida) se explica como temporal', () => {
    const problem = describeError(http(503, { detail: 'The service is temporarily unavailable' }));
    expect(problem.message).toContain('temporalmente');
  });

  it('un 500 de la API (con detail) es un fallo del servicio, no de conexion', () => {
    const problem = describeError(http(500, { detail: 'Internal server error' }));
    expect(problem.message).toContain('La API ha fallado');
  });

  it('un fichero grande se explica en espanol', () => {
    expect(describeError(http(413, { detail: 'x' })).message).toContain('1 MB');
  });

  it('los fallos del servidor no filtran detalles internos', () => {
    const problem = describeError(
      http(500, { detail: 'secret stack trace', errors: ['internal'] }),
    );
    expect(problem.message).not.toContain('secret');
    expect(problem.details).toEqual([]);
  });

  it('sin detalle usa un mensaje generico con el codigo', () => {
    expect(describeError(http(404, null)).message).toContain('404');
  });

  it('algo que no es un error HTTP no rompe', () => {
    expect(describeError(new Error('boom')).message).toBe('Ha ocurrido un error inesperado.');
  });
});

describe('translateApiText', () => {
  it.each([
    ['The file has no rows', 'El fichero no tiene filas.'],
    ['The file has more than 1000 rows', 'El fichero tiene más de 1000 filas.'],
    ['Missing required column(s): lon', 'Faltan columnas obligatorias: lon.'],
    [
      "The file is not valid UTF-8: save it as 'CSV UTF-8'",
      'El fichero no está en UTF-8: guárdalo como «CSV UTF-8».',
    ],
    [
      'line 5: lat is not a number (use a dot as the decimal separator)',
      'Línea 5 · lat: no es un número (usa punto como separador decimal)',
    ],
    [
      'line 2: window_start/window_end must be a time as HH:mm',
      'Línea 2 · window_start/window_end: debe ser una hora con formato HH:mm',
    ],
  ])('%s', (english, spanish) => expect(translateApiText(english)).toBe(spanish));

  it('lo que no conoce lo deja tal cual, sin perder informacion', () => {
    expect(translateApiText('something new from the API')).toBe('something new from the API');
    expect(translateApiText('line 7: address size must be between 0 and 200')).toBe(
      'Línea 7 · address: size must be between 0 and 200',
    );
  });
});
