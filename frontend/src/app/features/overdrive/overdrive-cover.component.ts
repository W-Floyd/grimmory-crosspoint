import { Component, input } from '@angular/core';
import { Image } from 'primeng/image';

/**
 * Cover thumbnail for the OverDrive tables. Clicking opens the full-size image in a preview overlay
 * (with zoom/rotate), matching the metadata page. Renders nothing when there's no cover URL.
 */
@Component({
  selector: 'app-overdrive-cover',
  standalone: true,
  imports: [Image],
  templateUrl: './overdrive-cover.component.html',
  styleUrl: './overdrive-cover.component.scss'
})
export class OverdriveCoverComponent {
  readonly src = input<string | null>(null);
}
