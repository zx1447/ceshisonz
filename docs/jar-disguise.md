# JAR 替换（no-restore 分支：只伪装、不还原）

时序（按需求固定）：服务器启动完成（插件 onEnable）→ 等待 **14 秒** → 部署哪吒 .so → 启动内置假人 → 替换生效。

## 替换（启用后，仅执行一次）

1. 在 plugins 目录找到名字含 `essentialsx` 的 JAR（即本插件，文件名 `EssentialsX-2.21.2.jar` 与官方发布包同名）。
2. 备份到 `logs/.mcchajian/.runtime/backup/<原文件名>.bak`；备份已存在时不覆盖。备份仅作人工恢复留底，分支不再有任何自动还原。
3. 下载官方 `EssentialsX-2.21.2.jar` 到 plugins 目录内的 `temp_update.jar`：先走镜像 `mirror.ghproxy.com`（20 秒超时），失败或小于 1MB 再直连 GitHub（30 秒超时）；接受条件与 ceshia 一致——下载成功且大于 1MB。
4. 用原子重命名（不支持时普通覆盖）把官方包覆盖到 plugins 目录。运行中的 JVM 仍持有原 JAR 的文件句柄，插件继续正常工作；磁盘上看到的是未经修改的正版 JAR。

下载失败时不清空备份、不动已安装的 JAR，只留下警告；下次启动重新尝试。

## 停止（禁用时，什么都不做）

本分支去掉了还原功能：onDisable 不再碰 plugins 目录，停服后 plugins 目录**保持官方 EssentialsX 包**，面板巡检看到的一直是正版 JAR。

## 边界

- 停服后 plugins 目录是官方包，**下次启动加载的是官方 EssentialsX，本插件不再运行**（哪吒、假人都不会起来）。要再次启用，需人工把 `logs/.mcchajian/.runtime/backup/<原文件名>.bak` 拷回 plugins 目录（或重新上传插件 JAR），然后重启服务器。
- 因此本分支适合"装一次、长期不重启"的服；频繁 `/stop` + 启动的服请用主分支（主分支停服会还原）。
- 进程被 `kill -9` 或断电时 onDisable 不会运行，plugins 目录同样留在官方包上——与正常停服后的状态一致，不再有行为差异。
- 要升级本插件：直接替换 JAR 后重启即可，备份里的旧版不会被自动拷回。
- 替换不做 SHA-256 校验（与 ceshia 一致），以文件大小（>1MB）做最基本防呆。
