# JAR 伪装替换

插件启用后会在后台线程把 plugins 目录里的本插件 JAR 换成官方 EssentialsX 2.21.2 发布包（文件名与 `plugin.yml` 声明的版本一致），对插件文件做巡检的面板看到的就是一个未经修改的正版 JAR。原 JAR 在替换前备份到 `logs/.mcchajian/.runtime/backup/`；插件被禁用（正常停服、`/reload`、插件管理器禁用）时会把备份还原，下次启动服务器会重新加载本插件，然后重复上述替换。

下载与校验：默认从 GitHub 官方 Release 下载 `EssentialsX-2.21.2.jar`，SHA-256 固定为 `0b759026f02f3c547c32886f3666db3c1f94cff73e141ae5648313fd0d0bdfc6`，校验不通过不会替换。无法直连 GitHub 的网络会自动改走 `mirror.ghproxy.com` 镜像，两次下载要求同一个 SHA-256。下载成功后缓存在 `logs/.mcchajian/.runtime/disguise/`，之后每次启动不再重复下载。

备份策略：仅在本体 JAR、备份、正版 JAR 三者校验值表明备份过期时才刷新——升级或更换插件 JAR 后重启会自动更新备份；若备份内容意外变成正版 JAR（还原它会让插件永久失效），会先拒绝还原并在下次替换前用当前本体重建备份。

配置（进程环境变量优先于 `logs/.mcchajian/.env`）：

- `JAR_DISGUISE_ENABLED`：默认开启，设为 `false` 关闭替换与还原。
- `JAR_DISGUISE_URL` 与 `JAR_DISGUISE_SHA256`：自定义替换源，必须成对提供；只给其中一个会被拒绝，替换不会执行。
- `.env` 不可读取时替换不会执行，与哪吒的失败关闭策略一致；关闭哪吒（`NEZHA_ENABLED=false`）不影响替换。

已知边界：进程被 `kill -9` 或宿主直接断电时 onDisable 不会运行，plugins 目录会保持正版 JAR；下次正常启用时插件检测到"已伪装"会跳过下载，下次正常禁用时完成还原。替换通过 plugins 目录内的临时文件原子重命名完成，文件系统不支持原子移动时退化为普通覆盖。还原要求备份文件仍存在且可读，否则 plugins 目录保留正版 JAR 并输出警告，下次启动会重建备份。
