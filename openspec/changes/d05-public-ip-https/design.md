## Decisions

入口分prepare与issue：先验证仅挑战HTTP入口，再在外部可达后签发证书。仅管理独立nginx文件；语法失败恢复原文件，既有业务容器和数据库不变。短IP证书约6天，每日两次续期检查、成功后重载nginx。公网API沿用现有Bearer鉴权，不开放开发文档。

## Limits

实例角色无法DescribeInstances，安全组未验证且未变更。production鉴权、People任务/Agent业务部署均不由TLS入口隐含完成。
