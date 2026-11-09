import {StrictMode} from 'react';
import {createRoot} from 'react-dom/client';
import {App} from './app/App';
import {createQueryClient} from './app/queryClient';
import {createAppRouter} from './app/router';
import {restoreSession} from './auth/session';
import './ui/theme.css';

restoreSession();
const queryClient = createQueryClient();
const router = createAppRouter({queryClient});

const root = document.getElementById('root');
if (!root) throw new Error('The #root element is missing from index.html.');

createRoot(root).render(
    <StrictMode>
        <App router={router} queryClient={queryClient}/>
    </StrictMode>,
);
