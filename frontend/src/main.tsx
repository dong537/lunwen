import { StrictMode, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { AlertTriangle, CheckCircle2, ChevronDown, FileCheck2, FileUp, LoaderCircle, MessageSquareWarning, RotateCcw } from 'lucide-react'
import './styles.css'

type Result = { ruleId: string; judgement: 'PASS' | 'FAIL' | 'REVIEW'; expected: string | null; actual: string | null; reasonCode: string | null }
type Report = { requestId: string; sourceSha256: string; report: { results: Result[]; diagnostics: string[]; tables: unknown[]; equations: unknown[]; references: unknown[] }; annotation: { available: boolean; downloadPath: string | null } }

const API = import.meta.env.VITE_API_URL ?? 'http://127.0.0.1:8080'

function App() {
  const [file, setFile] = useState<File | null>(null)
  const [report, setReport] = useState<Report | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [open, setOpen] = useState<string | null>(null)
  const counts = useMemo(() => ({
    pass: report?.report.results.filter(r => r.judgement === 'PASS').length ?? 0,
    fail: report?.report.results.filter(r => r.judgement === 'FAIL').length ?? 0,
    review: report?.report.results.filter(r => r.judgement === 'REVIEW').length ?? 0,
  }), [report])
  const visibleResults = report?.report.results.filter(result => result.judgement !== 'PASS') ?? []

  async function inspect() {
    if (!file) return
    setBusy(true); setError(''); setReport(null)
    try {
      const body = new FormData(); body.append('file', file); body.append('annotate', 'true')
      const response = await fetch(`${API}/api/inspections`, { method: 'POST', body })
      const data = await response.json()
      if (!response.ok) throw new Error(data.message ?? '检测失败')
      setReport(data)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '检测失败，请确认后端已启动') }
    finally { setBusy(false) }
  }

  return <div className="app-shell">
    <header className="topbar"><div className="brand-mark">L</div><div><p className="eyebrow">THESIS FORMAT REVIEW</p><h1>论文规范筛查</h1></div><span className="status-dot">本地检测服务</span></header>
    <main className="workspace">
      <section className="intro"><p className="eyebrow">学校规范 · school-v1</p><h2>把格式问题直接标回论文。</h2><p className="lede">上传 Word 文档，逐项核对页边距、字体、字号和首行缩进。发现的问题会写入可下载的 Word 批注。</p></section>
      <section className="grid">
        <article className="panel upload-panel"><div className="panel-heading"><span className="step">01</span><div><h3>选择论文</h3><p>只支持 .docx，单文件最大 16 MB</p></div></div>
          <label className={`dropzone ${file ? 'has-file' : ''}`}><input type="file" accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document" onChange={e => setFile(e.target.files?.[0] ?? null)} />{file ? <><FileCheck2 size={28}/><strong>{file.name}</strong><span>{(file.size / 1024 / 1024).toFixed(2)} MB · 已准备检测</span></> : <><FileUp size={28}/><strong>拖入论文，或点击选择</strong><span>Word 文档（.docx）</span></>}</label>
          <button className="primary-button" disabled={!file || busy} onClick={inspect}>{busy ? <><LoaderCircle className="spin" size={17}/> 正在读取 XML…</> : '开始检测'}</button>
          {error && <div className="error-box"><AlertTriangle size={17}/>{error}</div>}
        </article>
        <article className="panel scope-panel"><div className="panel-heading"><span className="step">02</span><div><h3>检测范围</h3><p>当前版本的可验证规则</p></div></div><ul className="scope-list"><li><span className="scope-icon">↔</span><span>四边页边距<small>按 2.6 cm（1474 twips）</small></span></li><li><span className="scope-icon">Aa</span><span>字体与字号<small>中文字体、英文字体、半点字号</small></span></li><li><span className="scope-icon">↦</span><span>段落首行缩进<small>按 2 字符规则检查</small></span></li><li className="muted"><span className="scope-icon">◎</span><span>图片、公式、真实分页<small>暂列为人工复核范围</small></span></li></ul></article>
      </section>
      {report && <section className="results"><div className="results-head"><div><p className="eyebrow">检测完成 · {report.requestId.slice(0, 8)}</p><h2>发现 {counts.fail + counts.review} 项需要处理</h2></div>{report.annotation.available && <a className="download-button" href={`${API}${report.annotation.downloadPath}`} download><MessageSquareWarning size={17}/> 下载带批注副本</a>}</div><div className="summary"><div className="metric pass"><CheckCircle2 size={19}/><strong>{counts.pass}</strong><span>通过</span></div><div className="metric fail"><AlertTriangle size={19}/><strong>{counts.fail}</strong><span>格式问题</span></div><div className="metric review"><MessageSquareWarning size={19}/><strong>{counts.review}</strong><span>需复核</span></div></div><div className="object-counts"><span>表格 {report.report.tables.length}</span><span>公式 {report.report.equations.length}</span><span>文献 {report.report.references.length}</span></div><div className="result-list">{visibleResults.map((result, index) => <div className={`result-row ${result.judgement.toLowerCase()}`} key={`${result.ruleId}-${index}`}><button className="result-toggle" onClick={() => setOpen(open === `${result.ruleId}-${index}` ? null : `${result.ruleId}-${index}`)}><span className="result-state">{result.judgement === 'FAIL' ? <AlertTriangle size={17}/> : <MessageSquareWarning size={17}/>}</span><span className="rule-id">{result.ruleId}</span><span className="result-label">{result.judgement === 'FAIL' ? '存在格式问题' : '需要人工复核'}</span><ChevronDown size={17} className={open === `${result.ruleId}-${index}` ? 'rotate' : ''}/></button>{open === `${result.ruleId}-${index}` && <div className="result-detail"><span>期望值：{result.expected ?? '未定义'}</span><span>实际值：{result.actual ?? '未知'}</span>{result.reasonCode && <span>定位：{result.reasonCode}</span>}</div>}</div>)}</div></section>}
      {!report && !busy && <div className="empty-state"><RotateCcw size={17}/> 检测结果会显示在这里</div>}
    </main>
    <footer>原文只读 · 结果绑定 DOCX SHA-256 · 不保存账户信息</footer>
  </div>
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
