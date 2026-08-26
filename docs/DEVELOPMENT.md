# 开发指南

## 环境

- JDK 17（`sourceCompatibility/targetCompatibility = 17`）
- Android SDK：compileSdk 36，minSdk 26，targetSdk 36
- Android Studio / Gradle wrapper（8.9）
- 手机：Redmi `25102RKBEC`（测试样本），需要“开发者选项 + USB 调试/无线调试”

## 构建

```bash
cd app
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 安装与设备

USB：
```bash
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

无线（手机热点场景）：
```bash
adb connect 10.176.58.121:5555   # 网关即手机，地址可能随热点变化，用 netstat 查默认网关
```

常用调试：
```bash
adb logcat -s ExpressApi:I ExpressReport:D      # 网络/日报日志
adb logcat -s SyncEngine:I JdListFetcher:I JdTrackFetcher:I TbOrders:I CaiNiaoResolver:I GoodsPres:I PddListFetcher:I PddTraceFetcher:I PddLogin:I  # 四源
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml   # UI 树
adb exec-out screencap -p > screen.png          # 截图
adb shell am force-stop com.halo.expressassistant
```

调试登录组合（不真正退出登录，`skip_channels` 逗号分隔）：
```bash
adb shell am start -n com.halo.expressassistant/.ui.MainActivity --es skip_channels xiaomi
adb shell am start -n com.halo.expressassistant/.ui.MainActivity --es skip_channels xiaomi,jd,taobao
adb shell am start -n com.halo.expressassistant/.ui.MainActivity --es skip_channels xiaomi,jd,taobao,pdd
```

## 代码约定

### 四源同步 · 多账号
- 新渠道接入 = `SyncEngine.syncInternal` 里一个并行分支：拉取 → 转 `ExpressItem`（带 `source`）→ 合并时小米优先、mailNo 去重
- **多账号（v0.5.0）**：同一平台用 `Store.accounts(channel)` 列表；同步内逐账号调用 `fetchWith(account)/syncAccount(account)`，一个账号失败只记错误不中断；商品预缓存合并保留旧 shortName
- 渠道失败必须只记 ChannelStatus.error，不抛断整个同步
- 写 Store.items 只在 SyncEngine 里做（Mutex 串行）；渠道内部的商品预缓存要**保留旧 shortName**（`copy(shortName = old.shortName)`）
- 详情轨迹按 `item.source` 路由，新渠道在 DetailActivity / MainActivity.refreshAllDetails 两处同步加分支；**详情/溯源按 `Store.accountForItem(item)` 取归属账号凭证**（找不到回退平台第一启用账号）
- WebView 类 Fetcher 的隐藏视图：`alpha=0.01 + IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS + decor index 0`；真实交互用 `dispatchTouchEvent`（合成 click 对 Taro/React 无效）
- 拼多多（v0.4.0）：WebView 类 Fetcher 前必须先 `PddCapture.setCookiesFor(act, cookie)`（隐藏 WebView 共享登录态）；退出登录只清拼多多域 Cookie

### 商品短名与取件码
- 短名：`GoodsPresentation.batchShorten` 每批 8 个、后台执行；提示词必须含“保留型号（如 MM3A）”；无 key 走 `ruleShorten`
- 取件码：`pickupCodeFrom` 正则（取件码/取货码/提货码/自提码/驿站码/取件验证码，排除登录验证码）；列表同步与详情轨迹两处都提取，跨同步保留

### 主题与纸感
- 每个 Activity `onCreate` 第一行调 `Themes.apply(this)`，随后 `EdgeToEdge.apply(...)`，最后 `Paper.apply(this, binding.root, binding.toolbar)`
- 页面根背景用 `?attr/surfaceBackground`，不要直接写死颜色
- 动态创建的卡片（弹窗/后加视图）用 `Paper.styleCard` / `Paper.styleTree`

### 设置页
- 所有“二级菜单”都是上弹卡片：用 `Sheets.create(context, title, subtitle)` 得到 `(BottomSheetDialog, LinearLayout)`
- 可选项统一用 `Sheets.optionRow(context, title, subtitle, checked) { ... }`
- 主题卡片支持“原地展开”：点击选项后重绘 body，不要 `recreate()`；只有需要整体换肤时才在关闭卡片时重建
- 新设置项 = Store 键 + SettingsActivity 里一个 row + 对应 sheet

### AI
- 对话风格在 `AiClient.systemPrompt(context)` 按 `Store.aiStyle()` 切换；新增风格 = Store 常量 + persona 文案 + 设置里的选项
- 进度计算提示词在 `AiClient.computeProgress`；日报提示词在 `DailyReporter.generateReport`
- `[[card:单号]]` 只在聊天里解析；日报会剥离

### 小组件
- 布局只允许 RemoteViews 支持的元素；禁止裸 `<View>`、`<include>`、RemoteViewsService
- 新增格子要同时维护 `widget_express_list.xml` 与 `widget_express_list_lark.xml`（ID 必须一致）

## 数据持久化

- 全部存在 `SharedPreferences("express_store")`，JSON 字符串字段：items / chat_history / report_schedules / pending_report / xiaomi_hidden / jd_goods
- 主要键：见 `Store.kt`；渠道凭证：xiaomi_token / xiaomi_cuser / jd_cookies / tb_cookies；主题相关：theme / theme_color(_day/_night) / theme_font(_day/_night) / paper_intensity(_day/_night) / custom_separate；日报：report_issue / report_first_date
- 不要手工编辑该文件；确有必要时：先 `am force-stop`，改完必须通过 XML 解析校验，否则 App 启动会把数据覆盖为空（调试期可用 `run-as cp` 从 `/data/local/tmp` 整份替换）

## 提交流程

工程是 git 仓库（origin + backup 两个私有 remote）。发布/上传需用户明确同意；当前不要推公共仓库。
