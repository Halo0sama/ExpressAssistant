# 主题与外观系统

## 三种主题

| 主题 | 配色 | 字体 | 纸感 |
|---|---|---|---|
| 莫奈取色 | 系统动态取色（Monet） | 无衬线 | 无 |
| 温暖纸感 | 暖色固定调色板 | 衬线 | 有（米纸纹理） |
| 自定义 | 莫奈 / 温暖 任选 | 衬线 / 无衬线 任选 | 0–200% 可调 |

自定义还可开启“白天 / 黑夜分别设置”，昼夜各自独立配色/字体/纸感。

## 主题样式映射（Themes.apply）

`Store.theme()` ∈ {monet, lark, custom}，custom 按 `colorScheme + themeFont` 组合：

| 组合 | Style |
|---|---|
| monet + sans | `Theme.ExpressAssistant` |
| monet + serif | `Theme.ExpressAssistant.Serif` |
| warm + serif | `Theme.ExpressAssistant.Lark` |
| warm + sans | `Theme.ExpressAssistant.LarkSans` |

样式定义在 `app/src/main/res/values/themes.xml` 与 `values-night/themes.xml`。

## 纸感（Paper）

- 纹理素材：`drawable-nodpi/rice_paper.png`（来自 openhanako，Apache-2.0，见 THIRD_PARTY_NOTICES）
- 运行时用 `Paper.apply(activity, root, toolbar)` 把页面背景/工具栏/卡片替换为动态纹理
- `TiledTextureDrawable` 负责平铺，且固有尺寸为 0（否则 wrap_content 卡片会被撑高）
- 强度 0–200：纹理 alpha 与提亮补偿按强度缩放；0 表示完全关闭
- 日间纹理配暖白提亮；夜间低强度纹理无提亮

## 设置交互约定

- 所有入口行统一：圆角卡片 + 图标 + 标题 + 摘要 + `›`
- 点击弹出 `BottomSheetDialog`（上弹卡片），内容用 `Sheets.create` 构建
- 选项统一用 `Sheets.optionRow`（标题 + 右侧勾选框）
- 主题卡片支持原地展开自定义项；关闭卡片时才整体重建页面
- 主题摘要：`themeLabel()` 显示当前主题/自定义组合

## 小组件主题

- 两套布局：默认（无衬线）与 lark（衬线 + 暖色纸面）
- 选择逻辑：`theme == lark || colorScheme == warm` 时用 lark 布局
- 小组件不跟随纸感强度（待办项）
- RemoteViews 限制：不用裸 View/include/ListView，格子独立 PendingIntent
