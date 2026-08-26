# 踩坑记录与排查指南

## 无线 adb 连不上
- 症状：`adb connect` 超时 / No route to host
- 常见原因：路由器“客户端隔离”（Mac 能到网关、到不了其他客户端）；或双方不在同一网段
- 解决：手机开热点，Mac 连热点后 `adb connect <网关>:5555`（网关即手机 IP）

## 主题切换后主界面不变
- 已修复：MainActivity.onResume 对比 `Themes.current`，变了自动 `recreate()`
- 其他已打开页面同理：切换主题后重新打开即生效

## 纸纹把卡片撑出大片空白
- 原因：BitmapDrawable 的固有尺寸（500px）把 wrap_content 卡片最小高度撑大
- 解决：`TiledTextureDrawable` 返回 `getIntrinsicWidth/Height = -1`、`getMinimumWidth/Height = 0`

## AI 运输进度显示 0%
- 原因：旧版本把 AI 误判的 0 存下且 `aiProgressAt` 为空，永不重算
- 解决：`displayProgress()` 把运输中的 0 视为无效；`aiProgressAt` 为空/过期强制重算；提示词约束运输中必须 > 0

## 定时日报不弹
- 设计如此：没有在途快递时不生成、不通知、不弹卡片；过期 pending 也会被清掉

## 日报期数不对
- 期数是真实的：每生成一篇 +1（`report_issue`），首次生成记录 `report_first_date`；不是按日期算的假刊号

## SharedPreferences 被清空/只剩 items
- 根因：手工编辑 XML 后结构不合法，App 启动解析失败 → 回退 seed（空列表）并覆盖保存
- 预防：编辑前 `am force-stop`；改完必须用 XML 解析器校验；不要用字符串替换删元素（容易留残片）
- 恢复：项目开发期在 /tmp 或备份里保留合法 XML 副本

## 小米登录报“R:S:OK”或登录态不完整
- 历史问题：新版登录页不再走旧 mint 流程；当前实现改为 WebView STS 跳转后取 `assistant_serviceToken`
- 若仍失败：清 Cookie（设置里退出登录）后重试

## 小组件显示“数据错误”
- 原因：曾用 RemoteViewsService/ListView 填充，小米桌面 fill-in 丢失数据
- 解决：每格独立 RemoteViews + 独立 PendingIntent，动态网格直接写在 provider 里

## 通知只显示两行
- 通知用 BigTextStyle，正文两段：摘要（在途 N 件 · 派送中 X · 预计）+ 日报预览前几行

## 京东同步 0 件 / 卡片跳京东登录页
- 先看日志：`adb logcat -s JdListFetcher:I`。响应 `errorCode:302 未登录` = cookie 没覆盖到 `api.m.jd.com` 域（`CookieManager.setCookie` 是 host-only，必须把 www/trade/wqs/api/jingfen 全注入）
- 跳 `plogin/nopasswordcmcc` = 会话被风控挑战：`JdListFetcher` 会把 WebView 显示出来让用户完成一次验证（短信码），完成后自动继续——这是设计行为
- **假登录**：登录页复用残留旧 cookie，轮询秒判已登录并保存死 `pt_key`。`JdLoginActivity` 现在登录前清空旧 cookie；若 Mac 直测 `passport.jd.com/user/petName/getUserInfoForMiniJd.action` 返回 302，说明 pt_key 已死，需重新登录

## 京东商品溯源/详情失败
- `deal_wuliu_jdm.shtml` 无参时是空 SPA，必须带 `from=orderdetail&dealState&dealId&orderType&skuid&shopid` 完整参数（列表同步时已拼好存 `queryChannel`）
- 轨迹 DOM 是“描述在上、时间在下”；解析见 `JdTrackFetcher.parseDetail`，别用“时间开头”假设

## 菜鸟/淘宝接口报 非法请求 / 被挤爆
- `FAIL_SYS_ILLEGAL_ACCESS`：mtop wapSign 的 `data` 必须是**原始 JSON（未 URL 编码）**参与 `md5(token&t&appKey&data)`；token 取 `_m_h5_tk` 下划线前部分
- `FAIL_SYS_USER_VALIDATE / RGV587_ERROR::SM`：token 请求缺 Origin/Referer 头（`Origin: https://page.cainiao.com`）
- 双 `_m_h5_tk`：OkHttp CookieJar 会与显式 Cookie 头合并——手动管理 Cookie（Set-Cookie 提取替换），别用 CookieJar
- 商品字段（packageItems）只有买家登录态才返回：游客 `extPackageAttr.isBuyer=false` 只有轨迹

## 卡片短名被冲掉 / 显示长名
- 原因：渠道同步预缓存商品时 `putAll` 覆盖了 `shortName`
- 已修复为保留旧 shortName 合并；若仍空白，等同步后的后台 `optimizeShortNames`（每批 8 个，约 1-4 分钟）完成再看

## 同步并发互相覆盖
- 自动同步与手动下拉会并发跑：`SyncEngine` 已用 Mutex 串行化；新代码勿在 sync 外直接写 `Store.saveItems`

## 调试日志
- 网络：`adb logcat -s ExpressApi:I`
- 日报：`adb logcat -s ExpressReport:D`
- 四源同步：`adb logcat -s SyncEngine:I JdListFetcher:I JdTrackFetcher:I TbOrders:I CaiNiaoResolver:I GoodsPres:I PddListFetcher:I PddTraceFetcher:I PddLogin:I`
- 调试登录组合：`adb shell am start -n com.halo.expressassistant/.ui.MainActivity --es skip_channels xiaomi,jd,taobao,pdd`（逗号分隔要跳过的渠道，不真正退出登录）
- UI：`uiautomator dump` + 截图
