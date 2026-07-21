import {HttpClient} from '@angular/common/http';
import {Injectable, inject} from '@angular/core';
import {API_CONFIG} from '../../../core/config/api-config';
import {AuthService} from '../../../shared/service/auth.service';

export interface CbxPageInfo {
  pageNumber: number;
  displayName: string;
}

@Injectable({providedIn: 'root'})
export class CbxReaderService {

  private readonly pagesUrl = `${API_CONFIG.BASE_URL}/api/v1/cbx`;
  private readonly imageUrl = `${API_CONFIG.BASE_URL}/api/v1/media/book`;
  private authService = inject(AuthService);
  private http = inject(HttpClient);

  private getToken(): string | null {
    return this.authService.getInternalAccessToken();
  }

  private appendToken(url: string): string {
    const token = this.getToken();
    return token ? `${url}${url.includes('?') ? '&' : '?'}token=${token}` : url;
  }

  // fileId takes precedence server-side; it disambiguates two files of the same format.
  private formatParam(bookType?: string, fileId?: number): string {
    if (fileId != null) {
      return `?fileId=${fileId}`;
    }
    if (bookType) {
      return `?bookType=${bookType}`;
    }
    return '';
  }

  getAvailablePages(bookId: number, bookType?: string, fileId?: number) {
    const url = `${this.pagesUrl}/${bookId}/pages${this.formatParam(bookType, fileId)}`;
    return this.http.get<number[]>(this.appendToken(url));
  }

  getPageInfo(bookId: number, bookType?: string, fileId?: number) {
    const url = `${this.pagesUrl}/${bookId}/page-info${this.formatParam(bookType, fileId)}`;
    return this.http.get<CbxPageInfo[]>(this.appendToken(url));
  }

  getPageImageUrl(bookId: number, page: number, bookType?: string, fileId?: number): string {
    const url = `${this.imageUrl}/${bookId}/cbx/pages/${page}${this.formatParam(bookType, fileId)}`;
    return this.appendToken(url);
  }
}
