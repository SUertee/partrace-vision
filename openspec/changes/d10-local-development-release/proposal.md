# D10 · 本地开发源码整合交付

## Why

用户授权把当前本地开发推送成一个版本。关联 #2、#4、#5、#6、#7、#8、#9、#10，但本次不关闭业务工作包。此前 Android/People/worker/Skill 主要保留在本地，远端 dev 只包含部分契约，不能复现已有开发功能。

## What Changes

固定本地源码快照，在独立工作区整合最新 dev，保留 shelf/person/cards 与既有 UTC 规则，修正整合冲突和编译错字，同步契约/样例/结构图及版本边界。已有行为由各 D02/D04/D06/D07 change 约束，本 change 仅追踪源码整合和可交接验证，skip_specs，不新增业务需求。AWS Bedrock/S3 提案保留为未实施设计。

## Impact

涉及 Android、Backend、Vision、Agent、infra 和文档；不读取或提交私有配置，不改原开发现场，不部署云端、不更改业务数据。验证范围见[版本说明](../../../docs/releases/DEVELOPMENT_20261017.md)，不会以代码提交代替 B/Agent/人工核查/D10 验收。
