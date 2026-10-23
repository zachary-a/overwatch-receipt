# 守望小票 · Overwatch Receipt

Android 守望先锋战绩小票应用。查询国服竞技与快速战绩，生成日／周总结或逐场纪念小票，保存到纪念册、导出 PNG，或通过蓝牙连接 9516 系列打印机打印。

当前版本：**0.2.5**（versionCode 7），Android 8.0 及以上。

## 下载与安装

从 [v0.2.5 Release](https://github.com/zachary-a/overwatch-receipt/releases/tag/v0.2.5) 下载 overwatch-receipt-0.2.5-live.apk；SHA-256 校验值随附件提供。该包沿用本地测试签名，可覆盖此前使用相同签名安装的版本。

这是测试版本：真机登录、蓝牙出纸及偶发滚动掉帧仍需在目标设备验收。当前接入竞技与快速，其他模式尚未接入。

## 0.2.5 变化

- 仅显示和连接名称以 9516 开头的打印机，连接期间及连接后隐藏设备选择。
- 纪念册长按删除，操作前确认。
- 打印固定默认 1 份、尾部送纸 140 点，移除配置入口。
- 新版 OW 小票图标，加强高刷请求；真实接入版关闭调试，启用 R8 与资源压缩。

## 构建

需要 JDK 17／21、Android SDK 36 和 PowerShell。设置 ANDROID_HOME 或 ANDROID_SDK_ROOT，然后在仓库根目录运行：

~~~powershell
New-Item -ItemType Directory -Path work -Force | Out-Null
Set-Location work
& ../android/build.ps1 -Tasks ":core:test", ":infra:testDebugUnitTest", ":app:assembleLive", ":app:lintLive"
~~~

构建产物位于 work/build；脚本将自动生成仅用于本地构建的测试签名。原始签名私钥不在仓库中，因此自行构建的包通常无法直接覆盖已发布 APK。面向正式分发时应配置自己的发布签名。

## 隐私与仓库内容

仓库只收录应用源码、构建配置、许可证和合成测试数据。登录 Cookie、业务凭据、用户战绩、纪念册、设备抓包、日志、截图、签名私钥以及本机配置均不随仓库或源码包分发。

账号由用户在官方网易网页中登录；业务凭据使用 Android Keystore 加密保存。详细实现和验证方式见 [Android 项目说明](android/README.md)。

## 素材来源

内置 Noto Sans SC 与 Barlow Condensed 字体的 OFL 许可证位于 android/infra/src/main/assets/licenses。OW 徽记来自 [官方图标](https://forge.akamaized.net/icons/overwatch.svg)，英雄和地图名称来自网易公开配置。守望先锋及相关标识的权利属于相应权利人；本项目为非官方测试应用。
