# 发票系统 Docker 镜像
# 用 Playwright 官方镜像打底：chromium、系统字体、依赖全都自带，
# 不用在服务器上装一堆 apt 包，这是 Linux 上最省事的做法。
FROM mcr.microsoft.com/playwright/python:v1.65.2-noble

ENV PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    TZ=Asia/Shanghai \
    INVOICE_HOST=0.0.0.0 \
    INVOICE_PORT=8765

WORKDIR /app

# 先装依赖（利用层缓存）
COPY 部署/requirements-linux.txt /tmp/requirements-linux.txt
RUN pip install --no-cache-dir -r /tmp/requirements-linux.txt \
    && rm -f /tmp/requirements-linux.txt

# 再拷程序（数据目录通过 volume 挂进来，不打进镜像）
COPY 程序 /app/程序
COPY 网页 /app/网页
COPY 自检.py 操作说明.txt /app/

# 数据与存档目录（运行时用 volume 覆盖）
RUN mkdir -p /app/数据 /app/发票存档

EXPOSE 8765

# 健康检查：容器自己判断服务是否真的起来了
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${INVOICE_PORT}/_health" || exit 1

CMD ["python", "/app/程序/server.py", "--host", "0.0.0.0", "--port", "8765", "--no-browser"]
