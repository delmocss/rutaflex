import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ImportResult, Stop, StopDay, Vehicle, Warehouse } from './models';

/** Cliente de route-api. Las rutas son relativas: en desarrollo las resuelve proxy.conf.json. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  warehouses(): Observable<Warehouse[]> {
    return this.http.get<Warehouse[]>('/api/warehouses');
  }

  vehicles(warehouseId: string): Observable<Vehicle[]> {
    return this.http.get<Vehicle[]>(`/api/warehouses/${warehouseId}/vehicles`);
  }

  stopDays(): Observable<StopDay[]> {
    return this.http.get<StopDay[]>('/api/stops/dates');
  }

  stops(date: string): Observable<Stop[]> {
    return this.http.get<Stop[]>('/api/stops', { params: new HttpParams().set('date', date) });
  }

  importStops(date: string, file: File, replace: boolean): Observable<ImportResult> {
    const body = new FormData();
    body.append('file', file, file.name);
    const params = new HttpParams().set('date', date).set('replace', replace);
    return this.http.post<ImportResult>('/api/stops/import', body, { params });
  }
}
