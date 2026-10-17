import { Routes } from '@angular/router';

export const AUTHORIZATION_ROUTES: Routes = [
  {
    path: '',
    title: 'Authorizations · cardrail',
    loadComponent: () => import('./authorization-list.component').then((m) => m.AuthorizationListComponent),
  },
  {
    path: ':id',
    title: 'Authorization · cardrail',
    loadComponent: () => import('./authorization-detail.component').then((m) => m.AuthorizationDetailComponent),
  },
];
