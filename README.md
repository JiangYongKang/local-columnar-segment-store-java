# Local Columnar Segment Store (Java / Spring Boot)

一套完全基于**本地文件系统**的列式存储与查询服务。数据以**不可变段（immutable segment）**落盘，
通过**原子快照指针（CURRENT）**对外发布；支持并发写入、快照隔离查询、只增不改的结构演进、
后台合并（compaction）以及合并/写入中断后的确定性恢复。

* 无外部服务、无真实凭据：仅依赖 Spring Boot Web / Jackson / JUnit，数据全部在本地磁盘。
* Java 21 字节码（可由 JDK 21+ 构建），Maven 构建。

---

## 1. 快速开始

```bash
mvn spring-boot:run          # 启动 HTTP 服务（默认随机端口由 server.port=0 决定）
mvn test                     # 运行全部 31 个测试（含并发/崩溃恢复重复用例）
```

### 1.1 REST 速览

| 方法 | 路径 | 说明 |
|---|---|---|
| PUT  | `/api/tables/{name}` | 建表（字段、主键、分区列与边界） |
| GET  | `/api/tables` | 列出所有表 |
| GET  | `/api/tables/{name}/snapshot` | 当前可见快照（版本/段数/行数）+ 当前 schema |
| POST | `/api/tables/{name}/rows` | 批量写入，逐条返回拒绝原因 |
| GET  | `/api/tables/{name}/rows` | 查询，参数 `low/high/eq/columns/schemaVersion` |
| POST | `/api/tables/{name}/columns` | **唯一允许的演进**：新增可空/带默认值的列 |
| POST | `/api/tables/{name}/columns/drop` | 删列 —— 明确拒绝（422） |
| POST | `/api/tables/{name}/columns/rename` | 改名 —— 明确拒绝（422） |
| POST | `/api/tables/{name}/columns/type` | 改类型/收窄 —— 明确拒绝（422） |
| POST | `/api/tables/{name}/compact` | 同步执行一次合并 |

建表与写入示例：

```bash
curl -s -X PUT localhost:${PORT}/api/tables/events \
  -H 'Content-Type: application/json' \
  -d '{"tableName":"events",
       "fields":[
         {"name":"id","type":"LONG","nullable":false,"key":true},
         {"name":"day","type":"LONG","nullable":false,"partition":true},
         {"name":"label","type":"STRING","nullable":true}],
       "partitionColumn":"day","lowerBound":0,"upperBound":5}'

curl -s -X POST localhost:${PORT}/api/tables/events/rows \
  -H 'Content-Type: application/json' \
  -d '{"rows":[{"id":1,"day":0,"label":"a"},
               {"id":"x","day":1,"label":"bad-type"},
               {"id":9,"day":9,"label":"bad-partition"}]}'
# accepted=1，rejected 中逐条给出 TYPE_MISMATCH / PARTITION_VIOLATION 与判定明细
```

---

## 2. 落盘布局

```
${store.data-dir}/
└── <tableName>/
    ├── meta/
    │   ├── schema-v1.json            每个 schema 版本永久保留（演进只追加）
    │   ├── schema-v2.json
    │   ├── manifest-v0.json          每个已发布快照（保留最近 N 个，见配置）
    │   ├── manifest-v1.json
    │   ├── CURRENT                   单行文本：当前可见清单文件名（原子替换）
    │   └── CURRENT.tmp               发布过程中的临时文件（崩溃残留会被忽略/覆盖）
    └── segments/
        ├── seg-1/                    不可变段目录
        │   ├── meta.json             SegmentMeta（schema 版本、列类型、行数、分区 min/max）
        │   ├── col-id.bin            每列一个二进制文件
        │   ├── col-day.bin
        │   └── col-label.bin
        ├── seg-2/
        └── seg-3.tmp/                写入/合并中途的临时段（崩溃后由恢复流程删除）
```

### 2.1 列文件二进制格式（自定义，小端序）

```
magic   4B   "LCSC"
type    1B   0=LONG 1=DOUBLE 2=STRING 3=BOOLEAN
count   4B   行数 N
nullMap ⌈N/8⌉B 位图，第 i 位置 1 表示该行该列为 NULL
values      仅非空值连续排列：
             LONG/DOUBLE 各 8B（DOUBLE 存 doubleToLongBits）
             BOOLEAN 1B；STRING 为 4B 长度 + UTF-8 字节
```

所有列文件先写到 `*.tmp` 再**原子 rename**；段目录先建成 `seg-<id>.tmp/`，
全部列与 `meta.json` 完成后再**原子 rename** 为 `seg-<id>/`。因此任何已发布段在磁盘上都是完整的。

### 2.2 清单（Manifest）

`manifest-vN.json` 记录：快照版本号、schema 版本、本段集合（含 segmentId/行数/分区 min/max）、
总行数、操作类型（`INIT|WRITE|SCHEMA|COMPACT`）与时间戳。
`CURRENT` 是一个单行指针文件，**查询与恢复都只通过它解析"当前快照"**。

---

## 3. 快照语义（为什么读到的永远是完整状态）

一次提交（写入/schema 演进/合并）是一次**两步原子切换**：

1. 新数据以不可变文件落盘（新段目录 / 新 `manifest-vN.json`），过程对读者不可见；
2. 用 `CURRENT.tmp` → `CURRENT` 的**原子 rename** 切换指针；只有这一动作让新快照可见。

因此读者只会看到「上一个完整快照」或「下一个完整快照」，永远看不到写入中途的中间状态。

* **查询**：打开读锁后一次性读取 `currentManifest` 引用（不可变对象），随后只读取其中引用的不可变段文件。
* **写入/演进/合并**：共用同把排他提交锁，串行发布新版本；版本号单调递增。
* **文件删除安全**：合并删除旧段时仍持有写锁，与查询的读锁互斥，正在扫描的读者不会遇到文件被删。
* **并发写冲突显式暴露**：两批写入竞争同一主键时，先拿到提交锁的批次落盘；
  后者的相关记录以 `DUPLICATE_KEY` 拒绝返回，**绝不静默覆盖**。

### 3.1 记录拒绝原因（不静默丢弃）

批量写入对**每条记录**独立校验并给出可区分的原因：

| RejectReason | 触发条件 |
|---|---|
| `MISSING_FIELD` | 必填字段缺失 / 显式 null 且无默认值 |
| `TYPE_MISMATCH` | 值类型与声明不符（LONG 与 DOUBLE 不做隐式互转）；出现 schema 中不存在的未知列 |
| `PARTITION_VIOLATION` | 分区列为空，或落在 `[lowerBound, upperBound]` 之外 |
| `DUPLICATE_KEY` | 同批内主键重复，或主键已存在于已提交快照 |

合法记录照常提交，非法记录逐条进入响应的 `rejected`（含批次内下标、原始内容、原因、判定明细）。
整批都非法时不产生新快照，响应中的 `snapshotVersion` 为校验时所基于的版本。

---

## 4. 结构演进与兼容规则

演进**只允许追加**，所有 schema 版本永久保存（`schema-vN.json`）：

1. **新增列**：必须满足 `nullable=true` **或**提供类型匹配的 `defaultValue`。
   该默认值就是所有历史行对该列的**长期稳定**取值；查询旧段缺列时用默认值/NULL 补全。
2. **删列 / 改名 / 改类型（含收窄与放宽）/ 改动主键或分区设置**：一律抛出
   `INCOMPATIBLE_SCHEMA`（HTTP 422），消息中给出具体判定依据，schema 版本不前进。
3. **历史查询可复现**：查询可传 `schemaVersion` 固定投影到任一历史 schema；
   列身份即列名，旧段文件头还冻结了写入时的列类型。

---

## 5. 合并（Compaction）与中断恢复

### 5.1 合并

`compact()` 在排他锁内：把当前快照的所有段按「段 id / 写入顺序」流式读入一个新段
（行的逻辑多重集逐字节不变），随后原子发布**只含新段**的新清单，再删除旧段目录。
显式调用时段数 ≥ 2 即合并（单段已收敛则 no-op）；配置阈值控制后台合并的积极程度。

### 5.2 崩溃恢复（打开表时自动执行）

`StoreCatalog` 启动时对每个表执行恢复，产出**唯一一致快照**：

1. 加载最高 schema 版本；
2. 在所有**已持久化且段目录完整**的清单中选取**最高版本**作为唯一一致快照：
   * 若 `CURRENT` 缺失/落后（崩溃发生在「清单已 fsync、指针未切换」之后），
     恢复会把指针**提升**到该版本——已持久化的事务被提交而不是回滚；
   * 若崩溃发生在清单落盘之前，则最高完整清单仍是旧快照，新数据不被可见；
3. 删除所有 `seg-*.tmp` 临时段，以及不被恢复快照引用的孤儿正式段；
4. 重建内存主键索引与段 id 分配器，保证崩溃后重提同一主键不会产生幻影冲突。

崩溃由测试中的 `CrashHooks`（`beforeManifestPublish` / `afterManifestPersisted`）
在两个发布边界精确注入 `JvmCrashError` 模拟 `kill -9`，随后**重新从磁盘打开**表验证。

---

## 6. 可配置项（`application.properties`，前缀 `store.`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `store.data-dir` | `${user.dir}/data` | 本地数据目录（不存在会自动创建） |
| `store.compact-segment-threshold` | `4` | 后台合并触发的段数阈值（显式 compact 不受此限） |
| `store.manifest-history-keep` | `3` | 每个表保留的历史清单文件数（schema 历史全保留） |
| `server.port` | `0` | HTTP 端口，0 表示随机端口 |

---

## 7. 验证方法

```bash
mvn test                      # 全部用例，秒级完成、可重复本地运行
mvn test -Dtest=CompactionRecoveryTest     # 仅崩溃恢复
mvn test -Dtest=ConcurrentWriteCompactTest # 并发写+合并（含 @RepeatedTest 重复）
mvn test -Dtest=SchemaEvolutionTest        # 不兼容变更被拒绝 / 默认值稳定性
mvn test -Dtest=PartitionBoundaryTest      # 分区边界（含跨段/合并/演进前后）
mvn test -Dtest=RestApiTest                # 真实 HTTP 端到端
```

| 测试类 | 覆盖场景 |
|---|---|
| `WriteQueryTest` | 提交可见、默认值、四类拒绝原因、提交后重复键冲突不覆盖、无主键建表被拒 |
| `SchemaEvolutionTest` | 加列默认值对历史行稳定、可空列、无默认值非空列被拒、默认值类型不符被拒、删/改/改名/改类型全被拒、按历史 schema 复现查询 |
| `CompactionRecoveryTest` | 合并收敛段数且行不变；两个崩溃注入点后重开得到唯一一致快照；写入早/晚崩溃分别回滚/提交；恢复幂等 |
| `ConcurrentWriteCompactTest` | 6 写线程 + 后台压缩 + 后台读线程交错：行数守恒、键唯一恰好一次、读者只见一致快照；同键竞争全部得到 `DUPLICATE_KEY` |
| `PartitionBoundaryTest` | 边界值闭区间、跨段与合并后一致、演进后边界行、非法区间与无分区表报错 |
| `RestApiTest` | 建表冲突 409、逐条拒绝、边界查询、演进 422、合并、404 |

所有单测日志均打印**输入记录、发布的快照版本（SNAPSHOT=vN）与判定依据（BASIS=...）**，
便于解释每个结果；并发与恢复用例使用 `@RepeatedTest`/独立临时目录，保证本地可重复运行。

---

## 8. 关键设计边界

* 单 JVM、单写者串行模型：并发安全由进程内读写锁保证；目录不支持多个进程同时写同一数据目录。
* 分区仅支持单列 `LONG` 与闭区间/等值谓词；其它过滤可在返回结果上进行。
* 主键（可复合）用于冲突检测，要求写入时提供（可空列不能作为主键）。
