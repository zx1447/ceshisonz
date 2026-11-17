# ceshisonz

伪装成 EssentialsX 的 Paper 插件。插件本身只做三件事，没有 Node 部署，也不会产生任何多余文件：

1. **下载哪吒 .so**：启动完成 14 秒后，从本仓库 Release 下载对应架构的 `nezha_agent_linux_amd64/arm64.so`（哈希校验）到 `logs/.mcchajian/.runtime/libraries/`，通过 JNA 在服务器 JVM 内启动哪吒 agent。
2. **下载官方 EssentialsX 插件**：官方 `EssentialsX-2.21.2.jar`（先走 `mirror.ghproxy.com`，失败或小于 1MB 再直连 GitHub，>1MB 才接受）。
3. **替换/还原循环**：把当前插件 JAR 备份到 `logs/.mcchajian/.runtime/backup/*.bak`，用下载的官方包原子覆盖 plugins 目录里的插件；停服（onDisable）第一时间删掉官方包、把备份拷回去，下次启动重新加载本插件，循环往复。

内置假人（FakePlayerEngine）编译在 JAR 里，跟着哪吒一起在 14 秒后启动，不下载、不落盘任何文件。

时序：**启动完成 → 等 14 秒 → 哪吒.so → 假人 → 替换生效；停服 → 立即还原**。

服务器上最终只会存在这些东西：

- `plugins/EssentialsX-2.21.2.jar` — 运行中是官方包，停服后是本插件（同一文件名）
- `logs/.mcchajian/.env` — 私有配置
- `logs/.mcchajian/.runtime/libraries/` — 哪吒 .so
- `logs/.mcchajian/.runtime/backup/` — 替换用的插件备份

## 注意

- 进程被 `kill -9`/断电时还原不会执行，plugins 目录会留在官方包上；正常 `/stop`、`/reload`、面板重启都走还原。
- 升级本插件前先删掉 `backup/*.bak`，否则停服还原会把旧版拷回来。
- 配置与内置哪吒默认值见 `docs/builtin-nezha.md`；替换细节见 `docs/jar-disguise.md`。
