import {render} from '@testing-library/react';
import {createMemoryHistory} from '@tanstack/react-router';
import {App} from '../app/App';
import {createQueryClient} from '../app/queryClient';
import {createAppRouter} from '../app/router';

export function renderApp(initialPath: string) {
    const queryClient = createQueryClient();
    const history = createMemoryHistory({initialEntries: [initialPath]});
    const router = createAppRouter({queryClient, history});
    const view = render(<App router={router} queryClient={queryClient}/>);
    const href = () => router.state.location.href;
    return {...view, router, queryClient, history, href};
}
