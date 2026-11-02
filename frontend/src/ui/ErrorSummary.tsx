import { useId, type ReactNode, type Ref } from 'react';
import { uiStrings } from './strings';
import styles from './ui.module.css';

export interface SummaryItem {
  message: string;
  /** The input this message belongs to; activating the item focuses it. */
  fieldId?: string;
}

/** D17: after a failed submit, this summary takes focus; each field error links to its input. */
export function ErrorSummary({ items, children, summaryRef }: { items: readonly SummaryItem[]; children?: ReactNode; summaryRef?: Ref<HTMLDivElement> }) {
  const titleId = useId();
  return (
    <div role="alert" tabIndex={-1} ref={summaryRef} aria-labelledby={titleId} className={styles.summary}>
      <h2 id={titleId}>{uiStrings.summaryTitle}</h2>
      <ul>
        {items.map((item, index) => (
          <li key={`${item.fieldId ?? 'form'}-${index}`}>
            {item.fieldId ? (
              <a
                href={`#${item.fieldId}`}
                onClick={(event) => {
                  event.preventDefault();
                  if (item.fieldId) document.getElementById(item.fieldId)?.focus();
                }}
              >
                {item.message}
              </a>
            ) : (
              item.message
            )}
          </li>
        ))}
      </ul>
      {children}
    </div>
  );
}
