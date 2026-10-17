import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * How a payment moves through cardrail, drawn inline so it follows the theme. A wide layout for
 * desktop and a stacked one for phones; only one is displayed at a time.
 */
@Component({
  selector: 'app-architecture-diagram',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="arch">
      <svg class="arch__wide" viewBox="0 0 660 252" role="img" aria-labelledby="arch-w-title arch-w-desc">
        <title id="arch-w-title">cardrail architecture</title>
        <desc id="arch-w-desc">
          A terminal sends an authorization request to cardrail-switch, which validates it, tokenizes the card, checks
          for duplicates, applies risk rules and asks the issuer, or decides by stand-in processing. The switch keeps
          its ledger, outbox and audit chain in PostgreSQL and publishes events to cardrail-clearing, which produces
          settlement positions. This console calls both services over REST.
        </desc>
        <defs>
          <marker id="arch-w-arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L8,4 L0,8 z" class="arrowhead" />
          </marker>
        </defs>

        <!-- edges -->
        <line class="e" x1="126" y1="59" x2="174" y2="59" marker-start="url(#arch-w-arrow)" marker-end="url(#arch-w-arrow)" />
        <text class="l" x="150" y="50" text-anchor="middle">0100</text>
        <line class="e" x1="444" y1="59" x2="492" y2="59" marker-start="url(#arch-w-arrow)" marker-end="url(#arch-w-arrow)" />
        <text class="l" x="468" y="50" text-anchor="middle">ask</text>
        <line class="e" x1="240" y1="140" x2="240" y2="176" marker-end="url(#arch-w-arrow)" />
        <line class="e" x1="406" y1="140" x2="406" y2="176" marker-end="url(#arch-w-arrow)" />
        <text class="l" x="414" y="163">events</text>
        <line class="e" x1="476" y1="207" x2="518" y2="207" marker-end="url(#arch-w-arrow)" />
        <polyline class="e e--dash" points="126,207 150,207 150,112 174,112" fill="none" marker-end="url(#arch-w-arrow)" />
        <text class="l" x="155" y="163">REST</text>

        <!-- nodes -->
        <rect class="n" x="8" y="30" width="118" height="58" rx="4" />
        <text class="t" x="20" y="54">Terminal</text>
        <text class="s" x="20" y="72">you, on /terminal</text>

        <rect class="n n--key" x="176" y="12" width="268" height="128" rx="4" />
        <text class="t" x="190" y="36">cardrail-switch</text>
        <text class="s" x="190" y="53">Java 21 · Spring Boot · Resilience4j</text>
        <g class="pills">
          <rect class="pill" x="190" y="66" width="58" height="22" rx="3" /><text class="pt" x="219" y="81" text-anchor="middle">validate</text>
          <rect class="pill" x="254" y="66" width="62" height="22" rx="3" /><text class="pt" x="285" y="81" text-anchor="middle">tokenize</text>
          <rect class="pill" x="322" y="66" width="76" height="22" rx="3" /><text class="pt" x="360" y="81" text-anchor="middle">duplicates</text>
          <rect class="pill" x="190" y="96" width="76" height="22" rx="3" /><text class="pt" x="228" y="111" text-anchor="middle">risk rules</text>
          <rect class="pill" x="272" y="96" width="124" height="22" rx="3" /><text class="pt" x="334" y="111" text-anchor="middle">issuer or stand-in</text>
        </g>

        <rect class="n" x="494" y="30" width="118" height="58" rx="4" />
        <text class="t" x="506" y="54">Issuer</text>
        <text class="s" x="506" y="72">simulated bank</text>

        <rect class="n" x="8" y="178" width="118" height="58" rx="4" />
        <text class="t" x="20" y="202">This console</text>
        <text class="s" x="20" y="220">Angular</text>

        <rect class="n" x="176" y="178" width="140" height="58" rx="4" />
        <text class="t" x="188" y="202">PostgreSQL</text>
        <text class="s" x="188" y="220">ledger · outbox · audit</text>

        <rect class="n" x="336" y="178" width="140" height="58" rx="4" />
        <text class="t" x="348" y="202">cardrail-clearing</text>
        <text class="s" x="348" y="220">Spring Batch · own DB</text>

        <rect class="n" x="520" y="178" width="132" height="58" rx="4" />
        <text class="t" x="532" y="202">Settlement</text>
        <text class="s" x="532" y="220">positions sum to zero</text>
      </svg>

      <svg class="arch__narrow" viewBox="0 0 320 404" role="img" aria-labelledby="arch-n-title">
        <title id="arch-n-title">
          cardrail architecture: terminal to switch, switch to issuer or stand-in, PostgreSQL, events to clearing and
          settlement.
        </title>
        <defs>
          <marker id="arch-n-arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L8,4 L0,8 z" class="arrowhead" />
          </marker>
        </defs>
        <line class="e" x1="105" y1="50" x2="105" y2="76" marker-start="url(#arch-n-arrow)" marker-end="url(#arch-n-arrow)" />
        <text class="l" x="113" y="67">0100</text>
        <line class="e" x1="200" y1="128" x2="222" y2="128" marker-start="url(#arch-n-arrow)" marker-end="url(#arch-n-arrow)" />
        <line class="e" x1="60" y1="226" x2="60" y2="252" marker-end="url(#arch-n-arrow)" />
        <line class="e" x1="170" y1="226" x2="230" y2="252" marker-end="url(#arch-n-arrow)" />
        <text class="l" x="204" y="236">events</text>
        <line class="e" x1="240" y1="304" x2="240" y2="330" marker-end="url(#arch-n-arrow)" />

        <rect class="n" x="10" y="8" width="190" height="42" rx="4" />
        <text class="t" x="22" y="34">Terminal <tspan class="s">· you</tspan></text>

        <rect class="n n--key" x="10" y="78" width="190" height="148" rx="4" />
        <text class="t" x="22" y="100">cardrail-switch</text>
        <text class="s" x="22" y="116">Java 21 · Spring Boot</text>
        <rect class="pill" x="22" y="126" width="74" height="22" rx="3" /><text class="pt" x="59" y="141" text-anchor="middle">validate</text>
        <rect class="pill" x="102" y="126" width="86" height="22" rx="3" /><text class="pt" x="145" y="141" text-anchor="middle">tokenize</text>
        <rect class="pill" x="22" y="154" width="74" height="22" rx="3" /><text class="pt" x="59" y="169" text-anchor="middle">duplicates</text>
        <rect class="pill" x="102" y="154" width="86" height="22" rx="3" /><text class="pt" x="145" y="169" text-anchor="middle">risk rules</text>
        <rect class="pill" x="22" y="182" width="166" height="22" rx="3" /><text class="pt" x="105" y="197" text-anchor="middle">issuer or stand-in</text>

        <rect class="n" x="224" y="100" width="86" height="56" rx="4" />
        <text class="t" x="234" y="124">Issuer</text>
        <text class="s" x="234" y="141">simulated</text>

        <rect class="n" x="10" y="254" width="120" height="50" rx="4" />
        <text class="t" x="20" y="276">PostgreSQL</text>
        <text class="s" x="20" y="292">ledger · audit</text>

        <rect class="n" x="170" y="254" width="140" height="50" rx="4" />
        <text class="t" x="180" y="276">cardrail-clearing</text>
        <text class="s" x="180" y="292">Spring Batch</text>

        <rect class="n" x="170" y="332" width="140" height="50" rx="4" />
        <text class="t" x="180" y="354">Settlement</text>
        <text class="s" x="180" y="370">positions sum to zero</text>

        <text class="l" x="10" y="398">This console calls both services over REST.</text>
      </svg>
    </figure>
  `,
  styles: `
    .arch { margin: 0; }
    svg { display: block; width: 100%; height: auto; font-family: var(--font); }
    .arch__narrow { display: none; max-width: 420px; margin: 0 auto; }
    @media (max-width: 640px) {
      .arch__wide { display: none; }
      .arch__narrow { display: block; }
    }
    .n { fill: var(--surface); stroke: var(--border-strong); stroke-width: 1; }
    .n--key { stroke: var(--accent); stroke-width: 1.5; }
    .t { fill: var(--text); font-size: 13px; font-weight: 600; }
    .s { fill: var(--text-muted); font-size: 11px; font-weight: 400; }
    .pill { fill: var(--accent-soft); }
    .pt { fill: var(--accent); font-size: 11px; font-weight: 600; }
    .e { stroke: var(--text-muted); stroke-width: 1.25; }
    .e--dash { stroke-dasharray: 4 3; }
    .l { fill: var(--text-muted); font-size: 11px; }
    .arrowhead { fill: var(--text-muted); }
  `,
})
export class ArchitectureDiagramComponent {}
