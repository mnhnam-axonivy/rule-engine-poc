import { ThemeProvider } from '@axonivy/ui-components';
import '@axonivy/vscode-webview-common/css/colors.css';
import * as React from 'react';
import { createRoot } from 'react-dom/client';
import { Messenger, type VsCodeApi } from 'vscode-messenger-webview';
import { DmnRulePage } from './DmnRulePage';
import { initTranslation } from './i18n';
import './index.css';

declare function acquireVsCodeApi(): VsCodeApi;

const start = () => {
  const rootElement = document.getElementById('root');
  if (!rootElement) {
    throw new Error('Root element not found');
  }
  const messenger = new Messenger(acquireVsCodeApi());
  messenger.start();
  initTranslation();
  createRoot(rootElement).render(
    <React.StrictMode>
      <ThemeProvider disabled={true}>
        <DmnRulePage messenger={messenger} />
      </ThemeProvider>
    </React.StrictMode>
  );
};

start();
