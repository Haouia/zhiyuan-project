# 知源 · 企业级 RAG 知识平台 — 演示事实包（Fact Pack）

> 生成：2026-11-25（Asia/Shanghai）｜生成者：evidence-scout｜用途：15 分钟演示 PPT/讲稿的唯一数字依据。
> 标记：✅ 已按仓库文件核实；⚠️ 与既有 README/提纲/手册/PPT 文案冲突（以本文件为准）；❓ 仓库内无法核实。
> 仓库根：`C:\Users\legion\Desktop\project-02`。行号均为当前工作区文件行号，改动后需复核。

## 0. 台上可说的总数字（速查）

| 项 | 可说的值 | 依据 |
|---|---|---|
| 本地全栈容器 | **13 个**（默认 compose；`tools` profile 另有 2 个临时作业容器） | `compose.yaml` services 15 个，默认 13 |
| K8s 应用 Deployment | **5 个**（gateway/doc/search/ai 各 2 副本，metric 1 副本 = 9 个应用 Pod；基础设施另 5 个 Deployment，共 10 Deployment/14 Pod） | `k8s/20-deployments.yaml`、`k8s/40-infra.yaml` |
| 探针/滚动策略 | 3 类探针（startup/liveness/readiness）、`maxUnavailable: 0`、`revisionHistoryLimit: 10` | `k8s/20-deployments.yaml:11-55` |
| 监控 | **10 个**服务探针、**9 个**治理指标；页面每 20 秒刷新 | `gateway/.../AdminMonitoring.java:65-74,257-289` |
| 业务指标 | **6 个** `rag_*` Prometheus 指标 + `X-Trace-Id` 透传 | `README.md:125` |
| 接口契约 | **20 条外部 + 10 条内部 = 30 条** | `contracts/interfaces.json`；`README.md:127` |
| API 冒烟 | **106 + 65 + 97 = 268 项**（另多轮 375 项，单独计） | `artifacts/*_report.json` summary |
| 浏览器 E2E | **84 项 = 28 + 22 + 15 + 19**，4 相位、3 档分辨率、控制台/网络/溢出 0 违规 | `artifacts/ui_visual_report-*.json` |
| 金标 | **40 题 = 30 有答案 + 10 资料不足**（含 1 负例、1 含手机号/邮箱样本） | `eval/golden.json:2-3` |
| 质量门禁 | Faithfulness **0.967** / Context Recall **0.967**（阈值 0.90 / 0.85）→ **通过** | 最新报告；`scripts/smoke_phase5.py:96-111` |
| 评测报告 | **47 个**报告文件；41 份 OK（其中 **38 份**全量双指标达标）；6 份 FAILED（演练） | `eval/reports/report-*.json` 实算 |
| 单日真实用量示例 | 输入 11,257 / 输出 511 tokens → 估算 **CNY 0.0283**，待对账 0 | `artifacts/screenshots/admin-usage-1440.png` |
| 发布 | `VERSION=0.1.0`；发布清单 2 份（phase5prev/curr，各 8 镜像） | `VERSION`、`release/*.json` |
| 单题成本 | ≈¥0.005/次 | ⚠️ 与用量页不一致，见 §6 |

## 1. 架构与服务

### 1.1 13 个默认容器（`compose.yaml`）

| 服务 | 端口 | 技术/镜像 | 作用 |
|---|---|---|---|
| gateway | 8080（宿主 127.0.0.1:8080） | Spring Cloud Gateway / JDK 21 | 唯一 Web 入口：`/admin/`、`/ask/`、`/register/`、`/api/v1/*`、`/actuator/*` |
| doc-service | 8082（仅内网） | Java | 上传/解析/版本/原文定位，Flyway `doc_db` |
| search-service | 8083（仅内网） | Java | Kafka 消费解析事件、写 OpenSearch，Flyway `search_db` |
| ai-service | 8081（仅内网） | Java | 限流、检索编排、引用校验、模型调用/切备、计量 |
| metric-service | 8090（仅内网） | Python 3.12 | 离线评测 `POST /internal/v1/evaluations` |
| keycloak | 8180→8080（宿主 127.0.0.1:8180） | `keycloak:26.8.2`（自构建，realm `rag-local`、主题 `rag`） | OIDC / 账号目录 |
| mysql | 3306（仅内网） | `mysql:8.4` | **单实例双库** `doc_db`、`search_db`（非两个容器） |
| kafka | 9092（仅内网） | `apache/kafka:4.2.2` | 解析完成/索引结果事件 |
| opensearch | 9200（仅内网） | `opensearch:3.10.0` | 索引 `rag-doc-chunks-v1`，128 维本地向量 |
| redis | 6379（仅内网） | `redis:7` | 限流、Token 台账、attemptId 去重 |
| nacos | 8848（仅内网） | `nacos-server:v2.3.2` | 服务注册/发现 |
| model-primary | 8095（宿主 127.0.0.1:8095） | 本地模型桩 | 主端点（可切正常/超时/429/5xx） |
| model-backup | 8096（宿主 127.0.0.1:8096） | 本地模型桩 | 备端点 |

- 宿主仅发布 `8080/8180/8095/8096`；文档/检索/AI/评测/Nacos/Kafka/OpenSearch/Redis/MySQL 只在 Compose 内网，经网关访问（`README.md:44-46`）。
- `tools` profile 只有 `metric-eval`、`metric-probe`（maven:3.9-eclipse-temurin-21），用于离线评测/连通性，不计入 13 个常驻容器。

### 1.2 版本（✅ 均来自文件）

| 组件 | 版本 | 依据 |
|---|---|---|
| JDK | 21 | `README.md:141`、`pom.xml` |
| Spring Boot | 4.0.9 | `pom.xml` parent、`README.md:141` |
| Spring Cloud | 2025.1.4 | `pom.xml`、`README.md:141` |
| Spring Cloud Alibaba | 2025.1.0.0 | `pom.xml`、`README.md:141` |
| 前端 | Vue 3.5.45（Vite 7.3.8）；Node 24 | `admin-web/package.json`、`README.md:7` |
| Python | 3.12（CI 与 metric-service） | `.github/workflows/ci.yml:34-37` |
| Keycloak | **26.8.2**（可说 Keycloak 26；勿说 26.8 以外版本） | `docker/keycloak/Dockerfile` |
| Kafka | 4.2.2 | `README.md:64`、`compose.yaml` |
| OpenSearch | 3.10.0 | `README.md:64`、`compose.yaml` |
| Nacos | **2.3.2**（⚠️ 现有 PPT 写 3.2.3，错） | `compose.yaml` image `nacos/nacos-server:v2.3.2` |
| MySQL / Redis | 8.4 / 7 | `compose.yaml` |
| K8s 版本 | ❓ 仓库无集群版本锁定，勿说“K8s 1.34” | 仅 `k8s/` 清单，无版本声明 |

### 1.3 文档发布链路（`README.md:58-64`）

- 上传返回 **202**，版本状态机：`PENDING → PARSING → INDEXING → READY`，任一步失败 → `FAILED`。
- 单文件上限 **10 MiB**；解析上限 **10,485,760 UTF-16 代码单元**、**100,000 个非空位置**，超限 `DOCUMENT_TOO_COMPLEX`；扫描 PDF → `FAILED/NO_EXTRACTABLE_TEXT`。
- 定位单位：DOCX 段落+表格、PDF 页号、TXT 行号，均 **从 1 开始**（`README.md:62`）。
- 索引一致性：OpenSearch 刷新后核对可搜索分块数；文档服务收到成功事件才推进生效版本（`README.md:60`）。
- Kafka 主题（`doc-service/.../DocumentPipeline.java:29-30,208-210`）：`rag.document.parsed.v1`、`rag.document.result.v1`，各配 `.DLT` 死信；本地 1 分区/1 副本。
- 幂等上传：请求头 `Idempotency-Key` 1–128 个可见 ASCII；同键同内容返回原版本，同键异内容 `409 IDEMPOTENCY_CONFLICT`，按租户隔离（`doc-service/.../DocumentUploads.java:21-49`）。

### 1.4 问答与引用（`README.md:66-72`）

- 检索：租户 + 向量模型版本预过滤 → BM25 与 k-NN 两路召回 → **RRF 融合** → 批量核验生效版本 → 过滤旧版补取 → **最多 5 个片段**给模型。
- 引用：编号与 `citations` 同序；引用必须**逐字**出现在本次召回片段与对应版本原文中；资料不足 → `INSUFFICIENT_EVIDENCE` + 空引用；模型输出/引文校验失败 → **502**；依赖不可用 → **503**。
- 多轮：前端提交最近 **3 轮** history，AI 服务最多用 **4 轮**；history 只帮助理解问题，绝不作证据。
- 时延：`.env` 模型超时 **20000ms**，README 声明“仍小于 **40 秒**问答预算”（`README.md:87`）；40s 是设计预算，代码未单独硬校验，台上说“设计预算”即可。
- 输出长度：规划文档写单次输出 **200 Token** 为压测基线（`企业级RAG知识引擎项目规划.md:175-177`），但代码 `AnswerModel.java:159` 实际 `max_tokens=900`；**不要宣称“输出上限 200 Token”**，可说“输出受控、成本按 token 计量”。

### 1.5 API 与错误码

- `contracts/interfaces.json`：`contractVersion=0.1.0`，20 外部/10 内部；错误信封固定 `{code, message, traceId}`。
- 成功：`200` 常规、`201` 注册成功、`202` 上传受理。
- 错误：`400`（空问题/非法参数/unknown content type）、`401`（未登录/无效令牌）、`403`（角色不足）、`404`（跨租户或不存在）、`409`（评测执行中 `EVALUATION_IN_PROGRESS`/用户名已注册 `USERNAME_TAKEN`）、`413`（>10 MiB）、`415`（不支持格式/媒体类型）、`429`（租户限额，带 `Retry-After`）、`502`（模型输出无效）、`503`（依赖/模型不可用）。
- 管理台 4 个哈希路由：`#/evaluation`、`#/monitoring`、`#/access`、`#/invites`，仅 `ADMIN`（`README.md:105`）。

## 2. 契约与测试规模（口径以文件实算为准）

### 2.1 接口契约：**30 条（20 外部 + 10 内部）** ✅

| 分组 | 条数 | 路径示例 |
|---|---|---|
| 身份/文档 | 8 | `GET /api/v1/identity`；`/documents`、上传、`/versions`、`/versions/{v}`、`/versions/{v}/source|parsed|file` |
| 问答/用量 | 2 | `POST /api/v1/questions`；`GET /api/v1/usage` |
| 评测 | 3 | `GET /api/v1/evals`、`GET /api/v1/evals/{reportId}`、`POST /api/v1/evals` |
| 监控/权限目录 | 2 | `GET /api/v1/monitoring`、`GET /api/v1/access` |
| 注册/邀请码 | 3 | `POST /api/v1/register`、`GET /api/v1/invite-codes`、`POST /api/v1/invite-codes/rotate` |
| 用户升降级 | 2 | `POST /api/v1/users/{username}/promote|demote` |

内部 10 条：`doc-active-versions`、`doc-parsed`、`doc-source`、`doc-file`、`search-retrieval`、`ai-questions`、`ai-usage`、`metric-evaluations`、`metric-health`、`keycloak-admin-users`（`contracts/interfaces.json`）。
`scripts/contract_tests.py:47-57` 按 `len(external)+len(internal)` 动态打印 **“20 external + 10 internal”**，末行 `CONTRACT_OK contractVersion=0.1.0`。
**⚠️ 旧口径全部作废**：提纲要“18+9 / 契约 27 条”、PPT `FACT` 常量 `contracts=27, contract_ext=18, contract_int=9`、手册 T4/T19 的“18+9”。

### 2.2 API 冒烟：**106 / 65 / 97，共 268 项，0 失败** ✅

| 报告 | 检查项 | 通过/失败 | 耗时 | 生成时间(UTC) |
|---|---|---|---|---|
| `artifacts/ui_api_report.json` | **106** | 106/0 | 9.8s | 2026-11-22T05:14:28Z |
| `artifacts/admin_features_report.json` | **65** | 65/0 | 4.9s | 2026-11-23T15:21:02Z |
| `artifacts/register_invite_report.json` | **97** | 97/0 | 6.8s | 2026-11-23T15:21:14Z |
| `artifacts/multiturn_report.json` | **375** | 375/0 | 46s | 2026-11-23T15:20:28Z |

- 覆盖：前者=基础链路/双租户/上传版本/幂等/错误码；后者=管理台评测、监控 10+9、权限目录与 401/403；注册=邀请码 120 分钟语义/轮换作废/注册 201·409·400/晋升降级/跨租户与自我修改防护（手册 T19 的描述与此一致）。
- 多轮 375 项单独报告：`scripts/multiturn_smoke.py` 对真实 DeepSeek 做 14 次提问，断言只校验契约与引用 grounding，答案不确定；**不要把它说成“确定性全绿 643 项”**。
- `ui_api_report.json` 唯一 deviation：UI 规范写 EMPLOYEE `GET /api/v1/documents` 返回 200，实际为 **403**，以 `GatewaySecurity` 与 `contract_tests.py` 为准（文档列表仅 ADMIN）。**⚠️ 旧口径**：提纲/手册“105 项（含 99 项）”无法从现有报告复现；正确为 106（ui_api）+65+97。

### 2.3 浏览器 E2E：**84 项 = 28+22+15+19** ✅

| 报告 | 相位 | 检查项 | 截图 | consoleErrors | failedRequests | overflowViolations |
|---|---|---|---|---|---|---|
| `ui_visual_report-new.json` | new（文档/上传/问答/响应式） | **28** | 14 | 0 | 0 | 0 |
| `ui_visual_report-admin.json` | admin（评测图表/监控/权限） | **22** | 5 | 0 | 0 | 0 |
| `ui_visual_report-chat.json` | chat（多轮/聚焦/移动 dock） | **15** | 3 | 0 | 0 | 0 |
| `ui_visual_report-register.json` | register（登录/邀请码/注册/成功卡） | **19** | 5 | 0 | 0 | 0 |
| 合计 | 4 相位 | **84** | 27 | 0 | 0 | 0 |

- 三档分辨率实测：**1440×900**（24 张）、**1024×768**（1 张）、**390×844**（6 张）。
- `ui_visual_report.json` 是 `-new` 的副本（同 `startedAt=2026-11-24T08:25:27.575Z`），勿重复计数；`-baseline`（2026-11-20）是旧 UI，0 检查、4 个 consoleErrors，**不得作为 E2E 证据**。
- **⚠️ 旧口径**：提纲“26+19+21=66”、手册 T4/T19“27/22/21”（漏 chat 相位且 new/register 数不对）；PPT `FACT e2e="26 + 19 + 21"` 与正文“66”均需改为 **28+22+15+19=84**。

### 2.4 五阶段验收（`README.md:48-54,133`）

| 脚本 | 证明什么（一句话） |
|---|---|
| `smoke.py` | OIDC 登录、租户隔离、Nacos 发现、Flyway、Java↔Python 连通 |
| `smoke_phase2.py` | 三种文件定位、上传限制、幂等/并发追加、延迟索引、重复投递/乱序、扫描件失败、OpenSearch 拒写 + Kafka 死信 |
| `smoke_phase3.py` | 双租户问答、无依据拒答、生效版本切换、旧引用回查、依赖故障 |
| `smoke_phase4.py` | 主备切换、双端点故障、熔断/半开、租户限额、逐次计量与待对账、评测停机重跑 |
| `smoke_phase5.py` | 契约、探针/指标、traceId、金标门禁与脱敏、备份恢复、索引重建、固定镜像/发布清单/K8s 对齐/回滚 |

### 2.5 CI、发布与 K8s（✅）

- CI：单 job `release-gate`，`ubuntu-latest`，`timeout-minutes: 90`；JDK21/Node24/Python3.12 → `mvn verify` + 前端 build + `validate_k8s.py` + `py_compile` → `ci_env.py` → `docker compose config` → `compose up --build --wait` → `contract_tests.py` + `smoke.py` + `smoke_phase2..5`；`always` 上传 `release/*.json`、`eval/reports/*.json`、`backups/*.sha256`；失败打印 compose 日志（`.github/workflows/ci.yml:14-78`）。
- 本地等价门禁 `scripts/verify_release.ps1`：Docker Linux 检查 → Compose 配置 → Maven → 前端 → K8s 校验 → 契约 → 阶段 1-4 → 阶段 5，末行 `RELEASE GATE PASSED`（共 **7 个 Invoke-Step**）。⚠️ PPT `FACT verify_checks="11"` 无依据，建议改 7（或按 CI 步骤说“10 步”）。
- 版本与发布：`VERSION=0.1.0`；`scripts/release.py --revision <sha>` 打不可变 tag `0.1.0-<12 位 revision>` 并写 `release/<tag>.json`（镜像 ID、OCI 标签、上一版），同名拒绝覆盖；现有 `release/0.1.0-phase5curr.json`（seq 2，createdAt 2026-11-19T14:14:41Z，8 镜像）与 `phase5prev`（seq 1）。
- K8s：Namespace/ConfigMap/5 应用 Deployment/5 基础设施 Deployment/Service/Ingress/4 PDB/`kustomization.yaml`；3 类探针（startup 走 liveness 路径 5s×30，readiness `/actuator/health/readiness`，liveness `/actuator/health/liveness`）；`maxUnavailable: 0`、`maxSurge: 1`、`revisionHistoryLimit: 10`；非 root（65532）、`readOnlyRootFilesystem`、drop ALL；secret 经 `secretRef`，`secret.example.yaml` 仅模板不进 kustomization；Ingress `rag.example.com`、`proxy-read-timeout: 60`；PDB 4 个（gateway/doc/search/ai，`minAvailable: 1`）。
- K8s 与 Compose 不是同一口径：Compose 13 常驻容器（含 keycloak/mysql/kafka/opensearch/redis/nacos/2 模型桩），K8s 清单为 10 Deployment/14 Pod（**未包含 Keycloak**，视为外部 IdP；基础设施仅 mysql/kafka/opensearch/redis/nacos），**不要说“K8s 里 13 个容器”**。

## 3. 评测与质量（✅ 以最新报告为准）

### 3.1 金标组成（`eval/golden.json:2-3`）

- **40 题 = 30 道有答案 + 10 道资料不足**；含 **1 道负例**（index 2“病假工资怎么算？”：回答“全额发放” vs 参考“按当地最低工资标准的 80% 支付”）与 **1 道含手机号/邮箱样本**（index 3，张三 13800138000 / zhangsan@example.com，测试数据）。
- 资料不足题 index：`1, 31..39`（共 10）；有答案题含 index `0,2,3,4..30`（共 30）；**前三题固定**（年假 7 天 / 试用期年假 / 病假工资）以兼容既有验收。
- 报告归档前脱敏：手机号→`[PHONE]`、邮箱→`[EMAIL]`，报告 `redacted: true`；原文仍送评测服务（`README.md:129`）。

### 3.2 报告盘点（47 个文件）

| 类别 | 数量 | 说明 |
|---|---|---|
| `eval/reports/report-*.json` 总数 | **47** | 均含 generatedAt/status/samples/averages/redacted |
| status=OK | **41** | 38 份 40 题全量；3 份早期 3 题（0.333/0.5，不达标） |
| status=FAILED | **6** | 3 份 3 题、3 份 40 题；失败原因 5×`java.net.http.HttpTimeoutException: request timed out`、1×`java.io.IOException: HTTP/1.1 header parser received no bytes`（演练保留） |
| 全量 40 题且门禁通过 | **38** | JSON `averages` 完全一致：faithfulness **0.725**、context_recall **0.9667** |
| 双指标达标/OK 报告 | **38 / 41** | 页面环图旧截图显示 37/41，是旧值 |

- 最新报告：`eval/reports/report-1795509292467.json`，`generatedAt=1795509292467`（**2026-11-24 16:34:52**，本地时间），status `OK`，redacted `true`，40 样本。
- 逐样本：faithfulness=0 共 **11** 个（10 个资料不足 + 负例 index 2）；context_recall=0 共 **1** 个（负例）；context_recall=null 共 **10** 个（资料不足，按设计不计算）。
- **门禁算法**（`gateway/.../AdminEvaluations.java:207-257`）：只取金标含 `reference` 的 30 题 → 最新报告平均 Faithfulness = **29/30 = 0.9667**、Context Recall = **29/30 = 0.9667**；阈值 0.90/0.85 → **PASS**。`scripts/smoke_phase5.py:96-111` 同样断言 30/10、两项均值、资料不足 recall 为 null、负例 faithfulness≈0、归档已脱敏。
- 口径解释：报告 JSON 的 `averages.faithfulness=0.725` 是 **40 题全量**均值（11 个 0 分），**不是门禁值**；台上说“**0.967 / 0.967 通过门禁**”，如被追问再解释 0.725 是含资料不足题的全量口径。

### 3.3 评测实现

- `metric-service` 在 `POST /internal/v1/evaluations` 对单题返回两项分数；本地是**不依赖外部模型的确定性词面覆盖评分**，生产替换为获批的 RAGAS 评测模型；无参考答案时 `context_recall=null`（`README.md:101`）。
- 评测不依赖在线问答链路；服务不可用作业失败并保留失败原因，恢复后可重跑；报告由离线 `metric-eval` 作业或网关 `POST /api/v1/evals` 归档。

### 3.4 UI 显示缺陷（⚠️ 必须避开）

- `admin-web/src/components/EvaluationPanel.vue:631` 抽屉“门禁判定”用 `detail.faithfulness/detail.contextRecall`（接口实际返回在 `detail.averages.*`）→ **恒显示“未通过”**；页头卡片（`:337-357`）用列表 summary，显示“通过”。截图 `admin-eval-drawer-1440.png` 同时含“未通过”和脱敏姓名样例，**禁止投屏**。
- `admin-eval-1440.png` 顶部 96.7%/96.7%/40 样本/“通过”可用；但环图“37/41”为旧值（现 38/41），投屏前建议重截或裁掉该环图。

### 3.5 Token 计量与成本（`README.md:93-97`）

- 单价：输入 **2.13**、输出 **8.52** CNY/百万 token（`RAG_CHAT_PRICE_INPUT/OUTPUT_PER_MILLION`，`.env:22-23`），币种 CNY（`RAG_USAGE_CURRENCY`）；仅用于估算展示。
- 公式：`CNY = input/1e6*2.13 + output/1e6*8.52`。实测示例：11,257 输入 + 511 输出 = **0.0283 CNY**（用量页同值）。
- 台账（`ai-service/.../UsageLedger.java`）：Redis key `rag:usage:<tenant>:<date>`，字段 `<model>|in`、`<model>|out`、`<model>|reconcile`（待对账次数），TTL 45 天；用途固定 `chat`。
- 去重（`UsageLedger.java:91,104`）：key `rag:attempt:<attemptId>` 原子 claim，TTL 2 天；`attemptId = traceId + ":" + endpoint.tag()`（`AnswerModel.java:153`），请求头 `X-Attempt-Id`（`:169`）；超时或响应无 usage → 记 `reconciling`（待对账），监控指标 `rag.model.reconciling`。
- ⚠️ 手册 T4 的“≈¥0.005/次（今日 ¥0.15 / 约 40 次）”与当前用量页 CNY 0.0283（11,257+511 tokens）不一致（若 40 次则约 ¥0.0007/次）；建议改说“按 token 单价实测当日约 **¥0.03**”，不要给固定单题价。

## 4. 安全与治理

### 4.1 租户与账号（`README.md:137`；手册 T24）

| 账号 | 密码 | 租户 | 角色 | 用途 |
|---|---|---|---|---|
| a-admin | `RagLocalExample42!` | tenant-a | ADMIN | 管理员演示 |
| a-employee | `RagLocalExample42!` | tenant-a | EMPLOYEE | 越权演示（403） |
| b-admin | `RagLocalExample42!` | tenant-b | ADMIN | 租户隔离演示 |
| b-employee | `RagLocalExample42!` | tenant-b | EMPLOYEE | 租户隔离演示 |
| no-role | `RagLocalExample42!` | 见 realm | 无角色 | 权限边界测试 |
| no-tenant | `RagLocalExample42!` | 无 | — | 权限边界测试 |
| qa-member | `RagLocalExample42!` | tenant-a | EMPLOYEE | 邀请码注册/晋升演示（重登生效） |

- **密码仅本地测试环境**；生产绝不能上屏/写进讲稿。Keycloak 重启会重新导入 realm（dev-mem），除 `tenant_id` 属性外还有 `tenant-a`/`tenant-b` 分组兜底（`README.md:111,137`）。
- 页面“用户与权限”实测：7 账号/2 管理员/4 员工/2 租户（含 qa-member）。

### 4.2 身份与授权

- OIDC：realm `rag-local`、公开客户端 `rag-web`、`S256` PKCE；管理员 `a-admin/b-admin`；角色 `ADMIN`/`EMPLOYEE`；IdP 令牌需含 `sub、exp、tenant_id、roles`；`tenant_id` ≤64 字符小写字母/数字/连字符、字母或数字开头（`README.md:137-139`）。
- 管理台 4 路由仅 ADMIN；普通成员访问被网关拒绝；`GET /api/v1/documents` 仅 ADMIN（EMPLOYEE 实测 403，`ui_api_report.json` deviation）。
- 邀请码：每租户 1 枚 **8 位大写**，字母表 `ABCDEFGHJKMNPQRSTUVWXYZ23456789`；**120 分钟**自动轮换，可立即轮换，历史保留 **10 条**；持久化 `${RAG_INVITE_DIR:-/app/data}/invite-codes.json`；`POST /api/v1/register` 匿名，用户名 3–32 位字母/数字/点/下划线/连字符、密码 8–128 位，成功 **201**，重复 `409 USERNAME_TAKEN`（`README.md:113`）。
- 晋升/降级：`POST /api/v1/users/{username}/promote|demote`，仅本租户；跨租户 **403**，改自己 **400 CANNOT_MODIFY_SELF**，重登后生效（`README.md:111`）。

### 4.3 限流、熔断与可观测性

- 限流：租户每分钟 **12** 次（`RAG_TENANT_QUESTION_LIMIT` 默认 12，`compose.yaml:287`）；Redis 固定窗口 key `rag:rate:<tenant>:<epochMinute>`（`RateLimiter.java:27`）；超额 **429 + Retry-After**（=60-now%60 秒，最小 1，`RateLimiter.java:37`），且不再调用模型或检索（`README.md:95`）。
- 切备：主端点超时/供应商 429/5xx，在请求总时限内**最多切备一次**；按端点**连续失败 2 次**熔断，冷却（默认 **8000ms**，`compose.yaml:285-286`）后放行一次**半开探测**。本地默认两个模型桩可演练；**当前 `.env` 的 `RAG_CHAT_BACKUP_URL` 为空 → 线上 DeepSeek 实际切备关闭**，勿说“生产已开启主备”。
- 监控：并行探测 **10 个**目标（gateway、doc-service、search-service、ai-service、metric-service、keycloak、opensearch、nacos、model-primary、model-backup），连接超时 2s；聚合 **9 个**指标：`questionCount、questionFailures、questionSuccessRate、averageLatencyMs、inputTokens、outputTokens、modelFailovers、kafkaPendingEvents、indexFailures`（`AdminMonitoring.java:65-74,257-289`）；单行失败不影响整页；页面 20 秒自动刷新。
- Prometheus 指标（`README.md:125`）：`rag_questions_seconds`（直方图）、`rag_question_results_total`、`rag_model_tokens_total`、`rag_model_failover_total`、`rag_kafka_pending_events`、`rag_index_failures_total`；网关生成/透传 `X-Trace-Id`，日志带 traceId、错误码、耗时。

## 5. 视觉素材（artifacts/screenshots 共 31 PNG + diagrams）

### 5.1 截图清单（尺寸/用途/状态）

| 文件 | 尺寸 | 内容/最适合的页 | 备注 |
|---|---|---|---|
| `login-page-1440.png` | 1440×900 | Keycloak 自定义主题登录页 | ✅ 干净无 PII，最适合封面/身份页 |
| `admin-dashboard-1440.png` | 1440×900 | 文档库：4 篇 READY、今日 Token 1.2 万/CNY 0.03 | ✅ 工作台/文档库页 |
| `admin-dashboard-1024.png` | 1024×768 | 同上，平板断点 | ✅ 三档分辨率证据 |
| `admin-dashboard-390.png` | 390×844 | 同上，手机断点 | ✅ 响应式证据 |
| `admin-document-drawer-1440.png` | 1440×900 | 文档详情：v4 生效、DOCX 39.4KB、正文 | ✅ 发布/版本页（测试制度文本） |
| `admin-document-content-1440.png` | 1440×900 | 文档内容页（解析正文） | ✅ 与上张同批；未逐字目检 |
| `admin-upload-dialog-1440.png` | 1440×900 | 上传弹窗：DOCX/PDF/TXT、≤10 MiB | ✅ 上传/边界页 |
| `admin-upload-validation-1440.png` | 1440×900 | 上传校验失败态 | ✅ 边界/错误处理页（未逐字目检） |
| `admin-usage-1440.png` | 1440×900 | 用量：11,257/511 tokens、CNY 0.0283、待对账 0 | ✅ 成本页 |
| `admin-nav-1440.png` | 1440×900 | 管理台导航总览 | ✅ 导航/全貌页 |
| `admin-nav-mobile-390.png` | 390×844 | 移动导航 | ✅ 响应式证据 |
| `admin-eval-1440.png` | 1440×900 | RAGAS：96.7%/96.7%、40 样本、通过 | ✅ 质量页；环图 37/41 旧值，可裁 |
| `admin-eval-drawer-1440.png` | 1440×900 | 评测报告抽屉：门禁“未通过”、张三[PHONE]/[EMAIL] | ❌ **禁用**（UI bug + 姓名样例） |
| `admin-monitoring-1440.png` | 1440×900 | 监控：10/10 正常、依赖拓扑、20s 刷新 | ✅ 运营页 |
| `admin-monitoring-390.png` | 390×844 | 监控手机断点 | ✅ 响应式证据 |
| `admin-access-1440.png` | 1440×900 | 权限：7 账号/2 管理员/4 员工/2 租户 | ✅ 可控页；邮箱为 `*.example.invalid` 测试值 |
| `admin-access-actions-1440.png` | 1440×900 | 权限操作（旧批次） | ⚠️ 2026-11-22，不在任何 E2E 报告清单，弃用 |
| `admin-invites-1440.png` | 1440×900 | 邀请码：8 位码、120 分钟、剩余 119:59、历史 10 条 | ✅ 注册/可控页（码为本地测试码） |
| `admin-invites-390.png` | 390×844 | 邀请码手机断点 | ✅ 响应式证据 |
| `ask-1440.png` | 1440×900 | 问答首页/空态 | ✅ 入口/场景页 |
| `ask-390.png` | 390×844 | 问答手机断点 | ✅ 响应式证据 |
| `ask-answer-1440.png` | 1440×900 | 年假问答：答案 + [1] + 来源 v2·第1-19行、traceId | ✅ 可信页 |
| `ask-chat-1440.png` | 1440×900 | 多轮：差旅住宿追问，引用 [1][2][3]、traceId | ✅ 多轮/可信页 |
| `ask-chat-focus-1440.png` | 1440×900 | 多轮输入聚焦态 | ✅ 交互细节（未目检） |
| `ask-chat-dock-390.png` | 390×844 | 移动端固定输入 dock | ✅ 响应式证据 |
| `ask-citation-drawer-1440.png` | 1440×900 | 引用原文抽屉：v2、第1-19行、高亮引文 | ✅ 引用回查页，无 PII |
| `register-1440.png` | 1440×900 | 注册表单（邀请码+账号+密码） | ✅ 注册页 |
| `register-success-1440.png` | 1440×900 | 注册成功卡：qa-visual-mwug9wad 加入 tenant-a | ⚠️ 临时账号名，投屏前裁掉或换 register-1440 |
| `baseline-admin-1440.png` | 1440×900 | 旧 UI（2026-11-20），4 个 consoleErrors | ❌ 禁用 |
| `baseline-admin-390.png` | 390×844 | 旧 UI 手机 | ❌ 禁用 |
| `baseline-ask-1440.png` | 1440×900 | 旧 UI 问答 | ❌ 禁用 |

- 所有截图均来自本地演示数据；`ask-*` 的答案引用为 4 篇测试制度，无真实客户资料。评测/权限页出现的姓名与联系方式均为测试数据且已脱敏，但仍建议不要放大展示 `admin-eval-drawer`。

### 5.2 架构图 `artifacts/diagrams/rag-pipeline.png` / `.svg`

- PNG 原生 **3360×2420**（2× 渲染）；SVG `viewBox="0 0 1680 1210"`，宽高比 **1.388（≈25:18）**，适合 16:9 页内留白放置。
- 布局（viewBox 坐标）：ACCESS 标签 y134、4 框 y146-240；EDGE 网关深色框 (64,298,1208×98)、Nacos (1232,298,384×98)；WRITE 标签 (64,440)、写入框组 x64-824 y452-826；QUERY 标签 (880,440)、问答框组 x880-1656 y452-838；GOVERNANCE 标签 y886、3 框 y898 h104；PLATFORM 标签 y1032、平台条 (64,1044,1552×76)；页脚 y1156。
- 建议裁剪矩形（SVG viewBox 坐标；若裁 PNG，全部 ×2）：

| 用途 | x | y | w | h | 覆盖内容 |
|---|---|---|---|---|---|
| 全图 | 0 | 0 | 1680 | 1210 | 完整五带 |
| 接入+网关 | 40 | 120 | 1600 | 290 | ACCESS 四框 + EDGE 网关/Nacos |
| 写入链路 | 40 | 425 | 820 | 425 | 上传→doc-service→Kafka→search→OpenSearch |
| 问答链路 | 836 | 425 | 828 | 430 | 提问→ai→检索→引用校验→DeepSeek（含 x848 竖排标签） |
| 治理+平台 | 40 | 870 | 1600 | 265 | GOVERNANCE 3 框 + PLATFORM 条 |

- 裁剪后需目检一次边缘；图上文字含“13 容器/40 题/10 服务/9 指标/120 分钟/8 位/10 MiB/topK 5/RRF/20s”等，与本文件一致。

### 5.3 演讲手册 `docs/演讲手册-企业级RAG知识引擎.docx`（109 段/29 表/1 内嵌图）

- 章节：使用说明、一页纸速览、全链路实现图（讲解核心）、图怎么讲（五段台词）、SCRIPT 按时间码、15 分钟标准讲稿、证据链（附录 A）、答辩深挖四页、Q&A 预案（10 问）、附录 B 账号/地址/命令、附录 C 讲前 10 项检查、附录 D 三种时长取舍。
- 重点表：T4 六数字行（⚠️ 18+9 错、106/65/97 对、E2E 27/22/21 错、0.967 对、≈¥0.005 存疑）、T16 8 步演示脚本（含兜底：4 篇 READY、42 份报告旧值）、T19 证据链（⚠️ 18+9、42 份旧值）、T24 账号表（qa-member）、T25 十条命令、T27 讲前检查（13 容器 healthy/4 篇 READY/报告≥42 旧值）、T28 5/15/30 分钟取舍。
- Q&A 10 问（T23，可直接用于答辩）：为什么不用现成 RAG 平台/怎么控制幻觉/数据安全怎么保证/效果数字可信吗/成本和性能如何/出故障怎么办/怎么上线/最难的点是什么/为什么写那么多测试/后面怎么做大。
- 四个深挖主题（T21）：**版本一致性**（索引失败但版本已生效怎么办）、**检索融合**（为何 BM25+向量、RRF）、**模型治理**（超时/429 怎么办）、**评测体系**（为何只有 30 题参与召回门禁）。

## 6. 不一致清单（上台前必须统一口径）

| # | 旧说法（出现处） | 正确值/建议 | 依据 |
|---|---|---|---|
| 1 | “18 条外部+9 条内部”“契约 27 条”（提纲 105/134 行；手册 T4/T19；PPT `FACT`） | **20 外部 + 10 内部 = 30 条** | `contracts/interfaces.json`、`README.md:127` |
| 2 | “API 冒烟 105 项（含 99 项）”（提纲 107 行；PPT `smoke=105`） | **106 + 65 + 97 = 268 项**（另多轮 375 单独） | `artifacts/*_report.json` |
| 3 | “E2E 26+19+21 项”“66 项”（提纲 108/134 行；PPT `e2e=26 + 19 + 21`、正文 66） | **28+22+15+19 = 84 项**，4 相位、3 分辨率 | `artifacts/ui_visual_report-*.json` |
| 4 | “E2E 27/22/21”（手册 T4/T19） | 同上 84；手册漏 chat 相位且 new/register 数不准 | 同上 |
| 5 | “至少 26 份评测报告”“42 份历史报告”（提纲 171 行；手册 T16/T19） | **47 个报告文件**；41 OK（38 份双达标）；6 FAILED 演练 | `eval/reports/*.json` |
| 6 | 页面环图“37/41 份报告双指标达标”（截图；PPT `pass_reports=37 / 41`） | **38 / 41** | `eval/reports` 实算 |
| 7 | “MySQL×2”“mysql-doc:3306 / mysql-search:3306”（手册 T4；PPT 第 679 行） | **1 个 mysql:8.4 实例，双库 doc_db/search_db** | `compose.yaml` |
| 8 | “Nacos 3.2.3”（PPT 第 694 行） | **Nacos 2.3.2**（`nacos/nacos-server:v2.3.2`） | `compose.yaml` |
| 9 | “K8s 1.34”（PPT 第 694 行） | ❓ 仓库无版本锁定，建议删掉具体版本 | 无文件依据 |
| 10 | “输出上限 200 Token”（规划文档 :175-177 压测基线） | 代码 `max_tokens=900`；**不要台上说 200 上限** | `ai-service/.../AnswerModel.java:159` |
| 11 | “40 秒预算”（PPT 多处） | 仅 README 的设计表述，无代码断言；可说“20s 模型超时，仍留足 40s 设计预算” | `README.md:87` |
| 12 | “≈¥0.005/次”“今日 ¥0.15/40 次”（手册 T4） | 用量页实测 **CNY 0.0283**（11,257 in + 511 out）；固定单题价不成立，建议只说 token 单价与当日总量 | `admin-usage-1440.png`、`.env:22-23` |
| 13 | 评测抽屉“未通过”（`admin-eval-drawer-1440.png`） | 页头/列表显示“通过”；抽屉是前端 bug，**禁用该截图** | `EvaluationPanel.vue:631` vs `:337-357` |
| 14 | “verify_release 检查项 11”（PPT `FACT verify_checks=11`） | `verify_release.ps1` = **7 个 Invoke-Step**（CI 约 10 个步骤） | `scripts/verify_release.ps1:16-28` |
| 15 | “主备切换已开启”（演示若暗示生产） | 本地 `.env` 的 `RAG_CHAT_BACKUP_URL` 为空，DeepSeek 主备关闭；切备只可用模型桩演练 | `.env:25`、`README.md:89` |
| 16 | “EMPLOYEE GET /documents=200”（UI 规范） | 实际 **403**（仅 ADMIN），契约测试也断言 403 | `ui_api_report.json` deviation |

- ❓ 未核实：K8s 集群版本；“单次 ¥0.005”的推导；PPT `verify_checks=11` 的口径；`release.py` 生成的 revision-tag 清单未出现在 `release/`（当前只有 phase5prev/curr）。
- ✅ 已核对正确的旧文案：13 个容器、10 个服务探针、9 个治理指标、40 题金标 30/10、阈值 0.90/0.85、0.967/0.967、106/65/97、首次登录/邀请码/晋升降级细节、K8s 5 应用 Deployment/3 类探针/maxUnavailable 0。

## 7. 给建 deck 脚本的替换清单（`docs/ppt/build_deck.py`，当前常量已过期）

```python
"containers": "13",              # 正确
"services": "10", "metrics": "9", # 正确
"gold": "40", "answered": "30", "insufficient": "10", # 正确
"faith_gate": "0.90", "recall_gate": "0.85", "faith": "96.7%", "recall": "96.7%", # 正确
"pass_reports": "38 / 41",       # 原 37 / 41
"reports_total": "47",           # 正确（41 OK / 6 FAILED）
"smoke": "106",                  # 原 105
"e2e": "28 + 22 + 15 + 19",      # 原 "26 + 19 + 21"；正文 66 → 84
"contracts": "30", "contract_ext": "20", "contract_int": "10", # 原 27/18/9
"verify_checks": "7",            # 原 11
```

- 第 647 行硬编码 `("66", "浏览器 E2E 条")` 改为 `("84", ...)`；第 679 行 `mysql-doc/mysql-search` 改为 `mysql:3306（doc_db/search_db）`；第 694 行 `Keycloak 26 · Nacos 3.2.3`、`K8s 1.34` 需修正/删除。
