# 前端部署检查（2026-10-18）

## 范围与结论

本次只检查现有前端 Docker 构建链、依赖锁定和 API 地址，并使用已安装依赖执行生产构建；未修改前端源码、配置或依赖，未执行 SSH、Maven 或 Docker。现有前端构建通过，未发现前端产物中的本地 API 地址阻断。Docker 镜像构建和线上业务验证由部署主任务执行。

## 构建证据

- 工作目录：`frontend/`。
- 执行命令：`rtk npm run build`，实际脚本为 `tsc -b && vite build`。
- 退出码：`0`；Vite `8.3.3`，转换 `1895` 个模块，Vite 构建耗时 `1.66s`。
- 本地 Node：`v24.12.0`。
- `package-lock.json` 为 lockfile v3，根包 dependencies/devDependencies 与 `package.json` 一致；8 个直接依赖的安装版本均与锁文件一致。
- Dockerfile 使用 `node:22-alpine` + `npm ci` 构建，`nginx:1.27-alpine` 提供静态文件；Vite 与 React 插件的 Node engine 为 `^20.19.0 || >=22.12.0`，服务器镜像实际版本须由 Docker 构建验证。

| 产物 | 字节 | SHA-256 |
| --- | ---: | --- |
| `frontend/dist/index.html` | 169 | `82b2bf3a9d484e95e1479af606751bf99a61095e58230c19c700c308df424226` |
| `frontend/dist/assets/index-CzGHUsbj.css` | 8048 | `558f77c50280a9a49a52bac199776c86cba815b03f0d7f069766ce73fea21fcd` |
| `frontend/dist/assets/index-Dx6i39hy.js` | 243802 | `dad05b70e26c5e4cc30be689d045502cbe68caae27bfa5d2aaac8a6edbc8c210` |

## API 与服务契约

- `src/main.tsx` 使用 `import.meta.env.VITE_API_URL ?? ''`；本次产物中的 API 前缀已编译为空字符串，请求路径为同源 `/api/inspections`。生成 JS 不含 `localhost` 或 `127.0.0.1`。
- `vite.config.ts` 的 `http://127.0.0.1:18098` 只用于开发代理，未进入本次生产 JS。
- `nginx.conf` 将 `/api/` 原路径代理至 `http://backend:18098`，部署网络必须使前端容器能够解析并访问 `backend:18098`。报告下载使用相同 API 前缀。
- Nginx 配置包含 SPA 回退 `try_files $uri /index.html`、16 MiB 请求体限制和 900 秒代理读写超时。
- 当前检查不替代容器启动、真实浏览器或上传/下载业务 smoke check。
