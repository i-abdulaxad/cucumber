import {uiStrings} from './strings';

/** The product mark: a 24 px primary rounded square with a "C", plus the wordmark (DS-7). */
export function Logo({size = 'md'}: { size?: 'md' | 'lg' }) {
    return (
        <span className="inline-flex items-center gap-2">
      <span
          aria-hidden="true"
          className={
              size === 'lg'
                  ? 'inline-flex size-10 items-center justify-center rounded-lg bg-primary text-xl font-semibold text-on-primary shadow-sm'
                  : 'inline-flex size-6 items-center justify-center rounded-md bg-primary text-sm font-semibold text-on-primary'
          }
      >
        C
      </span>
      <span
          className={size === 'lg' ? 'text-lg font-semibold tracking-tight' : 'text-base font-semibold tracking-tight'}>
        {uiStrings.productName}
      </span>
    </span>
    );
}
