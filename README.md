# ExpressAssistant

A local-first Android app that aggregates express deliveries from multiple platforms into one list, with AI daily reports, scheduled reminders, and per-package tracking notifications.

## Releases

- Latest: [v0.6.0](https://github.com/Halo0sama/ExpressAssistant/releases/tag/v0.6.0) (in-transit-first redesign + full-channel polling)

## Features

- Multi-source binding: **Xiaomi / JD.com / Taobao(Cainiao) / Pinduoduo** — one or more accounts per platform
- **In-transit-first home**: only in-transit packages on the main page (Delivering / Shipped / Not Shipped); completed & abnormal packages live in a **second-level panel** (bottom-right "已完成" capsule)
- Second-level panel: search / calendar / abnormal-packages (isolated from the home list), real month calendar, floating pager with per-page jump, page size auto-adapts to screen (up to 8 cards per page)
- Streaming progressive sync: each new batch appears immediately; in-transit items load first, completed/abnormal are stored after the round finishes; first batch stops the refresh spinner
- Full tracking timeline, estimated arrival time, transport progress, pickup codes
- Tracked-package notifications on new updates
- **Configurable background polling** (Settings → 快递轮询): off by default; enabling tracking auto-defaults to 15 minutes; only platforms with in-transit tracked packages are polled; daily reports wait for the sync to finish
- JD / Taobao / Pinduoduo / Xiaomi trace caching (details open instantly after first load)
- AI chat: reads local package data, works with any OpenAI-compatible API (DeepSeek by default)
- Scheduled daily reports: multiple schedules with once / daily / weekdays / weekends / custom weekday repeat rules
- Kuaidi100 fallback query (optional)
- Accessibility import from Cainiao / Taobao pages (optional fallback)
- AI assistant "Lark" (云雀): tool calls to rename / change status / move sections / toggle tracking / sync, express cards inside replies, preset questions
- Express calendar: month view marking today and estimated arrival dates
- AI-powered transport progress and estimated delivery time (uses your saved address)
- Home address in settings with auto-locate or manual input
- Local CLI and MCP-like JSON-RPC interface for integrations
- No ads, no analytics, all data stays on device

## Build

Requirements:

- JDK 17
- Android SDK (compileSdk 36)
- Android Studio or Gradle CLI

```bash
./gradlew :app:assembleDebug
```

The debug APK will be generated at `app/build/outputs/apk/debug/`.

## Usage

1. Log in from Settings (H5 login per platform): Xiaomi (Shizuku-authorized), JD.com, Taobao, Pinduoduo.
2. Pull down on the home page to sync: in-transit packages appear immediately; completed/abnormal land afterwards in the second-level panel.
3. Optional: add a Kuaidi100 key as fallback in Settings > More Connections.
4. Optional: configure an OpenAI-compatible API in Settings > Daily Reports (DeepSeek by default), then set up scheduled reports.
5. Long-press any package card to enable tracking (auto-enables 15-minute background polling), rename, or remove it.

> Taobao sync needs the "Order number protection" disabled in Taobao privacy settings for new orders.

## Privacy

- All package data, login tokens and API keys are stored locally only (SharedPreferences), and are never uploaded to any server.
- The project has no backend; network requests only go to services you configure yourself (Xiaomi, JD, Taobao, Pinduoduo, Kuaidi100, Cainiao, etc.).
- No real phone numbers, tracking numbers or secrets are included in the code.

## Disclaimer

This project is not affiliated with or endorsed by Xiaomi, Cainiao, Taobao, JD.com, Pinduoduo, Kuaidi100 or any other company. Interface stability is not guaranteed. Do not use it for commercial purposes.

## Acknowledgments

- DeepSeek v4 flash — development & debugging assistance, and the default AI model for chat and daily reports

## License

[MIT](LICENSE)

---

# 快递助手

一个本地优先的 Android 快递聚合应用：把不同平台的快递统一收进一个列表，支持 AI 日报、定时提醒和快递跟踪通知。

## Release

- 最新：[v0.6.0](https://github.com/Halo0sama/ExpressAssistant/releases/tag/v0.6.0)（在途优先重构 + 全渠道后台轮询）

## 功能

- 多源绑定：**小米 / 京东 / 淘宝（菜鸟）/ 拼多多**，每个平台可绑定一个或多个账号
- **在途优先首页**：主页只展示在途（派送中 / 已发货 / 未发货）；已完成与异常包裹进入**二级面板**（右下角「已完成」胶囊）
- 二级面板：搜索 / 日历 / 异常包裹（与首页在途域隔离）、真月历、悬浮分页 + 页码跳页、**单页件数自适应屏幕**（本机 8 卡/屏）
- 流式渐进同步：每批新单立即出现；在途先加载、完成/异常一轮收尾后入库；首批到达即停转圈
- 完整物流轨迹、预计送达时间、运输进度、取件码
- 快递跟踪：被跟踪的快递有新动态时发本地通知
- **后台轮询可配置**（设置 → 快递轮询）：默认关闭；开启跟踪自动默认 15 分钟；只轮询「有在途跟踪件」的平台；日报生成前先等同步完成
- 京东 / 淘宝 / 拼多多 / 小米轨迹缓存（详情首次加载后秒开）
- AI 问询：读取本地快递数据，接入任意 OpenAI 兼容接口（默认 DeepSeek）
- 定时日报：多个定时、仅一次 / 每天 / 工作日 / 周末 / 自定义星期重复规则
- 快递100 兜底查询（可选）
- 无障碍导入：从菜鸟裹裹 / 淘宝页面读取快递（可选下策）
- AI 助手“云雀”：工具调用（改名 / 改状态 / 移分区 / 开关跟踪 / 同步）、回答内贴快递卡片、预制问题
- 快递日历：月历视图，标记今天与预计到达日期
- AI 运输进度与预计送达时间（结合你保存的地址）
- 设置里的“我的地址”：自动定位或手动填写
- 本地 CLI 与 MCP 风格 JSON-RPC 接口，方便外部集成
- 无广告、无统计、数据只存在本地

## 构建

环境要求：

- JDK 17
- Android SDK（compileSdk 36）
- Android Studio 或命令行 Gradle

```bash
./gradlew :app:assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/`。

## 使用

1. 在设置里按平台登录（H5 登录）：小米（需 Shizuku 授权）、京东、淘宝、拼多多。
2. 首页下拉同步：在途立即出现；完成/异常随后进入二级面板。
3. 可选：在“更多连接方式”里填快递100 key 作为兜底。
4. 可选：在“设置 → 快递日报”里填 AI 接口地址和 API Key（默认 DeepSeek），然后配置定时日报。
5. 长按任意快递卡片即可开启跟踪（自动开启 15 分钟后台轮询）、改名或移除。

> 淘宝件同步不上时，请在淘宝的隐私设置里关闭“订单号码保护”后再下单。

## 隐私

- 所有快递数据、登录令牌、AI Key 都只保存在手机本地（SharedPreferences），不上传任何服务器。
- 项目本身不包含任何后端，网络请求只发给你自己配置的服务（小米、京东、淘宝、拼多多、快递100、菜鸟等）。
- 代码中不含任何真实手机号、快递单号或密钥。

## 免责声明

本项目与小米、菜鸟、淘宝、京东、拼多多、快递100 等公司均无任何隶属或合作关系，不保证接口稳定性。请勿用于商业用途。

## 致谢

- DeepSeek v4 flash — 开发与调试协助，以及默认的 AI 对话与日报模型

## 许可

[MIT](LICENSE)
