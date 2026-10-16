# ParTrace Vision · 后端写库对接文档

> 面向：后端开发（Python / FastAPI）
> 目标库：AWS Aurora PostgreSQL **17.9** + pgvector **0.8.1**，库名 `partrace`
> 文档版本：2026-10-16 ｜ 数据库状态：**已建好并通过 22 项功能测试** ｜ 后端：**已有 development 本地实现；Aurora IAM 认证已在代码实现（`backend/storage/credentials.py`），真实集群联调尚未验证**
> 契约基线：表结构、字段与约束以 [schema.sql](schema.sql)（`0.3-mvp-draft`，11 表 / 77 字段）为准。当前目标环境固定为 Aurora PostgreSQL **17.9** + pgvector **0.8.1**（已核对项目实际仅用 `vector` / `vector_dims` / `vector_norm` / `<=>`，功能维度满足，决定不升级），本文连接与报错实测结论均来自该实例。DDL 草案早前在 PostgreSQL 17 / pgvector 0.8.7 隔离环境验证（见 [schema-catalog.json](schema-catalog.json)），仅作历史验证记录，与目标环境版本的差异已确认无功能影响。
> 范围说明：本文的建库与功能验证不替代后端服务实现、真实媒体、模型编码与业务闭环验收（D10），与 [README.md](README.md#建表检索与验证) 的验证口径一致。
> 本地实现与运行入口见 [Backend README](../../backend/README.md)：本地使用独立 PostgreSQL／pgvector 和 development 鉴权；Aurora IAM 连接机制已实现（见 1.3），但服务尚未对该实例完成联调验证，不代表已接通。

---

## 0. 一句话背景

数据库（11 表 / 77 字段 / 13 外键 / 30 索引 / 52 CHECK / 25 触发器）已经建好并在真实 Aurora 上验证通过。
IAM 认证连接已经在 `backend/storage/credentials.py` 实现；你要做的是按下面第 3、4 节的规则把数据写进去。

**数据库已经用触发器和约束把业务不变量都兜住了** —— 你不用在代码里重复实现"不可变""只追加""幂等去重"这些规则，写错了数据库会直接拒绝并给出明确报错。你要做的是**正确捕获这些报错并映射成 HTTP 状态码**（见第 7 节）。

---

## 1. 连接方式（最容易踩坑的部分，务必先读完）

### 1.1 连接参数

| 参数 | 值 |
|---|---|
| `host` | `database-1.cluster-c74o0swyw1y4.us-west-2.rds.amazonaws.com` |
| `port` | `5432` |
| `dbname` | `partrace` |
| `user` | `postgres` |
| `sslmode` | `require` |
| `region` | `us-west-2` |
| 密码 | **无**（用 IAM 令牌，见下） |

### 1.2 ⚠️ 三个必须知道的前提

**① 必须写域名，绝对不要写 IP。**

endpoint 会解析出 3 个公网 IP 轮询（`44.225.103.178` / `44.245.30.107` / `54.201.252.93`）。
写死任何一个 IP，在 Aurora 故障转移、扩缩容或集群改名（`database-1` → `database-anker`）之后必然断连。

**② 不能用密码登录 —— 该集群强制 IAM 认证。**

集群启用了 **Internet Access Gateway（IAG）**，它强制要求 IAM DB 认证且**无法关闭**。
实测：即使新建一个普通 DB 用户并设置密码，登录也会被拒：

```text
FATAL:  PAM authentication failed for user "app_test"
```

这是**集群级**限制，不是只针对主用户。所以别去尝试"建个用户配个密码"。

**③ IAM 令牌有效期 15 分钟。**

但过期规则很反直觉，见 1.4。

### 1.3 连接实现（已在 backend 落地）

服务跑在 EC2 上时，**不需要配置任何 access key** —— 实例角色会自动提供凭据。

IAM 令牌签名已在 `backend/storage/credentials.py` 实现：等价于 `aws rds generate-db-auth-token` 的 SigV4 预签 URL 用**标准库**重写，不引入 boto3，镜像的哈希锁定依赖集保持不变。实例角色的临时凭据通过 IMDSv2 读取，只保存在内存、到期前 5 分钟换新；每个令牌有效期 15 分钟。

```python
from backend.core.settings import Settings
from backend.storage.credentials import connection_class

settings = Settings()  # PARTRACE_DATABASE_IAM_AUTH=true、PARTRACE_DATABASE_IAM_REGION=us-west-2
factory = connection_class(settings)          # asynchronous=True 时返回异步连接类
conn = factory.connect(settings.database_url.get_secret_value())
```

> **关键点：令牌挂在“新建物理连接”这个时机上。**
> 实现继承 `psycopg.Connection`／`AsyncConnection` 并重写 `_get_connection_params`，在每次新建物理连接时现签令牌（背景见 `credentials.py` 注释）。连接池因此永远不会重放过期令牌，不需要 `max_lifetime` 回收或刷新定时器。
> 连接池（`backend/services/facts.py`）、就绪检查（`backend/storage/database.py`）、Agent 执行器（`backend/ai/runtime.py`）和管理命令（`backend/manage.py`）共用同一工厂；`PARTRACE_DATABASE_IAM_AUTH=false`（默认）时工厂返回原生类，密码部署完全不变。
> 签名正确性由 `backend/tests/test_credentials.py` 锁定：冻结时间戳下与 botocore `SigV4QueryAuth` 的参考签名逐字节一致。取不到实例角色时抛出 `CredentialUnavailable`（`psycopg.OperationalError` 子类），既有就绪探针和请求事务统一按“数据库暂时不可用”处理。

部署时在 EC2 上使用 compose 叠加层（变量见 `infra/.env.example`），只启动 API：

```sh
docker compose -f infra/compose.yaml -f infra/compose.aurora.yaml up -d --no-deps api
```

叠加层按 `PARTRACE_AURORA_HOST`／`PARTRACE_AURORA_REGION` 生成无密码、`sslmode=require` 的连接 URL 并强制开启 IAM 认证；捆绑 postgres 服务的变量仍须保留（Compose 会解析整个文件）。

### 1.4 令牌过期规则（反直觉，但很重要）

令牌只在**建立连接握手时**校验一次。连上之后就不再检查了。所以：

| 场景 | 结果 |
|---|---|
| 一条连接活了几小时 | ✅ 正常，令牌过期不影响已建立的连接 |
| 缓存令牌，15 分钟后拿它建新连接 | ❌ `PAM authentication failed` |
| 每次新建连接时现签令牌（上面的写法） | ✅ 永远正确 |

**所以：不要在应用启动／模块加载时算一次令牌存起来。** 要挂在"建连接时"这个时机上——1.3 的连接类工厂已经按这个原则实现。

### 1.5 本地开发怎么办

服务的 IAM 路径只从 EC2 实例元数据（IMDSv2）读取临时凭据，**不读取本地 access key**。因此本地不要以 IAM 方式连 Aurora：本地开发继续使用本地 PostgreSQL／pgvector 容器和密码连接（见 [Backend README](../../backend/README.md) 本地准备），Aurora 联调在带实例角色的 EC2 上按 1.3 的叠加层进行。

### 1.6 权限与生产建议

当前 EC2 实例角色是 `AnkerEC2RDSRole`，其策略只允许连 `postgres` 这一个用户：

```text
arn:aws:rds-db:us-west-2:525881111202:dbuser:cluster-BL34VX7QM6R4EXHTAHYZ6P3Q2E/postgres
```

**生产环境建议改用独立应用账号**，不要用主账号：

```sql
-- 在库内执行（需要先有连接）
CREATE ROLE app WITH LOGIN;
GRANT rds_iam TO app;        -- ← 必须！IAG 下新用户不能设密码，只能走 IAM
GRANT CONNECT ON DATABASE partrace TO app;
GRANT USAGE ON SCHEMA public TO app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app;
```

再到 IAM 策略里追加一条 Resource：`.../dbuser:cluster-BL34VX7QM6R4EXHTAHYZ6P3Q2E/app`
（改 IAM 策略需要去 CloudShell，EC2 的角色没这个权限）

---

## 2. 写入接口 → 表映射

后端是**唯一写入口**。按设计有 5 个写入接口 + 3 个 Agent 写入接口（统一前缀 `/api/v1`）：

| 接口 | 落表 | 事务要求 |
|---|---|---|
| `POST /api/v1/recordings` | `recordings` + `events` + `evidence` | **单事务原子发布**，失败整体回滚 |
| `POST /api/v1/event-embeddings` | `event_embeddings` | 单条写入 |
| `POST /api/v1/review-records` | `review_records` | 单条写入 |
| `POST /api/v1/parcels` | `parcels` | 单条写入 |
| `POST /api/v1/parcels/{parcel_id}/records` | `parcel_records` | **需锁包裹**，见 6.3 |
| `POST /api/v1/agent/conversations` | `conversations` | 单条写入 |
| `POST /api/v1/agent/conversations/{conversation_id}/messages` | `messages`（role=`user`） | 单条写入 |
| `POST /api/v1/agent/runs` | `runs` + `messages`（role=`assistant`） | **完成态与答复同事务** |

`users` 表**没有写接口** —— 按设计不提供注册/登录/用户管理，用户由服务端预置或外部同步。

---

## 3. 表结构与写入顺序

### 3.1 写入依赖顺序（外键决定，不能乱）

```text
users ──┬─→ recordings ─→ events ─┬─→ evidence ─→ event_embeddings
        │                          └─→ review_records
        ├─→ conversations ─→ messages(role=user) ─→ runs ─→ messages(role=assistant)
        │                                              ↑
        └─→ parcels ─→ parcel_records                  └── runs 与 messages 循环外键
```

**循环外键注意**：`messages.run_id` → `runs.run_id`，而 `runs.input_message_id` → `messages.message_id`。
所以正确顺序是：

1. 先插 `role='user'` 的消息（此时 `run_id` 必须为 `NULL`）
2. 再插 `runs`（引用那条 user 消息）
3. 最后插 `role='assistant'` 的消息（带 `run_id`）

### 3.2 全部 11 张表

#### `users` — 用户（5 列）

| 列 | 类型 | 约束 |
|---|---|---|
| `user_id` | TEXT | **PK**, NOT NULL |
| `display_name` | TEXT | NOT NULL |
| `role` | TEXT | NOT NULL，∈ `admin` / `viewer` |
| `status` | TEXT | NOT NULL，默认 `active`，∈ `active` / `disabled` |
| `created_at` | TIMESTAMPTZ | NOT NULL，≥ 1970-01-01 |

#### `recordings` — 录像（10 列）

| 列 | 类型 | 约束 |
|---|---|---|
| `recording_id` | TEXT | **PK**, NOT NULL |
| `camera_id` | TEXT | NOT NULL，非空串 |
| `config_version` | TEXT | NOT NULL，非空串 |
| `source_mode` | TEXT | NOT NULL，∈ `prerecorded` / `sdk` / `mock` |
| `started_at` | TIMESTAMPTZ | **可空**（未验证 UTC 起点时填 NULL） |
| `duration_ms` | BIGINT | NOT NULL，> 0 |
| `manifest_sha256` | TEXT | NOT NULL，**必须 64 位小写 hex** |
| `storage_key` | TEXT | 可空；`media_status='available'` 时**必填** |
| `media_status` | TEXT | NOT NULL，默认 `missing`，∈ `available` / `missing` |
| `created_at` | TIMESTAMPTZ | NOT NULL |

> **不可变**：除 `media_status` 外全部字段不可 UPDATE。想改事实必须新建 `recording_id`。

#### `events` — 事件（10 列）

| 列 | 类型 | 约束 |
|---|---|---|
| `event_id` | TEXT | **PK**, NOT NULL |
| `recording_id` | TEXT | NOT NULL，**FK → recordings** |
| `zone_id` | TEXT | NOT NULL，非空串 |
| `track_key` | TEXT | 可空（仅在同一 recording 内有效） |
| `event_type` | TEXT | NOT NULL，∈ `enter_zone` / `dwell` / `shelf_interaction` / `leave_zone` |
| `observation_status` | TEXT | NOT NULL，默认 `candidate`，∈ `observed` / `candidate` |
| `start_offset_ms` | BIGINT | NOT NULL，≥ 0 |
| `end_offset_ms` | BIGINT | NOT NULL，≥ `start_offset_ms` |
| `detection_source` | TEXT | NOT NULL，∈ `automatic` / `manual` / `mock` |
| `summary` | TEXT | 可空 |

> **额外校验**：`end_offset_ms` 不得超过所属录像的 `duration_ms`（触发器拦截）。
> **完全不可变**：任何 UPDATE 都被拒。

#### `evidence` — 关键帧（6 列）

| 列 | 类型 | 约束 |
|---|---|---|
| `evidence_id` | TEXT | **PK**, NOT NULL |
| `event_id` | TEXT | NOT NULL，**FK → events** |
| `offset_ms` | BIGINT | NOT NULL，≥ 0，且 ≤ 录像 `duration_ms` |
| `bbox_json` | JSONB | 可空，见下方格式 |
| `storage_key` | TEXT | 可空；`available` 时必填 |
| `media_status` | TEXT | NOT NULL，默认 `missing`，∈ `available` / `missing` |

唯一约束：`UNIQUE (evidence_id, event_id)`（供 `event_embeddings` 复合外键引用）

`bbox_json` 格式：归一化 `[x, y, w, h]`，四个都是 number，且
`x ≥ 0`、`y ≥ 0`、`w > 0`、`h > 0`、`x + w ≤ 1`、`y + h ≤ 1`。

```json
[0.12, 0.34, 0.08, 0.21]
```

> **位置不可变**：`evidence_id` / `event_id` / `offset_ms` / `bbox_json` / `storage_key` 不可 UPDATE。

#### `event_embeddings` — 事件描述向量（10 列）

| 列 | 类型 | 约束 |
|---|---|---|
| `embedding_id` | TEXT | **PK**, NOT NULL |
| `event_id` | TEXT | NOT NULL，**FK → events** |
| `evidence_id` | TEXT | 可空；非空时**复合 FK → (evidence_id, event_id)** |
| `source_text` | TEXT | NOT NULL，非空串 |
| `embedding` | **VECTOR** | NOT NULL（无定长） |
| `model_name` | TEXT | NOT NULL |
| `model_version` | TEXT | NOT NULL |
| `dimensions` | INTEGER | NOT NULL，1–16000 |
| `content_sha256` | TEXT | NOT NULL，**必须 64 位小写 hex** |
| `created_at` | TIMESTAMPTZ | NOT NULL |

> **三个硬校验**：
> 1. `vector_dims(embedding) = dimensions` —— 维数必须自洽
> 2. `vector_norm(embedding) > 0` —— **全零向量被拒**
> 3. 向量本身拒绝 `NaN` / `Infinity`
>
> **幂等去重**（两条部分唯一索引，自动生效）：
> - 无关键帧时：`(event_id, model_name, model_version, content_sha256)` 唯一
> - 有关键帧时：`(event_id, evidence_id, model_name, model_version, content_sha256)` 唯一
>
> **完全不可变**：任何 UPDATE 都被拒。

写入示例：

```python
cur.execute(
    """INSERT INTO event_embeddings
       (embedding_id, event_id, evidence_id, source_text, embedding,
        model_name, model_version, dimensions, content_sha256, created_at)
       VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, now())""",
    (emb_id, event_id, evidence_id, source_text,
     embedding_list,          # 直接传 Python list[float]，psycopg 会转成 vector
     model_name, model_version, len(embedding_list), content_sha256),
)
```

> `embedding` 列直接传 Python 的 `list[float]` 即可，psycopg3 支持自动适配。
> 传之前自己先校验"有限、非零、长度 = dimensions"，避免把 500 变成 422。

#### `review_records` — 人工核查（7 列）

| 列 | 类型 | 约束 |
|---|---|---|
| `review_id` | TEXT | **PK**, NOT NULL |
| `event_id` | TEXT | NOT NULL，**FK → events** |
| `query_json` | JSONB | NOT NULL，**必须是 object** |
| `decision` | TEXT | NOT NULL，∈ `confirmed` / `dismissed` / `uncertain` |
| `note` | TEXT | 可空 |
| `reviewer_id` | TEXT | NOT NULL，**FK → users** |
| `created_at` | TIMESTAMPTZ | NOT NULL |

> **只追加（append-only）**：任何 UPDATE 都被拒。人工决定不修改原事件的类型或位置。

#### `conversations` — AI 会话（3 列）

| 列 | 类型 | 约束 |
|---|---|---|
| `conversation_id` | TEXT | **PK**, NOT NULL |
| `user_id` | TEXT | NOT NULL，**FK → users** |
| `created_at` | TIMESTAMPTZ | NOT NULL |

> **不可变**：会话身份、归属、创建时间都不可 UPDATE。

#### `messages` — 会话消息（6 列）

| 列 | 类型 | 约束 |
|---|---|---|
| `message_id` | TEXT | **PK**, NOT NULL |
| `conversation_id` | TEXT | NOT NULL，**FK → conversations** |
| `role` | TEXT | NOT NULL，∈ `user` / `assistant` |
| `content` | TEXT | NOT NULL，非空串 |
| `run_id` | TEXT | 可空，**FK → runs** |
| `created_at` | TIMESTAMPTZ | NOT NULL |

> **关键约束 `ck_message_role_run`**：
> - `role='user'` → `run_id` **必须为 NULL**
> - `role='assistant'` → `run_id` **必须非 NULL**
>
> **`role='assistant'` 额外要求**：所引用的 run 必须是 `status='completed'` 且属于同一会话（触发器拦截）。
> **唯一约束**：每个 run 最多一条 assistant 消息。
> **只追加**：任何 UPDATE 都被拒。

#### `runs` — 分析运行（10 列）

| 列 | 类型 | 约束 |
|---|---|---|
| `run_id` | TEXT | **PK**, NOT NULL |
| `conversation_id` | TEXT | NOT NULL，**FK → conversations** |
| `input_message_id` | TEXT | NOT NULL，**FK → messages**（必须是同会话的 user 消息） |
| `status` | TEXT | NOT NULL，默认 `running`，∈ `running` / `completed` / `stopped` / `failed` |
| `plan_json` | JSONB | NOT NULL，**结构受严格校验**，见下 |
| `references_json` | JSONB | NOT NULL，默认 `[]`，**必须是 array** |
| `model_key` | TEXT | NOT NULL，非空串 |
| `error_json` | JSONB | 可空，非空时必须是 object |
| `created_at` | TIMESTAMPTZ | NOT NULL |
| `finished_at` | TIMESTAMPTZ | 可空，非空时 ≥ `created_at` |

**`plan_json` 必须满足的结构**（否则 CHECK 拒绝）：

```json
{
  "query": {
    "recording_id":   "rec_xxx",
    "camera_id":      "cam-01",
    "zone_id":        "zone-A",
    "start_offset_ms": 0,
    "end_offset_ms":   10000
  },
  "query_text": "查一下 zone-A 的进入事件",
  "steps": []
}
```

硬性要求：
- `query` 是 object，`query_text` 是非空 string，`steps` 是 array
- `query.recording_id` / `camera_id` / `zone_id` 都是**非空 string**
- `query.start_offset_ms` / `end_offset_ms` 是 number，且 `0 ≤ start < end`

**状态机约束 `ck_run_terminal`**：

| status | finished_at | error_json |
|---|---|---|
| `running` | 必须 NULL | 必须 NULL |
| `completed` / `stopped` | **必须非 NULL** | 必须 NULL |
| `failed` | **必须非 NULL** | **必须非 NULL** |

> **触发器**：插入时校验 `input_message_id` 是同会话 user 消息，且 `plan_json.query.recording_id` 对应的录像存在。
> **终态不可变**：`status` 一旦离开 `running`，整行不可再 UPDATE。
> **事实不可变**：`run_id` / `conversation_id` / `input_message_id` / `plan_json` / `model_key` / `created_at` 不可改（`status` / `finished_at` / `references_json` / `error_json` 可以改）。

**运行生命周期示例**：

```sql
-- 1) 创建运行
INSERT INTO runs (run_id, conversation_id, input_message_id, status,
                  plan_json, references_json, model_key, created_at)
VALUES ('run_1','conv_1','msg_user_1','running',
        '{"query":{...},"query_text":"...","steps":[]}'::jsonb, '[]'::jsonb,
        'mk-1', now());

-- 2) 完成（status 从 running 离开后整行冻结）
UPDATE runs SET status='completed', finished_at=now(), references_json='[...]'::jsonb
WHERE run_id='run_1' AND status='running';

-- 3) 同一事务内写入助手答复
INSERT INTO messages (message_id, conversation_id, role, content, run_id, created_at)
VALUES ('msg_asst_1','conv_1','assistant','命中 3 条事件','run_1', now());
```

#### `parcels` — 包裹（3 列）

| 列 | 类型 | 约束 |
|---|---|---|
| `parcel_id` | TEXT | **PK**, NOT NULL |
| `parcel_no` | TEXT | NOT NULL，**UNIQUE** |
| `created_at` | TIMESTAMPTZ | NOT NULL |

> `parcel_no` 是业务单号（二维码内容），**TEXT 类型保留前导零**，例如 `000123456789`。
> 不要转成数字，不要去掉前导零。
> **不可变**：包裹身份和创建时间不可 UPDATE。

#### `parcel_records` — 入出库流水（7 列）

| 列 | 类型 | 约束 |
|---|---|---|
| `record_id` | TEXT | **PK**, NOT NULL |
| `parcel_id` | TEXT | NOT NULL，**FK → parcels** |
| `sequence_no` | BIGINT | NOT NULL，**> 0**，同包裹内 UNIQUE |
| `action` | TEXT | NOT NULL，∈ `inbound` / `outbound` |
| `zone_id` | TEXT | 可空；**`action='inbound'` 时必填** |
| `operator_id` | TEXT | NOT NULL，**FK → users** |
| `created_at` | TIMESTAMPTZ | NOT NULL |

> 唯一约束：`UNIQUE (parcel_id, sequence_no)`
> **只追加**：任何 UPDATE 都被拒。
> 状态是**派生**的（按最后一条 `sequence_no`），`parcels` 表不存状态字段。

---

## 4. 数据库会自动拦截什么

### 4.1 触发器清单（25 个）

所有触发器抛出的异常 **ERRCODE 都是 `23514`**（`check_violation`），
你可以捕获 `23514` 并读 `e.diag.message_primary` 来区分具体业务规则。

**BEFORE INSERT（业务校验，4 个触发器、5 条拒绝条件）**

| 表 | 触发器 | 拒绝条件 | 报错信息 |
|---|---|---|---|
| `runs` | `run_input_and_scope` | 输入消息不是同会话 user 消息 | `run input must be a user message in the same conversation` |
| `runs` | `run_input_and_scope` | `query.recording_id` 不存在 | `run query must refer to an existing recording` |
| `messages` | `assistant_message_completed_run` | 引用的 run 不是 completed 或不同会话 | `assistant reply requires a completed run in the same conversation` |
| `events` | `event_offset_in_recording` | `end_offset_ms` 超录像时长 | `event offsets exceed recording duration` |
| `evidence` | `evidence_offset_in_recording` | `offset_ms` 超录像时长 | `evidence offset exceeds recording duration` |

**BEFORE UPDATE（不可变 / 只追加，11 个）**

| 表 | 触发器 | 报错信息 |
|---|---|---|
| `recordings` | `recording_facts_immutable` | `recording facts are immutable; create a new recording` |
| `evidence` | `evidence_location_immutable` | `evidence location is immutable` |
| `runs` | `run_facts_immutable` | `run input, confirmed plan and model configuration are immutable` |
| `events` | `event_facts_immutable` | `event facts are immutable` |
| `review_records` | `review_append_only` | `review records are append-only` |
| `event_embeddings` | `embedding_facts_immutable` | `embeddings are immutable; reuse an existing row or create a new embedding` |
| `conversations` | `conversation_ownership_immutable` | `conversation identity, ownership and creation time are immutable` |
| `messages` | `message_append_only` | `messages are append-only` |
| `runs` | `run_terminal_immutable` | `terminal runs are immutable`（仅当 OLD.status ≠ running） |
| `parcels` | `parcel_facts_immutable` | `parcel identity and creation time are immutable` |
| `parcel_records` | `parcel_record_append_only` | `parcel records are append-only` |

**BEFORE DELETE（历史保留，10 个）**

`recordings` / `events` / `evidence` / `review_records` / `event_embeddings` /
`conversations` / `messages` / `runs` / `parcels` / `parcel_records`
—— **全部禁止 DELETE**，报错形如 `keep recording history`。

> ⚠️ **这意味着没有"删除"接口，也没有"改错重来"。** 写错了只能新建一条记录（换 ID）。
> 业务代码里不要写 `DELETE`，也不要写"覆盖更新"的逻辑。

### 4.2 唯一索引（幂等去重的依据）

| 索引 | 表 | 列 | 用途 |
|---|---|---|---|
| `uq_parcel_no` | `parcels` | `parcel_no` | 单号全局唯一 |
| `uq_parcel_record_sequence` | `parcel_records` | `parcel_id, sequence_no` | 流水序号同包裹唯一 |
| `uq_evidence_event` | `evidence` | `evidence_id, event_id` | 复合外键目标 |
| `uq_messages_assistant_run` | `messages` | `run_id` WHERE `role='assistant'` | 每个 run 最多一条答复 |
| `uq_embeddings_event_text_source` | `event_embeddings` | `event_id, model_name, model_version, content_sha256` WHERE `evidence_id IS NULL` | 无帧描述去重 |
| `uq_embeddings_evidence_source` | `event_embeddings` | `event_id, evidence_id, model_name, model_version, content_sha256` WHERE `evidence_id IS NOT NULL` | 带帧描述去重 |

---

## 5. 幂等实现要点

设计上**不用额外的幂等键表**，而是用"客户端提供的稳定 ID + 比较规范化请求内容"来实现：

| 接口 | 幂等键 | 重复请求处理 |
|---|---|---|
| `POST /api/v1/recordings` | `recording_id` | 同 ID 同内容 → 200 返回原结果；同 ID 异内容 → **409** `idempotency_conflict`；首次 → 201 |
| `POST /api/v1/event-embeddings` | `embedding_id` | 比较**全部规范请求**（含规范化后的 float32 向量、模型、维度、引用）；不同 ID 但同源同摘要 → **409** `duplicate_embedding_source` |
| `POST /api/v1/review-records` | `review_id` | 同 ID 同内容（排除服务端字段）→ 200；异内容 → 409 |
| `POST /api/v1/parcels` | `parcel_id` | 同 ID 重试 → 200，复用原生成单号；同 ID 指定不同单号 → 409 `idempotency_conflict`；另一 ID 占用同单号 → 409 `parcel_no_conflict` |
| `POST /api/v1/parcels/{parcel_id}/records` | `record_id` | 同 ID 同内容同操作人 → 200 返回**原序号/原时间/原货架**；异内容 → 409 |
| Agent 会话 / 消息 / 运行 | 各自的稳定 ID | 同 ID 同内容 → 200 回读原资源 |

> **重要**：向量幂等**不能只比 `content_sha256`** —— 摘要里不含向量和模型。
> 必须比较规范化后的完整请求（float32 归一化后的向量 + 模型名 + 版本 + 维度 + 引用）。

---

## 6. 事务与并发

### 6.1 录像导入（`POST /api/v1/recordings`）

整包必须**单事务**写入：`recordings` + 全部 `events` + 全部 `evidence`。
失败整体回滚，**绝不能留下"半包"**（录像存在但事件缺失）。

```python
with pool.connection() as conn:
    with conn.transaction():        # 任何异常自动回滚
        cur.execute("INSERT INTO recordings (...) VALUES (...)")
        cur.executemany("INSERT INTO events (...) VALUES (...)", event_rows)
        cur.executemany("INSERT INTO evidence (...) VALUES (...)", evidence_rows)
    # 媒体文件与数据库发布失败要补偿清理（媒体不入库，但 storage_key 指向的文件要删）
```

### 6.2 消息与运行的循环外键

必须按 3.1 的顺序，且**助手答复与运行终态在同一事务提交**：

```python
with pool.connection() as conn:
    with conn.transaction():
        cur.execute("UPDATE runs SET status='completed', finished_at=now() WHERE run_id=%s AND status='running'", (run_id,))
        if cur.rowcount == 0:
            raise ConflictError("run is not running")   # 幂等/并发保护
        cur.execute("INSERT INTO messages (...) VALUES (..., 'assistant', ..., %s, now())", (run_id,))
```

### 6.3 包裹入出库（必须加锁）

序号分配必须串行化，否则并发会撞 `uq_parcel_record_sequence`：

```python
with pool.connection() as conn:
    with conn.transaction():
        # 1) 锁包裹，串行化同包裹的并发操作
        cur.execute("SELECT parcel_id FROM parcels WHERE parcel_id=%s FOR UPDATE", (parcel_id,))
        if cur.fetchone() is None:
            raise NotFound("parcel_not_found")

        # 2) 先查重试（重试优先于状态校验）
        cur.execute("SELECT * FROM parcel_records WHERE record_id=%s", (record_id,))
        row = cur.fetchone()
        if row:
            return row                      # 200 返回原记录

        # 3) 校验状态：首次必须 inbound；in_stock 不能再 inbound；outbound 不能再 outbound
        cur.execute("""SELECT action, zone_id, sequence_no FROM parcel_records
                       WHERE parcel_id=%s ORDER BY sequence_no DESC LIMIT 1""", (parcel_id,))
        last = cur.fetchone()
        ...

        # 4) 分配序号并追加
        next_seq = (last["sequence_no"] + 1) if last else 1
        cur.execute("INSERT INTO parcel_records (...) VALUES (..., %s, ...)", (next_seq,))
```

`status` / `current_zone_id` 从流水**派生**，不要写回 `parcels`：

| 最后一条流水 | `status` | `current_zone_id` |
|---|---|---|
| 无流水 | `pending` | `null` |
| `inbound` | `in_stock` | 该次入库的 `zone_id` |
| `outbound` | `outbound` | `null` |

### 6.4 隔离级别

用默认的 **READ COMMITTED** 即可（配合上面的 `FOR UPDATE`）。
不需要 SERIALIZABLE —— 会引入不必要的重试。

---

## 7. 数据库错误 → HTTP 状态码映射

**这是后端最容易写错的地方**，建议统一在一个异常处理器里做。

| PG SQLSTATE | 含义 | 建议 HTTP | 典型场景 |
|---|---|---|---|
| `23505` | `unique_violation` | **409** | 幂等冲突、单号重复、序号重复、答复重复 |
| `23514` | `check_violation` | **422** | CHECK 约束 + **全部 25 个触发器** |
| `23503` | `foreign_key_violation` | **422** | 引用不存在的录像/事件/用户 |
| `23502` | `not_null_violation` | **422** | 必填字段缺失 |
| `22001` | `string_data_right_truncation` | 422 | 超长 |
| `22P02` / `22007` | 类型/时间格式错误 | 422 | JSONB、TIMESTAMPTZ 解析失败 |
| `40001` | `serialization_failure` | **503**（可重试） | 并发冲突 |
| `40P01` | `deadlock_detected` | **503**（可重试） | 死锁 |
| `42501` | `insufficient_privilege` | **403** | 权限不足 |
| `28P01` / `28P02` | 认证失败 | **500** | 令牌过期（应重连，不该暴露给客户端） |
| `53300` | `too_many_connections` | **503** | 连接池耗尽 |

`23514` 的细分建议：读 `e.diag.message_primary` 判断是"业务规则被违反"还是"字段格式错"。
触发器消息是英文短句（见 4.1），可以按关键字映射成更友好的业务错误码。

统一错误响应格式（按 API 设计）：

```json
{
  "error": { "code": "idempotency_conflict", "message": "...", "request_id": "..." },
  "api_version": "0.3-mvp-draft"
}
```

---

## 8. 开发环境注意事项

| 项 | 说明 |
|---|---|
| Python | EC2 上是 **3.12.3** |
| `pip` | **未安装**！需先 `sudo apt install -y python3-pip python3-venv` |
| `boto3` | ✅ 已预装（但服务实现未使用：`backend/storage/credentials.py` 用标准库完成 SigV4 签名，镜像哈希锁定依赖集不变） |
| `psycopg` / `psycopg_pool` / `fastapi` / `uvicorn` | ❌ 未装，需在 venv 里装 |
| `pg_dump` | 客户端 16.15 < 服务端 17.9，**不能用来导出**（版本不匹配）。要用系统目录查询 |
| 时区 | 所有连接执行 `SET TIME ZONE 'UTC'`；SQL 一律用 `TIMESTAMPTZ` |
| JSON | 一律用 `JSONB`，传 Python dict/list 即可 |
| 向量 | `VECTOR` 列直接传 `list[float]` |

---

## 9. 快速自检

连上之后跑这几条确认环境正常：

```sql
-- 1) 库与版本
select current_database(), current_user, version();

-- 2) 表数量应为 11
select count(*) from information_schema.tables
where table_schema='public' and table_type='BASE TABLE';

-- 3) 向量扩展
select extname, extversion from pg_extension where extname='vector';

-- 4) 触发器应为 25
select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid
join pg_namespace n on n.oid=c.relnamespace
where n.nspname='public' and not t.tgisinternal;

-- 5) 令牌是否被识别（应返回你当前的 IP 段）
select inet_client_addr();
```

**当前库是空的**（11 张表全部 0 行），可以直接开始写入。

---

## 10. 参考

| 资料 | 位置 |
|---|---|
| 数据库设计说明 | [README.md](README.md) |
| 表结构 DDL 源文件 | [schema.sql](schema.sql) |
| 表结构目录（JSON） | [schema-catalog.json](schema-catalog.json) |
| 数据库关系图 | [schema-canvas.html](schema-canvas.html) |
| 包裹与入出库扩展设计 | [PARCEL_DESIGN.md](PARCEL_DESIGN.md) |
| 语义检索 SQL 参考 | [semantic-search.sql](semantic-search.sql) |
| API 设计说明 | [API_DESIGN.md](../api/API_DESIGN.md) |
| OpenAPI 3.1 规范 | [openapi.json](../api/openapi.json) |
| 请求/响应样例 | [examples/v0.3/](../api/examples/v0.3/) |
| 开发计划与验收 | [DEVELOPMENT_PLAN.md](../DEVELOPMENT_PLAN.md)（D05 即本模块） |
