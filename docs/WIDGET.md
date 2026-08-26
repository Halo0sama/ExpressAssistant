# 云雀 · 快递助手 — 桌面小组件（AppWidget）全事记

> 本文档覆盖小组件**全部事宜**：用户功能、实现原理、渲染引擎、刷新链路、主题联动、交互、设置、已知局限、
> 两轮 HyperOS 4.0 排障实录（含证据链）、验证工具箱、文件清单。
> 核心代码：`widget/ExpressWidgetProvider.kt`；配置：`res/xml/express_widget_info.xml`；布局：`res/layout/widget_express_*`（5 个）。
> 回归工具：`research/widget_bench.py`（注入任意件数 → 渲染 → 截图像素分析，见 §十二）。

---

## 一、总览

小组件是 App 的"桌面只读橱窗"：把本地 `Store.items()` 里的快递按状态过滤后，以**头像 + 状态 + 时间**三元素网格或**摘要卡片**呈现，点击可直达详情 / 添加 / 打开 App。

| 维度 | 取值 |
|---|---|
| Provider | `ExpressWidgetProvider : AppWidgetProvider` |
| 布局族 | 网格（list / list_lark）与摘要（small / small_lark），+ loading 初始布局 |
| 主题联动 | `theme=="lark"` 或 `colorScheme=="warm"` → lark（暖纸衬线）变体 |
| 数据源 | `Store.items()`（SharedPreferences JSON，本地无网络） |
| 刷新 | 系统 30min 周期 + 数据/设置变更即时 + 手动刷新按钮 + 打开 App 兜底 |
| 交互 | 每格点开详情、空位"+"添加、头部刷新/打开、小卡整卡打开 |
| 状态过滤 | 派送中/运输中/未发货三开关；**已完成/异常固定不显示** |
| 技术形态 | 纯 `RemoteViews`（无 RemoteViewsService/集合视图），**静态 4×8 网格按需裁剪** |
| 行高 | 4 行都是 `0dp + layout_weight=1`，可见行**等分**卡片剩余高度（§五.6） |
| 容量 | 中卡 3×8=24、大卡 4×8=32；**超出按最大网格钳制**，不再退化（§五.5） |
| 修复轮次 | ① 2026-08-20 HyperOS 4.0 桌面 action 截断（§十）② 同日第二轮：底部空白 / 末行裁字 / 鬼格子 / 溢出丢件 / 陈旧 GONE（§十一） |


---

## 二、用户可见功能清单

1. **三种形态自适应**（按桌面实际给到的 dp 尺寸）：
   - **大卡**（宽≥300dp 且高≥250dp）：最多 4 行均衡网格
   - **中卡**（宽≥300dp）：最多 3 行均衡网格（实测 348×171dp → 10 件时 5 列 × 2 行）
   - **小卡**（其余）：摘要卡片（"N 件在途 · M 派送中 · K 异常" + 第一件在途的"公司 · 预计 ETA"）
2. **格子内容**：公司名缩写头像（状态色块）+ 状态文字（状态色）+ 时间（ETA 取"X月X日"或轨迹时间取"MM-dd"）。
   **三行在任何件数下都完整显示**：行挤时自动换紧凑版布局（字号 10/8/7sp），不再裁字（§五.6）。
3. **动态数量**：件数变化 → 均衡网格自动重排（容量最小、行少优先、单行≤4 格、≤8 列），空位显示灰色"+"占位（点击直接打开添加弹窗）。
   件数超过网格容量（中卡 24 / 大卡 32）时**占满最大网格**并展示优先级最高的前 N 件。
4. **点击行为**：格子→快递详情页；"+"→添加；头部刷新按钮→四源同步（前台静默 / 后台拉起同步后自动回桌面）；头部打开按钮→主界面；小卡整卡→主界面。
5. **状态筛选**：设置页「小组件」卡片三开关（派送中/运输中/未发货，默认全开），已完成/异常固定不显示。
6. **主题联动**：温暖纸感（lark 主题或暖色配色）→ 暖米纸底 + 衬线字体变体；莫奈取色 → 普通白卡。
   夜间由 `values-night` 自动切深色（卡片 `#1C1713` / `#1C1E1E`），**RemoteViews 在桌面进程 inflate，跟的是桌面的深色模式**。

---

## 三、配置声明（`res/xml/express_widget_info.xml`）

```xml
<appwidget-provider
    android:minWidth="110dp" android:minHeight="110dp"
    android:minResizeWidth="110dp" android:minResizeHeight="110dp"
    android:targetCellWidth="4" android:targetCellHeight="2"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"          <!-- 30 分钟系统周期 -->
    android:widgetCategory="home_screen"
    android:initialLayout="@layout/widget_express_loading"
    android:previewImage="@drawable/widget_preview" />
```

- 预览图 `widget_preview.png`：368×800 RGBA 长条截图（添加小组件时显示）。
- 初始布局 `widget_express_loading`：云雀图标 + 应用名居中（添加后由首次 `render` 立即替换）。
- Manifest：`<receiver android:name=".widget.ExpressWidgetProvider" android:exported="false">`，intent-filter 仅系统 `APPWIDGET_UPDATE`（**2026-08-20 已删掉残留 action `com.halo.expressassistant.REFRESH`**——显式 PendingIntent 广播本就不需要 filter 匹配）。
- 系统在 `onUpdate`（周期/安装/重启）与 `onAppWidgetOptionsChanged`（拖拽缩放）时回调 Provider。

---

## 四、布局体系（7 个 layout + 17 个 drawable）

### 1. `widget_express_loading.xml`（28 行）
FrameLayout（`widget_card_bg`）居中：36dp 云雀图标 + 12sp 应用名。仅作 initialLayout。

### 2. `widget_express_list.xml`（1573 行）— 网格大卡（普通版）
垂直 LinearLayout：
- **头部（28dp）**：左侧应用名（13sp bold）＋ 右侧 `widget_refresh`（22dp 刷新）＋ `widget_open`（24dp 打开）。
- **4 行 × 8 列静态网格**：行 `widget_row_1..4`，每行 8 个 `widget_cell_N`（N=1..32，**行优先编号 = row*8+col+1**），列间 1dp 竖分隔线 `widget_v_{row}_{col}`（col=1..7，28 条），行间横分隔线 `widget_hdiv_1..3`。
- **行是 `layout_height="0dp"` + `layout_weight="1"` 且默认 `gone`**：可见的行等分卡片剩余高度，
  不可见的行不参与权重分配。这样内容天然铺满卡片，也不会堆在顶部（2026-08-20 第二轮修复，见 §十一）。
- 每个 cell（`layout_weight=1` 均分列宽，垂直居中）三件套：
  - `widget_cell_N_avatar`：TextView 做"头像"（公司名缩写），圆角色块背景，白色粗体（11sp，padding 4dp）
  - `widget_cell_N_state`：状态文字（9sp bold，状态色）
  - `widget_cell_N_time`：时间（8sp 次要色）
- **2026-08-20 起**：32 cell + 28 竖线 + 3 横线 + 4 行**全部默认 `android:visibility="gone"`**
  （渲染时 VISIBLE/GONE 都显式下发，见 §五.7 与 §十一）。

### 3. `widget_express_list_lark.xml`（1670 行）
与普通版**结构完全相同**，差异仅：
- 背景 `@drawable/widget_card_bg_lark`（暖米色 `#FAF8F3`，夜间 `#1C1713`）
- 标题/次要文字 → `widget_lark_title`（`#211B17`）/ `widget_lark_text_secondary`（`#554840`）
- 分隔线 → `widget_lark_card_stroke`（`#1F160F`）
- 全部 TextView 加 `serif` 衬线字体（97 处）

### 4. `widget_express_list_compact.xml` / `_lark_compact.xml`（**自动生成，勿手改**）
紧凑变体，给"每行放不下宽松版三行内容"的密网格用（3 行及以上，见 §五.6）。
与源布局**id 完全一致**（32 cell / 32 avatar / 32 state / 32 time），差异只有尺寸：

| 元素 | 宽松版 | 紧凑版 |
|---|---|---|
| cell padding | 1dp | 0dp |
| avatar | 11sp / padding 4dp | 10sp / padding 3dp |
| state | 9sp / marginTop 2dp | 8sp / marginTop 1dp |
| time | 8sp | 7sp |
| 全部 TextView | 默认字体行距 | `includeFontPadding="false"`（三行合计再省 ~7dp） |

一格总高：宽松版 ≈53dp → 紧凑版 ≈37dp（3 行网格每行只有 ~44dp）。
生成命令：`python3 research/gen_widget_compact.py`（幂等，改了源布局就重跑）。
**为什么用布局变体而不是 `setTextViewTextSize`**：动态字号是 RemoteViews 反射 action，
32 格 ×3 处 = 96 个额外 action，正是 §十 里 HyperOS 4.0 桌面敏感的东西；
换 layout id 是 0 action，还顺带强制桌面重新 inflate（不走 reapply，无陈旧状态）。

### 5. `widget_express_small.xml`（58 行）— 摘要小卡
FrameLayout（`widget_card_bg`，12dp 内边距）内垂直容器**贴底**：
- 首行：18dp 云雀图标 + 应用名（12sp bold）
- `widget_small_summary`：19sp bold，≤2 行摘要
- `widget_small_detail`：10sp 次要色，≤2 行明细
- 整卡 `widget_root` 可点击（打开 App）

### 6. `widget_express_small_lark.xml`
同 small + `widget_card_bg_lark` 背景 + lark 文字色 + 3 处 `serif`。

### 7. 配套 drawable（17 个）
| 文件 | 内容 |
|---|---|
| `widget_card_bg(.lark)` | 24dp 圆角矩形 + 1dp 描边（普通白 / lark 暖米） |
| `widget_avatar_{blue,green,grey,orange,red}` | 7dp 圆角色块（头像底色） |
| `widget_chip_{blue,green,grey,orange,red}` | 9dp 圆角 chip（状态色底，7/2dp 内边距） |
| `widget_divider_{h,v}` | 分隔线（h 实际未用） |
| `ic_widget_refresh` / `ic_widget_open` | 头部按钮矢量图（`widget_icon` 灰） |
| `widget_preview.png` | 添加预览图（368×800） |

---

## 五、渲染引擎（`ExpressWidgetProvider.renderOne`）

### 1. 尺寸读取与分档
```kotlin
val (width, height) = widgetSize(options)   // dp
```
- `widgetSize`：优先 `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT`；若为 0（新系统可能不填充），API 31+ 回退 `OPTION_APPWIDGET_SIZES` 取最大档；仍为 0 兜底 300×250（按大卡渲染，保证格子不缩水）。
- 分档：
```kotlin
val large  = width >= 300 && height >= 250   // 4 行网格
val medium = width >= 300                     // 3 行网格
// 其余 → small 摘要布局
```

### 2. 主题判定（选布局）
```kotlin
val lark = Store.theme(context) == Themes.LARK || Store.colorScheme(context) == "warm"
```
> ⚠️ 默认配置（theme=monet 未改 + 配色默认 warm）**默认就走 lark 变体**；只有设置里把配色改成"莫奈取色"才用普通版。lark 判定**不看**衬线开关，但 lark 布局本身硬编码 serif。

### 3. 数据过滤
```kotlin
items = Store.items(context).filter {
    when (sectionKeyOf(it)) {
        "delivering" -> Store.widgetShowDelivering(context)  // 派送中开关
        "shipped"    -> Store.widgetShowShipped(context)     // 运输中开关
        "notshipped" -> Store.widgetShowNotShipped(context)  // 未发货开关
        else -> false                                        // done/abnormal 固定不显示
    }
}
```
`sectionKeyOf`（`data/Express.kt`）：`stateNum==105/106 或 state==5 → delivering`；`stateNum∈106..107 或 state==3 → done`；`stateNum∈108..111 或 state==4 → abnormal`；`101/103 或 state==1 → notshipped`；其余 shipped。分区覆盖 `partitionOverride` 优先。

### 4. 排序
`orderOf`：delivering(0) > shipped(1) > notshipped(2) > abnormal(3) > 其余(4)。

### 5. 均衡网格（`gridFor(n, maxRows)`）
目标：容量最小、行少优先；约束：≤8 列、单行最多 4 格、行数 ≤ maxRows（large=4 / medium=3）。评分 `cap*1000 + rows` 取最小。
- 空列表 → `4×1`（一行 4 个"+"占位）。
- 示例：10 件 medium → 5×2（cap=10）；5 件 → 3×2（cap=6，1 个占位）；24 件 → 8×3。
- **溢出钳制**：件数超过最大容量（medium 24 / large 32）时所有候选都被"≤8 列"挡掉，
  此时返回 `8 × maxRows` 占满最大网格。**早期版本会掉回初值 `4×1`，26 件在途只显示 4 件**
  （静默丢件，实测踩过，见 §十一.3）。

### 6. 行高与紧凑档（决定"三行放不放得下"）
- 行高由**布局权重**决定：4 行都是 `0dp + layout_weight=1`，可见行等分卡片剩余高度。
  **不再 `setMinimumHeight`**（HyperOS flutter 桌面不支持该反射方法，且每格一个 setInt 会加剧 action 截断）。
- 渲染时按同一套算式算出每行实得多少 dp，用来选布局档：
```kotlin
val rowDp   = (height - HEADER_DP /*28*/ - PADDING_DP /*10*/ - (gridRows - 1)) / gridRows
val compact = rowDp <  ROW_DP_ROOMY    /* 56 */   // 换紧凑版布局（0 action 代价）
val showTime= rowDp >= ROW_DP_MIN_3LINE /* 34 */  // 连紧凑版都塞不下才藏时间行
```
- 348×171dp 中卡实测：

| 网格 | rowDp | 档位 | 结果 |
|---|---|---|---|
| 1 行（≤4 件） | 136 | 宽松 | 三行，内容居中 |
| 2 行（5~16 件） | 67 | 宽松 | 三行 |
| 3 行（9、18~24+ 件） | 44 | **紧凑** | 三行（10/8/7sp） |
| 极矮组件 | <34 | 紧凑 + 藏时间 | 头像 + 状态 |

- 紧凑档还会**改时间文案**：`timeOf(item, compact=true)` 去掉"预计"前缀并压到 5 字
  （"预计今天送达" → "今天送达"）。8 列时列宽仅 43dp，不去前缀会被横向省略成"预计今…"，白占一行。

### 7. 网格裁剪（把静态 4×8 变成动态）
- 布局中 4 行 + 32 cell + 28 竖线 + 3 横线**全部默认 `gone`**。
- 渲染 **VISIBLE / GONE 都显式下发**（幂等）：
  - 行：`row < gridRows` → VISIBLE，否则 GONE（4 个 action）
  - 横线：`d < gridRows` → VISIBLE，否则 GONE（3 个）
  - 只遍历**可见行**内部（GONE 的行会连带隐藏该行 8 个格子，省掉整行 action）：
    竖线 `widget_v_{row}_{c}` 按 `c < gridCols`、cell `widget_cell_{row*8+col+1}` 按 `col < gridCols`
- ⚠️ **不能"只发 VISIBLE 不发 GONE"**：桌面 `updateAppWidget` 命中同一 layout id 时走
  `AppWidgetHostView.reapply`（复用旧视图树），漏发的 GONE 不会自动复位——件数变少时桌面留下
  **鬼格子**（实测 24 件→4 件仍显示 24 格，见 §十一.2）。同理 `bindCell` 里状态行的 VISIBLE 也**不能省**。
- 有数据的 cell → `bindCell`；无数据 → `bindPlaceholder`（灰色"+"，点击打开添加弹窗）。
- action 总量：10 件约 110 个、24 件满格约 220 个，实测 HyperOS 4.0 桌面均完整渲染（§十一 修正了 §十 的阈值结论）。

### 8. cell 绑定（`bindCell`，每个 cell ≈ 7 个 action）
| 元素 | 内容 |
|---|---|
| avatar | `companyName.take(2)`（空则"快"），背景按状态：delivering→橙 `#E65100`、done→绿、abnormal→红、notshipped→灰、其余→蓝（`avatarBg`） |
| state | `item.stateLabel()`（分区覆盖 > stateName > 状态码文案），文字色同状态 chip 色（`chipText`）；**可见性必须显式发 VISIBLE**（见 §五.7 与 §十一.4） |
| time | `timeOf(item, compact)`：优先 ETA（`eta`→`aiEta`）取"X月X日"，否则去"预计"前缀截 5 字（紧凑）/ 7 字（宽松）；无 ETA 则 `latestTime` 取 MM-dd。按 `showTime` 显式 VISIBLE/GONE |
| 字号 | 布局默认值（宽松 11/9/8sp、紧凑 10/8/7sp），**不动态 setTextSize**（换布局档代替，0 action） |
| 点击 | `PendingIntent.getActivity(requestCode = widgetId*100+index+1)` → `DetailActivity`，`putExtra("mailNo", ...)`（2026-08-20 起替代整件 JSON 直传，压缩序列化体积；DetailActivity 兼容按 mailNo 查 Store） |

`bindPlaceholder`（空位"+"）：灰色头像 + `addPending`；状态/时间行**留空但占位**（`setTextViewText("")`），
否则"+"因内容更矮而垂直居中到别处，跟同行邻格头像错位（实测差 38px）。
全空列表（一行全是"+"）没有对齐对象，此时状态/时间直接 GONE，让"+"真正居中。

### 9. 头部与小卡点击
- `widget_refresh` → 显式广播 `ACTION_REFRESH`（`com.halo.expressassistant.WIDGET_REFRESH`），requestCode=2
- `widget_open` → 打开 `MainActivity`，requestCode=0
- small 整卡 `widget_root` → 打开 `MainActivity`
- 空位"+" → `MainActivity` 带 `putExtra("add", true)`（直接弹添加对话框），requestCode=3

### 10. 摘要文案（small 布局）
- `smallSummary`：`N 件在途 · M 派送中` / `暂无在途`，异常时追加 `· K 异常`（`countsOf` 统计 transit/delivering/abnormal/done）。
- `smallDetail`：第一件在途（按 orderOf）的 `公司名 · 预计 ETA`；无在途 → `点击打开快递助手`。

---

## 六、刷新链路（8 条触发路径）

| # | 触发 | 入口 | 说明 |
|---|---|---|---|
| 1 | 系统周期 30min | `onUpdate` → `renderAll` | `updatePeriodMillis=1800000`；HyperOS 冻结下可能被拦截（见 §九.12） |
| 2 | 组件尺寸变化 | `onAppWidgetOptionsChanged` → 重渲染 | 拖拽缩放实时重排网格 |
| 3 | 数据保存 | `Store.saveItems` | 任何渠道同步完成写库后即时刷新 |
| 4 | 登录态清理 | `Store.clearLogin`（含渠道退出） | 退登后立刻重渲染 |
| 5 | 主题/配色变更 | `Store.saveTheme` / `saveColorScheme` | 换主题即时切布局 |
| 6 | 小组件显示开关 | `saveWidgetShowDelivering/Shipped/NotShipped` | 三个开关各自触发 |
| 7 | 手动刷新按钮 | `ACTION_REFRESH` → `onReceive` | **四源同步**：App 在前台（`App.topActivity` 生命周期跟踪）→ 静默 `SyncEngine.sync` 不跳转；不在前台 → 拉起 `MainActivity`（`sync_now` extra）同步完成 `moveTaskToBack` 自动回桌面 → `updateAll` |
| 8 | 打开 App | `MainActivity.onResume` → `updateAll` | 2026-08-20 新增，兜底 HyperOS 冻结导致的周期刷新失效 |

另外：后台 `TrackingWorker`（WorkManager 30min 周期，需联网）更新轨迹后走 `saveItems` 间接刷新。
`updateAll` 容错：无 widget 实例或桌面未就绪时**静默跳过**；`renderOne` 包 try-catch，单实例失败打 `Log.e("ExpressWidget", ...)` 不影响其他实例。

---

## 七、设置入口（`SettingsActivity.showWidgetSheet`）

设置页 →「小组件」上弹卡片：
- 文案："选择显示哪些状态的快递，**已完成和异常固定不显示**"
- 三个开关（默认全开）：派送中 / 运输中 / 未发货
- 切换即存并触发刷新（§六.6）

---

## 八、数据与颜色映射速查

```
状态 → sectionKey → orderOf 排序 | avatarBg          | chipText（state 文字色）
派送中  delivering  0   | 橙 #E65100            | #E65100
运输中  shipped     1   | 蓝                    | #1565C0
未发货  notshipped  2   | 灰                    | #616165
异常    abnormal    3   | 红                    | #C5221F   （渲染过滤，不显示）
已完成  done        (4) | 绿                    | #1E8E3E   （渲染过滤，不显示）
```

`timeOf` 展示规则：`eta ?? aiEta` → 正则取 `X月X日`；否则**去掉"预计/预计于"前缀**后截前 7 字
（"预计今天送达" → "今天送达"，各件数档统一）；无 ETA 则用 `latestTime`（"YYYY-MM-DD HH:mm" 截 MM-dd，其它截前 7 字）。

---

## 九、已知细节与局限（13 条）

1. ~~手动刷新只同步小米~~ **已修复（2026-08-20）**：`ACTION_REFRESH` 走四源 `SyncEngine.sync`（见 §六.7）。
2. ~~Manifest action 残留~~ **已清理**：`com.halo.expressassistant.REFRESH` 已从 intent-filter 删除。
3. **RemoteViews 限制下的设计**：无 `RemoteViewsService`/集合视图，靠 4×8=32 个静态 cell 裁剪出任意 ≤32 格动态网格；所有 view id 用 `getIdentifier` 动态解析。
4. **已完成/异常固定不显示**（设置页明示）；`smallSummary` 的 `· K 异常` 分支实际因过滤永远为 0（逻辑残留，未清）。
5. **无商品信息**：商品图/AI 短名/取件码均未上组件（RemoteViews 加载网络图需额外链路）——用户确认维持现状。
6. **主题判定与 App 不完全一致**：App 内 custom 主题还受衬线开关影响，widget 只看 theme/colorScheme；默认 warm 配色导致**默认即 lark 布局**。
7. ~~行高均分~~ **已修复（2026-08-20 第二轮）**：4 行改 `0dp + layout_weight=1`，可见行等分卡片高度；
   原先 `wrap_content` 导致"内容堆顶部、底部空 38dp"与"3 行时末行被裁"（§十一.1）。
8. ~~点击 JSON 直传~~ **已改**：cell 点击传 `mailNo`，DetailActivity 兼容两种 extra；requestCode 按 `widgetId*100+index+1` 错开防 PendingIntent 复用串数据。
9. **`targetCellWidth=4/targetCellHeight=2` 是建议落格**，实际由桌面按 resize 约束决定；`minWidth=110dp` 保证小卡能放。
10. ~~无夜间独立配色~~ **此条原描述有误，已更正**：`values-night/colors.xml` 里 `widget_*`（普通版）与
    `widget_lark_*`（暖纸版）**都有夜间色且实测生效**——RemoteViews 在桌面进程 inflate，跟随桌面深色模式。
    实测卡片底像素 `#1B1713` ≈ `widget_lark_card` 夜间值 `#1C1713`。
    真实局限是：夜间用的是**固定色板**，不跟随莫奈取色，也不跟随 App 内 0–200 纸感强度。
11. **action 截断的真实边界比原结论宽**：§十 曾推断"阈值在 110~160 之间"，
    但第二轮实测 **24 格满绑定（约 220 action）在 HyperOS 4.0 上完整渲染**（8×3 全部显示、编号 01–24 齐全）。
    更可能的真凶是当年每格 PendingIntent 直传整件 JSON 造成的**序列化体积**，而非 action 条数。
    结论：当前 ≤32 格的静态网格方案够用；真要再扩容量（>32）才需要 collection view（`RemoteViewsService`
    或 API 31+ `RemoteCollectionItems`）。
12. **HyperOS 冻结拦截后台广播**：`updatePeriodMillis` 周期广播与后台广播会被冻结机制拦截（logcat `freezeUid FAILED ... tryDelay_noPending`）。对策：①`MainActivity.onResume` 无条件 `updateAll`；②手动刷新走四源同步；③尺寸读取兼容 `OPTION_APPWIDGET_SIZES`；④渲染 try-catch + 日志。
13. **`updateAppWidget` 是 reapply 语义，不是重画**：layout id 不变时桌面复用旧视图树，
    所以任何"这次不需要"的可见性都必须显式发 GONE，否则残留上一次的状态（§十一.2/§十一.4 两次踩坑）。
    换 layout id（宽松↔紧凑档切换）反而会强制重新 inflate，天然干净。

---

## 十、2026-08-20 排障实录（一）："10 件只显示 5 件"

> 用户报告：模拟 10 个包裹，小组件只显示 5 个；系统从旧版升级到 **Android 17 / HyperOS 4.0** 后出现。

### 排查过程（证据链）

| 步骤 | 动作 | 证据 | 结论 |
|---|---|---|---|
| 1 | 代码审查 | `gridFor(10, 3)` 数学上必返回 5×2；布局 32 cell 齐全；`sectionKeyOf` 过滤后 10 件 | 代码逻辑无问题 |
| 2 | 读设备数据 | `run-as cat shared_prefs/express_store.xml`：28 件 = 10 DEMO（`partitionOverride=delivering`）+ 18 完成 | 过滤后应为 10 件 |
| 3 | 加诊断日志 | `RENDER id=729 all=28 items=10 grid=5x2 cap=10` | **代码确实渲染 10 格并成功推送** |
| 4 | 像素分析截图 | 橙色头像块仅 1 行 5 个（x=198/398/599/800/1001），无第二行、无横线 | 桌面显示与推送内容不符 |
| 5 | 重加 widget（全新视图树 id=729） | 仍 5 格 | 排除"视图树缓存/旧状态复用" |
| 6 | 重启桌面进程（force-stop miui.home） | 仍 5 格 | 排除"桌面进程缓存" |
| 7 | 对照实验 A：**去掉全部可见性操作 + setMinimumHeight**（仅内容绑定） | **2 行 5 列 10 格全部显示** | 问题出在 action 数量/构成 |
| 8 | 统计 action 数 | 原实现 ≈160（可见性 44 + setTextSize 30 + setInt 10 + 绑定 ≈110）；精简版 ≈110 | **截断阈值在 110~160 之间** |

### 根因

**HyperOS 4.0 的桌面（flutter 渲染器）应用 RemoteViews 时存在 action 数量/体积截断**：排在后面的 action（第二行 cell_9+ 的绑定）全部丢失。旧系统（原生渲染器）无此限制，所以"更新系统前完美"。

### 修复（action 压到 ≈90）

1. **布局默认 GONE**：list/list_lark 的 32 cell + 28 竖线 + 3 横线全部预设 `visibility="gone"`（sed 批量 63 处 × 2 布局）。
2. **渲染只发 VISIBLE**：需要显示的才发 VISIBLE（约 19 个可见性 action），不再"先全 GONE 再 VISIBLE"。
3. **压缩绑定**：去掉 `setTextSize`（字号用布局默认）、去掉 `setMinimumHeight`（行高内容自适应）。
4. **点击传 `mailNo`**：替代整件 JSON 直传，大幅压缩 PendingIntent 序列化体积（DetailActivity 兼容按 mailNo 查 Store）。

### 验证

- 像素分析：两行各 5 块（x=197/399/599/801/1002，y=842..946 / 998..1100）→ **10 格全显示**。
- 视觉模型复核：第一行 顺丰/京东/申通/圆通/中通；第二行 极兔/顺丰/京东/申通/圆通；每格"派送中 + 预计今…"。

### 经验总结

1. **RemoteViews 动态网格的 action 预算**：可见性操作 + 反射方法（setInt/setFloat）+ 大 extra 的 PendingIntent 都是重量级，flutter 桌面渲染器可能截断——**能省则省**（布局预设可见性、布局预设字号、传 id 不传 JSON）。
2. **排障顺序**：先确认"数据对不对"（run-as 读 prefs）→ 再确认"渲染了什么"（加日志打 items/grid/cap）→ 最后确认"桌面显示了什么"（截图像素分析）——三层证据对不上时，问题在中间层（桌面渲染器）。
3. **对照实验是定位渲染器 bug 的最快手段**：逐个删减 action 类型，一次定位触发面。

---

## 十一、2026-08-20 排障实录（二）：底部空白 / 末行裁字 / 鬼格子 / 溢出丢件 / 陈旧 GONE

> 用户报告：① "10 个快递时下方有一长条空白"；② "最高容纳 3 行 8 列 24 个，高件数排版没验证过，很可能有问题"；
> ③ 后续追加："24 个时信息显示不全，让三条信息全显示出来，字小些没关系"；④ "'预计'两个字去掉，所有件数都去掉"。
> 起因同样是 HyperOS 3 → 4 升级。**本轮共揪出 5 个 bug，其中 2 个是静默丢数据。**

### 1. 底部一长条空白 + 3 行时末行被裁（同一个根因）

- **证据**：像素分析 10 件时"网格区留白 上 0dp / 下 38dp"（内容全堆在顶部）；
  24 件时视觉模型 + 像素双证"第三行的'预计今天送达'被卡片底边裁掉，只剩上半截"。
- **根因**：4 个行容器都是 `layout_height="wrap_content"` 且**没有 `layout_weight`**。
  于是件数少时行只占内容高度、剩余空间全掉在底部；件数多时 3 行内容（3×53dp）超过可用高度，末行被裁。
- **修复**：4 行改 `0dp + layout_weight=1` + 默认 `gone`，可见行等分卡片剩余高度。
  → 10 件"上 8dp / 下 3dp"，24 件"上 1dp / 下 3dp"，均衡。

### 2. 鬼格子：件数变少时桌面残留上一次的格子

- **证据**：注入 26 件（当时会退化成 `grid=4x1 cap=4`），logcat 明确打 `cap=4`，
  但截图数出 **24 个头像块**——桌面显示的是上一轮 24 件的画面。
- **根因**：第一轮为压 action 采用"布局默认 GONE + 渲染只发 VISIBLE"。
  但 `AppWidgetHostView.updateAppWidget` 在 layout id 不变时走 **reapply（复用旧视图树）**，
  漏发的 GONE 永远不会复位。
- **修复**：VISIBLE/GONE 全部显式下发；靠"整行 GONE 连带隐藏该行 8 格"把 action 控制在同一量级
  （只遍历可见行内部）。

### 3. 溢出丢件：>24 件在途只显示 4 件

- **证据**：`gridFor(26, 3)` 数学上所有候选都被"≤8 列"挡掉 → 落回初值 `4 to 1` → `cap=4`。
- **影响**：真实设备曾有 31 件；一旦在途件数 >24（中卡）或 >32（大卡），**静默只显示 4 件**。
- **修复**：无候选时返回 `8 × maxRows` 占满最大网格。→ 26/31/40 件均稳定 `8x3 cap=24`。

### 4. 陈旧 GONE：曾当过占位格的格子永久缺"派送中"（本轮自己引入又自己抓出）

- **来龙去脉**：修 #2 时把 `bindCell` 里的 `setViewVisibility(stateId, VISIBLE)` 当"冗余"删掉了
  （因为布局默认可见）。但空列表渲染时占位格会把状态行设成 GONE ——
  于是 **"跑过一次 0 件" 之后，1~4 号格子永久丢失状态文字**。
- **抓出方式**：**视觉模型复核**报告"第一行前四格没有'派送中'"，起初怀疑是幻觉，
  用像素证伪：该区域只有时间色 `#D4C4BA` 文字、没有状态色 `#F4B777`，且头像整体下移 20px（少一行→居中下沉）。
  **纯像素脚本当时只数头像块，抓不到这个**——双复核（像素 + 视觉）才是完整的。
- **修复**：`bindCell` 恢复显式 `VISIBLE`；回归台加"数每格头像下方文字行数"的检查项，
  以后这类 bug 自动可见（`头像下文字行=[2,2,2,...]` 不一致即报警）。

### 5. 24 件三行显示不全 → 紧凑布局档

- **需求**：3 行 8 列时每行仅 43.7dp，宽松版一格需 ~53dp，放不下三行。
- **候选方案与取舍**：
  - `setTextViewTextSize` 动态改字号 → 32 格 ×3 = 96 个反射 action，正是第一轮的敏感面，**否**。
  - 全局缩小字号 → 用户日常是 10 件（2 行），会把每天看的视图也变小，**否**。
  - **紧凑布局变体（采用）** → 换 layout id 是 0 action，且强制重新 inflate（顺带免疫 #2/#4 的陈旧状态）。
- **实现**：`research/gen_widget_compact.py` 从宽松版生成紧凑版（id 完全一致，仅字号/内边距/`includeFontPadding`），
  一格 53dp → 37dp；渲染时按 `rowDp < 56` 选档。
- **附带**：紧凑档列宽仅 43dp，"预计今天送达"会横向省略成"预计今…"，
  于是 `timeOf` 去掉"预计"前缀 → "今天送达"（按用户要求**所有件数统一去掉**，
  顺手修掉小卡摘要 `· 预计 预计今天送达` 的重复）。
- **验证**：OCR 逐格核对 24 格 → 编号 01–24 齐全、每格三行、"今天送达"完整无省略号。

### 本轮回归矩阵（348×171dp 中卡，真机 Redmi 25102RKBEC / HyperOS 4.0）

| 件数 | 网格 | rowDp | 档位 | 头像块 | 未绑定 | 每格文字行 | 上下留白 |
|---|---|---|---|---|---|---|---|
| 0 | 4×1 | 136 | 宽松 | 4（全"+"） | 0 | — | 55 / 59 ✓ |
| 1 | 1×1 | 136 | 宽松 | 1 | 0 | 2 | 42 / 48 ✓ |
| 5 | 3×2 | 67 | 宽松 | 6（1 占位） | 0 | 2 | 8 / 14 ✓ |
| 9 | 3×3 | 44 | 紧凑 | 9 | 0 | 2 | 1 / 7 ✓ |
| 10 | 5×2 | 67 | 宽松 | 10 | 0 | 2 | 8 / 3 ✓ |
| 16 | 8×2 | 67 | 宽松 | 16 | 0 | 2 | 8 / 3 ✓ |
| 18 | 6×3 | 44 | 紧凑 | 18 | 0 | 2 | 1 / 3 ✓ |
| 24 | 8×3 | 44 | 紧凑 | 24 | 0 | 2 | 1 / 3 ✓ |
| 26 / 31 / 40 | 8×3（钳制） | 44 | 紧凑 | 24 | 0 | 2 | 1 / 3 ✓ |

（"每格文字行"=头像下方的行数，2 = 状态 + 时间都在；"未绑定"= 蓝色默认底色的格子数，0 = 无 action 丢失）
另测：格子点击 → `DetailActivity` 且 extra `mailNo` 正确；占位"+" → `MainActivity`。

### 经验总结（补充第一轮）

1. **`updateAppWidget` 是 reapply 不是重画**：动态网格的每一个可见性都要"正反都发"，
   否则件数变化后残留旧画面。想彻底躲开就换 layout id（强制 inflate）。
2. **"省 action" 要省对地方**：省掉 `setViewVisibility(state, VISIBLE)` 直接造成永久性内容缺失；
   而换布局资源能 0 action 换到整套字号——**该省的是反射 action 和大 extra，不是幂等性**。
3. **像素分析和视觉模型各有盲区**：像素脚本只数它被写去数的东西（当时只数头像块，漏了缺失的文字行）；
   视觉模型能发现"看起来不对"但会自我怀疑。两者都上、互相证伪，才定位到 #4。
4. **回归要跑"件数序列"而不是单点**：#4 只在"先 0 件、再有件"的顺序下暴露；
   #2 只在"先多后少"暴露。`widget_bench.py` 支持任意件数注入正是为此。
5. **数学退化路径要有兜底**：`gridFor` 的初值 `4 to 1` 在无候选时被静默返回，
   造成丢件——凡"循环里挑最优"的函数，都要显式处理"一个候选都没有"。

---

## 十二、验证工具箱

### 0. 回归台 `research/widget_bench.py`（首选）

一条命令完成"注入任意件数 → 触发渲染 → 截图 → 像素分析"，并给出结论：

```bash
python3 research/widget_bench.py backup        # 先备份设备现有 prefs（inject 时也会自动备份一次）
python3 research/widget_bench.py run 24 n24    # 注入 24 件 → 渲染 → 截图 outputs/widget_bench_n24.png → 分析
python3 research/widget_bench.py analyze outputs/widget_bench_n24.png   # 只分析已有截图
python3 research/widget_bench.py restore       # 还原真实数据（跑完务必执行）
```

输出的判读要点：

| 指标 | 含义 |
|---|---|
| `卡片 bbox ... dp` | 桌面实际给的尺寸（对照 logcat 的 `rowDp`/分档） |
| `头像色块 共 N 个，分 R 行` | 桌面**真实显示**的格子数与行数（不是代码以为的） |
| `{'orange': n}` / `{'blue': n}` | 橙=绑定成功；**蓝=布局默认底色 = 绑定丢失（action 被截断）** |
| `头像下文字行=[...]` | 每格状态/时间行是否都在；**数值不一致 = 有格子陈旧 GONE 未复位** |
| `网格区留白: 上 X / 下 Y` | 判**对称性**而非"有没有空白"：行数少时上下都留白是正常居中，偏斜 >12dp 才是 bug |

配套：`research/gen_widget_compact.py` 重新生成紧凑布局变体（改了宽松版布局就要重跑）。

### 1. 手工命令

```bash
# 1. 看渲染诊断（尺寸分档 / items / 网格 / cap / rowDp / compact / showTime / 主题）
adb logcat -d | grep -E 'WidgetDebug'

# 2. 看渲染异常（单实例渲染失败会打这里）
adb logcat -d | grep 'ExpressWidget'

# 3. 读设备数据（模拟数据/状态分布）
adb shell "run-as com.halo.expressassistant cat shared_prefs/express_store.xml" > /tmp/store.xml

# 4. 看 widget 实例与尺寸
adb shell dumpsys appwidget | awk '/^Widgets:/,/^Hosts:/' | grep -E 'Widget #|provider=|ExpressWidgetProvider'

# 5. 手动触发渲染（打开 App 即 onResume→updateAll；系统 APPWIDGET_UPDATE 是保护广播，adb 发不了）
adb shell am start -n com.halo.expressassistant/.ui.MainActivity

# 6. 点击链路验证（先算出格子中心坐标，再看落到哪个 Activity）
adb shell input tap 264 920
adb shell dumpsys activity activities | grep ResumedActivity

# 7. 视觉模型 / OCR 复核：数格子、读每格三行文字、查裁切与省略号（像素脚本的盲区靠它补）
# 8. 强制桌面重建（排障用，效果有限——reapply 残留可用切换 layout id 规避）
adb shell am force-stop com.miui.home
```

排障截图存档：`outputs/widget_check*.png`（第一轮：5 格异常态、INVISIBLE 实验态、修复后 10 格正常态）、
`outputs/widget_bench_*.png`（第二轮回归台产物）、
`outputs/widget_before_10_crop.png` ↔ `widget_after_fix_crop.png`（底部空白修复前后）、
`outputs/widget_final_24_crop.png`（24 格三行全显示）。

---

## 十三、文件清单

```
java/.../widget/ExpressWidgetProvider.kt   渲染引擎 + 刷新 + 点击（全部逻辑，≈450 行）
res/xml/express_widget_info.xml           provider 元数据
res/layout/widget_express_loading.xml     初始布局
res/layout/widget_express_list.xml        网格大卡（普通；行 weight 均分，行/cell/分隔线默认 gone）
res/layout/widget_express_list_lark.xml   网格大卡（暖纸衬线，同上）
res/layout/widget_express_list_compact.xml       网格大卡·紧凑档（自动生成，勿手改）
res/layout/widget_express_list_lark_compact.xml  网格大卡·暖纸紧凑档（自动生成，勿手改）
res/layout/widget_express_small.xml       摘要小卡（普通）
res/layout/widget_express_small_lark.xml  摘要小卡（暖纸衬线）
research/gen_widget_compact.py            紧凑档生成器（从宽松版派生）
research/widget_bench.py                  回归台：注入件数 → 渲染 → 截图像素分析
res/drawable/widget_card_bg(.lark).xml    卡片底
res/drawable/widget_avatar_{5 色}.xml     头像块
res/drawable/widget_chip_{5 色}.xml       状态 chip 底
res/drawable/widget_divider_{h,v}.xml     分隔线
res/drawable/ic_widget_refresh.xml        刷新按钮
res/drawable/ic_widget_open.xml           打开按钮
res/drawable-nodpi/widget_preview.png     添加预览图（368×800）
```

关联代码：`data/Store.kt`（items/开关/主题/保存即刷新）、`data/Express.kt`（sectionKeyOf/stateLabel）、`ui/SyncEngine.kt`（四源同步）、`ui/MainActivity.kt`（onResume 刷新 + sync_now 处理）、`ui/DetailActivity.kt`（mailNo 兼容）、`App.kt`（topActivity 跟踪）、`TrackingWorker.kt`（周期轨迹→saveItems→刷新）。

---

## 十四、相关文档与素材

| 文档 | 内容 |
|---|---|
| `docs/WIDGET.md` | 本文档 |
| `docs/HANDOFF.md` | 交接总纲与决策史 |
| `docs/API.md` | 四源接口 |
| `docs/ARCHITECTURE.md` | 模块地图 |
| `docs/DEVELOPMENT.md` | 构建调试 |
| `docs/TROUBLESHOOTING.md` | 排查指南 |
| `outputs/widget_check*.png` | 第一轮排障截图（5 格异常态 / INVISIBLE 实验态 / 修复后 10 格） |
| `outputs/widget_now.png` | 第二轮起点：10 件、底部空 38dp（修复前全图） |
| `outputs/widget_before_10_crop.png` | 同上裁剪图（对比用） |
| `outputs/widget_after_fix.png` / `_crop.png` | 10 件上下均衡（行权重修复后） |
| `outputs/widget_bench_pre24.png` / `_crop.png` | 24 件末行"预计今天送达"被裁（修复前） |
| `outputs/widget_bench_pj24.png` / `widget_final_24_crop.png` | 24 件三行全显示、编号 01–24 齐全（紧凑档，OCR 已核） |
| `outputs/widget_bench_pj10.png` / `widget_final_10_crop.png` | 10 件三行 + 时间去掉"预计"（OCR 已核） |
| `outputs/widget_bench_ship.png` | 最终出厂态（真实数据 28 件 → 在途 10 件 5×2） |
| `outputs/ExpressAssistant-widget-latest.apk` | 本轮修复后的调试包 |
