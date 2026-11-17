import { useEffect, useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight, Download, Search } from 'lucide-react';
import type { ArtifactRendererProps } from './MarkdownArtifactRenderer';
import styles from './SpreadsheetArtifactRenderer.module.css';

const MAX_ROWS = 2000;
const MAX_COLS = 80;
const PAGE_ROWS = 80;
type Sheet = { name: string; rows: string[][]; columns: number; truncated: boolean };

function columnLabel(index: number): string {
  let n = index + 1;
  let label = '';
  while (n > 0) { label = String.fromCharCode(65 + (n - 1) % 26) + label; n = Math.floor((n - 1) / 26); }
  return label;
}

/** master 的工作表解析和上限，增加分页 DOM 预算以保持长表可操作。 */
async function readSheets(preview: ArtifactRendererProps['preview']): Promise<Sheet[]> {
  const xlsx = await import('xlsx');
  let officeData: ArrayBuffer | undefined;
  if (preview.kind === 'binary') {
    const response = await fetch(preview.objectUrl);
    if (!response.ok) throw new Error('文件内容不可读取');
    officeData = await response.arrayBuffer();
    const signature = new Uint8Array(officeData, 0, Math.min(4, officeData.byteLength));
    if (signature.length < 4 || signature[0] !== 80 || signature[1] !== 75
        || signature[2] !== 3 || signature[3] !== 4) throw new Error('工作表文件格式无效');
  }
  const workbook = preview.kind === 'text'
    ? xlsx.read(preview.content, { type: 'string', raw: false, FS: ',' })
    : xlsx.read(officeData!, { type: 'array', cellDates: true });
  return workbook.SheetNames.map((name) => {
    const worksheet = workbook.Sheets[name];
    const originalRange = worksheet?.['!ref'];
    const range = originalRange ? xlsx.utils.decode_range(originalRange) : undefined;
    const truncated = Boolean(range && (range.e.r + 1 > MAX_ROWS || range.e.c + 1 > MAX_COLS));
    const boundedRange = range ? { s: range.s, e: { r: Math.min(range.e.r, MAX_ROWS - 1), c: Math.min(range.e.c, MAX_COLS - 1) } } : undefined;
    const matrix = worksheet ? xlsx.utils.sheet_to_json<unknown[]>(worksheet, {
      header: 1, defval: '', raw: false, blankrows: true,
      ...(boundedRange ? { range: xlsx.utils.encode_range(boundedRange) } : {}),
    }) : [];
    const columns = Math.max(10, Math.min(MAX_COLS, boundedRange ? boundedRange.e.c + 1 : 10));
    const rows = matrix.slice(0, MAX_ROWS).map((row) =>
      Array.from({ length: columns }, (_, index) => String(row[index] ?? '')));
    return { name, rows, columns, truncated };
  });
}

export function SpreadsheetArtifactRenderer({ preview, downloadHref }: ArtifactRendererProps) {
  const [sheets, setSheets] = useState<Sheet[]>([]);
  const [active, setActive] = useState(0);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<{ row: number; col: number }>();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  useEffect(() => {
    let cancelled = false;
    setStatus('loading');
    void readSheets(preview).then((result) => {
      if (cancelled) return;
      setSheets(result);
      setActive(0);
      setPage(0);
      setSelected(undefined);
      setStatus('ready');
    }).catch(() => { if (!cancelled) setStatus('error'); });
    return () => { cancelled = true; };
  }, [preview]);

  const sheet = sheets[active];
  const query = search.trim().toLocaleLowerCase();
  const matches = useMemo(() => {
    if (!sheet || !query) return new Set<string>();
    const found = new Set<string>();
    sheet.rows.forEach((row, r) => row.forEach((value, c) => {
      if (value.toLocaleLowerCase().includes(query)) found.add(`${r}:${c}`);
    }));
    return found;
  }, [sheet, query]);
  const pageCount = Math.max(1, Math.ceil((sheet?.rows.length ?? 0) / PAGE_ROWS));
  const firstRow = page * PAGE_ROWS;
  const visibleRows = sheet?.rows.slice(firstRow, firstRow + PAGE_ROWS) ?? [];
  const selectedValue = selected ? sheet?.rows[selected.row]?.[selected.col] ?? '' : '';
  const selectedRef = selected ? `${columnLabel(selected.col)}${selected.row + 1}` : '';

  if (status === 'loading') return <p className={styles.state} role="status">正在读取工作表</p>;
  if (status === 'error' || !sheet) return <div className={styles.state} role="alert">
    <p>工作表无法预览</p>{downloadHref && <a href={downloadHref}><Download size={15} />下载原件</a>}
  </div>;
  return <div className={styles.reader}>
    <div className={styles.toolbar}>
      <span className={styles.address}>{selectedRef || '-'}</span>
      <span className={styles.value} title={selectedValue}>{selectedValue || sheet.name}</span>
      <label className={styles.search}><Search size={15} /><input type="search" aria-label="搜索工作表" placeholder="搜索" value={search} onChange={(event) => setSearch(event.target.value)} /></label>
    </div>
    {sheet.truncated && <p className={styles.notice}>仅预览前 {MAX_ROWS} 行、{MAX_COLS} 列；完整内容请下载原件。</p>}
    <div className={styles.grid}>
      <table><thead><tr><th className={styles.corner} />{Array.from({ length: sheet.columns }, (_, index) => <th key={index}>{columnLabel(index)}</th>)}</tr></thead>
        <tbody>{visibleRows.map((row, offset) => <tr key={firstRow + offset}>
          <th>{firstRow + offset + 1}</th>{row.map((value, col) => {
            const rowIndex = firstRow + offset;
            const isSelected = selected?.row === rowIndex && selected.col === col;
            return <td key={col} className={`${matches.has(`${rowIndex}:${col}`) ? styles.match : ''} ${isSelected ? styles.selected : ''}`}>
              <button type="button" title={value} aria-label={`${columnLabel(col)}${rowIndex + 1}: ${value}`} onClick={() => setSelected({ row: rowIndex, col })}>{value}</button>
            </td>;
          })}</tr>)}</tbody></table>
    </div>
    <div className={styles.footer}>
      <div className={styles.tabs} role="tablist" aria-label="工作表">
        {sheets.map((item, index) => <button key={`${item.name}-${index}`} type="button" role="tab" aria-selected={active === index} onClick={() => { setActive(index); setPage(0); setSelected(undefined); }}>{item.name}</button>)}
      </div>
      <div className={styles.pager}>
        <button type="button" title="上一页" aria-label="上一页" disabled={page === 0} onClick={() => setPage(page - 1)}><ChevronLeft size={15} /></button>
        <span>{page + 1} / {pageCount}</span>
        <button type="button" title="下一页" aria-label="下一页" disabled={page + 1 >= pageCount} onClick={() => setPage(page + 1)}><ChevronRight size={15} /></button>
      </div>
    </div>
  </div>;
}
