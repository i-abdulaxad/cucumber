import { useEffect, type MouseEvent } from 'react';
import { Link, Outlet, useRouter } from '@tanstack/react-router';
import { endSession } from '../auth/session';
import { useSession } from '../auth/useSession';
import { appStrings } from './strings';
import styles from './shell.module.css';

function focusMain(event: MouseEvent<HTMLAnchorElement>) {
  event.preventDefault();
  document.getElementById('main')?.focus();
}

/** Root layout: skip link, header, one main landmark. Focus moves to the page h1 after navigation (D17). */
export function Shell() {
  const router = useRouter();
  const session = useSession();

  useEffect(
    () =>
      router.subscribe('onResolved', (event) => {
        if (!event.fromLocation || !event.pathChanged) return;
        setTimeout(() => {
          const heading = document.querySelector<HTMLElement>('main h1');
          (heading ?? document.getElementById('main'))?.focus();
        }, 0);
      }),
    [router],
  );

  return (
    <>
      <a href="#main" className={styles.skip} onClick={focusMain}>
        {appStrings.skipLink}
      </a>
      <header className={styles.header}>
        <nav aria-label={appStrings.mainNav}>
          <Link to="/" className={styles.brand}>
            {appStrings.appName}
          </Link>
        </nav>
        {session ? (
          <div className={styles.user}>
            <span>
              <span className="visually-hidden">{appStrings.signedInAs} </span>
              {session.email}
            </span>
            <button type="button" className={styles.logout} onClick={() => endSession('logout')}>
              {appStrings.logOut}
            </button>
          </div>
        ) : null}
      </header>
      <main id="main" tabIndex={-1} className={styles.main}>
        <Outlet />
      </main>
    </>
  );
}
