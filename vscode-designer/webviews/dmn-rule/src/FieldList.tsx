import {
  Button,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxInput,
  ComboboxInputGroup,
  ComboboxItem,
  ComboboxList,
  ComboboxRoot,
  ComboboxTrigger,
  Flex,
  Label
} from '@axonivy/ui-components';
import { IvyIcons } from '@axonivy/ui-icons';
import { useTranslation } from 'react-i18next';
import type { RuleField } from './protocol';

export type FieldRow = { key: number; field: RuleField | null };

const MAX_RESULTS = 50;

const label = (field: RuleField) => `${field.dataClass}.${field.name}`;

/** Every word of the search matches the field, its data class, its type, its namespace or its project. */
const matches = (field: RuleField, query: string) => {
  const text = `${label(field)} ${field.type} ${field.namespace} ${field.project}`.toLowerCase();
  return query
    .toLowerCase()
    .split(/\s+/)
    .filter(word => word.length > 0)
    .every(word => text.includes(word));
};

type FieldSearchProps = {
  fields: RuleField[];
  value: RuleField | null;
  onChange: (field: RuleField | null) => void;
  autoFocus: boolean;
};

const FieldSearch = ({ fields, value, onChange, autoFocus }: FieldSearchProps) => {
  const { t } = useTranslation();
  return (
    <ComboboxRoot<RuleField>
      items={fields}
      value={value}
      onValueChange={field => onChange(field)}
      itemToStringLabel={label}
      isItemEqualToValue={(item, other) => item.id === other.id}
      filter={matches}
      limit={MAX_RESULTS}
      autoHighlight
    >
      <ComboboxInputGroup className='flex-1'>
        <ComboboxInput placeholder={t('searchPlaceholder')} autoFocus={autoFocus} />
        <ComboboxTrigger />
      </ComboboxInputGroup>
      <ComboboxContent>
        <ComboboxEmpty>{t('noFieldFound')}</ComboboxEmpty>
        <ComboboxList>
          {(field: RuleField) => (
            <ComboboxItem key={field.id} value={field}>
              <Flex alignItems='center' gap={2} className='w-full min-w-0'>
                <span className='truncate'>
                  {field.dataClass}.<b>{field.name}</b>
                </span>
                <span className='ml-auto shrink-0 text-n700'>{field.type}</span>
              </Flex>
            </ComboboxItem>
          )}
        </ComboboxList>
      </ComboboxContent>
    </ComboboxRoot>
  );
};

type FieldListProps = {
  title: string;
  help: string;
  fields: RuleField[];
  rows: FieldRow[];
  onChange: (rows: FieldRow[]) => void;
  focusKey?: number;
  onAdd: () => void;
  isTaken: (field: RuleField) => boolean;
};

export const FieldList = ({ title, help, fields, rows, onChange, focusKey, onAdd, isTaken }: FieldListProps) => {
  const { t } = useTranslation();
  const update = (key: number, field: RuleField | null) => onChange(rows.map(row => (row.key === key ? { key, field } : row)));
  const remove = (key: number) => onChange(rows.filter(row => row.key !== key));
  return (
    <Flex direction='column' gap={2}>
      <Flex direction='column' gap={1}>
        <Label>{title}</Label>
        <span className='text-xs text-n700'>{help}</span>
      </Flex>
      {rows.map(row => (
        <Flex key={row.key} alignItems='center' gap={1}>
          <FieldSearch
            fields={fields.filter(field => field.id === row.field?.id || !isTaken(field))}
            value={row.field}
            onChange={field => update(row.key, field)}
            autoFocus={row.key === focusKey}
          />
          <Button icon={IvyIcons.Trash} aria-label={t('remove')} title={t('remove')} onClick={() => remove(row.key)} />
        </Flex>
      ))}
      <Flex>
        <Button icon={IvyIcons.Plus} onClick={onAdd}>
          {t('addMore')}
        </Button>
      </Flex>
    </Flex>
  );
};
