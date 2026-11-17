import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from '@/app/App';
import { RuntimeProvider } from '@/app/RuntimeProvider';
import '@/styles/index.css';

createRoot(document.getElementById('root')!).render(<StrictMode><RuntimeProvider><App /></RuntimeProvider></StrictMode>);
