# Aura 0.1.83 — Winlator 许可入口与来源说明

Aura 0.1.83（Android 版本号 84）面向 Android 14 及以上的 ARM64 设备，应用 ID 为 `com.android.aura`。

## 本版更新

- 在 **Settings → Version** 增加 **Winlator 开源许可**入口，可在应用内离线阅读完整的 GNU LGPL 2.1 文本。
- 在许可界面提供对应源码与重链接说明入口，并说明用户自行修改、替换 LGPL 组件和调试修改版本时的逆向工程权限。
- 修正版本页的来源说明，明确 Aura 使用了修改后的 Winlator 引擎，以及独立应用部分与第三方组件各自的许可范围。
- 公开 README 补充 Winlator、JavaSteam、FEXCore、Wine/Proton、DXVK、VKD3D-Proton、Mesa、AdrenoTools、SDL 和其他支持组件的来源及作用，单独列出图形驱动和设备限制。

本版没有新增或升级游戏运行依赖，也没有修改游戏运行、ESync、画面呈现或性能设置。它不包含已经通过实机测量证实的 FPS 改善、Steam/ESync 故障修复或新增游戏兼容性保证。

## 使用与升级

1. 从本 Release 的附件下载 `Aura-0.1.83-arm64-release.apk`，并核对下面的 SHA-256。
2. 升级前先正常退出游戏和 Wine 会话，并备份重要存档。
3. 此 APK 沿用原有签名，可在签名一致的旧版 Aura 上覆盖安装。遇到安装错误时请保留现有应用并记录报错；卸载或清除数据可能删除应用私有的游戏、容器和存档。
4. 安装后进入 **Settings → Version → Winlator 开源许可**，可查看离线许可正文及公开材料入口。

## 开源项目与图形组件

Aura 的 Android 运行集成基于 **Winlator 11.2**；Steam 协议接入使用 **JavaSteam 1.8.0**。Windows 运行部分使用 **Proton Wine 11.0-2 / ARM64EC** 与 **FEXCore 2610**，并结合 **DXVK 2.7.1**、**VKD3D-Proton 3.0.1** 等图形转换组件。

当前图形路径通过 Turnip 或 Bionic Wrapper 使用 Vulkan，并使用 Zink 提供 OpenGL。捆绑基线包括 Mesa Turnip `25.2.0-devel (6defe2244c)`、Zink `24.3.0-devel`，以及可选的社区 Turnip 26.0 R8、Turnip 25.3 PROFILED 和 Qualcomm Adreno v863.1 包。系统 Vulkan 选项使用手机厂商随固件提供的驱动。

这些组件承担不同工作，不会同时全部启用。可选社区驱动受 GPU 型号和内存页面大小限制；PROFILED 包不支持 Adreno 710/720。Qualcomm v863.1 包含专有组件，不能称为开源 Mesa 驱动。具体来源、用途和限制见 [README](https://github.com/topoftree/Aura/blob/main/README.md) 与 [第三方通知](https://github.com/topoftree/Aura/blob/main/THIRD_PARTY_NOTICES.md)。

## Winlator 部分如何履行 LGPL 要求

Aura 对 Winlator Android 引擎部分采用 LGPL 2.1 第 6 节所述的对应源码及可重链接交付方式。独立 Aura 应用源码是否可以保持私有，取决于独立应用与 LGPL 派生代码之间的边界成立；更换包名、翻译语言或混淆代码不会使 Winlator 派生实现变成独立的专有代码。

0.1.83 提供以下材料及权限。对应引擎源码和混淆应用对象可从 [版本化套件](https://github.com/topoftree/Aura/tree/v0.1.83/relink/v0.1.83) 或本 Release 的 `Aura-0.1.83-relink.zip` 取得；ZIP 包含实际对象 JAR，无需 Git LFS。其交付方式对应 LGPL 2.1 第 6(a) 节的库源码及应用重链接材料路线：

| 事项 | 交付内容与用户权利 |
| --- | --- |
| 修改过的 Winlator 引擎 | 提供实际配套的 Java/Kotlin、C/C++、Android 资源和构建定义，包括 Aura 对该派生引擎的修改；保留上游来源、版权、LGPL 正文及其他文件原有许可。 |
| 修改记录 | 提供固定上游版本和逐文件修改记录，说明哪些文件保持原样、被修改或新增。 |
| 独立 Aura 应用部分 | 提供经预混淆的编译对象、配套依赖、资源和构建输入，用于与用户修改后的引擎重新组合；不发布独立 Aura Kotlin UI/业务源码、私有 Git 历史或生产签名密钥。 |
| 修改与重链接 | 提供独立构建步骤，使接收者能够修改 LGPL 引擎、生成组合 APK，并用自己的密钥签名，无需访问私有 Aura 仓库或私有混淆映射。 |
| 调试修改版本 | 明确允许为个人使用修改组合程序，以及为调试 LGPL 组件的修改而进行逆向工程；Aura 的一般版权声明不得取消这些权限。 |
| 应用内通知 | 本版新增可见的 Winlator 许可入口、离线 LGPL 全文和公开源码／重链接说明入口。 |
| 版本对应关系 | 0.1.83 使用自己的匹配对象与资源；已经发布的 0.1.82 套件仍保留，不能仅把旧套件改名来代替新版本的对应材料。 |

LGPL 部分仍以可编辑源码提供。独立应用对象的混淆只增加阅读和逆向成本，不改变上述权利，也不能保证无法反编译。此前已经公开或下载的旧对象不会因新版发布而消失。

接收者可以使用自己的签名密钥生成修改后的 APK。由于 Android 的签名校验，不同签名的 APK 通常不能直接覆盖官方安装；测试修改版本时应使用独立设备／用户配置或备份后安装。生产签名密钥不需要公开。

### 仍需单独处理的许可事项

以上说明覆盖 **Winlator Android 引擎及其与独立 Aura 对象的组合**，不能据此宣称整个 APK 已全面合规。Wine/Proton、静态纳入的 ntsync、Wine Mono、媒体／音频库等仍有各自的对应源码、构建、重组合及可能适用的安装信息义务；Qualcomm 专有驱动包的再分发许可也仍需落实。上游主页链接和许可证副本不能代替这些交付。

具体材料、适用范围和未完成事项以 [SOURCE_CODE.md](https://github.com/topoftree/Aura/blob/main/SOURCE_CODE.md)、对应版本的 `NOTICE.md` 和 [第三方通知](https://github.com/topoftree/Aura/blob/main/THIRD_PARTY_NOTICES.md) 为准。

## 构建与验证范围

已有 0.1.83 私有发布记录确认：

- Gradle release 构建成功，启用了 R8 优化、混淆和资源压缩；版本、签名和 ZIP 对齐检查通过。
- 3 组共 33 项定向回归通过，lint 为 0 错误且无新增问题。
- APK 内的 Winlator LGPL 文本与引擎原许可逐字节一致，中英文说明及链接已进入成品。
- 运行资产与原生库沿用 0.1.82，274 项原生索引哈希验证通过，8 套匹配原生符号已私有归档。

本次 0.1.83 套件另外完成了以下主机验证：

- 独立应用 1,103 个类保留，1,085 个类改名，源文件／行号／局部变量／SMAP／Kotlin／协程调试元数据清除；对象 JAR 不含 Winlator 引擎类。
- 原始与转换对象各通过 1,103 类字节码检查、21 项序列化反射查找和 48 项行为断言。
- 基线和修改引擎后的 Gradle `relinkRelease` 均通过；临时 Java 方法、原生函数和新增资源实际进入组合 APK。临时测试标记未包含在公开引擎源码中。
- 7,781 个原资源 ID 保持一致，274 项运行库哈希、24 项 Steam 反射接口及 8 个类中的 12 个 JNI 字段检查通过。
- 使用独立临时密钥签名的修改版通过签名及 ZIP 对齐检查；生产密钥没有用于测试或进入公开材料。
- 版本套件提供 956 项逐文件 SHA-256 校验；详细结果与限制见 [VALIDATION.md](https://github.com/topoftree/Aura/blob/v0.1.83/relink/v0.1.83/VALIDATION.md)。

没有进行本版 Android 真机覆盖安装、许可弹窗操作、Steam 真实登录或游戏运行验证；构建成功不等于所有 GPU、游戏或 16 KB 页面设备均兼容。

## 附件与文件校验

| 附件 | 用途 |
| --- | --- |
| `Aura-0.1.83-arm64-release.apk` | 官方签名的 ARM64 应用安装包。 |
| `Aura-0.1.83-relink.zip` | 本版 Winlator 对应引擎源码、混淆应用对象、依赖及构建说明，不含独立 Aura Kotlin UI／业务源码、私有映射或签名密钥。 |
| `SHA256SUMS.txt` | 上述 APK 与重链接 ZIP 的 SHA-256。 |

下载三个附件后，可在同一目录执行 `sha256sum -c SHA256SUMS.txt`。解压重链接 ZIP 后，进入 `Aura-0.1.83-relink/` 并执行 `sha256sum -c SHA256SUMS` 核对套件文件，再按其中的 README 修改、构建和签名。

文件名：`Aura-0.1.83-arm64-release.apk`  
大小：`374,793,571` 字节  
SHA-256：

```text
10becdd7562bdb6df3a2e9c94a501750c48a9bb60698605982dee85fe7e58190
```

```sh
sha256sum Aura-0.1.83-arm64-release.apk
```

项目名称和商标属于各自所有者。Aura 与 Valve、Qualcomm 或这些上游项目不存在由本发行说明建立的隶属或官方背书关系。
