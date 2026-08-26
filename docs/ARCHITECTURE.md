# 架构说明

## 模块地图（app/src/main/java/com/halo/expressassistant/）

| 模块 | 职责 |
|---|---|
| `data/` | 数据模型与持久化：`ExpressItem`（含 `source` 渠道、`accountId/accountLabel` 归属、`pickupCode` 取件码）、`ExpressDetail`、`JdGoods`（含 `shortName` 短名）、**`BoundAccount`（多源绑定账号：id/label/enabled/payload）**、`Store`（SharedPreferences 封装，含四平台账号列表）、分区/进度工具 |
| `api/` | 小米接口（`XiaomiApi` 加密请求、`XiaomiSync` 列表、`XiaomiDetail` 详情、`XiaomiPassport` 登录）、**`TbOrders`（淘宝订单列表 + SSR 物流详情 + mtop wapSign）**、`KuaiDi100` 兜底、`EtaParser` |
| `ai/` | `AiClient`（DeepSeek 聊天 + 进度/ETA 计算）、`Markdown`（Markwon 渲染） |
| `ui/` | 全部页面与 UI 基建：Main/Detail/Chat/Settings、**`SyncEngine`（四源融合同步 + 短名后台优化）**、**`JdListFetcher`（京东订单列表，WebView+JS 捕获）**、**`JdTrackFetcher`（京东物流页轨迹）**、**`JdOrderResolver`（京东商品溯源）**、**`CaiNiaoGoodsResolver`（菜鸟轨迹+商品，纯 OkHttp）**、**`PddCapture`（拼多多隐藏 WebView 公共设施）**、**`PddListFetcher`（拼多多包裹列表）**、**`PddTraceFetcher`（拼多多轨迹+商品）**、**`GoodsPresentation`（取件码解析 + 短名 AI 优化）**、`JdLoginActivity`/`TbLoginActivity`/`PddLoginActivity`（京东/淘宝/拼多多 H5 登录）、`JdTraceActivity`（逆向实验工具）、`Themes`、`Paper`、`Sheets`、`EdgeToEdge` |
| `widget/` | 桌面小组件 `ExpressWidgetProvider` |
| `service/` | Shizuku shell 客户端、无障碍导入服务、`LocalApiServer`（CLI/MCP） |
| 根目录 | `DailyReporter`（生成+通知）、`ReportScheduler`/`ReportReceiver`/`BootReceiver`、`TrackingWorker`/`TrackingNotifier`、`ApiServer` 启动 |

## 数据模型

`ExpressItem` 关键字段：

| 字段 | 说明 |
|---|---|
| `mailNo` | 运单号，也是列表合并主键（京东渠道 = 京东订单号，与小米同约定） |
| `source` | 数据来源渠道：`xiaomi` / `jd` / `taobao` / `pdd`（决定详情轨迹路由与商品溯源渠道） |
| `accountId/accountLabel` | 多源绑定归属（`BoundAccount.id` / 展示名）；详情页头部「来自绑定」 |
| `pickupCode` | 聚合取件码（从轨迹文本正则解析，跨同步保留） |
| `companyCode/companyName` | 快递公司（淘宝渠道来自承运商名） |
| `state/stateNum/stateName` | 状态；`stateNum` 沿用小米状态码语义（101 未发货、103 已揽收、104 中转、**105 派送**、**106/107 签收**、108–111 异常）。注意 106 在 adapter bucket 里算“完成” |
| `latestText/latestTime` | 最新轨迹与时间 |
| `eta/aiEta` | 平台预计送达 / AI 预计送达 |
| `aiProgress/aiProgressAt` | AI 运输进度及其计算时的轨迹时间 |
| `tracked/notifiedText/notifiedTime` | 跟踪通知 |
| `stateOverride/partitionOverride` | 用户手动改名分区覆盖 |
| `jumpLinks` | 小米列表返回的电商深链 JSON（“查看来源”用） |
| `queryChannel` | 渠道专用：京东=物流页完整 URL；淘宝=淘宝订单号（SSR 详情用）；拼多多=拼多多订单号（order_detail 用） |

`JdGoods`（商品溯源缓存，`Store.jdGoods` 按 mailNo）：`name`（原名）、`imageUrl`、`count`、`shortName`（AI 优化短名）。

分区逻辑在 `data/Express.kt` 的 `sectionKeyOf()`：`delivering / shipped / notshipped / done / abnormal`。

## 数据流

### 同步（四源融合 · 多账号）
```
设置/下拉刷新 → SyncEngine.sync()（Mutex 串行；同平台内逐账号、跨平台并行）
  ├─ 小米已登录账号们：XiaomiSync.syncAccount()（优先级最高，同 mailNo 覆盖他源）
  ├─ 京东已登录账号们：JdListFetcher.fetchWith(account)（隐藏 WebView + JS JSONP 捕获 + 滑动翻页）
  │    → ExpressItem(source=jd, accountId/Label) + 商品预缓存（保留旧 shortName）
  ├─ 淘宝已登录账号们：TbOrders.fetchBoughtListWith(cookies)（mtop 订单列表）
  │    → 过滤交易关闭/退款 → 逐单 SSR 物流详情（并发 4）→ ExpressItem(source=taobao) + 商品预缓存
  └─ 拼多多已登录账号们：PddListFetcher.fetchWith(account)（隐藏 WebView + PddCapture.HOOK_JS + 滚动翻页）
       → ExpressItem(source=pdd) + 商品预缓存（候选键容错解析）
  → mailNo 去重合并（小米 > 京东 > 淘宝 > 拼多多；同平台先绑定优先）→ 保留用户覆盖字段（含 pickupCode）
  → Store.saveItems() → 刷新 UI + 小组件
  → （后台，不阻塞）GoodsPresentation.batchShorten 批量优化卡片短名 → reload
```

### 详情（按 source 路由轨迹）
```
点卡片 → DetailActivity
  source=xiaomi → XiaomiDetail.fetch()（v2/query）；轨迹空且开快递100 → 兜底
  source=jd     → JdTrackFetcher（隐藏 WebView 加载 deal_wuliu 完整参数页 → DOM 解析"描述在上/时间在下"）
  source=taobao → TbOrders.fetchSsrTraces(bizOrderId)；失败 → CaiNiaoGoodsResolver.fetchTraces（queryalltrace）
  source=pdd    → PddTraceFetcher.fetch（隐藏 WebView 加载 order_detail.html?order_id= → JS 钩子捕获物流响应）
  → 渲染轨迹时间线；解析取件码写回 item
  → 商品条：缓存 JdGoods → 缩略图+名称+数量；无缓存且已登录 → 自动溯源（京东 JdOrderResolver / 菜鸟 CaiNiaoGoodsResolver / 拼多多 PddTraceFetcher.resolveGoods）
  → 若 AI 进度过期/无效 → AiClient.computeProgress() 计算并写回
```

### AI 对话 / 定时日报 / 小组件 / 本地接口
与四源改造前相同（读 `Store.items` 合并结果，渠道透明）；短名优化为新增的批量 AI 调用（每批 8 个、后台执行）。

## 线程模型

- 网络与加密请求都在 `Dispatchers.IO`，UI 在主线程
- 京东 WebView 类 Fetcher 用主线程 Handler 驱动（WebView 必须在主线程创建），结果经回调/挂起桥接
- `SyncEngine.sync` 用 Mutex 串行化；短名优化在同步返回后单独协程执行
- 日报生成在 `CoroutineScope(Dispatchers.Main)` 里调用 suspend AI
- 小组件渲染在 provider 主线程内同步完成（数据已本地化，不联网）
