import { StrictMode, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { AlertTriangle, CheckCircle2, ChevronDown, FileCheck2, FileUp, LoaderCircle, MessageSquareWarning, RotateCcw } from 'lucide-react'
import './styles.css'

type SourceAddress = { partName: string; elementPath: number[]; start: number; end: number }
type Result = { ruleId: string; judgement: 'PASS' | 'FAIL'; expected: string | null; actual: string | null; reasonCode: string | null; source: SourceAddress }
type AnnotationItem = { ruleId: string; status: 'WRITTEN' | 'UNSUPPORTED_ANCHOR' | 'REJECTED'; reason: string; source: SourceAddress }
type Report = { requestId: string; sourceSha256: string; report: { results: Result[]; diagnostics: string[]; tables: unknown[]; equations: unknown[]; references: unknown[]; images: unknown[] }; annotation: { available: boolean; downloadPath: string | null; items: AnnotationItem[] } }
type ResultGroup = { ruleId: string; results: Result[] }

function resultKey(ruleId: string, source: SourceAddress) {
  return `${ruleId}|${source.partName}|${source.elementPath.join(',')}`
}

function annotationLabel(item?: AnnotationItem) {
  if (!item) return '批注状态：未返回'
  if (item.status === 'WRITTEN') return 'Word 批注已写入'
  const reasons: Record<string, string> = {
    PART_NOT_SUPPORTED: '该位置暂不支持 Word 批注',
    EMPTY_ANCHOR: '该位置没有可批注的文字或图片',
    PATH_OUT_OF_RANGE: '原文结构变化，无法定位',
    SOURCE_HASH_MISMATCH: '论文内容变化，批注未写入',
    WRITE_FAILED: '批注文件生成失败',
  }
  return `未写入 Word 批注：${(reasons[item.reason] ?? item.reason) || '未知原因'}`
}

// 开发环境通过 Vite 代理访问同一套后端；部署时可用 VITE_API_URL 指向独立 API。
const API = import.meta.env.VITE_API_URL ?? ''

function App() {
  const [file, setFile] = useState<File | null>(null)
  const [report, setReport] = useState<Report | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [open, setOpen] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
  const counts = useMemo(() => ({
    pass: report?.report.results.filter(r => r.judgement === 'PASS').length ?? 0,
    fail: report?.report.results.filter(r => r.judgement === 'FAIL').length ?? 0,
  }), [report])
  const visibleResults = report?.report.results.filter(result => result.judgement !== 'PASS') ?? []
  const resultGroups = useMemo<ResultGroup[]>(() => {
    const groups = new Map<string, Result[]>()
    for (const result of visibleResults) groups.set(result.ruleId, [...(groups.get(result.ruleId) ?? []), result])
    return [...groups].map(([ruleId, results]) => ({ ruleId, results }))
  }, [report])
  const annotationStates = useMemo(() => new Map(report?.annotation.items.map(item => [resultKey(item.ruleId, item.source), item]) ?? []), [report])
  const hasUnwrittenAnnotations = report?.annotation.items.some(item => item.status !== 'WRITTEN') ?? false

  function selectFile(candidate?: File) {
    if (!candidate) return
    if (!candidate.name.toLowerCase().endsWith('.docx')) {
      setFile(null)
      setError('只能上传 .docx Word 文档')
      return
    }
    if (candidate.size > 16 * 1024 * 1024) {
      setFile(null)
      setError('文件不能超过 16 MB')
      return
    }
    setError('')
    setFile(candidate)
  }

  async function inspect() {
    if (!file) return
    setBusy(true); setError(''); setReport(null)
    try {
      const body = new FormData(); body.append('file', file); body.append('annotate', 'true')
      const response = await fetch(`${API}/api/inspections`, { method: 'POST', body })
      const raw = await response.text()
      let data: unknown = null
      try { data = raw ? JSON.parse(raw) : null } catch { /* 非 JSON 错误页 */ }
      if (!response.ok) {
        const message = typeof data === 'object' && data !== null
          && typeof (data as { message?: unknown }).message === 'string'
          ? (data as { message: string }).message
          : `检测请求失败（HTTP ${response.status}）`
        throw new Error(message)
      }
      if (typeof data !== 'object' || data === null || !('report' in data) || !('annotation' in data))
        throw new Error('检测服务返回了无法识别的结果')
      setReport(data as Report)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '检测失败，请确认后端已启动') }
    finally { setBusy(false) }
  }

  return <div className="app-shell">
    <header className="topbar"><div className="brand-mark">L</div><div><p className="eyebrow">THESIS FORMAT REVIEW</p><h1>论文规范筛查</h1></div><span className="status-dot">本地检测服务</span></header>
    <main className="workspace">
      <section className="intro"><p className="eyebrow">学校规范 · school-v1</p><h2>把格式问题直接标回论文。</h2><p className="lede">上传 Word 文档，逐项核对页边距、字体、字号和首行缩进。发现的问题会写入可下载的 Word 批注。</p></section>
      <section className="grid">
        <article className="panel upload-panel"><div className="panel-heading"><span className="step">01</span><div><h3>选择论文</h3><p>只支持 .docx，单文件最大 16 MB</p></div></div>
          <label className={`dropzone ${file ? 'has-file' : ''} ${dragging ? 'is-dragging' : ''}`}
            onDragEnter={event => { event.preventDefault(); setDragging(true) }}
            onDragOver={event => { event.preventDefault(); event.dataTransfer.dropEffect = 'copy'; setDragging(true) }}
            onDragLeave={event => { if (event.currentTarget === event.target) setDragging(false) }}
            onDrop={event => { event.preventDefault(); setDragging(false); selectFile(event.dataTransfer.files?.[0]) }}>
            <input type="file" accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document" onChange={e => selectFile(e.target.files?.[0])} />
            {file ? <><FileCheck2 size={28}/><strong>{file.name}</strong><span>{(file.size / 1024 / 1024).toFixed(2)} MB · 已准备检测</span></> : <><FileUp size={28}/><strong>拖入论文，或点击选择</strong><span>Word 文档（.docx）</span></>}
          </label>
          <button className="primary-button" disabled={!file || busy} onClick={inspect}>{busy ? <><LoaderCircle className="spin" size={17}/> 正在读取 XML…</> : '开始检测'}</button>
          {error && <div className="error-box"><AlertTriangle size={17}/>{error}</div>}
        </article>
        <article className="panel scope-panel"><div className="panel-heading"><span className="step">02</span><div><h3>检测范围</h3><p>当前版本的可验证规则</p></div></div><ul className="scope-list"><li><span className="scope-icon">↔</span><span>四边页边距<small>按 2.6 cm（1474 twips）</small></span></li><li><span className="scope-icon">Aa</span><span>字体与字号<small>中文字体、英文字体、半点字号</small></span></li><li><span className="scope-icon">↦</span><span>段落首行缩进<small>按 2 字符规则检查</small></span></li><li><span className="scope-icon">◎</span><span>图片、公式、真实分页<small>不符合规范直接判为格式错误</small></span></li></ul></article>
      </section>
      {report && <section className="results"><div className="results-head"><div><p className="eyebrow">检测完成 · {report.requestId.slice(0, 8)}</p><h2>发现 <span className="accent-number">{counts.fail}</span> 项格式错误</h2><p className="result-meta">已读取 {report.report.results.length} 项规则 · 原文只读</p>{hasUnwrittenAnnotations && <p className="annotation-warning">有些意见未能写入 Word 批注，展开对应问题可查看原因。</p>}</div>{report.annotation.available && <a className="download-button" href={`${API}${report.annotation.downloadPath}`} download><MessageSquareWarning size={17}/> 下载带批注副本</a>}</div><div className="summary"><div className="metric pass"><CheckCircle2 size={19}/><strong>{counts.pass}</strong><span>通过</span><em>规则符合要求</em></div><div className="metric fail"><AlertTriangle size={19}/><strong>{counts.fail}</strong><span>格式错误</span><em>{resultGroups.length} 类问题</em></div></div><div className="object-counts"><span>表格 <b>{report.report.tables.length}</b></span><span>图片 <b>{report.report.images.length}</b></span><span>公式 <b>{report.report.equations.length}</b></span><span>文献 <b>{report.report.references.length}</b></span></div><div className="result-toolbar"><span>问题分类</span><span>{resultGroups.length} 类 · {counts.fail} 处</span></div><div className="result-list">{resultGroups.map(group => { const groupKey = `group-${group.ruleId}`; const expanded = open === groupKey; const first = group.results[0]; return <div className={`result-group ${expanded ? 'expanded' : ''}`} key={group.ruleId}><button className="result-toggle" onClick={() => setOpen(expanded ? null : groupKey)}><span className="result-state"><AlertTriangle size={17}/></span><span className="rule-id">{group.ruleId}</span><span className="result-label">格式错误 <b>{group.results.length} 处</b></span><span className="group-hint">{first.reasonCode ?? '需要修改'}</span><ChevronDown size={17} className={expanded ? 'rotate' : ''}/></button>{expanded && <div className="group-details">{group.results.map((result, index) => <div className="result-detail" key={`${result.ruleId}-${index}`}><span className="detail-index">{String(index + 1).padStart(2, '0')}</span><div className="detail-content"><div><span>期望：{result.expected ?? '按学校规范'}</span><span>实际：{result.actual ?? '未符合要求'}</span></div>{result.reasonCode && <span>定位：{result.reasonCode}</span>}<span>{annotationLabel(annotationStates.get(resultKey(result.ruleId, result.source)))}</span></div></div>)}</div>}</div> })}</div></section>}
      {!report && !busy && <div className="empty-state"><RotateCcw size={17}/> 检测结果会显示在这里</div>}
    </main>
    <footer>原文只读 · 结果绑定 DOCX SHA-256 · 不保存账户信息</footer>
  </div>
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
