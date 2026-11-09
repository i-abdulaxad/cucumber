import type {ReactNode} from 'react';
import {Button as AriaButton, type ButtonProps as AriaButtonProps} from 'react-aria-components';
import {cx} from './cx';
import {Spinner} from './Spinner';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';
export type ButtonSize = 'sm' | 'md' | 'lg';

const base =
    'relative inline-flex select-none items-center justify-center gap-2 whitespace-nowrap rounded-md font-medium transition duration-120 ease-standard data-disabled:cursor-not-allowed data-disabled:opacity-50';

const variants: Record<ButtonVariant, string> = {
    primary: 'bg-primary text-on-primary shadow-xs data-hovered:bg-primary-hover data-pressed:bg-primary-hover',
    secondary:
        'border border-border bg-surface-raised text-text shadow-xs data-hovered:bg-surface-hover data-pressed:bg-surface-hover',
    ghost: 'text-text-muted data-hovered:bg-surface-hover data-hovered:text-text data-pressed:bg-surface-hover',
    danger: 'bg-danger text-on-danger shadow-xs data-hovered:bg-danger-hover data-pressed:bg-danger-hover',
};

const sizes: Record<ButtonSize, string> = {
    sm: 'h-7 px-2 text-sm',
    md: 'h-8 px-3 text-base',
    lg: 'h-10 px-4 text-md',
};

const iconSizes: Record<ButtonSize, string> = {sm: 'size-7', md: 'size-8', lg: 'size-10'};

export function buttonClasses(variant: ButtonVariant, size: ButtonSize, iconOnly = false): string {
    return cx(base, variants[variant], iconOnly ? iconSizes[size] : sizes[size]);
}

export interface ButtonProps extends Omit<AriaButtonProps, 'className' | 'style' | 'children'> {
    variant?: ButtonVariant;
    size?: ButtonSize;
    /** Icon-only buttons need an aria-label. */
    iconOnly?: boolean;
    /** Shows a spinner, keeps the label for width, sets aria-busy and ignores presses. */
    loading?: boolean;
    fullWidth?: boolean;
    children: ReactNode;
}

/** DS-9 Button on react-aria-components, styled through its data-* state attributes. */
export function Button({
                           variant = 'secondary',
                           size = 'md',
                           iconOnly = false,
                           loading = false,
                           fullWidth = false,
                           children,
                           onPress,
                           ...props
                       }: ButtonProps) {
    // Loading is handled here, not with react-aria isPending: its live announcement leaves a node labelled by an
    // element that disappears after navigation (axe role-img-alt).
    return (
        <AriaButton
            {...props}
            onPress={loading ? undefined : onPress}
            aria-busy={loading || undefined}
            className={cx(buttonClasses(variant, size, iconOnly), fullWidth && 'w-full')}
        >
            {loading ? (
                <>
                    <span className="inline-flex items-center gap-2 opacity-0">{children}</span>
                    <span className="absolute inset-0 inline-flex items-center justify-center">
            <Spinner/>
          </span>
                </>
            ) : (
                children
            )}
        </AriaButton>
    );
}
