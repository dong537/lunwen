import { Empty, Typography, theme } from 'antd'

export interface DailyPoint {
  date: string
  count: number
}

export function DailyTrend({ points, label }: { points: DailyPoint[]; label: string }) {
  const { token } = theme.useToken()
  if (points.length === 0) return <Empty description="所选范围内没有提交记录" />
  const width = 640
  const height = 190
  const left = 38
  const right = 12
  const top = 16
  const bottom = 32
  const plotHeight = height - top - bottom
  const plotWidth = width - left - right
  const maximum = Math.max(1, ...points.map((point) => point.count))
  const coordinates = points.map((point, index) => ({
    x: left + (points.length === 1 ? plotWidth / 2 : (index / (points.length - 1)) * plotWidth),
    y: top + plotHeight - (point.count / maximum) * plotHeight,
  }))
  const line = coordinates.map(({ x, y }) => x + ',' + y).join(' ')
  const area =
    left +
    ',' +
    (top + plotHeight) +
    ' ' +
    line +
    ' ' +
    (left + plotWidth) +
    ',' +
    (top + plotHeight)
  return (
    <div style={{ width: '100%', overflowX: 'auto' }}>
      <svg
        role="img"
        aria-label={
          label + '：' + points.map((point) => point.date + ' ' + point.count + ' 次').join('，')
        }
        viewBox={'0 0 ' + width + ' ' + height}
        style={{ display: 'block', width: '100%', minWidth: 300, maxHeight: 230 }}
      >
        <line
          x1={left}
          y1={top + plotHeight}
          x2={width - right}
          y2={top + plotHeight}
          stroke={token.colorBorder}
        />
        <line
          x1={left}
          y1={top}
          x2={width - right}
          y2={top}
          stroke={token.colorBorderSecondary}
          strokeDasharray="4 4"
        />
        <text
          x={left - 8}
          y={top + 4}
          textAnchor="end"
          fill={token.colorTextSecondary}
          fontSize="12"
        >
          {maximum}
        </text>
        <text
          x={left - 8}
          y={top + plotHeight + 4}
          textAnchor="end"
          fill={token.colorTextSecondary}
          fontSize="12"
        >
          0
        </text>
        <polygon points={area} fill={token.colorPrimaryBg} />
        <polyline
          points={line}
          fill="none"
          stroke={token.colorPrimary}
          strokeWidth="3"
          strokeLinejoin="round"
        />
        {coordinates.map(({ x, y }, index) =>
          index === 0 || index === coordinates.length - 1 ? (
            <circle key={points[index].date} cx={x} cy={y} r="4" fill={token.colorPrimary} />
          ) : null,
        )}
        <text x={left} y={height - 6} fill={token.colorTextSecondary} fontSize="12">
          {points[0].date}
        </text>
        {points.length > 1 && (
          <text
            x={width - right}
            y={height - 6}
            textAnchor="end"
            fill={token.colorTextSecondary}
            fontSize="12"
          >
            {points[points.length - 1].date}
          </text>
        )}
      </svg>
    </div>
  )
}

export function RankedBars({
  items,
  emptyText,
}: {
  items: { key: string; label: string; value: number }[]
  emptyText: string
}) {
  const { token } = theme.useToken()
  if (items.length === 0) return <Empty description={emptyText} />
  const maximum = Math.max(1, ...items.map((item) => item.value))
  return (
    <div style={{ display: 'grid', gap: 12 }}>
      {items.map((item) => (
        <div key={item.key}>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'baseline',
              gap: 12,
            }}
          >
            <Typography.Text style={{ overflowWrap: 'anywhere' }}>{item.label}</Typography.Text>
            <Typography.Text strong style={{ whiteSpace: 'nowrap' }}>
              {item.value}
            </Typography.Text>
          </div>
          <div
            style={{
              height: 9,
              marginTop: 4,
              borderRadius: 5,
              background: token.colorFillQuaternary,
            }}
          >
            <div
              style={{
                height: '100%',
                width: (item.value / maximum) * 100 + '%',
                borderRadius: 5,
                background: token.colorPrimary,
              }}
            />
          </div>
        </div>
      ))}
    </div>
  )
}

export function DistributionBars({ items }: { items: { label: string; students: number }[] }) {
  const { token } = theme.useToken()
  if (items.length === 0) return <Empty description="所选范围内没有成功审核" />
  const maximum = Math.max(1, ...items.map((item) => item.students))
  return (
    <div style={{ display: 'flex', gap: 12, alignItems: 'end', minHeight: 172, overflowX: 'auto' }}>
      {items.map((item) => (
        <div key={item.label} style={{ flex: '1 0 64px', textAlign: 'center' }}>
          <Typography.Text strong>{item.students}</Typography.Text>
          <div
            style={{
              height: 114,
              display: 'flex',
              alignItems: 'end',
              margin: '6px auto',
              width: 'min(100%, 74px)',
            }}
          >
            <div
              style={{
                width: '100%',
                height: Math.max(4, (item.students / maximum) * 108),
                background: token.colorPrimary,
                borderRadius: '4px 4px 0 0',
              }}
            />
          </div>
          <Typography.Text type="secondary">{item.label}</Typography.Text>
        </div>
      ))}
    </div>
  )
}

export function IssueHeatmap({
  rules,
  students,
}: {
  rules: { ruleId: string; description: string }[]
  students: { studentId: string; name: string; counts: Record<string, number> }[]
}) {
  const { token } = theme.useToken()
  if (rules.length === 0 || students.length === 0) {
    return <Empty description="所选范围内暂无可展示的规则明细" />
  }
  const maximum = Math.max(
    1,
    ...students.flatMap((student) => rules.map((rule) => student.counts[rule.ruleId] ?? 0)),
  )
  return (
    <div style={{ overflowX: 'auto', maxWidth: '100%' }}>
      <table
        style={{
          borderCollapse: 'separate',
          borderSpacing: 4,
          width: 'max-content',
          minWidth: '100%',
        }}
      >
        <thead>
          <tr>
            <th scope="col" style={{ textAlign: 'left', minWidth: 80 }}>
              学生
            </th>
            {rules.map((rule) => (
              <th
                key={rule.ruleId}
                scope="col"
                style={{
                  fontWeight: 400,
                  minWidth: 104,
                  maxWidth: 150,
                  lineHeight: 1.3,
                  overflowWrap: 'anywhere',
                }}
              >
                {rule.description}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {students.map((student) => (
            <tr key={student.studentId}>
              <th scope="row" style={{ textAlign: 'left', whiteSpace: 'nowrap', fontWeight: 500 }}>
                {student.name}
              </th>
              {rules.map((rule) => {
                const count = student.counts[rule.ruleId] ?? 0
                const intensity = Math.round(18 + (count / maximum) * 65)
                const darkCell = count > 0 && count / maximum >= 0.75
                return (
                  <td
                    key={rule.ruleId}
                    title={student.name + '：' + rule.description + '，' + count + ' 个问题'}
                    style={{
                      textAlign: 'center',
                      padding: '8px 4px',
                      borderRadius: 4,
                      background: darkCell
                        ? 'color-mix(in srgb, ' +
                          token.colorPrimary +
                          ' ' +
                          Math.round(25 + (count / maximum) * 20) +
                          '%, black)'
                        : count
                          ? 'color-mix(in srgb, ' +
                            token.colorPrimary +
                            ' ' +
                            Math.min(40, intensity) +
                            '%, white)'
                          : token.colorFillQuaternary,
                      color: darkCell ? token.colorTextLightSolid : token.colorText,
                    }}
                  >
                    {count}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
