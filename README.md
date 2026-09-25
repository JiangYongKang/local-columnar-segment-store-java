# local-columnar-segment-store-java

基于本地文件系统的列式分段存储与查询服务（Spring Boot + Java 21，仅依赖常规开源库，无外部服务/凭据）。
写入以不可变段（segment）落盘，查询只能看到完整提交的快照；后台合并在保证行级结果不变的前提下收敛文件数量；进程中断后重启可恢复出唯一一致的快照。

## 构建与运行

```bash
mvn compile          # 编译
mvn test             # 运行全部测试（关键用例可本地重复运行）
mvn spring-boot:run  # 启动 REST 服务（默认随机端口，见 application.properties）
```

## 落盘布局与快照语义

每张表一个目录（`<store.root-dir>/<table>/`）：

```
<table>/
  CURRENT                  # 指针文件：当前已提交快照的版本号
  manifest-<v>.json        # 快照 v 的清单：schema（含列缺省值）+ 可见段列表
  seg-<uuid>.col           # 不可变列式段文件
  *.tmp                    # 写入中途的临时文件（恢复时清理）
```

段文件为列式布局：文件头（magic `LCOL`、格式版本、写入时 schema 版本、分区、行数、列定义）后按列主序存放各列值块（每值带 null 标记）。段文件先写 `seg-<uuid>.col.tmp`，再原子改名（`ATOMIC_MOVE`）为正式名，因此读者永远不会看到写了一半的段。

**快照语义**：

- 一次提交 = 写 `manifest-<v>.json.tmp` → 原子改名为 `manifest-<v>.json` → 写 `CURRENT.tmp` → 原子改名为 `CURRENT`。
- 查询只读取 `CURRENT` 指向的、已完整提交的清单；写入/合并中途产生的 tmp 文件与未被引用的段对查询不可见。
- `query(partition, version)` 可按版本钉住快照重放，结论可复现（结构演进不删除旧段，故演进前后的钉版查询结果一致；合并会回收被替换的旧段，只保证最新快照行级结果不变）。
- 并发提交以"期望版本号"做乐观并发控制：提交时若 `CURRENT` 已不等于期望值，抛出 `ConcurrentCommitException` 并自动重试（重校验后重提交），重试耗尽后异常显式上抛，绝不静默覆盖。

## 写入与拒绝语义

`write(partition, records)` 对每条记录依次校验，被拒绝的记录**不会静默丢弃**，而是在 `WriteResult.rejected` 中返回可区分的原因：

| 原因 | 含义 |
|---|---|
| `MISSING_FIELD` | 缺少必填字段且无列缺省值 |
| `TYPE_MISMATCH` | 字段类型与列定义不匹配（含 INT 溢出） |
| `PARTITION_VIOLATION` | 分区列的值与目标分区不一致 |
| `KEY_CONFLICT` | 主键与已提交数据或同批次记录冲突 |

被接受的记录作为一个新不可变段随新快照版本一起提交；全部拒绝时不产生新版本。

## 结构兼容规则

- **允许**：`ADD COLUMN`，必须提供与列类型匹配的非空缺省值。历史段中没有该列，读取时按缺省值补齐，因此演进前后的历史查询结论可复现。
- **拒绝**（抛 `IncompatibleSchemaChangeException`，REST 返回 409 `INCOMPATIBLE_SCHEMA_CHANGE`）：`DROP COLUMN`、`RENAME COLUMN`、任何类型变更（收窄或 widening）、缺省值为空或类型不匹配的加列。

## 合并与恢复策略

- **合并**（`compact(partition)` / `Compactor`）：读取当前快照中该分区的全部段，合并为一个新段（行集完全不变），提交新快照后删除被替换的旧段文件，使分区收敛为单段。与写入并发时通过期望版本号检测冲突并重试；读（查询/键校验）与"删段"在同一把按表目录的锁下互斥，不会出现丢失、重复或部分可见。
- **恢复**（打开表时自动执行 `recover()`）：`CURRENT` 是唯一权威指针。恢复会删除所有 `*.tmp` 残骸、版本号大于 `CURRENT` 的未提交清单、以及未被当前快照引用的孤儿段（如合并中途写出的合并段）。崩溃无论发生在两次原子改名之间的哪一点，重启后都恰好恢复到一个一致快照，合并可安全重跑。

## 可配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `store.root-dir` | `./data` | 所有表数据的本地根目录 |
| `server.port` | `0`（随机） | REST 服务端口 |

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/tables/{table}` | 建表：`{columns:[{name,type}], keyColumn, partitionColumn}` |
| POST | `/tables/{table}/write` | 写入：`{partition, records:[{...}]}`，返回 `WriteResult` |
| GET | `/tables/{table}/query?partition=P[&version=V]` | 查询最新或指定快照 |
| POST | `/tables/{table}/schema/columns` | 加列：`{name, type, defaultValue}` |
| POST | `/tables/{table}/compact?partition=P` | 合并分区 |

错误：不兼容结构变更与并发提交冲突返回 409（分别为 `INCOMPATIBLE_SCHEMA_CHANGE` / `CONCURRENT_COMMIT`）。

## 验证方法

`mvn test` 覆盖关键场景，单测日志打印输入、快照版本与判定依据：

- `WriteQueryTest`：提交快照可查询/可复现；四类拒绝原因可区分；批内主键冲突。
- `SchemaEvolutionTest`：加列缺省值对历史数据稳定可读、钉版查询可复现；删列/改名/改类型被拒绝。
- `ConcurrentWriteCompactTest`：4 写线程 × 100 行与后台合并并发，400 行无丢失无重复，合并后收敛为单段。
- `CrashRecoveryTest`：合并中途崩溃（孤儿段 + 未生效清单 + tmp 残骸）后恢复出唯一一致快照并可安全重跑合并；并发提交冲突显式上抛。
- `PartitionBoundaryTest`：分区边界（月末/月初相邻日）查询不串区、越界记录被拒、单区合并不影响他区。
- `RestApiTest`：REST 端到端冒烟（建表→写入→查询→加列→合并→查询）。
