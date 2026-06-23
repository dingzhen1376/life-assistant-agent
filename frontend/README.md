# LifeManus Frontend

这是一个由 Nginx 托管的静态聊天前端，默认通过同源 `/api` 访问后端。

## 当前功能

- 为每个新对话生成 UUID `chatId`，同一对话持续复用。
- 使用 `EventSource` 接收最终自然语言回答和 `done` 事件。
- 自动滚动到最新消息，长回答可完整查看。
- 接收 SSE `permission` 事件并显示工具确认卡片。
- 每秒轮询待确认请求，作为 SSE 自定义事件的兜底。
- 用户确认或拒绝后立即移除权限卡片。
- 在输入框下方切换 `DEFAULT`、`ACCEPT_EDITS`、`PLAN`、`BYPASS` 和 `YOLO`。
- 删除对话时同步请求后端清理 Redis 和 PGVector 记录。
- 支持明暗主题和 API Base URL 设置。

## 后端地址

后端默认地址：

```text
http://localhost:8124/api
```

前端默认使用同源路径：

```text
/api
```

主要请求：

```text
GET    /api/ai/life/health
GET    /api/ai/life/chat/sse
GET    /api/ai/life/pending-permission
POST   /api/ai/life/tool-permission
POST   /api/ai/life/tool-permission-mode
DELETE /api/ai/life/conversations/{chatId}
```

页面加载后会通过 `health` 读取后端当前权限模式。切换模式只更新后端进程内状态，不会修改 `application.yml`。

## Windows Nginx

在 `frontend` 目录执行：

```powershell
.\start-nginx.ps1
```

停止：

```powershell
.\stop-nginx.ps1
```

访问：

```text
http://localhost:8080
```

## Nginx 代理

`nginx.conf` 将 `/api/` 代理到：

```text
http://host.docker.internal:8124/api/
```

如果 Nginx 直接运行在宿主机且该域名不可用，可改为：

```text
http://127.0.0.1:8124/api/
```

SSE 相关配置：

```nginx
proxy_buffering off;
proxy_cache off;
add_header X-Accel-Buffering no;
proxy_read_timeout 300s;
proxy_send_timeout 300s;
```

这些配置用于避免代理缓存流式响应。

## Docker Nginx

在 `frontend` 目录执行：

```powershell
docker run --rm -p 8080:8080 `
  -v ${PWD}:/usr/share/nginx/html:ro `
  -v ${PWD}/nginx.conf:/etc/nginx/conf.d/default.conf:ro `
  nginx:alpine
```

然后访问 `http://localhost:8080`。

## 本地状态

浏览器 `localStorage` 保存：

- API Base URL。
- 主题。
- 当前权限模式的显示缓存。
- 对话标题、消息和 `chatId`。

后端 `health` 返回的模式优先于本地缓存。Redis 和 PostgreSQL 中的数据由后端管理，不存放在浏览器中。