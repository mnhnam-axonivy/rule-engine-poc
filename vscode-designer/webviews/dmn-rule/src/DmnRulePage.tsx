import { Button, Flex, Message, Spinner } from '@axonivy/ui-components';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { HOST_EXTENSION } from 'vscode-messenger-common';
import type { Messenger } from 'vscode-messenger-webview';
import { FieldList, type FieldRow } from './FieldList';
import { DmnRuleApplyRequest, DmnRuleInitRequest, DmnRuleSkipNotification, type DmnRuleInit, type RuleField } from './protocol';

const chosenIds = (rows: FieldRow[]) => rows.flatMap(row => (row.field ? [row.field.id] : []));

export const DmnRulePage = ({ messenger }: { messenger: Messenger }) => {
  const { t } = useTranslation();
  const nextKeyRef = useRef(2);
  const [init, setInit] = useState<DmnRuleInit>();
  const [inputs, setInputs] = useState<FieldRow[]>([{ key: 0, field: null }]);
  const [outputs, setOutputs] = useState<FieldRow[]>([{ key: 1, field: null }]);
  const [focusKey, setFocusKey] = useState<number>(0);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    messenger.sendRequest(DmnRuleInitRequest, HOST_EXTENSION).then(setInit);
  }, [messenger]);

  if (!init) {
    return (
      <Flex alignItems='center' justifyContent='center' className='h-screen'>
        <Spinner />
      </Flex>
    );
  }

  const taken = new Set([...chosenIds(inputs), ...chosenIds(outputs)]);
  const isTaken = (field: RuleField) => taken.has(field.id);
  const addRow = (rows: FieldRow[], setRows: (rows: FieldRow[]) => void) => () => {
    const key = nextKeyRef.current++;
    setRows([...rows, { key, field: null }]);
    setFocusKey(key);
  };
  const apply = async () => {
    setBusy(true);
    try {
      const result = await messenger.sendRequest(DmnRuleApplyRequest, HOST_EXTENSION, {
        inputs: chosenIds(inputs),
        outputs: chosenIds(outputs)
      });
      setError(result.error);
    } finally {
      setBusy(false);
    }
  };

  const skip = () => messenger.sendNotification(DmnRuleSkipNotification, HOST_EXTENSION);
  const header = (
    <Flex direction='column' gap={1}>
      <h2 className='m-0 text-lg font-semibold'>{t('title', { model: init.model })}</h2>
      <span className='text-n700'>{t('intro')}</span>
    </Flex>
  );

  if (init.fields.length === 0) {
    return (
      <Flex direction='column' gap={4} className='max-w-3xl p-6'>
        {header}
        <Message variant='warning' message={t('noDataClasses')} />
        <Flex>
          <Button size='large' onClick={skip}>
            {t('skip')}
          </Button>
        </Flex>
      </Flex>
    );
  }

  return (
    <Flex direction='column' gap={4} className='max-w-3xl p-6'>
      {header}
      <FieldList
        title={t('inputs')}
        help={t('inputsHelp')}
        fields={init.fields}
        rows={inputs}
        onChange={setInputs}
        focusKey={focusKey}
        onAdd={addRow(inputs, setInputs)}
        isTaken={isTaken}
      />
      <FieldList
        title={t('outputs')}
        help={t('outputsHelp')}
        fields={init.fields}
        rows={outputs}
        onChange={setOutputs}
        focusKey={focusKey}
        onAdd={addRow(outputs, setOutputs)}
        isTaken={isTaken}
      />
      {error && <Message variant='error' message={error} />}
      <Flex gap={2}>
        <Button
          variant='primary'
          size='large'
          disabled={busy || chosenIds(inputs).length === 0 || chosenIds(outputs).length === 0}
          onClick={apply}
        >
          {t('create')}
        </Button>
        <Button size='large' onClick={skip}>
          {t('skip')}
        </Button>
      </Flex>
    </Flex>
  );
};
