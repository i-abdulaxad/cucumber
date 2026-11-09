import {Breadcrumb, Breadcrumbs as AriaBreadcrumbs, Link} from 'react-aria-components';

export interface Crumb {
    label: string;
    href?: string;
}

/** DS-9 Breadcrumbs: links with a "/" separator; the last item is current and not a link. */
export function Breadcrumbs({items}: { items: readonly Crumb[] }) {
    return (
        <AriaBreadcrumbs className="flex flex-wrap items-center gap-1.5 text-sm text-text-muted">
            {items.map((item, index) => {
                const last = index === items.length - 1;
                return (
                    <Breadcrumb key={`${item.label}-${index}`} className="flex items-center gap-1.5">
                        {last || !item.href ? (
                            <span className={last ? 'font-medium text-text' : undefined}
                                  aria-current={last ? 'page' : undefined}>
                {item.label}
              </span>
                        ) : (
                            <Link href={item.href} className="rounded-sm data-hovered:text-text">
                                {item.label}
                            </Link>
                        )}
                        {last ? null : <span aria-hidden="true">/</span>}
                    </Breadcrumb>
                );
            })}
        </AriaBreadcrumbs>
    );
}
