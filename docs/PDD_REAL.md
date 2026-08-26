# 拼多多渠道 · 真机验证记录（PDD_REAL）

> 本文件记录「拼多多渠道（第四数据源）」在**第三方真实设备**（Redmi 23113RKC6C / Android 16，仅拼多多登录，账号 pdduid=3908911224，实测 73 笔订单、有效 49~67 件）上的完整事实。
> 所有接口仅用于学习与本地聚合，遵守平台条款。字段样例以本文件的真实抓包为准。

## 1. 状态汇总（2026-08-26 实机更新）

- [x] 列表接口真实字段 & 解析修正（状态中文、跳过码表、商品图/数量、防误清）
- [x] 物流轨迹真实链路（order.html → 点击查看物流 → goods_express.html → DOM 解析）
- [x] 同步防误清保护（空/超时抓取保留旧数据）
- [ ] 运输中订单（多节点）轨迹样例行（本批全部已签收，PDD Web 只渲染最终节点；有运输中账号时再回填多节点样例）

## 2. 登录（不变）

- URL：`https://mobile.yangkeduo.com/login.html`；需拦截 `pinduoduo://` App deep link；「手机登录」手机号+验证码
- 登录态键：`PDDAccessToken` + `pdd_user_id`/`pdd_user_uin`/`pdd_vds`/`_nano_fp`/`api_uid` 等；全量 Cookie 存 `pdd_cookies`

## 3. 订单列表（PddListFetcher · 真实字段）

- 页面 `https://mobile.yangkeduo.com/orders.html`（React 无限滚动；为空 tab 时点「全部/查看全部」）
- 接口（页面 SDK 自动签名，HOOK_JS 捕获；**请求体实测**）：`POST proxy/api/api/aristotle/order_list_v4?pdduid=<uid>`
  - **请求体**：`{"type":"all","page":1,"origin_host_name":"mobile.yangkeduo.com","scene":"order_list_h5","page_from":0,"front_env":163,"pay_front_supports":[],"anti_content":"0asA…(SDK 签名,长)"}` —— 分页参数=`page`(从1)，**`anti_content` 为页面 SDK 动态生成的签名**
  - **纯数据可行性结论（2026-08-27 实测）**：① 无签名 GET `?pdduid=…` 只返回默认 14 单（`page` 被忽略）；② JS `scrollTop` 驱动只能到 ~41 单（PDD 虚拟列表靠 **touch 事件**触发增量加载）；③ 完整抓取（76→111→156 随滚动深度递增的历史订单，均真实 mailNo 去重）只能靠**触摸时序滚动**（swipeUp）+ 点「查看全部/查看更多」。故保留触摸滚动为主 + 首屏无签名 GET 作为快速种子 + HOOK_JS 已升级为同时记录**请求体 r**（REQ_BODY 日志）
  - PURE_FETCH_JS（种子）命中 `__pddFetch {done:true,pages:1,bodies:1}`；订单页真实标签：`搜索订单 | 全部 | 待付款 | 待分享 | 待发货 | 待收货 | 评价`（无「已完成/待评价」文本）
    - 对象字段（实测）：`type` `group_id` `group_order_id` `address_id` `order_sn`（=260729-1755… 订单号，无真实运单号）
      `status`(数字) `group_status` `order_status` `pay_status` `shipping_status` `comment_status` `combined_order_status`
      **`order_status_prompt`（中文状态提示，状态名优先取它）** `tracking_number`（**列表接口恒为空**）
      `order_link_url`（`order.html?order_sn=…`） `order_goods[]{goods_id,goods_name,goods_price,goods_number,thumb_url,spec}`
      `mall{mall_name}` `after_sales{after_sales_type,after_sales_status}` `price_desc`
    - 状态映射：prompt 含 签收/已完成/交易成功/待评价→3（完成）；派送/配送→5；待发货/未发货/待成团→1；其余→0；
      取消/关闭/退款/售后/待付款/未支付→跳过
  - `proxy/api/api/express/track/status?pdduid=` → `{"result_list":[{"order_sn":…,"status":"GOT"/"SIGN"/编码}]}` —— **状态码表，不是订单，必须排除**（否则 73 条码表覆盖真实订单）
  - `proxy/api/api/caterham/v3/query/my_order_group?pdduid=` → `data.goods_list[]` 精选推荐流（非订单，排除）
- 商品：`order_goods[0]` → JdGoods(goods_name/thumb_url/goods_number)；本轮 49 件卡片全部显示真实商品名+图 ✓
- **防误清**：某次抓取空/超时（页面偶发 0 件）→ 保留该通道旧数据，不覆盖列表（实测：超时空抓取后 items 保持 49）
- 已验证翻页指标：单轮抓取 16~67 件不等（React 虚拟列表加载时序差异），但对用户而言 0→N 与保值逻辑已兜住

## 4. 物流轨迹（PddTraceFetcher · 真实链路）

- 详情入口：`order.html?order_sn=<order_sn>`（来自 `order_link_url`）
- 流程：加载 order.html → 点击「查看物流」（DOM 中最小可见 物流 按钮）→ 跳转 **`goods_express.html?tracking_number=<真实运单号>&shipping_id=…&order_sn=…`**（**运单号在这里拿到，列表接口没有**）
  - 注意：页面自身会继续跳 `express_service.html` → `unique_logistic_chat.html`（物流客服聊天）——**一旦离开 order.html 就不要再点**，停留在 goods_express 页取 DOM
- **轨迹为 DOM 渲染**（不是可捕获 JSON）：`document.body.innerText`，结构：
  ```
  中通快递 : 79126955055649 复制  快递员：武毅  订单编号:… 收货地址:… 展开
  已签收2026-07-31 18:21:51（状态+时间粘连行）
  您的快件已送达，签收人：家门口，…（描述行，含电话/网点）
  展开
  （之后是 精选推荐 商品流 —— 解析到「即将恢复/本店已拼/价格」等即停止采集）
  ```
- 解析器要点：状态+时间粘连行切分时间与状态前缀；描述行优先（含 快递/送达/签收 关键词，即使含电话）；噪声行（复制/展开）跳过不清上下文；推荐流标记到达即落点并停止；续行只追加带物流关键词的行
- 本轮全部订单为「已签收」，PDD Web 只渲染**最终节点**（1 条）；运输中订单预期多节点，待回填

## 5. 回归结论

- 第三方设备「仅拼多多登录」全链路可用：登录→迁移→同步（0 件/空抓取不再清空）→ 列表真实商品名/图+中文状态 → 详情页真实轨迹+进度100%+取件码
- 已知边界：PDD Web 列表无真实运单号（详情页才有）；已签收单仅 1 个轨迹节点；翻页件数随加载时序波动
