# AWell

将本地目录中的照片显示为动态壁纸。前景照片完整居中、等比显示，背景可选黑底随机矩形或同图模糊拉伸。支持独立的解锁更换与定时更换，照片和背景同步更新。

## 使用

1. 打开应用，点击“选择照片目录”，在系统选择器中授权一个本地文件夹。
2. 应用递归读取所有可访问子目录的图片。只申请持久读取权限，不修改原始文件。
3. 点击“设置动态壁纸”，在系统预览中应用。
4. 在“自动更换”中分别设置解锁更换与定时更换。默认解锁开启、定时关闭；定时间隔可选 15 分钟、30 分钟、1 小时、6 小时、24 小时。
5. 在“背景效果”中选择“随机矩形”或“模糊拉伸”（默认随机矩形）。模糊背景用同一张照片等比铺满、模糊并压暗，前景保持完整清晰；切换效果不换照片、不重置计时。
6. 多张照片避免连续重复。单图目录的有效更新在随机模式刷新矩形，模糊模式画面可能相同，但仍按成功更新重新计时。照片留白和透明区域显示所选背景。

目录授权可跨应用进程重建和设备重启保留。目录失效时可重新选择；空目录在随机模式显示矩形、模糊模式显示黑底，临时读取失败保留上一组画面。

DataStore 保存设置、最近照片和单个计时基线，并自动迁移旧版目录配置。开启定时后等待完整间隔；任一成功换图后重新计时。启动、恢复可见或 Surface 就绪时先检查到期条件：未到期恢复旧图，已到期在可绘制后补换一次，漏过多个周期也不连续切换。隐藏期间不唤醒设备或扫描照片。普通重绘和读取失败不会推迟基线；定时开启且可见时，临时失败一分钟后重试。

无安全锁屏及厂商锁屏的解锁广播行为需要在目标设备验证；服务未运行期间无法回补历史解锁。定时使用系统 epoch 时间，接受手动改时引起的间隔偏差。设置与计时存储不参与系统备份或设备迁移，新设备需重新选择目录。

[功能 Spec](docs/specs/local-photo-wallpaper.html)

[定时更换壁纸 Spec](docs/specs/scheduled-wallpaper.html)

[模糊拉伸背景 Spec](docs/specs/blurred-photo-background.html)

## 验证

Release 默认通过系统钥匙圈签名，首次配置见 [Gradle 钥匙圈签名说明](docs/keyring-signing.html)。配置完成后直接运行 `./gradlew :app:assembleRelease`。

```sh
./gradlew --no-configuration-cache -PkeyringSigning=false :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew --no-configuration-cache -PkeyringSigning=false :app:connectedDebugAndroidTest
```

单元测试覆盖递归扫描、随机选择、JSON 兼容性与到期边界。设备测试覆盖真实 SAF 授权、DataStore 迁移及重开文件恢复、设置界面、到期与未到期启动、隐藏补偿、定时唤起、保存失败和预览隔离。SAF 测试使用测试 APK 自带提供者与独立 DataStore 文件，不操作用户照片或应用的实际目录配置。

完整验收还需按 Spec 在目标设备完成：通过系统选择器配置真实目录并应用壁纸，连续锁屏解锁、仅亮屏不解锁、等待正式定时间隔、系统回收重建、重启设备，以及撤销目录权限。自动测试中的解锁、时钟与 Engine 通过协调器注入，DataStore 测试通过关闭实例并重开文件验证恢复，不能替代系统锁屏广播、真实进程死亡、重启与实际壁纸 Surface 的端到端验收。

2026-10-27：Debug 构建、lint、17 个单元测试和 P0110（Android 16）上的 41 个设备测试通过；上述系统级端到端验收仍待完成。

若设备已有不同签名的应用，可使用独立 Debug 包测试并保留旧安装与数据：

```sh
./gradlew --no-configuration-cache -PkeyringSigning=false -PdebugApplicationIdSuffix=.debug :app:assembleDebug :app:connectedDebugAndroidTest
```

独立包名为 `top.ntutn.awell.debug`，使用独立设置与照片授权；不传该参数时包名保持不变。模糊背景只在画面变化时生成小尺寸缓存，失败回退黑底与清晰前景，不修改原始照片。

2026-10-31：模糊拉伸背景已实现，18 项单元测试与 NX888J（Android 16）上的 51 项设备测试通过，Debug 构建及 lint 通过（0 错误，6 项版本、旧图标资源及 KTX 建议）。已用指定照片目录的样本完成真实 SAF 授权、递归扫描、横竖照片桌面显示、两种背景切换不改 URI / 计时基线及壁纸进程终止后恢复验证。完整范围与未覆盖项见模糊背景 Spec 第 8 节。
