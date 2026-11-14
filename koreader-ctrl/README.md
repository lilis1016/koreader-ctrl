# KOReader 手柄控制器（Android 原生版）

把手机变成 Kindle 的蓝牙手柄：通过蓝牙手柄按键，向 Kindle 上的 KOReader
HTTP Inspector 发送翻页 / 亮度 / 日夜模式等指令。

本项目由原始 HTML 版本（依赖浏览器 Gamepad API）重写为原生 Android 应用，
协议与默认按键映射**完全保持一致**。

---

## 版本 1.0.4 修复

- HTTP 指令改为单线程队列顺序发送，避免亮度增量事件并发交错；增加 `Cache-Control: no-store` / `Pragma: no-cache` 请求头。
- 后台服务在屏幕熄灭期间持有 `PARTIAL_WAKE_LOCK`，屏幕点亮后立即释放；该锁不会主动点亮屏幕。
- 设置映射时同时消费游戏手柄按键的 DOWN/UP，降低 D-pad 上下键被系统当作页面滚动的概率。
- 主界面改为「首页 / 映射 / 后台 / 工具」四个底部导航页面。
- 后台设置页增加图片选择入口，可创建使用自选图片的桌面快捷方式。Android 不允许普通应用在运行时直接替换已安装 APK 的应用图标；该功能创建的是自定义快捷方式。
- 版本号升级为 1.0.4（versionCode 5），保留原有配置存储、签名配置与 GitHub Actions 工作流。

> 锁屏输入仍受 Android 设备厂商的蓝牙 HID 与系统输入分发策略限制。屏幕熄灭期间的部分唤醒锁可以帮助 CPU 保持处理能力，但不能保证所有 ROM 都会把唤醒屏幕的那一次按键同时交给无障碍服务。若某设备仍只亮屏不翻页，需要以该设备实测为准。

## 一、KOReader HTTP Inspector 协议

普通动作使用：
```
GET http://<KINDLE_IP>:8080/koreader/event/<EventName>
```

亮度增加/减少使用广播入口：
```
GET http://<KINDLE_IP>:8080/koreader/broadcast/IncreaseFlIntensity/1
GET http://<KINDLE_IP>:8080/koreader/broadcast/DecreaseFlIntensity/1
```

亮度属于全局设备事件。广播入口通过 `UIManager:broadcastEvent` 分发，避免事件只送到当前顶层弹层；普通动作仍使用 `UIManager:sendEvent`。请求按单线程队列发送，亮度事件之间至少间隔 180ms，并带 `no-cache` 请求头。
原生应用不使用浏览器的 CORS 限制，因此能读取 HTTP 状态码与响应体；但 HTTP 200 只表示 HTTP Inspector 接收了请求，不代表设备物理灯光已被硬件确认。

### 事件名核实结果

以下事件名**逐一对照 KOReader 源码 `frontend/dispatcher.lua` 核实**，非推测：

| 功能 | EventName | 源码位置 |
|---|---|---|
| 上一页 | `GotoViewRel/-1` | dispatcher.lua:183 + readerpaging.lua:62（`args = -1`） |
| 下一页 | `GotoViewRel/1` | dispatcher.lua:183 + readerpaging.lua:61（`args = 1`） |
| 返回 / 退出 | `Close` | uimanager.lua:89,114（UIManager 广播） |
| 确认 / 菜单 | `ShowMenu` | dispatcher.lua:82 |
| 切换日/夜模式 | `ToggleNightMode` | dispatcher.lua:139 |
| 亮度增加 | `IncreaseFlIntensity/1` | dispatcher.lua:134（增量事件需要数值参数） |
| 亮度减少 | `DecreaseFlIntensity/1` | dispatcher.lua:135（增量事件需要数值参数） |
| 全屏刷新 | `FullRefresh` | dispatcher.lua:142 |
| 截屏 | `Screenshot` | dispatcher.lua:87 |

> **关于「游戏大厅」**：`ShowGames` 并**不在**上游 KOReader 中——上游 `plugins/`
> 目录没有 `games.koplugin`（已核实，完整列表见 KOReader 仓库）。
> 该功能依赖用户自行在 Kindle 上安装的第三方 games 插件及其远程接口。
> App 会发送 `ShowGames` 事件，但**若未安装该插件，Kindle 端不会有任何反应**。
> UI 中已就此给出提示，不会造成误解。

### 参数分隔符

HTTP Inspector 的 `getVariablesFromUri`（main.lua:324）以 `/` 分隔参数，
因此翻页方向写作 `GotoViewRel/1` 与 `GotoViewRel/-1`，与原 HTML 一致。

---

## 二、默认按键映射（与原 HTML 的 `defaultMap` 一致）

| 动作 | 输入码 | 说明 |
|---|---|---|
| 上一页 | `Gamepad_14` | 十字键 ← / 左肩键区 |
| 下一页 | `Gamepad_15` | 十字键 → / 右肩键区 |
| 返回 | `Gamepad_1` | B / 底部键 |
| 确认 | `Gamepad_0` | A / 底部键 |

映射可在 App 内**逐项重新绑定**，支持一个动作绑定多个输入码。

---

## 三、后台监听：能做什么，不能做什么

这是本项目最需要如实说明的部分。Android 对后台输入有明确的系统限制。

### 实际实现的两条路径

| 路径 | 生效范围 | 是否需要用户授权 |
|---|---|---|
| **Activity 窗口事件分发** | App 在前台时可接收按键与摇杆输入 | 否 |
| **Foreground Service** | 保持服务运行、常驻通知并刷新设备列表；它本身不能读取其它 App 的输入 | Android 12+ 启动连接设备类型服务前需授予蓝牙连接权限 |
| **AccessibilityService** `onKeyEvent` | 在其它 App 前台或部分锁屏场景下接收按键；不保证所有 ROM 都转发手柄事件，也不接收摇杆轴 | 是，需在「设置 → 无障碍」手动开启；服务声明已显式请求按键过滤能力 |

### 为什么不只有一个「后台监听」开关

Foreground Service 能保证**进程存活**与**通知常驻**，但它**不能**让应用在
其它 App 处于前台时抢到按键事件 —— Android 的输入分发是「焦点优先」。
在其它 App 前台时，应用可尝试通过 `AccessibilityService` 接收按键；是否能收到仍取决于 Android 版本、手柄驱动和厂商 ROM。Android 公共 SDK 不提供后台读取摇杆当前轴值的接口，因此摇杆仅在本 App 前台收到 `MotionEvent` 时可用。

因此 App 的「权限设置」按钮会直接跳转到系统无障碍设置页，并实时显示
当前监听模式（`辅助功能（全局）` 或 `前台窗口`）。

### 已知限制（如实列出）

1. 摇杆轴值只能从前台窗口收到的 `MotionEvent` 中读取；Android 公共 SDK 不支持通过 `InputDevice.MotionRange` 在后台轮询实时轴值。
2. 部分定制 ROM（MIUI / EMUI 等）即使开启无障碍，也不派发手柄按键事件，
   此时退化为前台模式。
3. 蓝牙手柄在 Android 上**没有稳定的硬件唯一 ID**。App 使用
   「型号名 + MAC 地址（若可得）」作为持久化标识；拿不到 MAC 时退化为
   型号名 + 设备序号。因此**同一型号的多只手柄可能需要手动分配预设**，
   App 为此提供了「分配预设」功能。
4. Android 8+ 禁止普通广播注册开机自启，需要用户在系统设置中允许本应用
   「自启动 / 后台运行」。

---

## 四、构建

### 环境

- JDK 17
- Gradle 8.9（可使用项目自带的 `./gradlew`）
- Android SDK Platform 34 + Build-Tools 34+

请按自己的电脑环境配置 `JAVA_HOME` 和 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）；不要照抄某台构建机的绝对路径。项目使用 Android Gradle Plugin 自带的 AAPT2，不依赖特定设备上的包装脚本。

### 命令

```bash
# 根据本机实际安装位置设置，例如：
# export JAVA_HOME=/path/to/jdk-17
# export ANDROID_HOME=/path/to/android-sdk

cd koreader-ctrl
./gradlew :app:assembleRelease      # 产出已签名 APK
./gradlew :app:testDebugUnitTest    # 运行核心逻辑单元测试
```

产物：`app/build/outputs/apk/release/app-release.apk`

签名配置位于 `app/build.gradle.kts`，keystore 为 `keystore/ctrl.jks`
（口令 `koreader`，别名 `ctrl`）—— 这是为了让 Release 构建可直接产出可安装 APK
而附带的**开发用签名**，正式分发前请替换为你自己的 keystore。

### 单元测试覆盖

`app/src/test/java/com/koreader/ctrl/CoreLogicTest.kt` 断言了：

- 9 个事件名与 KOReader `dispatcher.lua` 逐项一致
- URL 构造与原 HTML 格式一致（含 `GotoViewRel/1`、`GotoViewRel/-1`、
  去除 `http://` / `https://` 前缀与尾部斜杠）
- 预设的绑定 / 解绑 / 多码绑定 / JSON 序列化往返
- 默认映射与原 HTML `defaultMap` 逐项一致

---

## 五、项目结构

```
app/src/main/java/com/koreader/ctrl/
├── MainActivity.kt              主界面：按钮、手柄管理、映射编辑、权限引导
├── model/Model.kt               Action 枚举、MappingPreset、GamepadInfo
├── data/Prefs.kt                SharedPreferences 存储（对齐原 HTML 的 localStorage）
├── net/KoreaderClient.kt        HTTP Inspector 客户端
├── gamepad/GamepadEngine.kt     手柄枚举、按键/摇杆事件映射
└── service/
    ├── ActivityInputDispatcher.kt   Activity 前台输入事件分发
    ├── InputHub.kt                  共享输入引擎与回调中枢
    ├── BackgroundInputService.kt   前台服务 + ActionDispatcher（动作解析）
    ├── GamepadAccessibilityService.kt  无障碍按键监听
    ├── Notifier.kt                通知渠道与常驻通知
    ├── BootReceiver.kt            开机自启
    └── BluetoothStateReceiver.kt  蓝牙状态变化
```

### 输入码命名

- `Gamepad_<n>`：按钮序号 0–15。Android `KEYCODE_BUTTON_1..16` 会转换为从 0 开始的编号，以兼容浏览器 Gamepad API 的索引语义。
- `Axis_<axis>_<dir>`：摇杆轴与方向，`dir` ∈ {-1, 0, 1}。

---

## 六、Kindle 端准备

1. 在 Kindle 上安装并启用 **httpinspector** 插件（KOReader 自带，需手动启用）。
2. 插件设置中勾选 **autostart**，并确认端口为 `8080`。
3. 确保 Kindle 与手机在同一局域网，且 Kindle 防火墙允许 8080 端口
   （httpinspector 会在 Kindle 上自动添加 iptables 规则）。
4. 在 App 中填入 Kindle IP，点「测试连接」验证连通性。

---

## 七、与原 HTML 版本的差异

| 方面 | 原 HTML | 本应用 |
|---|---|---|
| 后台监听 | 不可用（浏览器限制，页面需保持前台） | 前台服务 + 无障碍服务两条路径 |
| 连接反馈 | 无法读取响应（`no-cors`） | 可显示 HTTP 状态码与响应体 |
| 手柄标识 | 浏览器 Gamepad API index | Android `InputDevice` + 型号/MAC 组合 |
| 预设存储 | `localStorage` | `SharedPreferences`（同名键 `joyconKeyMap`） |
| 默认映射 | `defaultMap` | 完全相同 |
| 协议 | `/koreader/event/<Name>` | 完全相同 |


Version 1.0.4: refreshed warm-neutral/lavender visual theme, updated launcher artwork using the supplied rabbit image, and added the same full-color image as the notification large icon. Android status-bar small notification glyph remains monochrome by platform design.
