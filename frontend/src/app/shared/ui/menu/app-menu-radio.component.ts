import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
} from '@angular/core';
import { LucideCheck } from '@lucide/angular';

import { AppMenuComponent } from './app-menu.component';
import { AppMenuAriaItemDirective } from './menu-aria-item.directive';
import { setupMenuItem } from './menu-item-setup';
import { AppMenuRadioGroupComponent } from './app-menu-radio-group.component';
import {
  appMenuCheckIconClass,
  appMenuItemRowClass,
  appMenuLabelClass,
  appMenuLeadingSlotClass,
  appMenuShortcutClass,
} from './menu.styles';

@Component({
  selector: 'app-menu-radio',
  standalone: true,
  imports: [LucideCheck],
  hostDirectives: [{ directive: AppMenuAriaItemDirective, inputs: ['disabled'] }],
  host: {
    '[class]': 'rowClass',
    '[attr.role]': "'menuitemradio'",
    '[attr.aria-checked]': 'checked()',
    '(click)': 'onEvent($event)',
    '(keydown)': 'onEvent($event)',
  },
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span [class]="leadingSlotClass">
      @if (checked()) {
        <svg lucideCheck [class]="checkClass" aria-hidden="true"></svg>
      }
    </span>
    <span [class]="labelClass" data-menu-label><ng-content /></span>
    @if (shortcut()) {
      <span [class]="shortcutClass" aria-hidden="true">{{ shortcut() }}</span>
    }
  `,
})
export class AppMenuRadioComponent<T> {
  readonly value = input.required<T>();
  readonly shortcut = input('');
  readonly closeOnSelect = input(true, { transform: booleanAttribute });
  readonly searchLabel = input('');

  readonly selected = output<T>();

  private readonly group = inject<AppMenuRadioGroupComponent<T>>(AppMenuRadioGroupComponent);
  private readonly menuItem = inject(AppMenuAriaItemDirective);
  private readonly owner = inject(AppMenuComponent);

  protected readonly checked = computed(() => this.group.isSelected(this.value()));
  protected readonly rowClass = appMenuItemRowClass('default');
  protected readonly leadingSlotClass = appMenuLeadingSlotClass;
  protected readonly checkClass = appMenuCheckIconClass;
  protected readonly labelClass = appMenuLabelClass;
  protected readonly shortcutClass = appMenuShortcutClass;

  constructor() {
    setupMenuItem(this.searchLabel);
  }

  protected onEvent(event: Event): void {
    if (event instanceof KeyboardEvent && (event.repeat || (event.key !== 'Enter' && event.key !== ' '))) return;
    event.stopPropagation();
    event.preventDefault();
    if (this.menuItem.disabled()) return;
    this.choose();
  }

  private choose(): void {
    this.group.select(this.value());
    this.selected.emit(this.value());
    if (this.closeOnSelect()) this.owner.closeChain();
  }
}
