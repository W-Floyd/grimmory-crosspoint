import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../../core/config/api-config';

export type OpdsSortOrder = 'RECENT' | 'TITLE_ASC' | 'TITLE_DESC' | 'AUTHOR_ASC' | 'AUTHOR_DESC' | 'SERIES_ASC' | 'SERIES_DESC' | 'RATING_ASC' | 'RATING_DESC';

export interface OpdsUserV2CreateRequest {
  username: string;
  password: string;
  sortOrder?: OpdsSortOrder;
  defaultPreset?: string | null;
}

export interface OpdsUserV2 {
  id: number;
  userId: number;
  username: string;
  sortOrder?: OpdsSortOrder;
  defaultPreset?: string | null;
}

export interface OpdsDevicePreset {
  id: string;
  label: string;
  brand?: string | null;
  model?: string | null;
  maxWidth: number;
  maxHeight: number;
  jpegQuality: number;
  grayscale: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class OpdsService {

  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/v2/opds-users`;
  private readonly devicePresetsUrl = `${API_CONFIG.BASE_URL}/api/v1/opds-device-presets`;
  private http = inject(HttpClient);

  getUser(): Observable<OpdsUserV2[]> {
    return this.http.get<OpdsUserV2[]>(this.baseUrl);
  }

  getDevicePresets(): Observable<OpdsDevicePreset[]> {
    return this.http.get<OpdsDevicePreset[]>(this.devicePresetsUrl);
  }

  createUser(user: OpdsUserV2CreateRequest): Observable<OpdsUserV2> {
    return this.http.post<OpdsUserV2>(this.baseUrl, user);
  }

  updateUser(id: number, sortOrder: OpdsSortOrder, defaultPreset?: string | null): Observable<OpdsUserV2> {
    return this.http.patch<OpdsUserV2>(`${this.baseUrl}/${id}`, {sortOrder, defaultPreset: defaultPreset ?? null});
  }

  deleteCredential(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
