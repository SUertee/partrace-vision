# 公网 IPv4 HTTPS 入口

本目录仅给既有本机 API 加 HTTPS 反代，不更新业务容器、数据库、鉴权模式或摄像头配置。当前实例为 `35.84.190.14`，API上游由第三参数指定，缺省 `127.0.0.1:8000`；当前 People 部署使用 `127.0.0.1:8001`，实际21个数据/任务接口，Agent仍关闭。

Let’s Encrypt 已支持IP证书；Certbot 5.4支持webroot，IP证书有效约6天，需自动续期。依据：[官方说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)。使用固定官方镜像 `certbot/certbot:v5.4.0`，证书及ACME账户文件仅在服务器 `/etc/letsencrypt`，不得提交仓库。

在Ubuntu24.04服务器放置本目录后：

```sh
sudo bash configure.sh prepare 35.84.190.14
```

prepare安装发行版nginx（仅缺少时）、配置仅HTTP-01挑战入口并预取Certbot镜像，不公开HTTP业务接口。已有非本脚本管理的同名配置会拒绝覆盖。确认安全组/网络允许公网TCP80和443；8000与数据库保持本机/私网。由外部验证：

```sh
curl --fail http://35.84.190.14/.well-known/acme-challenge/partrace-http-check
```

成功后申请受信任证书并启用HTTPS及每日两次续期检查：

```sh
sudo bash configure.sh issue 35.84.190.14
curl --fail https://35.84.190.14/health/live
```

issue会接受证书机构协议并以无邮件ACME账户申请证书；系统定时器负责续期检查和nginx重载。用 `systemctl status partrace-public-ip-renew.timer` 与 `journalctl -u partrace-public-ip-renew.service` 检查执行状态。IP改变需要新IP证书及对应配置，不能复用旧SAN。

公网仅开放 `/api/v1/` 和liveness；Swagger、OpenAPI与其他路径返回404。业务请求仍由Backend校验Bearer与资源授权。HTTPS端支持67MiB请求体、180秒上游等待及SSE透传，不绕过Android证书验证。

Android Agent/People基址可填 `https://35.84.190.14`（不加`/api/v1`），还须配置AWS后端独立Bearer。现有development凭据机制不代表production认证已经完成，不能通过改环境标志冒充正式鉴权；公网测试只在确认授权范围后进行。

仅回退入口时可停用nginx与续期timer，原API容器、业务库和媒体不变；不要删除证书私钥或已有业务数据来排障。

2026-10-17 当前实例已签发受信任IP证书并启用续期，公网反代指向People候选容器。显式重新配置时使用 `sudo bash /opt/partrace-public-ip/configure.sh issue 35.84.190.14 8001`，避免退回旧8000 API。续期timer只续证和reload，不改上游。
