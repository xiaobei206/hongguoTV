# 第三方代码与许可说明

## 开心果 Android（xiaobei206/kaixinguo-android）
本项目复用其 Kotlin 内容模型、目录访问封装、剧集分页模型、播放状态机、MP4 CENC 渐进解密器、Media3 自定义 DataSource 与诊断代码。原仓库采用 MIT License，版权及许可文本见 [LICENSE](https://github.com/xiaobei206/kaixinguo-android/blob/main/LICENSE)。相关文件保持原有许可归属与实质内容，仅调整 Kotlin package namespace 以适配本项目。

## openhongguo
正式 Release CI 从 `xiaobei206/openhongguo` 的 `main` 拉取 SDK 源码，应用 `xiaobei206/kaixinguo-android` 维护的 Android 诊断补丁并自行构建 AAR；TV 和开心果因此使用同一套协议实现。未通过 `patchedOpenhongguoAar` 指定文件的本地 Gradle 构建，仍以 Maven 回退依赖 `io.github.beilunyang:openhongguo:0.4.0` 编译；该回退依赖不代表最新 Release 的源码版本。项目不包含或分发红果视频内容。

## AndroidX / Kotlin / Media3
均使用其各自发布许可证，详见相应依赖仓库。
