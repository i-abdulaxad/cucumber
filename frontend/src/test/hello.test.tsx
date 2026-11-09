import {describe, expect, it} from 'vitest';
import {render, screen} from '@testing-library/react';

describe('test harness (F1)', () => {
    it('renders "Hello" through Testing Library', () => {
        render(<p>Hello</p>);
        expect(screen.getByText('Hello')).toBeTruthy();
    });
});
