import type { ReactNode } from 'react'

/**
 * 轻量且 100% 防 XSS 的 Markdown 渲染器（不依赖 dangerouslySetInnerHTML）。
 * 支持标题、加粗、行内代码、有序/无序列表、代码块与 GitHub 风格数据表格。
 */
function renderInline(text: string): ReactNode[] {
  const tokens: ReactNode[] = []
  const re = /(\*\*[^*]+\*\*|`[^`]+`)/g
  let last = 0
  let match: RegExpExecArray | null
  let k = 0
  while ((match = re.exec(text)) !== null) {
    if (match.index > last) {
      tokens.push(text.slice(last, match.index))
    }
    const raw = match[0]
    if (raw.startsWith('**') && raw.endsWith('**')) {
      tokens.push(<strong key={k++}>{raw.slice(2, -2)}</strong>)
    } else if (raw.startsWith('`') && raw.endsWith('`')) {
      tokens.push(<code key={k++} className="ai-inline-code">{raw.slice(1, -1)}</code>)
    }
    last = match.index + raw.length
  }
  if (last < text.length) {
    tokens.push(text.slice(last))
  }
  return tokens
}

function splitTableRow(line: string): string[] {
  let s = line.trim()
  if (s.startsWith('|')) s = s.slice(1)
  if (s.endsWith('|')) s = s.slice(0, -1)
  return s.split('|').map((c) => c.trim())
}

function isTableSeparator(line: string): boolean {
  const s = line.trim()
  return s.includes('|') && /^\|?[\s:|-]+\|?$/.test(s) && s.includes('-')
}

export default function MarkdownView({ content }: { content: string }) {
  const lines = (content || '').replace(/\r\n/g, '\n').split('\n')
  const blocks: ReactNode[] = []
  let i = 0
  let key = 0

  while (i < lines.length) {
    const line = lines[i]
    const trimmed = line.trim()

    if (!trimmed) {
      i++
      continue
    }

    // 代码块 ```
    if (trimmed.startsWith('```')) {
      const buf: string[] = []
      i++
      while (i < lines.length && !lines[i].trim().startsWith('```')) {
        buf.push(lines[i])
        i++
      }
      if (i < lines.length) i++
      blocks.push(
        <pre key={key++} className="ai-code-block">
          <code>{buf.join('\n')}</code>
        </pre>
      )
      continue
    }

    // 表格（header + separator + rows）
    if (trimmed.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      const headers = splitTableRow(lines[i])
      i += 2
      const rows: string[][] = []
      while (i < lines.length && lines[i].trim().includes('|') && lines[i].trim() !== '') {
        rows.push(splitTableRow(lines[i]))
        i++
      }
      blocks.push(
        <div key={key++} className="ai-md-table-wrap">
          <table className="ai-md-table">
            <thead>
              <tr>
                {headers.map((h, idx) => (
                  <th key={idx}>{renderInline(h)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r, rIdx) => (
                <tr key={rIdx}>
                  {headers.map((_, cIdx) => (
                    <td key={cIdx}>{renderInline(r[cIdx] ?? '')}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )
      continue
    }

    // 标题 ### / ## / #
    const hMatch = /^(#{1,4})\s+(.+)$/.exec(trimmed)
    if (hMatch) {
      const lvl = hMatch[1].length
      const cls = `ai-md-h ai-md-h${lvl}`
      blocks.push(<div key={key++} className={cls}>{renderInline(hMatch[2])}</div>)
      i++
      continue
    }

    // 无序列表
    if (/^[-*•]\s+/.test(trimmed)) {
      const items: string[] = []
      while (i < lines.length && /^[-*•]\s+/.test(lines[i].trim())) {
        items.push(lines[i].trim().replace(/^[-*•]\s+/, ''))
        i++
      }
      blocks.push(
        <ul key={key++} className="ai-md-list">
          {items.map((it, idx) => <li key={idx}>{renderInline(it)}</li>)}
        </ul>
      )
      continue
    }

    // 有序列表
    if (/^\d+[.)]\s+/.test(trimmed)) {
      const items: string[] = []
      while (i < lines.length && /^\d+[.)]\s+/.test(lines[i].trim())) {
        items.push(lines[i].trim().replace(/^\d+[.)]\s+/, ''))
        i++
      }
      blocks.push(
        <ol key={key++} className="ai-md-list ordered">
          {items.map((it, idx) => <li key={idx}>{renderInline(it)}</li>)}
        </ol>
      )
      continue
    }

    // 分隔线
    if (/^[-*_]{3,}$/.test(trimmed)) {
      blocks.push(<hr key={key++} className="ai-md-hr" />)
      i++
      continue
    }

    // 普通段落
    const para: string[] = [trimmed]
    i++
    while (
      i < lines.length &&
      lines[i].trim() !== '' &&
      !lines[i].trim().startsWith('#') &&
      !lines[i].trim().startsWith('```') &&
      !/^[-*•]\s+/.test(lines[i].trim()) &&
      !/^\d+[.)]\s+/.test(lines[i].trim()) &&
      !(lines[i].trim().includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1]))
    ) {
      para.push(lines[i].trim())
      i++
    }
    blocks.push(
      <p key={key++} className="ai-md-p">
        {para.map((p, idx) => (
          <span key={idx}>
            {idx > 0 && <br />}
            {renderInline(p)}
          </span>
        ))}
      </p>
    )
  }

  return <div className="ai-md">{blocks}</div>
}
