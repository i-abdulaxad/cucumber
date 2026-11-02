import { useEffect, useRef, useState } from 'react';
import type { SummaryItem } from '../../ui/ErrorSummary';

/** Field errors plus the summary that takes focus after every failed submit (D17). */
export function useFormErrors<F extends string>() {
  const [fields, setFields] = useState<Partial<Record<F, string[]>>>({});
  const [summary, setSummary] = useState<SummaryItem[]>([]);
  const [attempt, setAttempt] = useState(0);
  const summaryRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (attempt > 0) summaryRef.current?.focus();
  }, [attempt]);

  return {
    fields,
    summary,
    summaryRef,
    show(nextFields: Partial<Record<F, string[]>>, nextSummary: SummaryItem[]) {
      setFields(nextFields);
      setSummary(nextSummary);
      setAttempt((n) => n + 1);
    },
    clear() {
      setFields({});
      setSummary([]);
    },
  };
}

export function codePoints(value: string): number {
  return [...value].length;
}

export function utf8Bytes(value: string): number {
  return new TextEncoder().encode(value).length;
}
