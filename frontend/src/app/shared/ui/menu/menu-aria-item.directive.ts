import { Directive, forwardRef, input } from '@angular/core';
import { MenuItem } from '@angular/aria/menu';

import type { AppMenuComponent } from './app-menu.component';

@Directive({
  selector: '[appMenuAriaItem]',
  standalone: true,
  providers: [{ provide: MenuItem, useExisting: forwardRef(() => AppMenuAriaItemDirective) }],
})
export class AppMenuAriaItemDirective extends MenuItem<unknown> {
  override readonly value = input<unknown>('app-menu-item');
  override readonly disabled = input(false);

  owner: AppMenuComponent | undefined;
}
