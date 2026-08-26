# 接口与集成说明（四源）

## 一、小米逆向接口（优先级最高的渠道）

### 登录
- `XiaomiLoginActivity`：WebView 打开 `XiaomiPassport.getLoginUrl()`，完成登录后 STS 跳转写入 Cookie
- 从 `https://api.assistant.miui.com` 的 Cookie 取 `assistant_serviceToken`（旧版 `serviceToken` 兜底），配合 `cUserId`
- `AdvertisingIdHelper` 探测 OAID/VAID，随请求发送
- 令牌存 `xiaomi_token` / `xiaomi_cuser` / `xiaomi_account_id` / `xiaomi_oaid` / `xiaomi_vaid`

### 请求封装（XiaomiApi）
- HOST：`https://verca.xpa.assistant.miui.com`
- AES-ECB 加密路径：key `d101b17c77ff93cs`
- 签名：`SHA1("yellowpage_encparam" + enc + "77eb2e8a5755abd016c0d69ba74b219c").uppercase`
- URL：`path?version=<PA版本号>&appkey=yellowpage&yellowpage_encparam=...&sign=...&_encparam=...`
- Header：`Cookie: serviceToken=...; cUserId=...`，Body 为 `MiuiCrypto.encode(wrapBody(...))`
- 响应 `data` 字段加密，需 `MiuiCrypto.decode` 后才是业务 JSON

### 端点
| 端点 | 用途 | 关键入参 |
|---|---|---|
| `/cpa/express/v2/getList` | 全量列表 | phones, limit, deletedMailNos, modifiedMailNos |
| `/cpa/express/v2/query` | 物流详情 | cpCode, mailNo, name, provider, stateNum, logisticsUpdateTime, phone, queryChannel, channel |
| `/cpa/express/matchCompany` | 手动添加时识别公司 | mailNo |
| `/cpa/express/phone/sendVerificationCode` | 绑定手机号 | phone |
| `/cpa/express/phone/checkVerificationCode` | 校验验证码 | phone, verificationCode |
| `/cpa/express/phone/bind` | 绑定 | type, phone, phoneList |

### 返回结构要点
- getList：`data.expressList[]`，每项含 `jumpList`（电商深链数组：`{"link": "...", "type": "app|h5"}`）
- v2/query：`data.details[]`（time/desc）、`stateNum`；其 `jumpList` 常常为空
- **注意**：小米接口**不含商品名/图**（字段只有 mailNo/cpCode/name 通用名/state/details/jumpList/iconUrl），商品溯源必须走下方京东/菜鸟渠道

## 二、京东渠道（登录态 = `jd_cookies`，pt_key/pt_pin）

### 登录
- `JdLoginActivity`：加载 `plogin.m.jd.com`，轮询 CookieManager 抓 `pt_key`/`pt_pin`；**加载前先清空旧京东 cookie**（否则会复用死会话产生假登录）

### 订单列表（order_list_m）
- SPA：`https://wqs.jd.com/order/orderlist_merge.shtml` → 重定向 `trade.m.jd.com/order/orderlist_jdm.shtml`
- API：`https://api.m.jd.com/client.action?functionId=order_list_m&appid=m_core&loginWQBiz=golden-trade&body={...curTab,page,pageSize,keyword...}`
- **带 h5st 签名（body 绑定、时间敏感），无法自算**——实现方式是：隐藏 WebView 加载订单中心 + document-start 注入 JS 改写 JSONP `callback`（覆盖 `HTMLScriptElement.src` setter）+ XHR/fetch 钩子，响应写入 `window.name`，Kotlin 轮询读取（见 `JdListFetcher`）。不要用 OkHttp 转发这类请求。
- 响应：`body.orderList[]`：`orderId`、`orderStatusInfo{orderStatusName, originOrderStatus}`、`progressInfo{content 最新轨迹, tip 时间, progressLink 物流页完整URL}`、`wareInfoList[]{wareName, imageUrl, num, skuId}`、`shopInfo{shopId}`、`orderDetailLink.url`
- 翻页：`dispatchTouchEvent` 滑动触发 SPA 无限滚动加载

### 物流轨迹（deal_wuliu_jdm）
- URL 必须带完整参数：`?from=orderdetail&dealState=<originOrderStatus>&dealId=<orderId>&orderType=<orderType>&skuid=<skuId>&shopid=<shopId>`（无参是空 SPA）
- DOM 结构：描述行在上、时间行（`yyyy-MM-dd HH:mm:ss`）在下；解析见 `JdTrackFetcher.parseDetail`
- 搜索结果页（单号 → 订单）：`trade.m.jd.com/order/orderlist_jdm.shtml?orderType=search&searchKey=<订单号>`，商品从 DOM/短名缓存取

### 风控
- 会话被挑战时页面跳 `plogin/nopasswordcmcc`；`JdListFetcher` 会把 WebView 显示出来让用户完成验证后自动继续
- 订单中心接口频率过高会 601；`errorCode:302 未登录` = cookie 没覆盖到 `api.m.jd.com` 域

## 三、淘宝 / 菜鸟渠道（登录态 = `tb_cookies`）

### 登录
- `TbLoginActivity`：加载 `login.m.taobao.com/login.htm`，轮询抓 `cookie2`+`unb` 等，存 `.taobao.com` cookie

### mtop 通用签名（wapSign）
- 刷新 token：GET `https://acs.m.taobao.com/h5/<api>/<v>/?appKey=12574478`（带 Origin: https://page.cainiao.com 等头），响应 Set-Cookie 里取新 `_m_h5_tk`
- `sign = md5(token & t & appKey & data)` 小写 hex；`token = _m_h5_tk` 下划线前部分；`t` 毫秒时间戳；**`data` 为原始 JSON（未 URL 编码）**
- 请求 URL：`...?jsv=2.3.18&appKey=12574478&t=<t>&sign=<sign>&type=originaljson&data=<urlencode(data)>`
- Cookie 手动管理（显式 Cookie 头 + 每次从 Set-Cookie 替换 `_m_h5_tk`/`_m_h5_tk_enc`），**不要用 CookieJar**（双 `_m_h5_tk` 会被当游客）

### 端点
| 端点 | 用途 | 关键入参 / 返回 |
|---|---|---|
| `mtop.taobao.order.queryboughtlistv2/1.0` | 订单列表 | data={tabCode:all, page, appName:tborder, condition:...}；JSONP 包裹 `callback(...)`，`data.result` 是 JSON 字符串，内层 `mainOrders[]`：`id`、`extra.tradeStatus`、`statusInfo.text`（物流状态）、`subOrders[0].itemInfo{title, pic}` |
| `mtop.taobao.logisticstracedetailservice.queryalltrace/1.0` | 物流详情+商品 | data={mailNo, appName:GUOGUO, actor:RECEIVER, isShowItem:true, ...}；`data.result[0]`：`fullTraceDetail[]{time, desc}`、`packageItems[]{goodsName, itemPic, goodsQuantity}`（**需买家登录态**，游客 `isBuyer=false` 只有轨迹）、`extPackageAttr.isBuyer` |
| SSR 物流页（HTTP GET，免签名） | 轨迹+运单号 | `https://pages-g.m.taobao.com/wow/z/app/mtb/logisticsV2/h5-detail?x-ssr=true&bizOrderId=<订单号>`；HTML 里 `__ICE_SUSPENSE_LOADER__['undefined'] = {...}`：`result.data.newLogistics.fields{logisticCompany{mailNo,name}, multiStage[]{title, subtitle, labelDesc{richContent[]{text}}}` |

### 商品 / 取件码
- 商品预缓存：订单列表 `subOrders[0].itemInfo`（title + pic，pic 需补 `https:` 前缀）
- 取件码：`GoodsPresentation.pickupCodeFrom` 正则 `(取件码|取货码|提货码|自提码|驿站码|取件验证码)[:：\s]*([A-Za-z0-9][A-Za-z0-9-]{1,12})`

## 四、AI（DeepSeek）

- 默认 base：`https://api.deepseek.com`，模型：`deepseek-v4-flash`；设置 → 云雀 可改
- `AiClient.ask`：OpenAI 兼容 `/chat/completions`，temperature 0.3，支持 tools 循环
- 进度/ETA：`computeProgress` 输出 `{"progress": 0-100, "eta": "M月d日送达 或 空"}`；运输中返回 0 判无效
- **商品短名**：`GoodsPresentation.batchShorten` 批量（每批 8 个）让 AI 把长名改写成"厂商/品牌+型号+产品名"≤12 字（提示词明确保留型号如 MM3A），无 key 时规则兜底（去【】（）括号、截 12 字）
- 日报：`DailyReporter` 只把在途件交给 AI

## 五、本地接口（CLI / MCP）

手机内 `ApiServer`（NanoHTTPD）监听 `127.0.0.1:8765`：

```bash
adb forward tcp:8765 tcp:8765
python3 tools/express-cli.py list / detail / sync / track / rename / export / mcp ...
```

## 六、快递100 与无障碍（兜底）

- 快递100：设置 → 更多连接方式 里填 key/customer，用于手动添加识别公司与轨迹兜底
- `ExpressImportService` 无障碍导入，默认关闭

## 七、拼多多渠道（第四数据源，source=pdd）

> 登录态 = `pdd_cookies`（`PddLoginActivity` 抓取的拼多多 H5 Cookie 串）。拼多多 H5 的 proxy 接口要求 `anti_content` 等动态签名（由页面 JS 生成），
> 因此不在 App 内自算签名：与京东同思路——隐藏 WebView 加载页面、注入 JS 钩子读响应（`PddCapture.HOOK_JS`），Kotlin 只做容错解析。
> 字段名随拼多多版本有差异，实现采用「候选键 + 递归扫描」；以下为当前实现依据，**真机抓到的具体接口与字段以 `docs/PDD_REAL.md` 现场记录为准**。

### 登录
- `PddLoginActivity`：WebView 打开 `https://mobile.yangkeduo.com/login.html`，登录前先清空拼多多域旧 Cookie（防假登录；**已有 `PDDAccessToken=` 则不清**，避免重开页面自毁会话），
  轮询 `CookieManager.getCookie("https://mobile.yangkeduo.com")`，抓到 `PDDAccessToken` + (`pdduid`/`PASS_ID`/`pdd_user_id`/`pdd_user_uin`) 即视为登录成功，全量 Cookie 串存 `pdd_cookies`
  - **必须拦截 App deep link**：页面 JS 会强制跳 `pinduoduo://com.xunmeng.pinduoduo/index.html`；`shouldOverrideUrlLoading` 对 `pinduoduo://`/`xunmeng://` 返回 true 后留在 H5，可用「手机登录」（手机号+短信验证码）
  - 实测 Cookie 键：`PDDAccessToken`、`pdd_user_id`、`pdd_user_uin`、`pdd_vds`、`_nano_fp`、`api_uid`、`jrpl`、`njrpl`、`dilx`、`webp`
  - 注：暂 `exported=true`（与京东/淘宝登录一致，便于 adb 启动验证；发布前收紧）
- 入口：设置 → 拼多多登录（登录 / 退出 / 重新登录）；退出只清拼多多域 Cookie，不影响京东/淘宝/小米会话

### 列表同步（PddListFetcher）
- 页面：`https://mobile.yangkeduo.com/orders.html`（拼多多 H5 买家订单页；React 无限滚动，空态文案「您还没有相关的订单 / 试试查看全部」）
- 机制：document-start 注入 `PddCapture.HOOK_JS`（XHR/fetch 拦截 `proxy/api|yangkeduo|pinduoduo` 的响应 → `window.name`），
  上滑滚动 + 尝试点击「加载更多 / 查看全部」翻页；成功页若跳转 login 则把 WebView 显示给用户完成安全验证后自动继续
- **实测接口**（页面 SDK 自动签名，无需自算 anti_content；2026-08-26 真机捕获）：
  - `https://mobile.yangkeduo.com/proxy/api/api/aristotle/order_list_v4?pdduid=<uid>` → 真实订单：`{"server_time":…,"orders":[…],"extra_info":{}}`（实测账号 `orders=[]`，账户当前无可展示 H5 订单）
  - `https://mobile.yangkeduo.com/proxy/api/api/caterham/v3/query/my_order_group?pdduid=<uid>` → `data.goods_list[]` = **精选推荐商品流（非订单）**，解析器容错下不会误解析
- 解析：递归扫描 JSON 找「订单对象」（含 `order_sn/orderId/mall_order_sn/…` 或 `tracking_no/mailNo/logistics_no/…` 标量键），
  候选键：运单号 `tracking_no|mailNo|mail_no|express_no|logistics_no|waybill_no|waybillCode|trackingNumber|logistics_sn|delivery_no`，
  商品 `goods_name|goodsName|goods_title|goods_brief|product_name|item_name|sku_name` + 图 `goods_image_url|image_url|thumbnail|pic|…`，
  状态词映射：签收/已完成→3、派送/配送→5、待发货→1、其余→0；自动跳过 取消/关闭/退款/售后/待付款 订单（`orders[]` 对象字段样例待有订单账号补，见 `docs/PDD_REAL.md`）
- 输出：`ExpressItem(source="pdd", provider="Pinduoduo", queryChannel=orderId)` + `JdGoods` 预缓存（保留已优化 shortName）
- 合并优先级：小米 > 京东 > 淘宝 > 拼多多（同运单号只保留高优先级；拼多多无运单号时以订单号占位，暂不做跨渠道去重）

### 物流轨迹 / 商品解析（PddTraceFetcher）
- 页面：`https://mobile.yangkeduo.com/order_detail.html?order_id=<orderId>`（若拼多多跳转到独立 logistics 页，按现场 URL 调整；**未实测**——本轮账号无订单，待回归）
- 同一隐藏 WebView + HOOK_JS + scheme 拦截；轨迹点 = JSON 中同时含「时间」与「描述」的对象（候选键见源码 `TIME_KEYS`/`DESC_KEYS`，含 `time/desc/context/status_desc` 等）
- 商品：同一次捕获里取 `goods_name` 等（`resolveGoods`），详情页「获取商品信息」按钮复用

### 已确认事实（真机实测 · 详见 docs/PDD_REAL.md）
- [x] 登录页可 H5 完成（手机号+验证码）；需拦截 `pinduoduo://` deep link；Cookie 键见上
- [x] 订单页 `orders.html`；`order_list_v4.orders[]` 为订单来源（字段含 `order_sn/order_status_prompt/order_goods[]/order_link_url/tracking_number(空)/after_sales`）；
  `express/track/status` 为 **order_sn→GOT/SIGN 码表（必须排除）**；`my_order_group.goods_list` 为推荐流（排除）
- [x] 状态名取 `order_status_prompt`（中文），数值 `status/order_status` 仅作兜底；状态映射见 PDD_REAL
- [x] 物流轨迹链路：`order.html?order_sn=…` → 点击「查看物流」→ **`goods_express.html?tracking_number=…`（真实运单号在此）** → **DOM innerText 解析**（状态+时间粘连行 + 描述行；推荐流到 `即将恢复/本店已拼/价格` 即停止；离开订单页后不再点击避免跳客服聊天页）
- [ ] 运输中订单的多节点轨迹样例行（本批全部已签收，PDD Web 只渲染最终节点）

## 八、多源绑定（v0.5.0）：每平台可绑定任意数量账号

> 设计：`Store.accounts(channel)` 返回账号列表 `BoundAccount{id,label,enabled,payload}`；payload 为平台私有 JSON：
> xiaomi = `XiaomiCred{token,cUser,accountId,oaid,vaid,phones[]}`；jd/tb/pdd = `{"cookie":"…"}`。

- **绑定**：所有登录 Activity 的「登录成功」= **追加一个新账号**（`addJdAccount/addTbAccount/addPddAccount/addXiaomiAccount`）；标签自动生成：
  京东 `pt_pin`（如 `jd_VNuAdf…`）、淘宝 `unb` 尾号、拼多多 `pdd_user_id` 尾号、小米手机号掩码（`192****22`）
- **管理**：设置 → 各平台行 → 账号面板：改名 / 启用·停用开关 / 移除（确认框）、「＋ 绑定新账号」；每个账号独立启停
- **同步**：SyncEngine 同平台内逐账号拉取（失败互不影响），跨平台并行；合并按 `mailNo` 去重（小米 > 京东 > 淘宝 > 拼多多，同平台先绑定账号优先）；
  `ExpressItem` 新增 `accountId/accountLabel` 归属标注（详情页头部显示「来自绑定：{label}」）
- **详情/溯源**：按件归属账号取凭证（`Store.accountForItem`：先按 item.accountId 找，找不到回退该平台第一个启用账号）→ 传入 `fetchWith/resolveWith` 系列接口
- **迁移**：旧版单账号凭证（`jd_cookies/tb_cookies/pdd_cookies/xiaomi_token` 等）首次读取时迁移为第一个绑定并清理旧键；旧 getter 仍可用（回退第一个启用账号）
- 实测（Redmi，2026-08-26）：四平台旧账号全部迁移出自动标签；注入第二个京东账号后同步出现两次抓取（36 原始件）→ 去重后 18 件，归属正确；详情页标签实测可见

## 九、多地址 / 快递指定地址 / 删除确认（v0.5.0 附加改造）

- **多地址**：`Store.addresses()` 返回 `List<HomeAddress{id,label,address}>`（键 `addresses`），`active_address_id` 标记当前地址；
  旧单地址 `home_address` 首次读取迁移为「默认地址」并清理；设置 → 我的地址：新增/编辑/删除（二次确认）/点行切换当前
- **快递指定地址**：`ExpressItem.addressId`（空 = 使用全局当前地址）；长按快递卡 → 「指定地址」选择器（使用全局默认 / 逐个地址，当前项打 ✓）；
  `Store.addressForItem(item)` 供 AI 进度/ETA 取值（件指定优先，未指定回退当前地址）；详情页显示「收件地址：{label}（已指定/全局默认）」；同步合并保留 `addressId`
- **删除二次确认**：地址删除（新）、移除绑定账号、移除快递、清空对话（双重确认）均已确认；「删除的快递」为恢复操作不设确认
- 手机号管理已并入「小米登录」账号面板：每账号独立管理（`Store.updateXiaomiPhones(accountId, phones)` 写该账号 payload，修复旧键断链）；设置页独立行已移除
