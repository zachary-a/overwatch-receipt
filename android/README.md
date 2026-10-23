# 战绩小票 · Android

以“当天战绩总结”和“由用户挑选值得纪念的对局”为主流程。用户可以选一场或多场，每场生成独立小票，逐张预览、保存、导出，或按顺序打印。没有自动评分或 AI 评价。

项目持续约束：完成版 App 始终保持简洁，只保留操作所必需的文案，不加入冗杂说明。开发细节、协议说明、统计口径及长篇说明写在项目文档中。所有后续功能、修复和改版均遵守项目根目录 AGENTS.md 的这一要求。

## 安装与使用

真实接入版为 work/build/app/outputs/apk/live/app-live.apk，包名 dev.owreceipt.app。0.2.5 起关闭 debuggable，启用 R8 优化及资源压缩，继续使用原有签名以支持覆盖升级。演示回归包为 debug/app-debug.apk，包名 dev.owreceipt.app.demo；两个包可以共存。

1. 在账号页打开网易大神官网，完成短信／邮箱登录及官方要求的验证；应用自动读取绑定角色。默认使用窄屏单列表单。页面显示异常时切换“宽页滑动”，可用安卓原生容器左右、上下拖动网页，再切回手机布局；切换保留同一个 WebView，不重新加载或清空表单。尚未绑定时先在官方页面绑定，再点击“重新读取绑定角色”。
2. 默认当天，日／周按北京时间和对局开始时间筛选，周一为每周起点。当前加载竞技与快速模式，可单独选择模式。
3. “区间总结”汇总已取得的记录；“挑选纪念对局”支持最多 50 场，每场单独成票。预览页可以逐张切换、全部保存、逐张导出 PNG、排队打印。当前模板为连续纸，自动送纸，不自动切纸。
4. 先断开乐写 App 对打印机的占用，在设备页允许附近设备权限，选择已配对设备或扫描。已配对、扫描和连接校验均限定设备名以 9516 开头；该型号前缀固定，不提供配置。连接期间和连接成功后隐藏设备选择，断开后恢复；连接失败且未取得设备信息时允许重新选择。先打印 576 × 96 短测试图，再尝试完整小票。

0.2.5 的纪念册支持长按小票唤起删除确认，短按仍打开预览，取消不会删除。应用打印固定使用默认 1 份、140 点尾部送纸和中浓度，已移除打印配置入口；多张小票队列仍逐张打印。图标使用 OW 官网徽记的准确矢量轮廓与小票造型，适配桌面遮罩和单色主题图标。徽记来源：https://forge.akamaized.net/icons/overwatch.svg 。

0.2.2 的总结提供战绩概况、单局高光、每 10 分钟表现和个人数据合计。单局高光包括最高伤害、最高治疗、最多消灭和最高减伤；十分钟数据按同时具备有效数值与正时长的对局加权计算。升级后新增模块默认勾选。

打印正文只保留身份、区间、模式和有效战绩，缺失项自动省略；空区间只显示记录对局 0 场。0.2.4 删除查询页和预览页的覆盖说明、统计口径等开发文案，以及重复账号按钮；点击昵称进入账号页，右上角保留主题设置。原始覆盖元数据仍保存在文档中。已存入纪念册的原始图片保持冻结。

0.2.3 依据用户提供的结算小票参考重做模板：票头、三列大号结算数字、日报逐场明细／周报按日明细、双列高光、每十分钟表现和收藏条码。明细是独立模块，可取消勾选以缩短小票。中文使用内置 Noto Sans SC 可变字体的真实字重，展示数字使用 Barlow Condensed，许可证随字体放在 infra 的 assets/licenses 中。版式在原生打印点宽上计算，用矢量绘制和超采样保持字形细节，再生成与打印数据一致的二值 PNG；没有低清缩放、仿旧噪点或像素字体。预览、导出和打印继续共用同一 300 ppi 栅格。

真实账号登录、token 换取与长期续期、自己的 App 首次蓝牙连接和实际出纸，仍需用户在目标手机与打印机上完成验收。源码实现、离线协议回归或模拟器页面加载均不能代替这些实物结果。

## 构建与验证

需要 JDK 17／21、Android SDK 36，minSdk 26，targetSdk 36。通过 ANDROID_HOME／ANDROID_SDK_ROOT 环境变量或 build.ps1 的 -SdkPath 参数指定 SDK。

在任务 work 目录执行：

    & ..\android\build.ps1 -Tasks ":core:test",":infra:testDebugUnitTest",":app:assembleDebug",":app:assembleLive",":app:assembleDebugAndroidTest",":app:lintDebug",":app:lintLive"
    & ..\android\verify-emulator.ps1 -Serial emulator-5580 -ResetDemoData

脚本将 Gradle 缓存、项目缓存、调试签名、构建、测试和临时文件均放在任务 work 下。首次构建需要下载依赖，版本由 gradle/libs.versions.toml 集中锁定。源码压缩包不包含本机 local.properties、缓存或签名文件；build.ps1 会生成调试签名。

0.2.5 起真实接入版启用 R8，应通过外部测试进程检查最终 APK，避免白盒测试依赖已被裁剪的类。先安装 live APK 以及演示包与演示测试 APK，再从演示测试进程启动真实接入版：

    adb -s emulator-5580 shell am instrument -w -r -e class dev.owreceipt.app.OptimizedAppSmokeTest -e runOptimizedSmoke true dev.owreceipt.app.demo.test/androidx.test.runner.AndroidJUnitRunner

该检查只使用公开的界面操作，验证优化包启动、设备页、原生账号页和 WebView 容器的创建，不输入账号、不连接硬件。官网网络加载与真实账号认证单独验收。完整演示回归包含真实 Room、Keystore、栅格与 Compose 流程。实体手机界面由用户操作。

滚动性能对照使用同一演示数据和同一滑动轨迹；benchmark 是与 live 使用相同 R8 配置、但仅使用演示账号的独立包，不用于交付。构建 :app:assembleBenchmark 并安装到项目模拟器后，在 work 目录运行 ../android/measure-scroll.ps1。该脚本仅重置模拟器中的两个演示包，做三轮帧耗时对照，结果保存在 work/scroll-comparison。模拟器结果不代表真机高刷效果。

## 模块与修改位置

依赖关系：app → ui / infra / core；ui → core；infra → core。

- app：入口、ViewModel、导航状态、Android 文件选择器、蓝牙权限、真实打印前台服务及 AppGraph 依赖组装。
- ui：AppUiState + UiAction、独立页面、复用控件与主题。页面不直接访问 WebView、HTTP、数据库或蓝牙；官方页面由 app 以内容插槽传入。
- core：范围、逐场记录、总结规则、不可变小票文档、业务接口、纯协议状态机与 Java 编码器。
- infra：网易登录／网络适配器、Keystore 密文、Room、DataStore、黑白栅格、经典蓝牙传输与演示适配器。

手机颜色、字体、间距在 ui/design；页面布局在 ui 的各 Screen 中。打印版式和内置字体装配在 infra/receipt/ReceiptLayout，MonochromeReceiptRenderer 统一生成 PNG 和打印位图，与手机主题独立。文档保留模板版本，已保存成品始终读取冻结 PNG。

小票默认宽 576 点、名义 300 ppi，中／大分别按 100%／112.5% 缩放字体层级。旧小号偏好恢复为中号，旧纪念册仍兼容原始小号文档和冻结图片。连接真实打印机后使用设备回复的有效点宽；点宽不同必须重新生成预览。预览、导出、未来重打消费同一黑白栅格，黑色位为 1、MSB 在左。

页面切换和主题颜色过渡使用 40 ms。窗口按当前分辨率支持的最高刷新率同时设置 preferredDisplayModeId 与 preferredRefreshRate，在恢复前台、窗口重新获焦和显示模式变化时重新检查；Compose 内容通过 preferredFrameRate(FrameRateCategory.High) 提交高刷新率偏好。最终调度由安卓系统、设备能力及用户省电设置决定。

## 数据与认证边界

日／周从 queryMatchList 按模式翻页、去重、筛选 beginTs，再用 queryMatchInfo 补时长。缺失值与真实零值分别保留；合计存在缺失时不按零计算。分页重复、请求失败、达到加载上限、未知时间戳以及未覆盖到区间起点，均明确标为部分数据。默认单模式最多 30 页、共约 300 场；跨赛季历史覆盖尚需实测，未覆盖不会冒充完整区间。

生涯总览读取累计场次、游玩时长、昵称、头衔和等级；gameTime 优先取 queryCard，并以 searchBnetAccount 的同名字段回退，单位为小时。另查询 sport、leisure 的 queryCountInfo，展示本赛季职责／开放队列的胜负、胜率、场均表现、常用英雄及竞技当前／最高段位。英雄 gameTime 单位为秒，使用官方名称表，不显示未知 GUID。模块标题区分生涯与本赛季；缺失、无效字段省略，真实零保留，不把赛季场数累加为生涯。服务器比较值不作为个人最佳。

0.2.1 修正了 0.2.0 登录后停在充值首页的问题。入口为 ds.163.com，先检查官网会话，再读取 payms.ds.163.com/v1/plutusuinfo/blizzard/accounts?game_code=ld5；按官方暴雪组件将 result.accounts[].account 映射到 role_id、nickname 映射到显示名，保留长数字 ID 原文。不再把通用 role_list?game_code=bn 当作已验证的战网角色入口。角色服务未共享会话时，打开官方当前守望先锋角色页面进行衔接；读取完成后返回官网换取业务凭据。“重新读取绑定角色”只重查当前会话，不重新导航到 /login。空绑定、未登录、响应变化、业务失败和网络失败分别提示，避免静默等待。

手机 User-Agent 访问官网会跳转 m.ds.163.com 信息流；当前未发现该页可用的网页短信登录入口。因此认证上下文使用桌面 UA，官网原有短信／邮箱表单外层应用窄屏布局，输入框、验证码和协议控件保持官方实现。已提供原生双向滚动兜底，避免依赖这些 CSS 类名才能填写登录。业务签名和 token 换取在官方 ds.163.com 网页上下文进行。原生请求区分调用者业务 token 和目标 customerToken；业务鉴权失败合并续期，原请求最多重试一次。隐私、网络和限流不清除会话。需要官方重新认证时显示官方界面，成功后自动恢复查询，并限制自动重新认证循环。

登录回归包括：官方响应契约解析、JavaScript 会话与重读、真实 WebView 安全桥到原生 READY 状态的合成回复回放，以及真实官网空白表单、键盘和旋转。原生滚动使用锁定 overflow/touch-action 的本地页面验证双向拖动和表单保留。合成凭据回放不等于真实账号登录和业务 token 验收。在 work 目录运行 JavaScript 回归：node --test ../android/infra/src/test/js/official-role-capture.test.cjs。

业务凭据用 Android Keystore AES/GCM 密钥加密后保存于应用私有目录；不会进入 UI、小票、历史文档或日志。官方 Cookie 留在本 App 的 WebView。桥只允许指定官方 HTTPS 来源的主框架，禁用明文、文件访问与 WebView 调试；页面证书错误直接停止。退出清除会话与私有临时状态，用户已保存的纪念小票保留。

英雄与地图名称来自网易公开配置 https://s.166.net/config/ds_ow/ ，内置 2026-10-23 名称快照作后备，查询时尝试刷新。没有下载图标或图片素材。

## 打印边界与证据

公开 SPP UUID 与反射 RFCOMM 通道 1 是独立连接策略；自动模式依次尝试，每次都必须收到真实 01 设备信息后才就绪。当前发送策略限定已交接的 type 1 / firmware 50。先 AC、04、01、02/A5，逐行节流发送，每 30 行检查缓冲，结束后等待 01 空闲。多张小票每张独立握手并确认结束，份数重复栅格和补白。

缺纸暂停后续发送。中途续传、未知 A4/A7/A8、反馈行号异常不会自动重传；用户停止后重新开始。停止先取消生产者，再发 04，断开关闭 socket。缓冲与回复超时为内部应用策略。140 点尾部补白沿用已抓取的短图任务，应用默认每张打印 1 份，不提供配置页面；实际纸面效果仍需目标打印机验证。

传输证据位于应用私有 files/printer-trace，包含时间、连接方式、RX/TX 十六进制、状态与任务 ID，单文件轮转限制 8 MB，最多保留两份；不会记录认证数据。Debug 包可在用户授权的 ADB 调试会话中读取。仓库仅保留无个人信息的协议测试向量，不包含原始抓包或设备交接资料。

上游接口依据：

- https://github.com/AddOneSecondL/Overstats/blob/main/Faststart.md
- https://github.com/AddOneSecondL/Overstats/blob/main/src/client/apiclient.py
- https://github.com/AddOneSecondL/Overstats/tree/main/src/client/api_structure
