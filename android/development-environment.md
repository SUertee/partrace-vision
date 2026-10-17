# Android 本地联调

App 通过 Backend API 查询事件及上传人物事件原片，不直接连接 PostgreSQL。服务地址决定使用哪个环境及数据库；本地、AWS 的凭据和数据各自独立。

- 模拟器访问 Mac 后端：`http://10.0.2.2:8000`。
- USB 真机：执行 `adb reverse tcp:8000 tcp:8000`，将 App 的 Agent 与 People 服务地址都设为 `http://127.0.0.1:8000`。
- AWS 环境：使用团队配置的 HTTPS API 地址及对应凭据。

本地后端需要单独启动，配置和数据库准备见 [Backend 说明](../backend/README.md)。使用 `scripts/setup-android-agent.py` 生成仅 Debug 使用的本地 API 配置；该配置、SDK AAR 和摄像头登录配置均不提交。SDK 登录、网络连接与 Backend 查询分别验证；安装成功不表示后端已启动或数据库已有可查询事件。Android README 中的环境回读记录属于当时本机联调结果，不代表本次安卓提交同时包含后端及算法工作区改动。

构建字节码目标仍为 Java 17；运行 Android 36 的 Robolectric 全量测试时使用 JDK 21（Android Studio 自带 JBR 21 可用）。
