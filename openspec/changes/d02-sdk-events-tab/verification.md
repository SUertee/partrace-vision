# 摄像头检测事件页 · 2026-10-17

> 同日后续实现已将卡片改为先进入共用事件过程，再显式打开原 SDK 回放；跨设备同 ID 的记录使用组合身份保留。最新实现、52 项测试与预览见 [D08 联动验证](../d08-agent-event-detail/verification.md)。下文保留事件页初次交付的实际凭据。

## 交付范围

- 在单一 `android/` 工程内增加「事件」根入口，四入口为首页／事件／AI 追溯／我的；AI 保留原全屏聊天。页面源码在 `station/ui/events`，数据适配在 `station/events`，未新增前端工程或依赖。
- 直接使用官方 ESIotKit 2.2.7 的当前账号摄像头与 `MegaEventManager.query`。本机日期、设备及检测类型约束经过本地二次过滤，事件去重并倒序；卡片复用真实图片请求与原回放屏幕。缺录像时只打开检测元信息。
- 查询限制 30 秒、200 条，离页／后台／切换筛选取消请求，迟到回调不能覆盖当前范围。SDK 成功回调可能含缓存或部分来源结果；界面明示缓存和达限提示，不承诺完整云端历史。无返回不解释为已证明全站没有事件。
- 同范围结果保留在 Voyager 根 ScreenModel 内存，账号退出销毁根状态；跨日期／设备／检测范围不展示旧卡片。日期、筛选和列表位置通过现有页面 SaveableStateProvider 恢复，SDK 事件不放入 saveable。刷新及已有结果的错误提示位于列表外，避免在回放返回时移动索引。
- 不修改 Backend／Agent／Vision、DB 或 API，不自动上传／下载／删除，不启动分析。站点标题仍是本地分组。

## 本次构建与测试

环境：macOS arm64，JDK 17.0.2.8.1-amzn，Gradle 8.13／AGP 8.11.1／Kotlin 2.2.21。`android/` 为 Gradle 根，官方 AAR 与私有配置沿用本地现有文件，未写入验证资料。

最终在 `android/` 执行：

```sh
JAVA_HOME=/Users/suerte/.sdkman/candidates/java/17.0.2.8.1-amzn ./gradlew \
  :core:testDebugUnitTest --tests '*StationEventsTest' --tests '*TraceAgent*' --tests '*SdkDeviceOrder*' \
  :app:assembleDebug :app:lintDebug --console=plain
```

结果：`BUILD SUCCESSFUL in 1m 31s`。本机完整日志为 `/private/tmp/partrace-sdk-events-tab-final2-20261017.log`；最终 APK 为 `android/app/build/outputs/apk/debug/app-debug.apk`，本轮没有安装到设备。

| 测试组 | 通过 |
| --- | ---: |
| StationEventsTest | 15 |
| TraceAgentContractTest | 4 |
| TraceAgentNetworkTest | 5 |
| SdkDeviceOrderTest | 3 |
| 合计 | 27 |

全部失败、错误、跳过均为 0。事件测试覆盖本地日边界及 23／25 小时夏令时、设备／类型隔离、排序去重、SDK 查询约束、原始 200 条计数、失败、超时、重复／迟到回调、取消注册竞态、数据快照失败和取消钩子异常。既有 Agent 与设备排序测试是相关回归，不替代设备实测。

lint 最终报告 133 项 Warning、0 Error/Fatal；源码复核没有发现新增实质问题。共享底栏、根路由、状态栏和 SDK 恢复白名单均已加入事件入口。真实返回滚动位置仍按下述操作待验。

## 界面凭据

使用已运行 Android Studio Rabbit 1 / 2026.2.1（ParTraceVision，pid 67753）的 CLI Compose 渲染。预览函数位于 [StationDesignPreviews.android.kt](../../../android/core/src/androidMain/kotlin/com/anker/esiotkit/sample/main/station/StationDesignPreviews.android.kt)，只传虚构数据和灰色图片占位，不初始化 SDK 或 People 协调器。

```sh
android studio render-compose-preview \
  android/core/src/androidMain/kotlin/com/anker/esiotkit/sample/main/station/StationDesignPreviews.android.kt \
  StationEventsListDesignPreview --project ParTraceVision --pid 67753 \
  --output-image-file /private/tmp/partrace-events-list-studio.png
```

分别调用 `StationEventsListDesignPreview`、`StationEventsEmptyDesignPreview`、`StationEventsFailureDesignPreview`，产出图片后逐张目检：蓝白背景、日期与设备筛选、横向类型、圆角白卡、失败重试、空态及四项导航正常。列表底部保留滚动内容间距；截图中的第三张卡片尚未滚动到完整视野，不能据截图声称滚动操作已验证。

| 状态 | 本轮保存的预览 |
| --- | --- |
| 事件列表 | [events-list.png](../../../android/design/rendered/events-list.png) |
| 暂未返回事件 | [events-empty.png](../../../android/design/rendered/events-empty.png) |
| 查询失败 | [events-failure.png](../../../android/design/rendered/events-failure.png) |

图片为 1082×2407px，预览框 412×917dp，明确标注「设计预览·模拟事件」。它们不是最终 APK 的设备截图，也不含真实摄像头图像、账号或凭据。另渲染过加载预览，但 Studio 缓存仍显示旧的禁用「刷新」文案；最终编译源码为「刷新中」，该图片不作为最终状态凭据保存。

## 文档与检查

同步 Android 源码索引、四入口说明、M01／M09、D02 和设计预览资料。以下本次检查均通过：

```sh
git diff --check
openspec validate d02-sdk-events-tab --strict --no-interactive
python3 scripts/export-schema-catalog.py --check
python3 scripts/build-schema-canvas.py --check
python3 scripts/sync-workbench-schema.py --check
python3 scripts/build-api-canvas.py --check
python3 scripts/sync-workbench-api.py --check
```

上述五项为现有离线设计同步检查，不访问或验证生产数据库。受影响 Markdown 的本地相对链接另检查通过。当前 change 保留用于评审，未归档，不关闭 D02／D06 Issue。

## 未验证与后续验收

本轮没有覆盖安装最终 APK，没有操作现有模拟器中的摄像头账号或 People 自动任务，也未以历史 SDK 成功记录冒充本轮新入口实测。仍需在最终安装版本验证：

1. 当前账号真实查询、缩略图与检测类型／日期／单台设备筛选。
2. 列表滚到中段 → 打开真实录像 → 返回，筛选及列表位置恢复。
3. 快速切换条件、查询中切后台、退出账号后旧记录消失。
4. SDK 失败／超时及达到 200 条的实际设备表现。
5. Android 真机、系统栏、多分辨率及放大字体。

这些是设备验收项，不影响本轮实现与离线验证的交付记录；不得据此宣布 SDK 全部能力、Android 真机或 D10 完成。
