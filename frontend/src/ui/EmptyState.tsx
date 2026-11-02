import type { ReactNode } from 'react';
import styles from './ui.module.css';

/** Explains why a view is empty; the action is passed only when the role allows it. */
export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className={styles.empty}>
      <p>
        <strong>{title}</strong>
      </p>
      {children}
      {action ? <div className={styles.actions}>{action}</div> : null}
    </div>
  );
}
