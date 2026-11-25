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

  it('errores de validacion como mapa campo -> mensaje', () => {
    const problem = describeError(
      http(400, { detail: 'Validation failed', errors: { name: 'must not be blank' } }),
    );
    expect(problem.details).toEqual(['name: must not be blank']);
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
