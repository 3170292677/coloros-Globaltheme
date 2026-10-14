# System UI Hook

一个面向 **ColorOS 16（SystemUI 16.00.12）** 控制中心深度定制的 **Xposed / LSPosed 模块**。

模块运行在被 hook 的 SystemUI 进程中，作用域固定为 `com.android.systemui`，通过拦截 OPlus 私有类实现：
按形状替换控制中心磁贴 / 卡片的底图、圆角、图标与文字颜色，替换控制中心面板背景，长按磁贴或空白处弹出模块自己的样式 / 背景面板，并支持从 ColorOS 全局主题包一次性导入整套界面底图。

> 适配范围很窄：模块大量依赖 ColorOS / OPlus 的私有类名、方法名与字段名，仅针对 ColorOS 16 / SystemUI 16.00.12 反编译核对过。其它 ROM 或版本需自行核对签名（见下文「契约校验」）。

## 功能特性

### 入口与弹窗

- 在控制中心右上角（✎ 按钮左侧）注入一颗自定义 **★ 按钮**，样式直接复制系统 ✎ 按钮的 Drawable 与尺寸。可在模块设置页关闭「让星星按钮可见」（关闭后控制中心不再显示 ★，磁贴长按样式面板也就进不去）。
- 点 ★ 弹出配置面板，包含：
  - **编辑模式**（总开关）：打开后所有形状的磁贴长按都由模块接管，弹出样式面板；关闭则恢复系统原本的长按行为（打开设置 / 应用）。
  - **控制中心背景长按**：拦截控制中心空白处长按，改弹背景面板（默认开启）。
  - **压掉背景模糊**：关闭控制中心背景层上的模糊（默认关闭，即保留系统模糊）。
  - **导入主题包**、**强制重启系统界面**。

### 磁贴 / 卡片底图与样式

按磁贴的**真实形状**分组，共 9 个形状：

| 形状 | 标识 | 说明 |
| --- | --- | --- |
| 1×1 圆形磁贴 | `1x1` | 手电筒 / 定位 / 飞行模式等 |
| 1×1 圆形小磁贴 | `entrance` | 控制中心右上角那排小圆钮（注入的 ★ 与系统自带的 ✎ / ⚙ / ⋮），单独一组，不跟 1×1 磁贴共用配置 |
| 1×1 方形卡片 | `card` | ColorOS 自带的「耳机」「设备空间」等 deviceplugin 卡片 |
| 2×1 宽卡片 | `2x1` | WLAN / 蓝牙这类大卡片 |
| 2×1 三阶段卡片 | `three` | 铃铛 / 震动 / 静音这类一张卡三个图标 |
| 2×2 媒体卡片 | `2x2` | 媒体大卡片 |
| 通知卡片 | `notification` | 通知栏里的通知背景 |
| 清除按钮 | `clear` | 通知栏底部「清除全部」圆形按钮 |
| 亮度 / 音量胶囊 | `slider` | 竖向亮度 / 音量条 |

同一形状的磁贴共用一份配置，长按磁贴弹出该形状的**样式面板**，可调：

- **开 / 关** 两套配置的分段切换（仅 1×1 圆形磁贴保留两套；其余形状没有开 / 关两种观感，已合并成一套）。
- **圆角**：0–40dp 滑杆，最左为「跟随系统」（1×1 磁贴、1×1 圆形小磁贴与清除按钮保持正圆，其余按系统实测圆角）。
- **可见度**：0–100%（100 = 完全遮住系统底图）；分套的形状开 / 关各一套。
- **底图**：「从相册选择底图」/「清除底图」（选图走模块的透明入口，用户只看到相册，不会看到模块界面弹到前台）。
- **图标底色**、**图标不透明度**：仅 2×1 宽卡片与 2×1 三阶段卡片有这项（图标底下压着一块小色块）。
- **图标颜色 / 图标着色（激活）/ 文字颜色**：各自「挑颜色 + 不透明度滑杆」。三阶段卡片有两副面孔，面板里同时出现「图标激活」与「图标着色」；其余形状只有一项「图标颜色」。亮度 / 音量胶囊没有标签文字，不出现文字颜色项。
- 亮度 / 音量胶囊额外分「**背景** / **进度条**」两页：背景页管圆角、胶囊可见度与胶囊底图；进度条页管进度块自己的可见度与进度底图，两份可见度互不影响。
- 面板底部附一行调试状态（读到配置、hook 命中、套用情况），便于排查「选了没效果」。
- 方形设备卡片是构建时取色的，改完需重启系统界面才看得到（面板内有提示）。

### 控制中心面板背景

长按控制中心**空白处**（默认可弹）后弹出背景面板：

- **选择 / 更换 / 清除背景图片**（相册）。
- **背景颜色** `#AARRGGBB`：没设图片时看到的就是这个纯色；设了图片则垫在图片底下，透明处透出颜色。
- **背景可见度** 0–200% 滑杆（0% = 完全透明、交还系统，100% = 完全不透明，200% = 叠两层补满），拖动即时生效。
- 背景层 alpha 跟随系统 QS 蒙层进度，面板退场时机与系统动画一致。

### 主题包导入

在设置页或 ★ 弹窗里「导入主题包」，选一个 ColorOS 全局主题包（那个以 `com.android.systemui` 命名的子包，完整的 `*.theme` 与直接下钻的子包两种形态都认），一次把界面底图按形状套到控制中心上：

- 覆盖 **10 个素材落点**：1×1 圆形磁贴（开 / 关各一张）、1×1 圆形小磁贴（右上角那排小圆钮）、2×1 宽卡片、2×1 三阶段卡片、2×2 媒体卡片、1×1 方形卡片、通知卡片、清除按钮、亮度 / 音量胶囊。
- 同时读取包内 `assets/colors.xml` 的颜色：**图标底色**（`status_bar_qs_tile_bg_color_active`）、**图标着色**（激活 / 未激活）、**文字颜色**、**通知材质背景色**（控制中心背景纯色）。
- 自动量取素材自带的圆角比例，按磁贴实际宽度换算，避免素材圆角被模块默认 20dp 裁掉。
- 10 条底图先静默写入、最后统一重启一次系统界面。

### 设置页（模块 App）

外观仿 LSPosed 管理器的卡片式布局（代码搭建，不引任何 UI 库），底部「首页 / 我的」两栏导航：

- **LSPosed 卡**：图标 + 名称 + LSPosed 版本 + 「● 模块已激活 / 未检测到模块」状态点，以及框架版本、作用域、模块版本三行。
- **星星按钮卡**：「让星星按钮可见」开关。
- **导入主题包卡**：选择主题包（zip）。
- **重置所有配置卡**：清空全部开关、每磁贴配置与按形状的自定义底图。
- 顶部固定栏右侧一颗「**强制重启系统界面**」按钮。
- 「我的」页显示作者信息（酷安主页）。

模块激活状态由「模块设置页发 `PING` → SystemUI 回 `PONG`」探测；跨进程 `XSharedPreferences` 不可靠，所有开关、底图与颜色都通过定向广播送进 SystemUI 自己的 prefs。

## 使用方法

1. 设备需已 **root**，并安装 **LSPosed** 框架。
2. 安装本模块 APK。
3. 打开 LSPosed，在模块列表中启用 System UI Hook，并把**作用域勾选为 `com.android.systemui`**。
4. 重启**系统界面**（设置页顶部那颗「强制重启系统界面」按钮，或直接重启手机）。
5. 打开模块设置页，主页状态点应显示「● 模块已激活」；未显示说明模块没注入进 SystemUI。
6. 下拉控制中心：
   - 点右上角 **★** 打开配置面板，按需打开「编辑模式」；
   - **长按磁贴 / 卡片**（需先开「编辑模式」；清除按钮与亮度音量胶囊默认已接管）弹出该形状的样式面板；长按右上角那排小圆钮（★ / ✎ / ⚙ / ⋮）弹出「1×1 圆形小磁贴」面板，是否接管同样跟随「编辑模式」；
   - **长按空白处**弹出控制中心背景面板；
   - ★ 面板里可「导入主题包」或「强制重启系统界面」。

## 构建

### 离线方式（推荐）

只依赖 Android SDK（`aapt2 / d8 / zipalign / apksigner`）与 JDK（`javac / keytool`），无需下载 Gradle / AGP 依赖：

```powershell
powershell -ExecutionPolicy Bypass -File build_apk.ps1
# 可选参数：-Sdk C:\Android\Sdk
# 产物：build\SystemUIHook.apk
```

- 自动使用 `keystore\debug.keystore` 签名；该文件不存在时会用 `keytool` 自动生成。
- 脚本流程：`aapt2 compile/link` → `javac` → `d8` → 写入 `classes.dex` → `zipalign` → `apksigner`。
- 为避免中文路径导致 SDK 原生命令行工具失败，源码会先暂存到 `%TEMP%\SystemUIHookBuild` 再编译。

### 常规方式

```bash
gradlew assembleDebug
```

需要联网拉取 AGP 7.4.2 等依赖。

## 契约校验

`tools/verify_hook_contract.py` 用于**防止 OPlus 私有名变更导致 hook 静默失效**：hook 源码里硬编码的 `com.oplus.*` 类名、方法名、字段名，一旦与真实 SystemUI 对不上，hook 在设备上会「什么都不做」且很难察觉。脚本会：

1. 从 `TileBackgroundHook.java` 重新解析出用到的类名 / 方法名 / 字段名；
2. 逐个到 SystemUI dex 中核对类是否存在、方法 / 字段能否在该类或其父类上解析；
3. 核对已构建 APK 的 `classes.dex` 里所有 `com.oplus.*` 字面量都能在 SystemUI dex 中「定义或引用」到。

**它需要自备反编译后的 SystemUI dex 目录**（含 `classes*.dex`）。该目录是厂商版权产物，**本仓库不包含**，请用命令行参数指定：

```bash
python tools/verify_hook_contract.py --systemui <反编译后的 SystemUI dex 目录>
```

- `--systemui` 不传时，默认在**项目同级目录**找 `系统界面_16.00.12_unpacked`；找不到会打印提示并以退出码 2 退出。
- 依赖 Python 库 `androguard`。
- 退出码：`0` = 契约成立，`1` = 发现不匹配，`2` = 未找到 SystemUI dex 目录。
- 校验报告写到项目根目录 `verify_hook_contract_report.txt`（已被 `.gitignore` 忽略）。

## 目录结构

```
system-ui-hook/
├── app/
│   ├── build.gradle                     # minSdk 21 / targetSdk 33 / compileSdk 33
│   ├── libs/xposed-api.jar              # 本地 Xposed API（compileOnly）
│   └── src/main/
│       ├── AndroidManifest.xml          # Xposed 模块标记、作用域、透明选图别名
│       ├── assets/xposed_init           # Xposed 入口类名
│       ├── java/com/example/systemuihook/   # 模块全部源码（hook + 设置页 + 弹窗）
│       └── res/                         # 图标与主题
├── tools/
│   └── verify_hook_contract.py          # OPlus hook 契约校验脚本
├── build_apk.ps1                        # 离线构建脚本
├── build.gradle / settings.gradle       # 根 Gradle 工程（AGP 7.4.2）
├── gradlew / gradlew.bat / gradle/      # Gradle Wrapper
├── keystore/debug.keystore              # 离线脚本的签名库（缺失时自动生成）
└── README.md
```

主要源码（均在 `app/src/main/java/com/example/systemuihook/`）：`SystemUIHook`（入口）、`TileBackgroundHook` / `TileBackgroundDrawable`（底图）、`TileCornerHook`（圆角）、`IconColorHook` / `TileColorHook`（图标底色与着色 / 文字颜色）、`SliderHook`（亮度音量胶囊）、`NotificationHook`（通知卡片）、`ClearAllButtonHook`（清除按钮）、`QsBackgroundHook` / `QsBackgroundPopup`（控制中心背景）、`TileLongPressInterceptor` / `TileStylePopup` / `TileLongPressPopup`（长按拦截与弹窗）、`TileImageChannel`（图片广播通道）、`TileLongPressConfig` / `PrefKeys` / `HookPrefs`（配置）、`SettingsActivity`（设置页）、`ThemePackImporter`（主题包解析）、`QuickEntranceButtonHook`（★ 按钮注入）。

## 实现原理（底图替换）

基于反编译 `classes6.dex` 得到的真实签名：

```
QsViewBackgroundProxy                            底图代理基类
 ├── getQsTileViewBackgroundProxy(QsTileViewInfoProvider)   静态工厂
 ├── refreshViewBackground() / onBackgroundAttach()          底图刷新入口
 └── 字段 qsView  →  getBackgroundView()                      承载底图的 View
QsTileViewBackgroundProxyImpl                    磁贴底图代理
 ├── $GlobalThemeBaseQsTileViewBackground
 │     obtainDrawableFor{Active,Inactive,Unavailable}(Context) → Drawable
 ├── $GlobalThemeBitmapTileViewBackground        全局主题位图底图（主要替换目标）
 └── $GlobalThemeColorTileViewBackground         全局主题纯色底图
QsHighlightTileViewBackgroundProxyImpl           高亮（大尺寸）磁贴底图代理
QsStaticBackgroundProxy                          静态背景（媒体面板等）
OplusQSTileBaseView                              磁贴视图，字段 mBackgroundProxy
```

Hook 分三层，逐步扩大覆盖面：

1. **底图工厂层**：拦截 `obtainDrawableFor*` 的返回值，直接换成自定义圆角底图，主题位图不会应用到磁贴；
2. **纯色 / 混合色层**：覆盖 `NormalColorTileViewBackground`、`MixColorTileViewBackground` 等非位图分支；
3. **视图兜底层**：在 `refreshViewBackground` / `onBackgroundAttach` 之后给 `getBackgroundView()` 重新设置底图，并在磁贴视图 `onConfigurationChanged` 后主动触发刷新，保证深 / 浅色切换立即生效。

> 注意：并非所有底图类都声明了 `obtainDrawableFor*`（纯色系没有这些方法），因此第 1、2 层分别针对位图系与纯色系。目标类 / 方法是否合契约由 `tools/verify_hook_contract.py` 对真实 dex 做静态校验。

其余落点各有一条独立链路：磁贴圆角改 `QsViewOutlineProvider` 的 outline；图标底色改图标底下那块 `GradientDrawable`；图标着色 / 文字颜色改图标 `ImageView` 的 tint 与标签 `TextView`；通知卡片、清除按钮、亮度音量胶囊、控制中心背景都不是磁贴、没有 `View#setBackground` 这个落点，各自单独 hook。

## 已知问题

- `app/build.gradle` 里 `consumerProguardFiles 'consumer-rules.pro'` 指向的文件在仓库中**并不存在**，走 Gradle 路径时可能因此报错；推荐使用离线脚本 `build_apk.ps1` 构建。
- 契约校验脚本依赖反编译后的 SystemUI dex 目录，仓库不提供（厂商版权产物），需自备。
- 主题包导入时，`status_nodevice_card_bg_inactive` 是宽卡图，套到 1:1 的「1×1 方形卡片」上会被中心裁掉两侧，属已知取舍。
- 底图 / 圆角 / 可见度都是视图创建时套上去的，改完大多需要重启系统界面才完整生效（选底图与主题包导入会自动重启一次）。

## 免责声明

- 仅适配 ColorOS 16 / SystemUI **16.00.12**；其它 ROM、其它 SystemUI 版本类名可能不同，hook 可能失效甚至引起系统界面异常，需自行核对签名。
- 需要 **root + LSPosed**；不满足条件请勿安装。
- Hook 有风险：不当使用可能导致 SystemUI 崩溃、界面异常或系统不稳定，请自行评估并自行承担后果。
- 本模块与 OPPO / ColorOS / LSPosed 官方无关。

## 许可证

本项目采用 MIT 许可证。
