# 前端（论文审核平台 Web）

React 19 + Ant Design 6 + React Router 7 + React Query 的管理系统 UI。
后端接口、权限与数据契约见仓库根 `README.md` 与 `.trellis/spec/platform-contract.md`。

## 技术栈

| 用途 | 选型 |
| --- | --- |
| 框架 | React 19、TypeScript（严格空检查） |
| 组件库 | Ant Design 6（`ConfigProvider` 中文、圆角 6、深色侧栏 `#001529`） |
| 路由 | React Router 7，`BrowserRouter` |
| 服务端状态 | React Query（`staleTime` 10s，4xx 默认不重试） |
| 构建 | Vite 8（Rolldown 内核），生产按 `antd / vendor / 业务` 分包 |
| 测试 | 只用 Playwright 做 e2e（`e2e/`），不写单测 |

说明：曾短暂引入过 Tailwind 与本地 mock，已全部移除——配色走 antd
`theme` token，演示与联调直接连真实后端。

## 启动与联调

```bash
cd frontend
npm ci
npm run dev          # 本地后端：/api 代理到 http://127.0.0.1:18099
npm run dev:remote   # 远程后端：/api 代理到 https://docx-check.test.chatgqt.top
```

- 只起前端、不起后端时，`/api/*` 会代理失败（502 / `ECONNREFUSED`），页面显示
  “服务暂时无法响应”并可重试——这是正常现象，不是前端 bug。
- `dev:remote` 下强制走服务器中转（`transferMode` 固定 `proxy`），因为浏览器
  源是 localhost，不在 COS CORS 白名单里，直传会被对象存储拒绝。
- 远程是生产库：上传、建号、导入都会写真实数据，谨慎操作。

## 常用命令

```bash
npm run typecheck      # tsc -b
npm run build          # 类型检查 + 生产构建（产物在 dist/）
npm run preview        # 预览生产构建
npm test               # Playwright e2e（会自己起 dev 服务器，无需后端）
npm run format         # Prettier 格式化
npm run format:check   # Prettier 检查
```

## 目录结构

```text
src/
  main.tsx                 # QueryClient + ConfigProvider + 路由入口
  styles.css               # 少量页面级样式（登录页、skip-link、上传区压紧）
  app/App.tsx              # Layout/Sider/Header/Content 壳、路由表、权限守卫
  features/auth/           # 登录页、改密弹窗、个人信息页、会话（useSession）
  features/submissions/    # 学生提交、提交详情、上传/下载链路（proxy/direct）
  features/students/       # 教师端：名下学生列表与详情
  features/users/          # 管理端：平台概览、账号管理、账号表单
  features/imports/        # 管理端：批量导入（预检 → 确认导入）
  shared/api/              # api 封装、错误类型、业务契约类型
  shared/ui.tsx            # PageHeader、ApiAlert、CenterSpin、useDocumentTitle
  shared/files.ts          # 文件校验、时间/大小格式化
public/
  favicon.svg              # 浏览器标签图标（学士帽）
  favicon-32.png           # Safari 等兜底
  apple-touch-icon.png     # 移动端收藏/桌面图标
  brand-cap.svg            # 站内白色学士帽（侧栏 logo、登录页品牌区）
```

## 登录与权限（前端侧）

- 只有登录，没有自助注册：`POST /api/auth/login`（账号 + 密码，初始密码
  `123456`），登录成功/失败都有 message 气泡。
- 未登录访问业务页统一跳 `/login`（`RequireSession`）；首次登录或密码被重置
  后，`mustChangePassword` 为 true 时弹不可关闭的强制改密框，改完才可操作。
- 改密平时在“个人信息”页点“修改密码”弹窗修改。
- 角色路由：学生 `/student/*`、教师 `/teacher/*`、管理员 `/admin/*`；
  管理员按后端规则不能查看论文内容，详情页会显示无权限。

## UI 约定

- 顶栏深蓝 `#001529` 带 3px 浅蓝顶边；侧栏深色可收起（底部触发条，收起只剩图标）。
- 表格默认紧凑（`size="small"`）；提交列表文件名列省略 + antd `Tooltip` 气泡，
  小屏（<576px）自动隐藏“提交时间/审核结果”列，无横向滚动条。
- 新增页面记得加 `useDocumentTitle('页面名')`，标题自动拼后缀。
- 颜色只用 antd token 与现有 hex（`#1677ff` 系 + `#001529`），不要引入新的 CSS 框架。
- 无障碍基线：表单 label 用 `htmlFor` + `id` 显式关联；按钮保持 antd 默认中文
  不加空格（`autoInsertSpaceInButton` 已关，e2e 依赖 exact 文案）。
