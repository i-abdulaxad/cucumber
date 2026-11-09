// D17 and AC-10.2: after navigation focus moves to the page h1, except when a drawer item was chosen; then focus
// returns to the drawer's menu button instead.
let skipUntil = 0;

export function skipNextHeadingFocus(): void {
    skipUntil = Date.now() + 1000;
}

export function shouldFocusHeading(): boolean {
    if (Date.now() < skipUntil) {
        skipUntil = 0;
        return false;
    }
    return true;
}
