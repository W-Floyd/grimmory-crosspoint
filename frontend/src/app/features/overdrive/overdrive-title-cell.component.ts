import { Component, input } from '@angular/core';

import { RouterLink } from '@angular/router';
import { TooltipModule } from 'primeng/tooltip';

/**
 * Shared title cell for the OverDrive catalog tables (search results, loans, holds): the title with
 * optional subtitle, and — when the title maps to a library book — an "In your library" link. The
 * cover is rendered in its own column by each table. Keeps the three tables consistent from one place.
 */
@Component({
  selector: 'app-overdrive-title-cell',
  standalone: true,
  imports: [RouterLink, TooltipModule],
  templateUrl: './overdrive-title-cell.component.html',
  styleUrl: './overdrive-title-cell.component.scss'
})
export class OverdriveTitleCellComponent {
  readonly title = input<string>('');
  readonly subtitle = input<string | null>();
  /** Id of the matched library book; when set, an "In your library" link is shown. Defaults to null
   * (not undefined) so an unbound bookId — e.g. on the holds table — doesn't render a bogus link. */
  readonly bookId = input<number | null>(null);
  /** Deep link to this title on libbyapp.com; when set, an external-link icon is shown. */
  readonly libbyUrl = input<string | null>(null);
}
