# 红果 TV（hongguoTV）

独立的 Android TV 红果短剧客户端，面向小米 E55A / E55S、东芝 75U6900C，以及可运行 Android APK 的华为智慧屏。项目与「开心果」手机/平板客户端独立发布，复用其 Kotlin 内容模型、红果 SDK 调用链、CENC 渐进解密和 Media3 播放实现；电视 UI 使用 Android 原生 View，优先保证 D-pad 遥控体验和低内存设备流畅度。

> 非官方个人项目，与红果及电视品牌厂商无关联。仅接入用户可合法访问的服务，不提供、不存储或分发视频内容。

## 1.0.1

增加开心果手机端局域网接力播放接收能力：在 hongguoTV 前台运行时广播设备信息，接收剧集 ID、集数和毫秒级播放位置，由电视独立解析播放源并确认开始播放。无需配对或持续连接；设备 UUID 和短编号固定保存，电视昵称可在设置中修改。

## 1.0.0

首个 TV 版本。最低 Android 6.0（API 23），单一通用 APK；默认使用硬件解码，不包含 FFmpeg 软件解码包。

### 目标设备
- 小米全面屏电视 E55A（低配性能基准）
- 小米全面屏电视 Pro E55S
- 东芝 75U6900C
- 可安装 Android APK 的华为智慧屏（具体机型以实机验证为准）

### 技术
- Kotlin + Android 原生 View / RecyclerView
- Media3 ExoPlayer
- 复用开心果的 openhongguo 业务模型与 CENC DataSource
- 本机收藏、观看历史、播放位置
- 与开心果 Android 客户端通过 NSD/mDNS + TCP 一次性交接播放任务
- 固定签名、Release 自动构建、SHA-256 校验

## 构建
需要 JDK 17 和 Android SDK 36：

```bash
./gradlew testDebugUnitTest assembleDebug
```

正式 Release 使用仓库根目录的 `hongguoTV-release.jks` 和 `signing-secrets.txt`。这是个人自用项目，按仓库所有者要求将签名材料放在仓库根目录以便自动构建。**该密钥公开，不能用于安全敏感应用；任何人都能用同一证书签署同包名 APK，因此签名不能证明发布来源。** 请勿将它复用于其他项目。

## 许可证与来源
本项目的复用代码保留原项目版权/许可说明。开心果原项目采用 MIT License；第三方库按各自许可证分发。详见 `THIRD-PARTY-NOTICES.md`。

## 免责声明
第三方接口可能变化、限流或停止服务；电视解码器行为、系统安装器及遥控器按键映射依赖具体固件。CI 通过不代表所有电视实机已经验收。
