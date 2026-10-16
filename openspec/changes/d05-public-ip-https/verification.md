# 公网 IP HTTPS 接入验证 · 2026-10-17

用户明确授权在35.84.190.14安装Nginx、拉取固定Certbot、写独立入口并启用HTTPS/续期。首次远程变更被自动审批认定只读授权而拒绝；取得明确授权后重新执行，未绕过拒绝。

- SSH密钥权限由0644收紧0600，未输出密钥内容。
- 远端代码39aa4ab/dev_hua，API14基础操作，Agent关闭，无recording-jobs；development，数据库IAM=true，Aurora主机，原ready200。
- Ubuntu24.04.4；无原nginx，UFW inactive。Nginx发行版1.24.0-2ubuntu7.18已装，官方Certbot5.4镜像已拉取（manifest digest sha256:c23159d30afdd9c97960578aa4654f5901de6cae394958f894074dedd55e599d）。
- 本地隔离nginx:1.28-alpine容器分别对HTTP/HTTPS模板nginx-t通过，无暴露测试端口，临时自签证书只用于语法检查且已删除；服务器nginx-t通过。
- 服务器127本机证书检查路径返回partrace-acme-ready，明文/api/v1/recordings为404；原API ready200、业务仍只绑127.0.0.1:8000。
- 实例安全组sg-0d5ecf64d23b78484；EC2 DescribeInstances与AuthorizeSecurityGroupIngress dryrun均被IAM拒绝，未修改安全组。已请求用户在控制台新增TCP80/443入站。
- 服务器已监听80，但外部挑战请求仍超时；本次尚未申请证书、启用443或安装续期timer，不能宣称公网HTTPS/App连接完成。
- 新文件位于仓库infra/public-ip，远端副本/opt/partrace-public-ip；原业务容器/数据库/服务器git工作树未修改。配置与shell语法/OpenSpec验证通过。

## 公网已启用（2026-10-17）

- 外部 HTTP-01 验证文件已返回200；实际签发 Let’s Encrypt IP SAN 证书，openssl checkip 匹配35.84.190.14，到期日2026-10-23。未跳过客户端证书验证。
- 外部HTTPS /health/live 200；缺少授权 /api/v1/user 401；/docs 与 /openapi.json 404。
- 已安装并启用每日两次检查的续期timer；手动执行 renew.service 返回Result=success / ExecMainStatus=0，包含nginx-t和重载。
- 随后用户要求硬件原片进入Aurora，增量部署People候选API到127.0.0.1:8001；原API8000保留。公网反代改为8001，今后显式重新运行issue时须传第三参数8001。
- 新容器实际21个数据/任务操作，Agent仍关闭；独立AWS App Bearer已通过公网读取user、station_sdk_v1配置、录像列表。Android配置和真实录像验收另见D06验证。

- Android已实际使用公网HTTPS完成SDK历史A与新自动A上传、完成回读及提示；球机B新自动原片已受理排队。至此本提案的公网入口/授权App调用要求已验证，People/算法未验收边界仍见D06，尤其B现场标定和本机worker运行依赖。
