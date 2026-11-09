/** QD1: joins truthy class strings. Components expose variants, not free className overrides. */
export function cx(...classes: Array<string | false | null | undefined>): string {
    return classes.filter(Boolean).join(' ');
}
