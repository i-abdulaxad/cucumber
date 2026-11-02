import { useEffect } from 'react';
import styles from './ui.module.css';

/** The page's single h1. It can take focus after navigation (D17) and names the document. */
export function PageHeading({ children }: { children: string }) {
  useEffect(() => {
    document.title = `${children} · Cucumber`;
  }, [children]);
  return (
    <h1 tabIndex={-1} className={styles.h1}>
      {children}
    </h1>
  );
}
