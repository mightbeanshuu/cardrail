import { inject } from '@angular/core';
import { CanMatchFn, Routes } from '@angular/router';
import { APP_CONFIG } from './core/config/app-config';

/** The issuer simulator route only exists when the config enables dev tools. */
export const devToolsEnabled: CanMatchFn = () => inject(APP_CONFIG).devTools;

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'cardrail · card payment switch demo',
    loadComponent: () => import('./features/overview/overview-page.component').then((m) => m.OverviewPageComponent),
  },
  {
    path: 'terminal',
    title: 'Terminal · cardrail',
    loadComponent: () => import('./features/terminal/terminal-page.component').then((m) => m.TerminalPageComponent),
  },
  {
    path: 'authorizations',
    loadChildren: () => import('./features/authorizations/authorizations.routes').then((m) => m.AUTHORIZATION_ROUTES),
  },
  {
    path: 'audit',
    title: 'Audit chain · cardrail',
    loadComponent: () => import('./features/audit/audit-page.component').then((m) => m.AuditPageComponent),
  },
  {
    path: 'settlement',
    title: 'Settlement · cardrail',
    loadComponent: () =>
      import('./features/settlement/settlement-page.component').then((m) => m.SettlementPageComponent),
  },
  {
    path: 'dev/issuer',
    title: 'Issuer simulator · cardrail',
    canMatch: [devToolsEnabled],
    loadComponent: () =>
      import('./features/dev-issuer/issuer-simulator-page.component').then((m) => m.IssuerSimulatorPageComponent),
  },
  {
    path: '**',
    title: 'Not found · cardrail',
    loadComponent: () => import('./not-found.component').then((m) => m.NotFoundComponent),
  },
];
