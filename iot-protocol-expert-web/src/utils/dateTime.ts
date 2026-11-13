import dayjs from 'dayjs'

/** Format date-times for display; timezone-less values remain local wall-clock times. */
export function formatDateTime(value: string | number | null | undefined, empty = '—'): string {
  if (value == null || value === '') return empty
  const date = dayjs(value)
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm.ss') : String(value)
}
