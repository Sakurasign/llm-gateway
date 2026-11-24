# LLM Gateway

**一个生产取向的多模型 AI 接入网关**：把 OpenAI、Anthropic、Gemini 三类互不兼容的协议统一到一个入口，
用优先级分层、会话亲和、熔断降权决定请求走向，用两阶段配额与阶梯定价把每一分钱算清楚，
并给出可回放的路由决策与可验证的性能数据。

> 后端 Java 17 · Spring Boot 3 · WebFlux · Reactor Netty · Redis · MySQL · RabbitMQ
> 前端 Vue 3 · TypeScript · Element Plus · Vite

<p align="left">
  <img alt="Java" src="https://img.shields.io/badge/Java-17-orange">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen">
  <img alt="Vue" src="https://img.shields.io/badge/Vue-3%20+%20TS-42b883">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-blue">
  <a href="https://github.com/Sakurasign/llm-gateway/actions/workflows/ci.yml">
    <img alt="CI" src="https://github.com/Sakurasign/llm-gateway/actions/workflows/ci.yml/badge.svg">
  </a>
</p>

部署方式：[本机 JAR](#本机-jar-部署) · [Docker Compose](#docker-compose-部署)

---

## 它解决什么问题

接入大模型时，几乎每个团队都会依次撞上三道墙：

**第一道：协议不通。** Claude Code 只会说 Anthropic Messages，Codex 只会说 Responses，
而你要接的国产模型可能只提供 OpenAI Chat 兼容端点。每换一家上游，客户端配置就要改一遍。

**第二道：路由不稳。** 单一渠道会限流、会 5xx、会突然变慢。更麻烦的是——
横向扩多个账号后，同一会话被轮流打到不同账号，**上游的 prompt cache 全部失效**，
成本可能直接翻 5~10 倍。

**第三道：成本失控。** 不同模型价差 20 倍，长上下文触发溢价，缓存命中与否差 10 倍，
图片按张、音频按秒……如果只在月底看账单，你永远不知道钱花在哪。

这个项目就是为这三道墙写的。

## 核心设计

### 双车道转发：能透传就不翻译

```
同协议（Anthropic 入口 → Anthropic 渠道）
  请求 → 只改 model 字段 → 字节级转发 → 客户端
         零解析、零拷贝、零聚合

跨协议（Anthropic 入口 → OpenAI 渠道）
  请求 → Ingress 解析 → CanonicalRequest → Egress 构造 → 上游
  响应 → SSE 解码 → CanonicalChunk → SSE 编码 → 客户端
```

大多数网关选择"全部翻译"，代价是每请求都要序列化/反序列化整个 body，
并且在 SSE 长连接上破坏流式。这里把透传做成默认路径，翻译只在必要时启用。

### 路由是一条四级流水线

```
候选集（group + 协议兼容 + 渠道启用）
   ↓ 会话亲和命中？→ 是则直接复用（保住 prompt cache）
   ↓ 健康过滤（熔断 / 并发闸门 / 降权）
   ↓ 优先级硬分层（只在最高优先级层内分配）
   ↓ 平滑加权轮询（SWRR，避免权重 5:1 时前 5 个全打 A）
选中渠道
```

**亲和为什么能压过优先级**：假设主渠道优先级更高，但某会话已绑定备渠道。
严格按优先级会让它切回主渠道，缓存全失效。而缓存收益远大于"用上更便宜渠道"，
所以亲和排在优先级之前——但前提是该渠道健康。

### 计费是两阶段提交

流式请求的最终 token 数在响应结束时才知道。只在结束时扣会超卖，只按预估值扣会浪费。

```
reserve(est) ──▶ 上游调用 ──▶ settle(actual)
                    │
                    └─失败──▶ release()
```

三个动作都是**单个 Redis Lua 脚本**，一次 RTT、天然原子。
并发 1000 次、余额恰好够 100 次时，成功数严格等于 100。

### 熔断与降权是两件事

| | 熔断 | 自动降权 |
|---|---|---|
| 粒度 | 渠道 × 模型 | 渠道 × 模型 |
| 行为 | 开关（摘除 / 半开） | 节流（权重按健康度缩放） |
| 目的 | 快速止损 | 让"半死不活"的渠道少接流量但保留探测 |

健康分用指数滑动：成功 `h += (1-h)×0.1`，严重失败 `h -= h×0.5`。
429 限流只轻微惩罚（×0.3），因为它不代表渠道坏了。


---

## 特性

### 协议层

- **三协议互通**：OpenAI Chat / Anthropic Messages / Gemini generateContent，覆盖入口与出口
- **双车道转发**：同协议走字节级透传（零拷贝），跨协议才走 IR 翻译
- **SSE 全链路流式**：不聚合响应，逐帧回写；客户端断开即取消上游（停止计费）
- **工具调用双向转换**：含 Anthropic `tool_use` ↔ OpenAI `tool_calls` ↔ Gemini `functionCall`

### 路由层

- **优先级分层 + 平滑加权轮询（SWRR）**：避免"权重 5:1 时前 5 个请求全打 A"的突发
- **会话亲和（Sticky）**：同一会话钉在同一渠道，保住上游 prompt cache（成本可降 5~10 倍）
- **熔断**：渠道 × 模型粒度，滑动窗口 + 半开探测
- **自动降权**：健康度差的渠道权重被压低但不摘除，保留探测流量以便自愈
- **故障转移**：仅在「尚未向客户端写出首字节」时重试，绝不拼接脏流

### 计费层

- **两阶段配额**：`reserve → settle / release`，Redis Lua 单脚本原子操作
- **分级定价**：阶梯价（长上下文溢价）+ 渠道倍率 + 分组折扣 + 缓存独立计价
- **多模态计价**：图片按张、音频按秒，与 token 计价可叠加
- **异步落库**：RabbitMQ 三队列（明细 / 聚合 / 账单），幂等消费

### 可观测

- Prometheus 指标（QPS、TTFT、附加延迟分位、熔断状态、成本）
- 路由决策可回放：每条请求记录候选、过滤原因、选中、重试
- 管理控制台：概览 / 账号管理 / 密钥管理 / 通用渠道 / 使用记录
- **明暗主题切换**

## 架构

```
                        ┌──────────────────────────────────────┐
 Claude Code ─/v1/messages─┐                                   │
 Codex ───────/v1/responses┤  ┌──────────┐   ┌─────────────┐  │
 Cherry Studio─/v1/chat/…──┼─▶│ 接入层    │──▶│ 协议适配层   │  │
 自研 App ────/v1beta/…────┘  │ Auth/限流 │   │ Ingress→IR  │  │
                             └──────────┘   │ IR→Egress   │  │
                                            └──────┬──────┘  │
                                                   ▼         │
                             ┌──────────────────────────────┐│
                             │ 路由：候选→亲和→优先级→SWRR   ││
                             │       →熔断→降权→并发闸门     ││
                             └──────────────┬───────────────┘│
                                            ▼                │
                             ┌──────────────────────────────┐│
                             │ 转发：快车道透传 / 慢车道翻译  ││
                             └──────────────┬───────────────┘│
                                            │                │
        ┌──────────────┬──────────────┬─────┴──────┬─────────┘
        ▼              ▼              ▼            ▼
    OpenAI        Anthropic        Gemini     自建 vLLM

    旁路：RabbitMQ → 用量落库 / 聚合 / 账单
    支撑：Redis（配额/亲和/熔断）  MySQL（配置/账单）  Caffeine（本地缓存）
```

## 部署

先克隆仓库并复制配置模板。在 Windows PowerShell 中也可以用 `Copy-Item .env.example .env`。

```bash
git clone https://github.com/Sakurasign/llm-gateway.git
cd llm-gateway
cp .env.example .env
```

在仓库根目录的 `.env` 设置 `ADMIN_USERNAME`、`ADMIN_PASSWORD` 和 `ADMIN_JWT_SECRET`。管理员密码至少 8 位。可用 `openssl rand -hex 32` 生成 JWT 密钥，首次部署建议同时设置并备份 `GATEWAY_CONFIG_KEY`。两种方式都会在网关端口提供管理台和 API。

### 本机 JAR 部署

本机需要 Java 17、Maven 3.9+、Node.js 22，以及已启动的 MySQL 8、Redis 和 RabbitMQ。数据库、Redis 和 RabbitMQ 可以运行在本机，也可以连接已有实例。

1. 在 MySQL 创建 `gateway` 数据库并导入初始表结构（首次部署执行）：

   ```bash
   mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS gateway CHARACTER SET utf8mb4"
   mysql -u root -p gateway -e "source db/schema.sql"
   ```

2. 在 `.env` 设置 `MYSQL_PASS`、`RABBIT_PASS`，并按实际连接信息填写以下变量。`MYSQL_USER` 需要对 `gateway` 库有读写和迁移权限。JAR 以 Java Properties 格式读取 `.env`，值直接填写。

   | 变量 | 默认值 |
   |---|---|
   | `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_USER` | `127.0.0.1` / `3306` / `root` |
   | `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` |
   | `RABBIT_HOST` / `RABBIT_PORT` / `RABBIT_USER` | `127.0.0.1` / `5672` / `gateway` |
   | `SERVER_PORT` | `18081` |

3. 在仓库根目录构建并启动网关：

   ```bash
   npm --prefix gateway-console ci
   npm --prefix gateway-console run build
   mvn package
   java -jar gateway-server/target/gateway-server-0.1.0-SNAPSHOT.jar "--spring.config.import=file:./.env[.properties]" --server.address=127.0.0.1
   ```

   路径为 `gateway-server/target/gateway-server-0.1.0-SNAPSHOT.jar` 的产物含控制台。默认访问 `http://localhost:18081`，使用 `.env` 中的管理员登录。需要本地模拟上游时可按[运行手册](RUNBOOK.md#三部署与验证)启动 Mock JAR。

### Docker Compose 部署

需要 Docker Engine 或 Docker Desktop，以及 Docker Compose v2 或兼容版本。在 `.env` 设置 `MYSQL_ROOT_PASSWORD` 和 `RABBIT_PASS`，再从仓库根目录执行：

```bash
docker compose up -d --build
docker compose ps
```

Compose 会构建网关和 Mock 上游，并启动 MySQL、Redis、RabbitMQ。MySQL 在空数据卷上自动初始化数据库。网关默认通过 `http://localhost:18081` 访问，宿主机端口可用 `.env` 的 `GATEWAY_PORT` 修改。

升级前备份 MySQL 和 `.env`，保留原来的配置加密密钥。更新代码后重新执行 `docker compose up -d --build`，已有数据卷会继续使用。

## 使用示例

本地联调时，在启动前将随机生成的 `DEMO_API_KEY` 填入 `.env`，并在调用端设置相同的值。留空时禁用演示 Key。

```bash
# Anthropic 协议调用（Claude Code 可直接用）
curl -N http://localhost:18081/v1/messages \
  -H "x-api-key: $DEMO_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-sonnet-4","max_tokens":100,"stream":true,
       "messages":[{"role":"user","content":"hello"}]}'
```

跨协议自动翻译：把 `model` 换成 `gpt-4o-mini` 或 `gemini-2.5-pro`，
网关会自动转换协议并返回 Anthropic 格式的响应。

## 实测数据

环境：单机，Mock 上游（首字节 ~200ms，单请求约 750ms）。

| 指标 | 结果 |
|---|---|
| 吞吐（200 并发） | 248.3 req/s，**零错误**（直连基线 261.9，达 94.8%） |
| 网关附加延迟（20 并发） | P50 **2.4ms** / P95 5.9ms / P99 5.1ms |
| 配额并发扣减 | 余额 100 时恰好 100 次成功，**零超卖** |
| 计费精度 | Claude 23+9 token → 204 micro（理论值一致） |
| 多模态 | 1 张图 + 23/9 token → 40204 micro（204 + 40000） |

完整数据与瓶颈分析见 [04-运维与压测](docs/04-运维与压测.md)。

> **诚实说明**：50 并发时 P99 升至 ~19ms（Redis 单连接排队）。
> 本项目实测上限为 200 并发零错误，不对更高并发做承诺。

## 文档

| 文档 | 内容 |
|---|---|
| [01-架构设计](docs/01-架构设计.md) | 分层、关键决策、时序、延迟预算 |
| [02-协议适配](docs/02-协议适配.md) | IR 模型、三协议差异矩阵、SSE 处理 |
| [03-路由与计费](docs/03-路由与计费.md) | 调度算法、亲和、熔断、降权、配额与定价 |
| [04-运维与压测](docs/04-运维与压测.md) | 指标、日志、告警、压测数据与瓶颈分析 |
| [05-设计参考](docs/05-设计参考.md) | 参考项目与差异说明 |
| [RUNBOOK](RUNBOOK.md) | 启动、验证、故障演练 |

## 项目结构

```
gateway-server/    后端（Spring Boot + WebFlux）
  ├── protocol/    协议适配（IR + 三协议）
  ├── routing/     路由、亲和、熔断、降权
  ├── quota/       两阶段配额
  └── usage/       MQ 异步落库
gateway-console/   前端（Vue 3 + TS + Element Plus）
mock-upstream/     Mock 上游（三协议）
db/                schema.sql + Lua 脚本
scripts/           压测工具
docs/              设计文档
```

## 开发

```bash
# 后端测试
mvn test

# 前端类型检查 + 构建
cd gateway-console && npm run build
```

## 配置与账号

- Docker Compose 构建会将管理台打包进 Gateway，默认从 `http://127.0.0.1:18081/` 打开。本地开发可在 `gateway-console` 下运行 `npm ci` 和 `npm run dev`。
- 启动时首次导入 `application.yml` 的渠道和模型绑定。之后从 MySQL 装载，控制台修改或导入 YAML 后立即发布新版本。YAML 导入按渠道 ID 和模型绑定合并，文件中省略的记录会保留。
- 「账号管理」支持浏览器授权和导入 Sub2API 账号 JSON。「添加账号」选择 WorkBuddy 后也可导入凭证 JSON。列表和编辑页显示授权到期时间。Sub2API 导入支持 OpenAI OAuth 账号及其中的具体模型映射。
- Codex 登录后把浏览器 `http://localhost:1455/auth/callback?...` 的完整地址粘贴回管理台。已有账号可从「更多 → 重新授权」更新凭证。
- 在「编辑账号」设置模型映射。WorkBuddy 上游模型 ID 带站点前缀，如 `cn:auto`。选择「保存并创建密钥」可直接为当前账号创建密钥。
- 「密钥管理」按平台分组。密钥可以访问该分组内启用账号的模型。旧密钥编辑保存后切换至分组。点「导入 CC Switch」并选择默认模型，可把网关地址和密钥一起导入 Codex 供应商。
- 密钥编辑可配置来源 IP 的允许和拒绝名单，支持 IPv4、IPv6 和 CIDR。以网关直接连接的客户端地址为准。经反向代理访问时，该地址是代理地址。
- `application.yml` 配置的旧密钥和通用模型路由继续使用原有方式。
- WorkBuddy 凭证迁移时先停用旧程序的刷新和签到，再导入账号。保活与积分刷新自动运行，签到由 `GATEWAY_WORKBUDDY_CHECKIN=true` 启用。

## 已知限制

- 当前面向单实例部署，内存中的续轮绑定在重启后需要客户端重新发送上下文。
- Responses 转 Chat 支持文本、图片及函数工具。`/v1/chat/completions` 可使用 OpenAI Chat 和 WorkBuddy 渠道。上游内置工具和后台任务需要原生适配。

## 安全说明

- 管理端凭证与 JWT 密钥**必须**通过环境变量提供，无默认值
- MySQL 中的上游凭证使用 AES-GCM。建议部署前设置并备份独立的 `GATEWAY_CONFIG_KEY`（32 字节 Base64）。未设置时从管理端 JWT 密钥派生，修改 JWT 密钥会导致旧凭证无法解密。
- 公网部署请务必：启用 HTTPS、限制管理端访问 IP、设置请求速率限制
- 本项目仅用于合法的 AI API 网关场景，请遵守各上游服务商的条款

## 许可证

[MIT](LICENSE)




