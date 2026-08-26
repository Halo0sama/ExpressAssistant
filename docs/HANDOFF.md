# 交接总纲（新接手 AI 先读）

## 一句话理解项目

这是一个“自己掌握快递数据”的 Android App：**四源融合**——小米智能助理接口（优先级最高）、京东订单中心、淘宝/菜鸟、拼多多四套数据源，按登录态任意组合拉取快递列表，本地合并去重；再叠加 AI 早报、AI 仓管、桌面小组件和主题定制，以及「商品溯源」（把物流单号解析成真实商品名+图）与「聚合取件码」。

## 当前完成度（截至 2026-08-14）

### 已可用
- **四源融合同步（SyncEngine）**：小米 / 京东 / 淘宝 / 拼多多四渠道按登录态独立拉取，`mailNo` 级别合并去重，小米字段优先；未登录渠道的数据不残留；单渠道失败不影响其他渠道；Mutex 串行化防并发覆盖。支持全部 2⁴ 登录组合。
- **京东登录 + 列表 + 轨迹 + 商品溯源**：App 内 H5 登录（`JdLoginActivity`，登录前清旧 cookie 强制真实会话）；隐藏 WebView 加载订单中心、JS 层 JSONP 回调改写捕获 `order_list_m`（不动网络层、绕开 h5st）；`deal_wuliu` 物流页轨迹（需 orderType/skuid/shopid/dealState 完整参数）；订单搜索式商品解析 + 列表预缓存商品。
- **淘宝登录 + 菜鸟列表/轨迹/溯源**：H5 登录（`TbLoginActivity`）；mtop wapSign 拉 `queryboughtlistv2` 订单列表 → 逐单请求 SSR 物流详情页（`pages-g.m.taobao.com/.../logisticsV2/h5-detail?x-ssr=true&bizOrderId=`，服务端渲染含运单号+承运商+轨迹）；商品名/图来自订单列表 `subOrders[0].itemInfo` 与菜鸟 `queryalltrace` 的 `packageItems`。
- **拼多多登录 + 快递列表/轨迹/商品**（v0.4.0）：H5 登录（`PddLoginActivity`，抓 `PDDAccessToken`/`pdduid` 存 `pdd_cookies`）；proxy 接口带 `anti_content` 动态签名无法自算 → 与京东同思路：隐藏 WebView 加载订单/物流页 + `PddCapture.HOOK_JS` 钩子捕获响应；`PddListFetcher`（列表，滚动翻页）与 `PddTraceFetcher`（轨迹 + 商品，候选键容错解析）；接口字段样例待真机验证补 `docs/PDD_REAL.md`。
- **详情页**：按 `item.source` 路由轨迹（小米→小米详情接口；京东→京东物流页；淘宝→SSR 详情，菜鸟 queryalltrace 兜底；拼多多→H5 订单详情 `order_detail.html?order_id=`）；头部商品条（缩略图+名称+数量）；顶部工具栏「获取商品信息」药丸按钮（主题色背景，右缘与卡片对齐）→ 溯源弹卡（查看/重新解析）。
- **卡片升级**：有溯源时卡片外显=商品缩略图+短名（AI 优化成"厂商+型号+产品名"≤12 字，`JdGoods.shortName` 缓存，无 key 规则兜底），无溯源回退原样；**取件码聚合**：从轨迹文本正则解析（取件码/取货码/提货码/自提码），存 `ExpressItem.pickupCode`，卡片高亮 chip 显示。
- 原有：小米登录与同步、首页三区、云雀对话（三种风格+工具调用）、定时日报、小组件、主题系统、本地接口（127.0.0.1:8765）、快递100/无障碍兜底。

### 待办 / 开放项（用户尚未拍板）
- 公共 GitHub 发布：用户明确“等我同意再上传”，当前只有私有仓库 origin/backup
- 小组件纸感强度：目前小组件只跟随主题配色，不跟随 App 内 0–200 纸感强度
- 京东列表跨 tab 捕获导致件数在 18~26 间波动（多出的也是真实件，已过滤退款/售后）；如需严格稳定可只保留 `curTab=all` 页
- 取件码当前无真实数据可回归（用户设备 31 件全签收）；有"待取件"件后自动生效
- `JdTraceActivity` 等调试 Activity 仍 `exported=true`，发布前收紧

## 关键决策史（踩坑后沉淀，别推翻）

1. **四源架构**：小米只当"渠道之一"，不再作为唯一数据源。合并时小米同 `mailNo` 覆盖京东/淘宝/拼多多；旧数据的用户覆盖字段（tracked/分区/通知/AI 进度/取件码）跨同步保留。
2. **小米接口不含商品信息**（已抓解密全量 JSON 实锤），溯源必须走电商平台侧：京东订单中心 / 菜鸟 packageItems。
3. **京东 h5st 无法自算**：`order_list_m` 等接口带 body 绑定的 h5st 签名（Mac 重放=601）。正确姿势是让页面自己发请求：document-start 注入 JS，改写 JSONP `callback` 参数 + 覆盖 `HTMLScriptElement.src` 属性 setter（SDK 用属性赋值而非 setAttribute），响应存 `window.name`（跨导航存活）再轮询读取。**不要**在网络层转发这些请求（OkHttp TLS 指纹会被风控，且转发会丢 Cookie 域）。
4. **京东订单中心挑战**：WebView 会话被挑战会跳 `plogin/nopasswordcmcc`；处理方式是显示 WebView 让用户完成一次验证，之后自动继续。
5. **京东 deal_wuliu 页必须带完整参数**：`from=orderdetail&dealState=<originOrderStatus>&dealId=<订单号>&orderType=<orderType>&skuid=<skuId>&shopid=<shopId>`；无参时是空 SPA。轨迹 DOM 结构是"描述在上、时间在下"，解析用"desc 累积 + 时间行提交"。
6. **mtop wapSign**：`sign = md5(token & t & appKey & data)`（小写 hex），token = `_m_h5_tk` cookie 下划线前部分，`data` 是**原始 JSON（未 URL 编码）**参与签名；需带 `jsv`/`type=originaljson`。曾用 URL 编码后的 data 签名导致全部 `FAIL_SYS_ILLEGAL_ACCESS`。
7. **OkHttp CookieJar 与显式 Cookie 头会合并出双 `_m_h5_tk`**，被服务端当游客；改为手动管理 Cookie（从 Set-Cookie 提取刷新值，替换后整串发送）。
8. **淘宝商品数据锁在买家登录态后**：菜鸟 queryalltrace 游客身份 `isBuyer=false` 只有轨迹；带淘宝登录 cookie 后才有 `packageItems`（goodsName/itemPic/goodsQuantity）。
9. **京东/淘宝登录的 cookie 注入要覆盖 api 域**：`CookieManager.setCookie` 是 host-only 的，只注入 www/trade 域会导致 `api.m.jd.com` 收到"未登录"（errorCode 302）。注入列表：www/trade/wqs/api/jingfen（京东），h5.m/acss/h5api（淘宝）。
10. **京东登录页要先清旧 cookie**：否则登录页复用残留旧会话，轮询秒判"已登录"保存的是死 `pt_key`（假登录）。
11. **卡片短名缓存会被同步覆盖**：渠道预缓存商品时 `putAll` 会冲掉 `shortName`，必须保留旧 shortName 合并。
12. **AI 批量优化不要阻塞同步**：34 个长名 ≈ 5 批 × 30-60s，放同步后的后台协程里跑，完成后 reload。
13. **`stateNum` 语义**：106 在小米语义里是"完成"档（adapter bucket 106→done）；"派送中"用 105。
14. **隐藏 WebView**：挂 decor 底部 + `alpha=0.01` + `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`；合成 `.click()` 对 Taro/React 无效，真实触摸用 `dispatchTouchEvent`。
15. 沿用既有决策：主题切换重建 MainActivity；纸纹用 `TiledTextureDrawable`；AI 进度 0% 无效；日报不分栏；期数真实累计；SharedPreferences 手改必须 force-stop；小组件 RemoteViews 限制；无线 adb 手机热点最稳。
16. **拼多多（v0.4.0）H5 proxy 接口带 `anti_content` 等动态签名**：无法自算 → 与京东同思路：隐藏 WebView + document-start 注入 `PddCapture.HOOK_JS`（XHR/fetch 拦截 `proxy/api|yangkeduo|pinduoduo` 响应 → `window.name`）；解析用「候选键 + 递归扫描」容错（字段随版本有差异）；退出登录**只清拼多多域 Cookie**（不要 `removeAllCookies`，会误伤京东/淘宝/小米会话）。
17. **多源绑定（v0.5.0）**：凭证全部列表化 `Store.accounts(channel): List<BoundAccount{id,label,enabled,payload}>`（payload：xiaomi=`XiaomiCred` JSON；jd/tb/pdd=`{"cookie":…}`）；登录=追加账号（自动标签 pt_pin/unb/pdd_user_id/手机号掩码）；同步逐账号、合并按 mailNo 去重（先绑定优先）；详情按 `item.accountId` 找归属账号（`Store.accountForItem` 回退第一启用）；旧单账号键首次读自动迁移成第一个绑定并清理。

## 接手路线

1. 读 [ARCHITECTURE.md](ARCHITECTURE.md)（代码地图，注意 SyncEngine/各 Fetcher/Resolver 的职责边界）。
2. 读 [API.md](API.md)（四源接口 + 签名细节，这是项目根基）。
3. 读 [THEME.md](THEME.md) 与 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。
4. 本地跑通：`cd app && ./gradlew :app:assembleDebug`，安装到手机；设置里可分别登录小米/京东/淘宝/拼多多，主界面下拉同步（或 `--es skip_channels xiaomi,jd,taobao,pdd` 调试组合）。
5. 改动前先确认设备在线（`adb devices`），日志：`adb logcat -s SyncEngine:I JdListFetcher:I JdTrackFetcher:I TbOrders:I CaiNiaoResolver:I GoodsPres:I PddListFetcher:I PddTraceFetcher:I PddLogin:I`。

## 外部资源

- GitHub 私有仓库：`https://github.com/Halo0sama/ExpressAssistant.git`（origin）与 `ExpressAssistant-backup.git`（backup）
- GitHub Token 文件（勿写入仓库）：`/Users/halo/Documents/Codex/2026-08-04/sui-ib-internalbeyond-https-github-com/work/gh_token.txt`
- 测试手机：Redmi `25102RKBEC`（序列号 `bb5ab72d`），已升 **Android 17 / HyperOS 4.0**；无线调试常用 `adb connect 10.176.58.121:5555`（手机热点场景），系统更新后无线端口会变、需重连
- 设备数据：四渠道共 31 件（小米 8 / 京东 18 / 淘宝 11，合并去重后；拼多多件数待实测补录），是开发回归的天然样本；不要随意清空
- 京东/淘宝/拼多多登录 cookie 与小米 token 都存在 `express_store` SharedPreferences（迁移后为 `jd_accounts` / `tb_accounts` / `pdd_accounts` / `xiaomi_accounts` JSON 列表；旧键 `jd_cookies`/`tb_cookies`/`pdd_cookies`/`xiaomi_token` 首读后清理）

## 注意事项

- 不要在文档/仓库里写入用户 API Key、小米 token、京东/淘宝 cookie、GitHub token。
- 改设置相关代码时，遵循"二级菜单 = 上弹卡片 + 统一选项行"的现有约定。
- 发布前记得清理调试日志与测试数据（`FAKETEST*` 快递已被代码层过滤）。
- 四渠道同步会真实访问京东/淘宝/拼多多接口，频率别调太高（京东订单中心有风控，触发挑战会让用户看到验证页——这是设计行为，不是 bug）。
