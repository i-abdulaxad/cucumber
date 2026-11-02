import type { Ref } from 'react';
import styles from './ui.module.css';

export interface TextFieldProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: 'text' | 'email' | 'password';
  autoComplete?: string;
  hint?: string;
  errors?: readonly string[];
  required?: boolean;
  inputRef?: Ref<HTMLInputElement>;
}

/** A labelled input whose hint and errors are linked with aria-describedby (D17). */
export function TextField({ id, label, value, onChange, type = 'text', autoComplete, hint, errors, required, inputRef }: TextFieldProps) {
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const invalid = errors !== undefined && errors.length > 0;
  const describedBy = [hint ? hintId : null, invalid ? errorId : null].filter(Boolean).join(' ');
  return (
    <div className={styles.field}>
      <label htmlFor={id}>{label}</label>
      {hint ? (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      ) : null}
      <input
        id={id}
        ref={inputRef}
        type={type}
        value={value}
        autoComplete={autoComplete}
        required={required}
        aria-invalid={invalid ? true : undefined}
        aria-describedby={describedBy || undefined}
        onChange={(event) => onChange(event.target.value)}
      />
      {invalid ? (
        <p id={errorId} className={styles.fieldError}>
          {errors.join(' ')}
        </p>
      ) : null}
    </div>
  );
}
