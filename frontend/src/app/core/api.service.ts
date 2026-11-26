import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ImportResult,
  OptimizationJob,
  Stop,
  StopDay,
  StopInput,
  Vehicle,
  VehicleInput,
  Warehouse,
  WarehouseInput,
} from './models';

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

  // ---- escritura de datos ----

  createWarehouse(body: WarehouseInput): Observable<Warehouse> {
    return this.http.post<Warehouse>('/api/warehouses', body);
  }

  updateWarehouse(id: string, body: WarehouseInput): Observable<Warehouse> {
    return this.http.put<Warehouse>(`/api/warehouses/${id}`, body);
  }

  createVehicle(warehouseId: string, body: VehicleInput): Observable<Vehicle> {
    return this.http.post<Vehicle>(`/api/warehouses/${warehouseId}/vehicles`, body);
  }

  updateVehicle(id: string, body: VehicleInput): Observable<Vehicle> {
    return this.http.put<Vehicle>(`/api/vehicles/${id}`, body);
  }

  deleteVehicle(id: string): Observable<void> {
    return this.http.delete<void>(`/api/vehicles/${id}`);
  }

  createStop(body: StopInput): Observable<Stop> {
    return this.http.post<Stop>('/api/stops', body);
  }

  updateStop(id: string, body: StopInput): Observable<Stop> {
    return this.http.put<Stop>(`/api/stops/${id}`, body);
  }

  deleteStop(id: string): Observable<void> {
    return this.http.delete<void>(`/api/stops/${id}`);
  }

  // ---- optimizaciones ----

  requestOptimization(deliveryDate: string, warehouseId?: string): Observable<OptimizationJob> {
    return this.http.post<OptimizationJob>('/api/optimizations', { deliveryDate, warehouseId });
  }

  optimization(id: string): Observable<OptimizationJob> {
    return this.http.get<OptimizationJob>(`/api/optimizations/${id}`);
  }

  optimizations(date?: string, limit = 30): Observable<OptimizationJob[]> {
    let params = new HttpParams().set('limit', limit);
    if (date) {
      params = params.set('date', date);
    }
    return this.http.get<OptimizationJob[]>('/api/optimizations', { params });
  }
}
