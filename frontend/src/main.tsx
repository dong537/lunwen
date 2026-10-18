import { StrictMode, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { AlertTriangle, CheckCircle2, ChevronDown, Download, FileCheck2, FileUp, LoaderCircle, MessageSquareWarning, RotateCcw } from 'lucide-react'
import './styles.css'

type SourceAddress = { partName: string; elementPath: number[]; start: number; end: number }
type Result = { ruleId: string; judgement: 'PASS' | 'FAIL' | 'REVIEW'; expected: string | null; actual: string | null; reasonCode: string | null; source: SourceAddress }
type AnnotationItem = { ruleId: string; status: 'WRITTEN' | 'UNSUPPORTED_ANCHOR' | 'REJECTED'; reason: string; source: SourceAddress }
type Report = { requestId: string; sourceSha256: string; report: { results: Result[]; diagnostics: string[]; tables: unknown[]; equations: unknown[]; references: unknown[]; images: unknown[] }; annotation: { available: boolean; downloadPath: string | null; items: AnnotationItem[] }; reportFile: { available: boolean; downloadPath: string | null } }
type ResultGroup = { ruleId: string; results: Result[] }

const ruleNames: Record<string, string> = {
  'PAGE-01': '页边距', 'PAGE-03': '章节分页', 'PAGE-04': '页码格式', 'PAGE-05': '页码格式', 'BODY-FONT': '正文中英文字体', 'BODY-SIZE': '正文字号', 'BODY-INDENT': '正文首行缩进', 'BODY-LINE': '正文行距', 'BODY-UNIT': '计量单位写法',
  'HEADING-FONT': '标题字体', 'HEADING-SIZE': '标题字号', 'HEADING-LINE': '标题行距', 'HEAD-02-BLANK': '章标题后空行', 'HEAD-03-SPACING': '三级标题编号空格', 'HEAD-03-INDENT': '三级标题缩进',
  'HEAD-01-SPACING': '一级标题编号空格', 'HEAD-02-SPACING': '二级标题编号空格', 'HEAD-05-MARKER': '三级以下标题编号', 'HEAD-05-FONT': '三级以下标题字体', 'HEAD-05-SIZE': '三级以下标题字号', 'HEAD-05-LINE': '三级以下标题行距', 'HEAD-05-INDENT': '三级以下标题缩进',
  'ABSTRACT-FONT': '摘要字体', 'ABSTRACT-SIZE': '摘要字号', 'ABSTRACT-INDENT': '摘要首行缩进', 'ABSTRACT-LINE': '摘要行距', 'ABSTRACT-KEYWORD-BOLD': '中文关键词标签加粗', 'ABSTRACT-KEYWORD-FORMAT': '中文关键词格式', 'ABSTRACT-KEYWORD-ALIGN': '中文关键词对齐', 'EN-03-FORMAT': '英文关键词格式',
  'ZH-01': '中文摘要标题', 'ZH-01-SPACING': '中文摘要标题空格', 'ZH-01-FONT': '中文摘要标题字体', 'ZH-01-SIZE': '中文摘要标题字号', 'ZH-01-ALIGNMENT': '中文摘要标题对齐', 'ZH-02-BLANK': '摘要标题后空行', 'ZH-04-BLANK': '摘要与关键词间空行',
  'EN-01': '英文摘要标题', 'EN-01-FONT': '英文摘要标题字体', 'EN-01-SIZE': '英文摘要标题字号', 'EN-01-BOLD': '英文摘要标题加粗', 'EN-01-ALIGNMENT': '英文摘要标题对齐', 'EN-01-BLANK': '英文摘要标题后空行', 'EN-03-BOLD': '英文关键词标签加粗', 'EN-03-BLANK': '英文摘要与关键词间空行',
  'TOC-01': '目录标题', 'TOC-01-SPACING': '目录标题空格', 'TOC-01-FONT': '目录标题字体', 'TOC-01-SIZE': '目录标题字号', 'TOC-01-ALIGNMENT': '目录标题对齐', 'TOC-01-BLANK': '目录标题后空行', 'TOC-01-LINE': '目录标题行距', 'TOC-INDENT': '目录一级标题缩进', 'TOC-SPACING': '目录编号空格',
  'REF-01': '参考文献标题', 'REF-01-FONT': '参考文献标题字体', 'REF-01-SIZE': '参考文献标题字号', 'REF-01-ALIGNMENT': '参考文献标题对齐', 'REF-01-BLANK': '参考文献标题后空行', 'END-01': '致谢标题', 'END-01-FONT': '致谢标题字体', 'END-01-SIZE': '致谢标题字号', 'END-01-ALIGNMENT': '致谢标题对齐', 'END-01-SPACING': '致谢标题空格', 'END-01-LINE': '致谢标题行距', 'END-01-BLANK': '致谢标题后空行', 'APP-02': '附录标题', 'APP-02-FONT': '附录标题字体', 'APP-02-SIZE': '附录标题字号', 'APP-02-ALIGNMENT': '附录标题对齐', 'APP-02-SPACING': '附录标题空格', 'APP-02-LINE': '附录标题行距', 'APP-02-BLANK': '附录标题后空行',
  'TAB-01': '表格正文引用', 'TAB-02': '三线表边框', 'TAB-03': '表号与表题', 'TAB-04': '表头量和单位', 'TAB-05': '表号连续性', 'TAB-06': '表格重复文字', 'TAB-07': '表注位置', 'TAB-08': '续列表头',
  'FIG-01': '图片正文引用', 'FIG-02': '图片和图题位置', 'FIG-03': '图号与图题格式', 'FIG-04': '图片尺寸', 'FIG-05': '图号连续性', 'CAPTION-FONT': '图表题字体', 'CAPTION-SIZE': '图表题字号',
  'EQ-01': '公式编号', 'EQ-02': '公式编号字体字号', 'EQ-03': '公式对齐', 'EQ-04': '公式正文引用', 'EQ-05': '公式编号连续性', 'EQ-06': '公式对象格式', 'EQ-07': '公式变量说明',
  'COVER-01-FONT': '封面学校名称字体', 'COVER-01-SIZE': '封面学校名称字号', 'COVER-01-ALIGNMENT': '封面学校名称对齐', 'COVER-02-FONT': '封面论文题目字体', 'COVER-02-SIZE': '封面论文题目字号', 'COVER-03-FONT': '封面个人信息字体', 'COVER-03-SIZE': '封面个人信息字号', 'COVER-03-BOLD': '封面个人信息加粗', 'COVER-04-FONT': '封面日期字体', 'COVER-04-SIZE': '封面日期字号', 'COVER-04-BOLD': '封面日期加粗', 'COVER-04-CONSISTENCY': '封面日期一致性',
  'REF-02': '参考文献类型标识', 'REF-03': '参考文献引用和顺序', 'REF-04': '参考文献著录格式', 'REF-05': '参考文献数量', 'REF-07': '参考文献编号', 'REF-FONT': '参考文献字体', 'REF-SIZE': '参考文献字号', 'REF-LINE': '参考文献行距', 'CITE-01': '正文引用格式', 'APP-01': '附录编号',
}
const reasonNames: Record<string, string> = {
  MARGIN_MISMATCH: '页边距不是 2.6 cm', FONT_MISMATCH: '字体不符合规范', SIZE_MISMATCH: '字号不符合规范', HEADING_INDENT_MISMATCH: '标题缩进不符合规范',
  TABLE_CAPTION_MISSING: '缺少表号和表题', TABLE_CAPTION_BELOW: '表题应放在表格上方', TABLE_CAPTION_NUMBER_FORMAT: '表号与表题之间应只留一个空格', TABLE_NUMBER_SEQUENCE_MISMATCH: '表号未按章节连续编号', TABLE_UNITS_MISSING: '表头缺少量或单位', TABLE_UNIT_LOCATION_NOT_CAPTURED: '未检测到表格量名称和单位标注', TABLE_REPEATED_PLACEHOLDER: '不能使用“同前”“同左”', TABLE_NOTE_NOT_LEFT_ALIGNED: '表注应放在表格下方并左对齐', TABLE_CONTINUATION_HEADER_MISMATCH: '续表表头与原表不一致', PAGE_NUMBER_FORMAT_MISMATCH: '正文到附录应使用阿拉伯数字页码', PAGE_NUMBER_SEQUENCE_RESTARTED: '页码未保持连续编排', PAGE_NUMBER_IN_FRONT_MATTER: '封面、摘要和目录不编写页码',
  FIGURE_CAPTION_MISSING: '图片下方缺少图号和图题', FIGURE_CAPTION_NUMBER_FORMAT: '图号与图题格式不正确', FIGURE_NOT_CENTERED: '图题没有居中', FIGURE_NUMBER_SEQUENCE_MISMATCH: '图号未按章节连续编号', KEYWORD_ALIGNMENT_MISMATCH: '中文关键词没有两端对齐', LINE_SPACING_MISMATCH: '行距不是 1.5 倍', UNIT_STYLE_INCONSISTENT: '同一计量单位混用了全称和缩写',
  EQUATION_NUMBER_MISSING: '公式缺少公式号', EQUATION_NUMBER_FORMAT_MISMATCH: '公式号字体或字号不正确', EQUATION_NOT_REFERENCED: '公式没有在正文引用', EQUATION_NUMBER_SEQUENCE_MISMATCH: '公式号未连续编号', EQUATION_OBJECT_UNSUPPORTED: '公式不是可直接读取的 Word 原生公式', EQUATION_VARIABLES_MISSING: '公式变量缺少说明', EQUATION_VARIABLES_REVIEW: '公式变量说明不完整', EQUATION_LAYOUT_REVIEW: '公式与编号的版式无法确认', EQUATION_ALIGNMENT_UNKNOWN: '公式对齐属性缺失', EQUATION_NUMBER_FORMAT_UNKNOWN: '公式编号格式属性缺失',
  REFERENCE_CATEGORY_MISSING: '参考文献缺少类型标识', REFERENCE_NOT_CITED: '参考文献没有在正文引用', REFERENCE_ORDER_MISMATCH: '参考文献顺序与正文首次引用顺序不一致', REFERENCE_COUNT_TOO_LOW: '参考文献少于 10 篇', REFERENCE_NUMBER_SEQUENCE_MISMATCH: '参考文献编号不连续',
  REFERENCE_NUMBER_DUPLICATE: '参考文献编号重复', REFERENCE_NUMBER_INVALID: '参考文献编号格式不正确', REFERENCE_TYPE_MISSING: '参考文献缺少文献类型标识',
  TABLE_BORDER_REVIEW: '表格三线边框未能完整确认', TABLE_CONTINUATION_BORDER_REVIEW: '续表边框未能完整确认', TABLE_AUXILIARY_BORDER_REVIEW: '表格辅助线需要核对', TABLE_LAYOUT_REVIEW: '表题与表格版面未能确认', TABLE_CAPTION_NOT_CENTERED: '表题没有居中', TABLE_CAPTION_ALIGNMENT_UNKNOWN: '表题对齐属性缺失', TABLE_CONTINUATION_CAPTION_NOT_RIGHT: '续表标记没有右对齐', TABLE_CONTINUATION_NUMBER_FORMAT: '续表编号格式不正确', TABLE_NOT_CITED_IN_BODY: '正文没有引用该表',
  FIGURE_NOT_CITED_IN_BODY: '正文没有引用该图', FIGURE_REFERENCE_NEEDS_REVIEW: '图题引用结构不完整', FIGURE_NUMBER_UNKNOWN: '图号结构不完整', FIGURE_LAYOUT_REVIEW: '图片与图题版面未能确认',
  CHAPTER_BODY_BLANK_LINE_MISSING: '章标题与正文之间没有空一行', SECTION_TITLE_BLANK_LINE_MISSING: '标题与正文之间没有空一行', CHAPTER_PAGE_BREAK_REVIEW: '章节分页属性未能确认', HEADING_NUMBER_SPACING_MISMATCH: '标题编号后的空格不符合要求', LOWER_HEADING_MARKER_MISMATCH: '三级以下标题编号格式不正确',
  TOC_TITLE_BLANK_LINE_MISSING: '目录标题后没有空一行', TOC_TITLE_SPACING_MISMATCH: '目录标题空格数量不对', TOC_CHAPTER_INDENT_MISMATCH: '目录一级标题缩进不正确', TOC_NUMBER_SPACING_MISMATCH: '目录编号后空格不正确',
  ABSTRACT_TITLE_SPACING_MISMATCH: '中文摘要标题空格数量不对', ABSTRACT_TITLE_BLANK_LINE_MISSING: '中文摘要标题后没有空一行', ABSTRACT_KEYWORD_BLANK_LINE_MISSING: '摘要正文与关键词之间没有空一行', ACKNOWLEDGEMENT_TITLE_SPACING_MISMATCH: '致谢标题空格数量不对', APPENDIX_TITLE_SPACING_MISMATCH: '附录标题空格数量不对', APPENDIX_SEQUENCE_MISMATCH: '附录编号没有连续排列', APPENDIX_OBJECT_PREFIX_MISSING: '附录对象编号缺少附录字母', APPENDIX_OBJECT_LETTER_MISMATCH: '附录对象编号字母不匹配', ENGLISH_KEYWORD_SEPARATOR_MISMATCH: '英文关键词分隔符不符合要求',
  FONT_UNKNOWN: '字体属性缺失', SIZE_UNKNOWN: '字号属性缺失', ALIGNMENT_UNKNOWN: '对齐属性缺失', INDENT_UNKNOWN: '缩进属性缺失', LINE_SPACING_UNKNOWN: '行距属性缺失', MARGIN_UNKNOWN: '页边距属性缺失', BOLD_UNKNOWN: '加粗属性缺失', PAGE_NUMBER_ALIGNMENT_UNKNOWN: '页码对齐属性缺失', PAGE_NUMBER_FONT_UNKNOWN: '页码字体属性缺失', PAGE_NUMBER_SIZE_UNKNOWN: '页码字号属性缺失', CITATION_FORMAT_UNKNOWN: '引用字体或字号属性缺失', CITATION_POSITION_MISMATCH: '引用位置不符合要求', CITATION_RANGE_REVIEW: '引用编号范围未能确认', KEYWORD_LABEL_UNREADABLE: '关键词标签无法独立定位', KEYWORD_ALIGNMENT_UNKNOWN: '关键词对齐属性缺失',
  COVER_DATE_MISMATCH: '封面日期不一致', COVER_TITLE_LABEL_AND_VALUE_SHARE_RUN: '封面题目标签和内容未分开', COVER_FIELD_LABEL_AND_VALUE_SHARE_RUN: '封面字段标签和内容未分开', COVER_FIELD_VALUE_UNKNOWN: '封面字段内容无法定位', EQUATION_NOT_CITED: '正文没有引用该公式', EQUATION_NUMBER_DUPLICATE: '公式编号重复', EQUATION_NUMBER_PARENTHESES_MISSING: '公式编号缺少圆括号', EQUATION_ALIGNMENT_MISMATCH: '公式未居中或编号未右对齐',
}

function chineseRuleName(ruleId: string) { return ruleNames[ruleId] ?? (ruleId.startsWith('HEAD') ? '标题格式' : ruleId.startsWith('REF') ? '参考文献格式' : '论文格式') }
function chineseReason(reason?: string | null) { return reasonNames[reason ?? ''] ?? '格式不符合学校规范' }

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
    review: report?.report.results.filter(r => r.judgement === 'REVIEW').length ?? 0,
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

  function downloadReport() {
    if (!report?.reportFile?.available || !report.reportFile.downloadPath) return
    window.location.href = `${API}${report.reportFile.downloadPath}`
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
        <article className="panel scope-panel"><div className="panel-heading"><span className="step">02</span><div><h3>检测范围</h3><p>当前版本的可验证规则</p></div></div><ul className="scope-list"><li><span className="scope-icon">↔</span><span>四边页边距<small>按 2.6 cm（1474 twips）</small></span></li><li><span className="scope-icon">Aa</span><span>字体与字号<small>中文字体、英文字体、半点字号</small></span></li><li><span className="scope-icon">↦</span><span>段落首行缩进<small>按 2 字符规则检查</small></span></li><li><span className="scope-icon">◎</span><span>图题、公式与分页<small>只报告 DOCX XML 能直接判定的格式项</small></span></li></ul></article>
      </section>
      {report && <section className="results"><div className="results-head"><div><p className="eyebrow">检测完成 · {report.requestId.slice(0, 8)}</p><h2>发现 <span className="accent-number">{counts.fail}</span> 项格式错误</h2><p className="result-meta">已生成 {report.report.results.length} 条检测结果 · 原文只读</p>{hasUnwrittenAnnotations && <p className="annotation-warning">有些意见未能写入 Word 批注，展开对应问题可查看原因。</p>}</div><div className="results-actions"><button className="download-button" onClick={downloadReport}><Download size={17}/> 下载 Word 检查报告</button>{report.annotation.available && <a className="download-button" href={`${API}${report.annotation.downloadPath}`} download><MessageSquareWarning size={17}/> 下载带批注副本</a>}</div></div><div className="summary"><div className="metric pass"><CheckCircle2 size={19}/><strong>{counts.pass}</strong><span>通过</span><em>规则符合要求</em></div><div className="metric fail"><AlertTriangle size={19}/><strong>{counts.fail}</strong><span>格式错误</span><em>{resultGroups.length} 类问题</em></div></div><div className="object-counts"><span>表格 <b>{report.report.tables.length}</b></span><span>图片 <b>{report.report.images.length}</b></span><span>公式 <b>{report.report.equations.length}</b></span><span>文献 <b>{report.report.references.length}</b></span></div><div className="result-toolbar"><span>问题分类</span><span>{resultGroups.length} 类 · {counts.fail} 处</span></div><div className="result-list">{resultGroups.map(group => { const groupKey = `group-${group.ruleId}`; const expanded = open === groupKey; const first = group.results[0]; return <div className={`result-group ${expanded ? 'expanded' : ''}`} key={group.ruleId}><button className="result-toggle" onClick={() => setOpen(expanded ? null : groupKey)}><span className="result-state"><AlertTriangle size={17}/></span><span className="rule-id">{chineseRuleName(group.ruleId)}</span><span className="result-label">格式错误 <b>{group.results.length} 处</b></span><span className="group-hint">{chineseReason(first.reasonCode) ?? '需要修改'}</span><ChevronDown size={17} className={expanded ? 'rotate' : ''}/></button>{expanded && <div className="group-details">{group.results.map((result, index) => <div className="result-detail" key={`${result.ruleId}-${index}`}><span className="detail-index">{String(index + 1).padStart(2, '0')}</span><div className="detail-content"><div><span>期望：{result.expected ?? '按学校规范'}</span><span>实际：{result.actual ?? '未符合要求'}</span></div>{result.reasonCode && <span>问题说明：{chineseReason(result.reasonCode)}</span>}<span>{annotationLabel(annotationStates.get(resultKey(result.ruleId, result.source)))}</span></div></div>)}</div>}</div> })}</div></section>}
      {!report && !busy && <div className="empty-state"><RotateCcw size={17}/> 检测结果会显示在这里</div>}
    </main>
    <footer>原文只读 · 结果绑定 DOCX SHA-256 · 不保存账户信息</footer>
  </div>
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
