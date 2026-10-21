# ExpressAssistant

A local-first Android app that aggregates express deliveries from different platforms into one list, with AI daily reports, scheduled reminders, and per-package tracking notifications.

## Features

- Aggregate "My Express" data from Xiaomi Smart Assistant (scan QR code to log in)
- Match packages by recipient phone number; bind multiple numbers so Taobao/Cainiao packages also appear
- Three tabs: In Transit / Completed / Abnormal; in transit split into Delivering / Shipped / Not Shipped
- Full tracking timeline, estimated arrival time, and transport progress
- Company icons and long-press card management (track / rename / remove)
- Package tracking: notification on new updates, background check every 30 minutes
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

1. Open the app, tap sync and scan the QR code to log in with your Xiaomi account.
2. Bind your recipient phone numbers in Settings > Phone Numbers.
3. Optional: add a Kuaidi100 key as fallback in Settings > More Connections.
4. Optional: configure an OpenAI-compatible API in Settings > Daily Reports (DeepSeek by default), then set up scheduled reports.
5. Long-press any package card to enable tracking, rename, or remove it.

> If Taobao packages do not sync, disable "Order number protection" in Taobao privacy settings and place a new order.

## Privacy

- All package data, login tokens and API keys are stored locally only (SharedPreferences), and are never uploaded to any server.
- The project has no backend; network requests only go to services you configure yourself (Xiaomi, Kuaidi100, Cainiao, etc.).
- No real phone numbers, tracking numbers or secrets are included in the code.

## Disclaimer

This project is not affiliated with or endorsed by Xiaomi, Cainiao, Taobao, JD.com, Kuaidi100 or any other company. Interface stability is not guaranteed. Do not use it for commercial purposes.

## Acknowledgments

- DeepSeek v4 flash — development & debugging assistance, and the default AI model for chat and daily reports

## License

[MIT](LICENSE)

---

# 快递助手

一个本地优先的 Android 快递聚合应用：把不同平台的快递统一收进一个列表，支持 AI 日报、定时提醒和快递跟踪通知。

## 功能

- 聚合小米智能助理“我的快递”数据（扫码登录小米账号后自动同步）
- 按手机号聚合：可绑定多个手机号，淘宝/菜鸟件也能进来
- 在途 / 已完成 / 异常 三页签，在途细分为派送中、已发货、未发货
- 完整物流轨迹、预计送达时间、运输进度
- 快递公司图标、长按卡片管理（跟踪 / 改名 / 移除）
- 快递跟踪：被跟踪的快递有新动态时发通知，后台每 30 分钟检查一次
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

1. 打开 App，点击同步并按提示用小米账号扫码登录。
2. 在“设置 → 手机号管理”中绑定收件手机号。
3. 可选：在“更多连接方式”里填快递100 key 作为兜底。
4. 可选：在“设置 → 快递日报”里填 AI 接口地址和 API Key（默认 DeepSeek），然后配置定时日报。
5. 长按任意快递卡片即可开启跟踪、改名或移除。

> 淘宝件同步不上时，请在淘宝的隐私设置里关闭“订单号码保护”后再下单。

## 隐私

- 所有快递数据、登录令牌、AI Key 都只保存在手机本地（SharedPreferences），不上传任何服务器。
- 项目本身不包含任何后端，网络请求只发给你自己配置的服务（小米、快递100、菜鸟等）。
- 代码中不含任何真实手机号、快递单号或密钥。

## 免责声明

本项目与小米、菜鸟、淘宝、京东、快递100 等公司均无任何隶属或合作关系，不保证接口稳定性。请勿用于商业用途。

## 致谢

- DeepSeek v4 flash — 开发与调试协助，以及默认的 AI 对话与日报模型

## 许可

[MIT](LICENSE)
