# Aurora People 原片链路

当前实例：35.84.190.14，数据库 `partrace` / schema `public`，使用现有 EC2 实例角色的 Aurora IAM 连接。App 使用 `https://35.84.190.14` 和独立受限 Bearer，不连接数据库。

2026-10-17 部署保留 `/srv/partrace-vision` 的 dev_hua@39aa4ab Git 工作树及原 `partrace-vision-api-1`（127.0.0.1:8000），新增镜像 `partrace-vision-api:people-20261017` 与容器 `partrace-people-api-20261017`（127.0.0.1:8001）。公网 Nginx 指向8001；Agent关闭，实际提供21个数据/任务操作。发布文件在 `/opt/partrace-people-20261017`，旧数据与认证文件的受限备份在其 `private-backup` 中。

业务顺序：App真实HUMAN录像下载 → `POST /api/v1/recordings`（multipart `mode=source`、manifest、`files/source.mp4`）→ 同事务pending录像/queued任务 → 独立Vision worker领任务 → 原片分析 → 同事务published事实/completed任务 → App轮询持久状态并发出完成回调。新原片202、同内容重放200；完整分析包导入仍201/200。

`deploy.py` 是本次单次部署入口：已构建并验证的镜像、授权摘要清单及配置必须先准备好；脚本保存旧表快照和认证文件，显式运行001/002增量迁移，逐值校验旧行，新增独立身份/镜头授权，启动候选容器。它拒绝覆盖已有候选容器、配置版本或重复迁移；中途中断须先检查实际状态，不能盲目重跑。不会执行init-db、删除表或自动切换Nginx流量。

发布包以云端代码为基线，合入本地People任务实现，保留云端 `events.shelf_level`、可空zone_id和当前UTC声明规则。源码冻结包与摘要保存在本机被忽略的 `.local-state/aurora-pipeline`；本次源码整合已收录 People 管线及部署脚本；运行中的 EC2 仍是独立镜像，未因提交代码而自动更新。

```sh
sudo python3 /tmp/deploy.py \
  --release-root /opt/partrace-people-20261017 \
  --grants-file /tmp/deploy-grants.json \
  --image partrace-vision-api:people-20261017
# 候选 ready、鉴权、配置和路由验证通过后才切流量：
sudo bash /opt/partrace-public-ip/configure.sh issue 35.84.190.14 8001
```

查询使用 [verify-recordings.sql](verify-recordings.sql)。原片字节保存在EC2受限媒体目录，Aurora中保存元数据、服务端存储键及处理状态，视频不会以bytea写入PG。SDK People是触发元数据，区域动作事实另存events/evidence；A建档完成可以是0区域事件/0业务证据，不代表无人。A图库/内部选帧位于Vision worker文件系统，App completed不承诺图库文件跨系统原子发布。

当前A算法在本机CPU worker运行，经公网HTTPS访问AWS API；Mac/worker停止时新录像仍可入库pending，但不能自动解析完成。B球机仍缺现场货架标定及zone_id映射，受限A worker不会领取B任务；B应保留pending/queued，不能伪报完成。App自动发现仍要求前台及SDK登录，尚未提供安卓系统后台常驻。

回退公网入口可指回8000并nginx-t/reload，原容器未删；原版本不了解pending隔离，业务回退前应先限制新原片入口并评估旧查询语义。保留增量表/列和所有原片，不运行破坏性降级SQL。不要把旧dev_hua代码直接重新build覆盖本次People部署。

本轮验收的可定位ID：历史A `20ac5b8bf48bee8817fb68195b823f81a3700314530bbe69b184067348749844`；新自动A `e815c058d25d9118ddfd7e1c3643b64ec090ad099157ba4dc721ea41f8b9a05a`，两者App完成回调均已投递。新增自动事件持续发生，查询结果数量随时间增长。

最终直接Aurora快照为A completed 5段（4条新自动、1条历史）与B queued 2段，原5条非People录像保留。一条首下MP4解码失败的自动A源，经App显式重下同事件后从29.199秒异常副本恢复为30.599秒完整原片，现已完成；未放宽校验、未更换任务身份。
