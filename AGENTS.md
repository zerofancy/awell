# 开发约定

- 优先使用 Kotlin 开发；非必要不引入 Java，测试代码也遵循此规则。
- 时长统一优先使用 `kotlin.time.Duration`，常量和调用使用 `5.seconds`、`15.minutes` 等明确单位的写法；`delay`、`withTimeout`、`withTimeoutOrNull` 直接使用 Duration 重载。业务接口、回调、测试及多层传递链保持 Duration 类型，避免只在最终调用处将 Long 转成 Duration。
- 仅在持久化格式、平台或 Java API 边界转换时间单位；现有存储字段需保持兼容。区分时长和时间点：epoch 时间戳与单调时钟读数不作为 Duration 传递，保留明确的单位和时钟语义；跨层截止时间使用专用类型封装，同一操作共享截止时间，不在每层重新开始计时。
