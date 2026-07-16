import { useState } from 'react';

/**
 * Generic sort/filter hook for table data.
 *
 * <p>Previously copy-pasted (and drifted) across the admin management views;
 * this is the superset variant: string filters substring-match
 * case-insensitively, array filters AND together (every value must match),
 * and sorting handles strings (locale, base sensitivity), booleans
 * (true first on asc), and numbers, with nulls always last.
 *
 * @param {string} defaultSortKey - Initial sort column.
 * @param {'asc'|'desc'} defaultSortDir - Initial sort direction.
 * @param {Object<string, function>} accessors - Map of sort key to value accessor function.
 * @returns {object} Sort/filter state and handlers:
 *   {@code sortKey}, {@code sortDir}, {@code handleSort(key)},
 *   {@code setFilter(key, value)}, {@code getFilterValue(key)}, {@code apply(items)}.
 */
export default function useSortAndFilter(defaultSortKey, defaultSortDir, accessors) {
  const [sortKey, setSortKey] = useState(defaultSortKey);
  const [sortDir, setSortDir] = useState(defaultSortDir);
  const [filters, setFilters] = useState({});

  function handleSort(key) {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
  }

  function setFilter(key, value) {
    setFilters((prev) => ({ ...prev, [key]: value }));
  }

  function getFilterValue(key) {
    return filters[key] || '';
  }

  function apply(items) {
    let result = [...items];

    // Filter
    for (const [key, value] of Object.entries(filters)) {
      if (!value || (Array.isArray(value) && value.length === 0)) continue;
      const accessor = accessors[key];
      if (!accessor) continue;
      if (Array.isArray(value)) {
        result = result.filter((item) => {
          const val = accessor(item);
          if (val == null) return false;
          const lower = String(val).toLowerCase();
          return value.every((v) => lower.includes(v.toLowerCase()));
        });
      } else {
        const lower = value.toLowerCase();
        result = result.filter((item) => {
          const val = accessor(item);
          return val != null && String(val).toLowerCase().includes(lower);
        });
      }
    }

    // Sort
    const accessor = accessors[sortKey];
    if (accessor) {
      result.sort((a, b) => {
        const va = accessor(a);
        const vb = accessor(b);
        if (va == null && vb == null) return 0;
        if (va == null) return 1;
        if (vb == null) return -1;
        if (typeof va === 'string') {
          const cmp = va.localeCompare(vb, undefined, { sensitivity: 'base' });
          return sortDir === 'asc' ? cmp : -cmp;
        }
        if (typeof va === 'boolean') {
          const cmp = va === vb ? 0 : va ? -1 : 1;
          return sortDir === 'asc' ? cmp : -cmp;
        }
        const cmp = va < vb ? -1 : va > vb ? 1 : 0;
        return sortDir === 'asc' ? cmp : -cmp;
      });
    }

    return result;
  }

  return { sortKey, sortDir, handleSort, setFilter, getFilterValue, apply };
}
