import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { CommonModule } from '@angular/common';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { Subscription } from 'rxjs';
import { AuthService } from './shared/auth.service';
import { MessageService } from './shared/message.service';
import { GlassMessageComponent } from './shared/glass-message';

@Component({selector:'app-root',imports:[CommonModule,RouterOutlet,RouterLink,RouterLinkActive,
  MatToolbarModule,MatButtonModule,GlassMessageComponent],templateUrl:'./app.html',styleUrl:'./app.scss'})
export class App implements OnInit, OnDestroy {
  private readonly auth=inject(AuthService);
  private readonly router=inject(Router);
  private readonly messages=inject(MessageService);
  private readonly routeUrl=signal(this.router.url);
  private routeSubscription?: Subscription;
  readonly user=this.auth.user;
  readonly staffWorkspace=computed(()=>this.auth.staffWorkspace?.() ?? false);
  readonly pageBlurred=computed(()=>!!this.messages.message());
  readonly showChrome=computed(()=>this.auth.isLoggedIn() && !this.routeUrl().startsWith('/login'));
  ngOnInit() {
    this.routeSubscription=this.router.events.subscribe(event=>{
      if(event instanceof NavigationEnd) this.routeUrl.set(event.urlAfterRedirects);
    });
    this.auth.bootstrap().subscribe();
  }
  logout(){this.auth.logout();}
  ngOnDestroy(){this.routeSubscription?.unsubscribe();}
}
