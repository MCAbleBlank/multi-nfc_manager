# NFC 卡包

一个使用 Jetpack Compose + Material 3 编写的 Android NFC 卡片管理应用。可以读取、保存、编辑实体 NFC 卡，将 NDEF 内容写回标签，并通过 HCE 把保存的 NDEF 消息模拟成标准 Type 4 标签。

> 应用只管理你自己有权使用的卡片。读取和写入他人卡片、绕过门禁或复制银行卡均不在本项目的支持范围内。

## 目录

- [功能特性](#功能特性)
- [界面与交互](#界面与交互)
- [NFC 能力边界](#nfc-能力边界)
- [快速开始](#快速开始)
- [使用说明](#使用说明)
- [项目结构](#项目结构)
- [数据与隐私](#数据与隐私)
- [技术栈](#技术栈)
- [已知限制](#已知限制)

## 功能特性

### 卡片读取与保存

- 支持读取 `NfcA`、`NfcB`、`NfcF`、`NfcV`、`IsoDep`、`MifareClassic`、`MifareUltralight`、`Ndef` 标签。
- 解析 UID、ATQA、SAK、技术列表、存储容量和 NDEF 消息。
- NDEF 记录解码：文本、URI / 链接、MIME、智能海报、绝对 URI、空记录。
- 同一 UID 再次扫描时自动更新已有卡片，不会产生重复条目。
- 读取 MIFARE Classic 时使用内置常用密钥字典尝试认证，尽力导出全部可读扇区；失败扇区保留为空并在界面标记。

### 编辑与回写

- 编辑 NDEF 文本 / 链接记录，同时保留原始 NDEF 字节，未识别的记录不会被破坏。
- 直接编辑原始 NDEF 十六进制数据。
- 按扇区查看和编辑 MIFARE Classic 数据块，包括扇区尾块（Key A / 访问位 / Key B）。
- 支持编辑块 0（厂商块 / UID 块），但只有 UID 可写卡（Gen1a / Gen2 魔术卡）才能写入成功。
- 内置 32 位十六进制键盘：字节级选择、半字节高亮、自动进位、实时 ASCII / 值块 / BCC 解码。
- 写回 NDEF 内容；未格式化的 `NdefFormatable` 标签会自动格式化后写入。
- 按扇区写回 MIFARE 数据，写入前逐块比对，未变化的块不会被重写。
- 改写扇区尾块导致密钥变化时，后续校验和保存会自动使用新密钥。
- 写入块 0 导致 UID 变化时，应用会自动把卡片迁移到新的 UID 下。

### 卡模拟（HCE）

- 将选中卡片的 NDEF 消息模拟为**只读 NDEF Type 4 标签**（AID `D2760000850101`）。
- 应用在前台且选中了模拟卡时，通过 `setPreferredService` 优先路由到本应用。
- 模拟服务每次收到 APDU 都从本地存储读取当前卡片，切换或编辑内容立即生效。
- 支持 `SELECT`（按 AID / 文件 ID）与 `READ BINARY`，提供标准的 Capability Container 和 NDEF 文件。

### UID 模拟（实验性）

- 通过 Android NFC OEM 扩展 `NfcOemExtension.emulateNfcTechnologyATag()` 把保存的 4/7/10 字节 UID 交给 NFC 控制器，供读卡器读取。
- 仅重现 UID，不复制 MIFARE 扇区、密钥或加密认证；与 HCE 互斥，开启时会先停止 HCE。
- 需要 Android 16 QPR2（API 36.1）及以上，且厂商的 NFC 模块保留该扩展；详情页会显示检测结果。
- 关闭 NFC、重启或进程结束后模拟状态可能丢失，需要重新开启。

### 其他

- Material 3 + 动态取色（Android 12+），支持深色模式。
- 卡片内容、读取次数、上次读取时间、MIFARE 扇区数据全部本地持久化。
- 支持重命名、删除、复制 UID、重新扫描更新。
- 卡片视觉由 UID 确定性生成配色，同一张卡始终呈现相同外观。

## 界面与交互

### 首页展示样式

在设置页可切换五种布局，选择会被持久化：

| 样式 | 说明 |
| --- | --- |
| 3D 轮盘 | 卡片沿虚拟圆柱排布，聚焦卡居中，支持上下拖动和弹性吸附 |
| 横向封面流 | 左右滑动浏览，两侧卡片带透视缩放 |
| 叠层卡堆 | 卡片层叠成堆，上下滑动切换 |
| 双列网格 | 平铺展示，适合卡片较多时 |
| 纵向列表 | 紧凑排列，便于快速查找 |

### 操作方式

- **音量上 / 下**：首页切换上一张 / 下一张卡片；网格与列表模式下逐行滚动。连续按住时按约 140 ms 节流。
- **拖动轮盘**：每滑过一张卡片触发一次轻微触感反馈，松手后吸附到最近卡片。
- **点击侧边卡片**：把该卡转到中央；点击中央卡片进入详情。
- **搜索**：按名称、UID 或类型过滤，过滤后轮盘回到第一张匹配卡。
- **详情页倾斜**：开启后由陀螺仪驱动卡片 3D 倾斜，高光随手机姿态移动；离开页面自动注销传感器。

### 动画

- 首页列表依次淡入上浮、按压缩放、卡片数量滚动切换。
- 扫描弹窗包含扩散波纹、呼吸光晕与扫描态 / 结果态交叉过渡。
- 首页、详情、编辑、设置之间使用错开时长的横向滑动与淡入淡出。
- 轮盘、封面流等逐帧变换主要在 `graphicsLayer` 中执行，避免每帧重组。

## NFC 能力边界

这是使用前最需要了解的部分：

| 能力 | 支持情况 |
| --- | --- |
| 手机作为读卡器读取实体标签 | ✅ 支持 |
| 写回 NDEF 内容 | ✅ 支持（标签需可写） |
| 写回 MIFARE Classic 扇区 | ✅ 支持（需要已知密钥） |
| 改写 MIFARE 块 0 / UID | ⚠️ 仅 UID 可写卡 |
| 模拟 NDEF Type 4 标签 | ✅ 支持（只读） |
| 模拟 MIFARE Classic | ❌ Android 平台不支持第三方应用实现 |
| 自定义 HCE 的 UID | ❌ HCE 路由下 UID 由控制器决定 |
| 模拟 NFC-A UID（OEM 扩展） | ⚠️ 实验性，需 Android 16 QPR2+ 且厂商保留扩展 |
| 息屏 / 后台模拟 | ⚠️ 受系统默认钱包与厂商策略控制 |

具体说明：

1. **HCE 只暴露 ISO-DEP（ISO 14443-4）通信。** MIFARE Classic 的认证、扇区读写和 UID 由 NFC 控制器固件处理，普通应用既不能呈现 MIFARE UID，也不能应答 crypto1 命令。因此本应用只能模拟标准 NDEF Type 4 标签，无法模拟 MIFARE Classic 门禁卡。
2. **HCE 本身不能自定义 UID，UID 模拟走另一条实验性路径。** HCE 只暴露 ISO-DEP，UID 由 NFC 控制器决定。若系统提供 NFC OEM 扩展，应用可通过 `emulateNfcTechnologyATag()` 把保存的 UID 交给控制器；该路径需要 Android 16 QPR2+ 与厂商支持，且只重现 UID，不包含扇区或认证数据。门禁若额外校验密钥、滚动码或服务端状态，仍会拒绝。
3. **前台优先，不等于系统级替换。** 本应用只会在可见时申请成为首选 HCE 服务；退到后台后仍由系统默认钱包决定路由。普通应用无法静默夺走系统默认钱包的地位。
4. **读写都不会操作 eSE。** 应用不访问安全元件，也不包含任何绕过厂商限制、提权或修改 NFC 固件/HAL 的逻辑。
5. **只保存元数据不足以复制门禁卡。** 门禁卡通常依赖扇区密钥、滚动码或服务端校验，仅复制 UID / NDEF 不能保证可用。

如果目标是手机刷门禁，应当使用手机厂商钱包自带的门钥匙 / 门禁卡模拟功能，或继续使用实体卡。

## 快速开始

### 环境要求

- JDK 17
- Android SDK 36（compileSdk / targetSdk）
- Android Build Tools 35.0.0（ARM64）
- Gradle 8.14.3
- 运行设备：Android 9（API 28）及以上，且支持 NFC

### 构建

在项目根目录执行：

```bash
gradle assembleDebug
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的设备：

```bash
gradle installDebug
```

也可以把 APK 复制到手机上手动安装。

> `gradle.properties` 中为 Kotlin / D8 设置了 `-Xmx2048m -XX:MaxMetaspaceSize=768m`。在内存较小的构建设备上这能避免 dex 阶段 OOM，但构建速度会受 swap 影响。

## 使用说明

### 读取卡片

1. 打开应用，确认系统 NFC 已开启（首页状态条可跳转系统设置）。
2. 点击「扫描卡片」。
3. 将卡片贴近手机背面 NFC 天线区域。
4. 读取完成后可修改名称并保存；同一 UID 会更新原卡片。

扫描弹窗打开且尚未读到卡时，应用使用 Reader Mode 独占射频场；读取完成、关闭弹窗或退到后台后立即释放，避免占用其他读卡器。

### 编辑卡片内容

1. 打开卡片详情，选择「编辑卡片内容」。
2. 在 NDEF 区域修改文本 / 链接，或切换到原始十六进制编辑。
3. 在 MIFARE 区域选择扇区与数据块，点击块进入十六进制键盘编辑。
4. 保存后内容先写入应用本地；要同步到实体卡，使用「写入 NDEF 内容」或「写入 MIFARE 扇区」。

> 写入 MIFARE 扇区需要该扇区的 Key A 或 Key B。扫描时应用会尝试常用密钥字典，也可以在编辑器里手动填写密钥。改写扇区尾块可能改变访问位并锁死扇区，操作前请确认数据。

### 写入实体卡

1. 在详情页点击「写入 NDEF 内容」或「写入 MIFARE 扇区」。
2. 按提示把**同一张卡**贴近手机。
3. 应用会校验 UID，UID 不匹配时不会写入。
4. 保持卡片贴合直到提示写入完成。

### 卡模拟

1. 确保卡片已有文本或链接等 NDEF 内容。
2. 在详情页打开「卡模拟（HCE）」开关。
3. 保持应用在前台，将手机贴近外部 NDEF 读卡器。
4. 关闭开关、切换卡片或退到后台后，首选服务会被释放。

### UID 模拟（实验性）

1. 打开卡片详情，确认「UID 模拟（实验性）」显示设备支持。
2. 打开开关并在警告对话框中确认；开启前 HCE 会自动停止。
3. 再次关闭开关即可恢复控制器的默认配置。

> 该功能只重现 UID。门禁若校验扇区密钥、滚动码或服务端状态，仍会拒绝；支持情况取决于系统版本、厂商 NFC 模块和读卡器。

## 项目结构

```text
app/src/main/
├── AndroidManifest.xml                NFC 权限、标签 Intent 过滤器、HCE 服务声明
├── java/com/multinfc/manager/
│   ├── MainActivity.kt                NFC 模式调度、前景分发、Reader Mode、音量键
│   ├── data/
│   │   ├── NfcCard.kt                 卡片 / NDEF / MIFARE 数据模型与 JSON 序列化
│   │   ├── CardRepository.kt          SharedPreferences 持久化
│   │   ├── AppSettings.kt             展示样式、交互开关与偏好存储
│   │   └── NdefTools.kt               NDEF 构建、解析、十六进制工具
│   ├── nfc/
│   │   ├── NfcParser.kt               标签解析、NDEF 解码、MIFARE 扇区读取
│   │   ├── CardWriter.kt              NDEF / MIFARE 写回与写入校验
│   │   ├── NdefHostApduService.kt     HCE NDEF Type 4 卡模拟
│   │   ├── UidCodec.kt                4/7/10 字节 UID 解析
│   │   └── UidEmulation.kt            NFC-A UID 模拟（OEM 扩展）
│   └── ui/
│       ├── NfcApp.kt                  页面导航与全局状态
│       ├── CardViewModel.kt           卡片、扫描、写入、HCE 状态
│       ├── Formatters.kt              时间格式化
│       ├── components/                卡片视觉、图标、陀螺仪倾斜
│       ├── screens/                   首页、详情、编辑、扫描、设置
│       └── theme/                     Material 3 主题、颜色、字体
└── res/
    ├── xml/apduservice.xml            HCE AID 注册（NDEF Type 4）
    └── xml/nfc_tech_filter.xml        可读取的标签技术列表
```

### NFC 模式

`MainActivity` 根据界面状态在三种模式间切换：

| 模式 | 触发条件 | 行为 |
| --- | --- | --- |
| `Dispatch` | 应用在前台，未打开扫描弹窗 | 前景分发，优先接收标签点击，不锁射频场 |
| `Reader` | 扫描弹窗打开且等待标签 | Reader Mode 独占读取 |
| `Off` | 已显示扫描结果 / 应用退到后台 | 释放 Reader Mode 与前景分发 |

## 数据与隐私

- 所有卡片数据仅保存在设备本地，无网络上传、无云同步。
- 应用未声明 `INTERNET` 权限。
- 卡片集合（JSON）与当前模拟卡 UID 保存在 `SharedPreferences` 的 `nfc_card_pocket` 中；界面设置单独保存在 `nfc_card_settings` 中。
- 卸载应用会一并删除本地保存的卡片数据。
- 卡模拟服务在每次 APDU 请求时读取本地数据，不会把卡片内容发送到应用外部。

## 技术栈

| 组件 | 版本 |
| --- | --- |
| Kotlin | 1.9.22 |
| Android Gradle Plugin | 8.11.0 |
| Compose UI / Foundation / Animation | 1.6.1 |
| Material 3 | 1.2.0 |
| compileSdk / targetSdk | 36 |
| minSdk | 28 |
| JVM target | 17 |

依赖仅包含 AndroidX Core、Activity Compose、Lifecycle 与 Compose / Material 3，没有引入第三方网络、数据库或图片库。

## 已知限制

- 卡模拟只支持只读 NDEF Type 4；UID 模拟为实验性功能，且仅限提供 NFC OEM 扩展的设备，不支持 MIFARE Classic 或银行卡 APDU。
- UID 模拟未实现 raw NCI / 厂商私有 HCE 路径，因此 Android 15 及更早系统、或裁剪了 OEM 扩展的固件上不可用。
- UID 模拟在关闭 NFC、重启或进程结束后不会自动恢复，应用也不会在后台自动接管 NFC。
- HCE 在后台的路由由系统默认钱包决定，应用无法强制覆盖。
- MIFARE Classic 的读取依赖密钥字典；未知密钥的扇区无法读取或写入，除非手动填写正确密钥。
- 厂卡块 0 为只读；只有魔术卡可以改写 UID。
- MIFARE Ultralight 仅支持读取和 NDEF 写入，不提供扇区级页编辑。
- 智能海报只展示摘要，不支持在应用内展开编辑其嵌套 NDEF。
- 卡片集合整体以单个 JSON 字符串存储，适合个人卡包规模；不是为大规模或高并发场景设计。
- UID 解析包含 JVM 单元测试（`./gradlew testDebugUnitTest`）；NFC 读写与 UID 模拟仍需真机验证。

## 致谢

NFC-A UID 模拟的可行性验证参考了 [ch3sh1re/Emula](https://github.com/ch3sh1re/Emula)（GPL-3.0）与 AOSP `NfcOemExtension` API。本项目的实现为独立编写，未复制 Emula 源码。
