import type {Ref} from 'react';
import {FieldError, Input, Label, Text, TextField as AriaTextField} from 'react-aria-components';
import {cx} from './cx';

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
    /** Shows a visible required marker; off where every field is required. */
    showRequiredMarker?: boolean;
    readOnly?: boolean;
    inputRef?: Ref<HTMLInputElement>;
}

/** DS-9 TextField on react-aria-components: label, description and errors linked with aria-describedby. */
export function TextField({
                              id,
                              label,
                              value,
                              onChange,
                              type = 'text',
                              autoComplete,
                              hint,
                              errors,
                              required,
                              showRequiredMarker = false,
                              readOnly,
                              inputRef
                          }: TextFieldProps) {
    const invalid = errors !== undefined && errors.length > 0;
    return (
        <AriaTextField
            id={id}
            value={value}
            onChange={onChange}
            type={type}
            autoComplete={autoComplete}
            isRequired={required}
            isReadOnly={readOnly}
            isInvalid={invalid}
            validationBehavior="aria"
            className="flex flex-col gap-1.5"
        >
            <Label className="text-base font-medium text-text">
                {label}
                {showRequiredMarker && required ? (
                    <span aria-hidden="true" className="ml-1 text-danger">
            *
          </span>
                ) : null}
            </Label>
            {hint ? (
                <Text slot="description" className="text-sm text-text-muted">
                    {hint}
                </Text>
            ) : null}
            <Input
                ref={inputRef}
                className={cx(
                    'h-10 rounded-md border bg-surface-raised px-3 text-base text-text shadow-xs outline-none transition duration-120 ease-standard placeholder:text-text-muted data-focus-visible:border-primary data-focused:border-primary',
                    invalid ? 'border-danger' : 'border-border-control',
                    readOnly && 'bg-surface-hover',
                )}
            />
            <FieldError className="text-sm font-medium text-danger">{errors?.join(' ')}</FieldError>
        </AriaTextField>
    );
}
