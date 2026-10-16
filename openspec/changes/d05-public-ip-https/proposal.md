## Why

用户要求Android经公网连接AWS后端，确认没有域名、使用35.84.190.14。远端API已健康并接Aurora，但只绑本机8000；需要受信任IP证书与可重复的HTTPS入口，不修改现有业务行为。对应D05，协作D06/D08；不关闭D10。

## What Changes

- 配置nginx的HTTP-01与受信任IP HTTPS，保留原API本机监听。
- 使用Certbot5.4固定镜像申请短证书，配置系统续期和重载。
- 保持Bearer验证，公网不提供开发文档；不跳过Android证书校验。
- 独立记录公网端口/证书/手机连接与未部署业务接口的边界。

## Capabilities

### New Capabilities
- `public-ip-api`: Public-IP HTTPS transport for the existing authorized API.

## Impact

影响infra入口和运维，不改变DB或API字段；安全组需有授权的AWS身份配置。远端目前14基础接口，People/Agent部署另验收。
