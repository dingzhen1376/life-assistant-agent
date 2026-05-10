# LifeManus Frontend

这是一个静态前端页面，默认通过同源 `/api` 访问后端，适合放在 Nginx 下代理。

## Nginx 代理方式

后端默认启动在：

```text
http://localhost:8124/api
```

前端默认请求：

```text
/api/ai/life/chat/sse
```

因此 Nginx 只需要把 `/api/` 代理到后端即可。示例配置见：

```text
frontend/nginx.conf
```

## Windows 本地 Nginx

当前目录支持 Windows 版 Nginx 部署：

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

## Docker Nginx 示例

在 `life-assistant-agent/frontend` 目录执行：

```powershell
docker run --rm -p 8080:8080 `
  -v ${PWD}:/usr/share/nginx/html:ro `
  -v ${PWD}/nginx.conf:/etc/nginx/conf.d/default.conf:ro `
  nginx:alpine
```

然后访问：

```text
http://localhost:8080
```

如果 Nginx 不在 Docker 中运行，把 `nginx.conf` 里的：

```text
proxy_pass http://host.docker.internal:8124/api/;
```

改成：

```text
proxy_pass http://127.0.0.1:8124/api/;
```
