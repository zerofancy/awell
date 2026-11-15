# 开发约定

- 优先使用 Kotlin 开发；非必要不引入 Java，测试代码也遵循此规则。
- 时长统一优先使用 `kotlin.time.Duration`，常量和调用使用 `5.seconds`、`15.minutes` 等明确单位的写法；`delay`、`withTimeout`、`withTimeoutOrNull` 直接使用 Duration 重载。业务接口、回调、测试及多层传递链保持 Duration 类型，避免只在最终调用处将 Long 转成 Duration。
- 仅在持久化格式、平台或 Java API 边界转换时间单位；现有存储字段需保持兼容。区分时长和时间点：epoch 时间戳与单调时钟读数不作为 Duration 传递，保留明确的单位和时钟语义；跨层截止时间使用专用类型封装，同一操作共享截止时间，不在每层重新开始计时。

## 程序发布

- 发版 agent 从根目录 `local.properties` 读取 `updaterPublishBaseUrl` 和 `updaterPublishToken`。前者为完整发布前缀 `https://zp.ntutn.top/api/v1/publish/awell`，后者为仅绑定软件 `awell` 的发布凭据，包含 R2 上传权限。到期时间记录在该文件的注释中，到期后在服务后台重新签发并替换。
- `local.properties` 已被 Git 忽略，文件权限设为 `0600`。凭据仅供发版客户端读取，禁止输出到日志、命令行参数、提交、文档或聊天，也不得加入 APK、资源文件或 `BuildConfig`。
- 使用 `./gradlew :app:assembleRelease` 构建签名 APK。发布前核对 APK 的包名为 `top.ntutn.awell`、签名兼容性、实际版本和支持的 ABI，并查询线上版本，保证各目标的 `versionCode` 递增。软件包类型使用 `apk`，平台使用实际支持的 Android 架构配对，填写面向用户的纯文本更新说明。
- 通过 HTTP API 发版。以发布前缀下的 `POST /uploads` 登记文件名和字节数，直接 PUT 文件到返回的预签名 URL，再调用 `POST /uploads/{uploadId}/complete` 并确认状态为 `READY`。最后用 `POST /distributions` 一次提交全部包及更新说明，R2 包填写 `source=r2` 和 `uploadId`。保存成功后立即公开。
- 对本服务的请求使用 `Authorization: Bearer <token>`。R2 PUT 仅带返回的上传请求头。为上传登记和分发提交分别生成小写 UUID 作为 `Idempotency-Key`，在首次发送前保存键和请求内容。超时或结果未知时以原键、原内容重试，遵守 `Retry-After`。分发首次成功返回 `201`，原请求重放返回 `200`，同键不同内容返回 `409`。
- 完整字段、外链发布及错误处理见 [发布 API 规格](/home/zero/Project/updater-server/docs/publish-api-spec.html)。
