import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { WarmUpService } from '../../core/warm-up/warm-up.service';
import { AUTHOR_HANDLE, AUTHOR_URL, REPO_URL } from '../../demo/demo-links';
import { ArchitectureDiagramComponent } from './architecture-diagram.component';

interface Walkthrough {
  readonly title: string;
  readonly text: string;
  readonly link: string;
  readonly fragment?: string;
  readonly query?: Record<string, string>;
  readonly cta: string;
}

const WALKTHROUGH: readonly Walkthrough[] = [
  {
    title: 'Send a payment',
    text: 'Act as a card terminal: pick a test card, an amount and a merchant, and read the response code. Send it again to see duplicate detection.',
    link: '/terminal',
    cta: 'Open the terminal',
  },
  {
    title: 'Watch stand-in processing',
    text: 'Slow the issuer down. The switch approves small amounts on its behalf and declines larger ones with 91.',
    link: '/terminal',
    fragment: 'stand-in',
    cta: 'Slow the issuer',
  },
  {
    title: 'Reverse or capture',
    text: 'Open an approved authorization and release its hold, or capture all or part of it.',
    link: '/authorizations',
    query: { status: 'AUTHORIZED' },
    cta: 'Open authorizations',
  },
  {
    title: 'Verify the audit chain',
    text: 'Recompute the hash chain over every state change the switch has recorded.',
    link: '/audit',
    cta: 'Verify',
  },
  {
    title: 'Run settlement',
    text: "Settle a day's captures into net positions per acquirer, issuer and network. They must sum to zero.",
    link: '/settlement',
    cta: 'Open settlement',
  },
];

@Component({
  selector: 'app-overview-page',
  imports: [RouterLink, ArchitectureDiagramComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview-page.component.html',
  styleUrl: './overview-page.component.css',
})
export class OverviewPageComponent {
  protected readonly warmUp = inject(WarmUpService);
  protected readonly steps = WALKTHROUGH;
  protected readonly repoUrl = REPO_URL;
  protected readonly authorUrl = AUTHOR_URL;
  protected readonly authorHandle = AUTHOR_HANDLE;

  protected statusWord(status: string): string {
    switch (status) {
      case 'ready':
        return 'ready';
      case 'failed':
        return 'not answering';
      case 'idle':
        return 'not checked';
      default:
        return 'waking up';
    }
  }
}
