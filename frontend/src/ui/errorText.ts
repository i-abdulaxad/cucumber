import type {UiError} from '../api/problem';
import {uiStrings} from './strings';

/** The UI's own sentence for a mapped error (AC-9.1). */
export function errorText(error: UiError): string {
    return uiStrings.errors[error.kind];
}
