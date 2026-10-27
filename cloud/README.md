# FrameLink Cloud

Java17、Spring Boot3，MySQL生产存储；默认仅监听本机，使用H2和本地文件方便独立开发

```sh
FRAME_INVITE_CODE=local-dev-only mvn spring-boot:run
```

邀请注册需要设置`FRAME_INVITE_CODE`，默认禁用注册。摄影师使用邮箱与密码登录，客户使用链接和访问码。两类会话均可撤销，数据库只保存token摘要

生产环境设置`FRAME_DATABASE_URL`、数据库账号、`FRAME_STORAGE_MODE=s3`和`FRAME_S3_*`，使用私有桶，通过HTTPS反向代理提供API。账号默认10GB，分享默认7天，原片不自动上传

文件访问经过每次项目授权检查，支持HTTP Range。`POST /api/projects/{id}/media`只生成独立预览；交付文件需指定已确认照片的`parentId`。提交、确认和重开是独立操作，选片提交历史不可变

Web开发服务器默认代理`/api`到`127.0.0.1:8087`。部署时配置`FRAME_PUBLIC_URL`为客户网页地址
