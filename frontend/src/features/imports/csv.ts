import type { ImportError } from '../../shared/api/contracts'

function cell(value: string, identifier = false): string {
  const safe =
    identifier || /^[\s\x00-\x1f]*[=+@-]/.test(value) || /^[\t\r\n]/.test(value)
      ? `'${value}`
      : value
  return `"${safe.replaceAll('"', '""')}"`
}

export function importErrorsCsv(errors: ImportError[]): string {
  return (
    '\ufeff行号,账号,错误原因\r\n' +
    errors
      .map((error) =>
        [String(error.row), cell(error.username, true), cell(error.message)].join(','),
      )
      .join('\r\n')
  )
}
