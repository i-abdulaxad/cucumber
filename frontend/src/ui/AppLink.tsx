import type { MouseEvent, ReactNode } from 'react';
import { useRouter } from '@tanstack/react-router';

/** A link that navigates inside the app when a router is present, and is a plain link otherwise. */
export function AppLink({ href, children, className }: { href: string; children: ReactNode; className?: string }) {
  const router = useRouter({ warn: false });
  function onClick(event: MouseEvent<HTMLAnchorElement>) {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    router.history.push(href);
  }
  return (
    <a href={href} className={className} onClick={router ? onClick : undefined}>
      {children}
    </a>
  );
}
