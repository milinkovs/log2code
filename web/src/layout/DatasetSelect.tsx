import { ChevronDown, Database } from 'lucide-react';
import type { ChangeEvent } from 'react';
import { useSearchParams } from 'react-router';
import { useDatasets } from '../api/queries';

/** URL query parameter holding the selected dataset; same name as the API parameter. */
export const DATASET_PARAM = 'datasetId';

/**
 * Dataset selector. The choice lives in the URL (`?datasetId=`); no parameter means all datasets,
 * so deep links such as `/logs/<id>` from Dashboards work without knowing the dataset.
 */
export function DatasetSelect() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { data: datasets, isError } = useDatasets();
  const selected = searchParams.get(DATASET_PARAM) ?? '';

  const onChange = (event: ChangeEvent<HTMLSelectElement>) => {
    const value = event.target.value;
    setSearchParams((previous) => {
      const next = new URLSearchParams(previous);
      if (value) next.set(DATASET_PARAM, value);
      else next.delete(DATASET_PARAM);
      return next;
    });
  };

  // A dataset from the URL that the API does not (yet) list is still shown, not silently dropped.
  const options = datasets ?? [];
  const selectedMissing = selected !== '' && !options.some((d) => d.datasetId === selected);

  return (
    <div className="select">
      <Database size={14} className="select__icon" aria-hidden="true" />
      <select
        aria-label="Dataset"
        value={selected}
        onChange={onChange}
        aria-invalid={isError || undefined}
      >
        <option value="">All datasets</option>
        {selectedMissing && <option value={selected}>{selected}</option>}
        {options.map((d) => (
          <option key={d.datasetId} value={d.datasetId}>
            {d.datasetId} ({d.count.toLocaleString('en-US')})
          </option>
        ))}
      </select>
      <ChevronDown size={14} className="select__chevron" aria-hidden="true" />
    </div>
  );
}
