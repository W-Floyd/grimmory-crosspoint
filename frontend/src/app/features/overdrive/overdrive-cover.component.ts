import { Component, input } from '@angular/core';

/**
 * Cover thumbnail for the OverDrive tables that shows a larger preview on hover. Renders nothing when
 * there's no cover URL.
 */
@Component({
  selector: 'app-overdrive-cover',
  standalone: true,
  templateUrl: './overdrive-cover.component.html',
  styleUrl: './overdrive-cover.component.scss'
})
export class OverdriveCoverComponent {
  readonly src = input<string | null>(null);
}
