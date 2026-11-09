import type {ReactNode} from 'react';
import {Link} from 'react-aria-components';
import {buttonClasses, type ButtonSize, type ButtonVariant} from './Button';
import {cx} from './cx';

export type LinkVariant = 'inline' | 'subtle' | ButtonVariant;

const linkVariants: Record<'inline' | 'subtle', string> = {
    inline: 'rounded-sm font-medium text-primary underline-offset-4 data-hovered:underline',
    subtle: 'rounded-sm text-text-muted data-hovered:text-text',
};

/**
 * An in-app link. Inside the app it navigates through the router (the react-aria RouterProvider in App);
 * elsewhere it is a plain link. `variant` may be a Button variant to look like a button.
 */
export function AppLink({href, children, variant = 'inline', size = 'md'}: {
    href: string;
    children: ReactNode;
    variant?: LinkVariant;
    size?: ButtonSize
}) {
    const className = variant === 'inline' || variant === 'subtle' ? linkVariants[variant] : buttonClasses(variant, size);
    return (
        <Link href={href} className={cx(className, 'cursor-pointer')}>
            {children}
        </Link>
    );
}
