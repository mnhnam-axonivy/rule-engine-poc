import { getVscodeLanguage } from '@axonivy/vscode-webview-common';
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import deTranslation from './translation/dmn-rule/de.json';
import enTranslation from './translation/dmn-rule/en.json';
import jaTranslation from './translation/dmn-rule/ja.json';

export const initTranslation = () => {
  if (i18n.isInitializing || i18n.isInitialized) return;
  i18n.use(initReactI18next).init({
    debug: false,
    lng: getVscodeLanguage(),
    supportedLngs: ['de', 'en', 'ja'],
    fallbackLng: 'en',
    ns: ['dmn-rule'],
    defaultNS: 'dmn-rule',
    resources: {
      de: { 'dmn-rule': deTranslation },
      en: { 'dmn-rule': enTranslation },
      ja: { 'dmn-rule': jaTranslation }
    }
  });
};
