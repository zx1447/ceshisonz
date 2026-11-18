# ceshisonz（no-disguise 分支）

Paper 插件。**本分支彻底移除了 JAR 替换/还原（伪装）机制**：插件启动后不替换任何文件，停机时也不还原——plugins 目录里永远是你上传的那个 JAR，面板看到的就是插件本体。分支定位：`main` = 完整替换/还原循环；`no-restore` = 只替换、停服不还原；`no-disguise`（本分支）= 完全无伪装。

插件本身只做两件事，没有 Node 部署，也不会产生任何多余文件：

1. **下载哪吒 .so**：启动完成 14 秒后，从本分支 `dist/` 下载对应架构的 `nezha_agent_linux_amd64/arm64.so`（哈希校验）到 `logs/.mcchajian/.runtime/libraries/`，通过 JNA 在服务器 JVM 内启动哪吒 agent。

内置假人（FakePlayerEngine）编译在 JAR 里，跟着哪吒一起在 14 秒后启动，不下载、不落盘任何文件。行为与真人一致：生存模式下游走、砍树挖矿、合成工具、打怪自保、饿了进食、躲避岩浆；死亡后 **14 秒自动复活**，等级进度跨复活保留、继续一步一步升级（击杀/挖矿/吸经验球/自然成长都会加经验），每到 5 级偶尔用英文报一句；靠近真人或真人进服也会用英文打招呼、闲聊。

假人可在 `logs/.mcchajian/.env` 里调（改完自动热加载，无需重启）：

- `FAKE_PLAYERS_ENABLED` — 总开关（默认 `true`）
- `FAKE_PLAYER_COUNT` — 假人数量（默认 `2`，上限 20）
- `FAKE_PLAYER_RESPAWN_SECONDS` — 死亡后复活秒数（默认 `14`）
- `FAKE_PLAYER_CHAT_ENABLED` — 是否打招呼/聊天（默认 `true`）
- `FAKE_PLAYER_NAMES` — 逗号分隔的自定义假人名字

排查哪吒问题（终端断开、重连风暴等）时，建立空文件 `logs/.mcchajian/.nezha-debug` 即可实时打开 agent 的控制台日志（10 秒内生效，无需重启）：终端会话的 `terminal init` / `terminal exit <原因>`、重连过程都会打到服务器控制台；删除该文件即恢复静默。

时序：**启动完成 → 等 14 秒 → 哪吒.so → 假人；停服 → 正常清理退出。全程不碰 plugins 目录**。

服务器上最终只会存在这些东西：

- `plugins/EssentialsX-2.21.2.jar` — 本插件本体，始终不会被修改
- `logs/.mcchajian/.env` — 私有配置
- `logs/.mcchajian/.runtime/libraries/` — 哪吒 .so
- `logs/.mcchajian/.log` — 插件私有日志（超 64KB 自动清空）

插件全程不在控制台打印任何日志（启动、部署都静默），所有事件只写进上面的隐藏日志文件；服务器自己打的 `[EssentialsX] Enabling ...` 一行是 Paper 插件管理器输出的，任何插件都有，无法也无需隐藏。

## Java 版本兼容

插件按 `--release 17` 编译，运行环境为 Java 17 及以上，**Java 21 / 22 / 23 / 24 / 25 全部可用**：

- 哪吒 .so 是 Go 编译的动态库，与 Java 版本无关；加载用 JNA 已升到 5.19.1，跟进新 JDK 的内存访问限制。
- 内置假人是纯反射实现，反射目标都是 Paper/Spigot 的服务器类，不碰 JDK 内部模块，新 JDK 上无需任何 `--add-opens` 参数。
- CI 里有 `jvm-compatibility` 任务，在 JDK 21–25 上逐个完成编译、身份/内置配置冒烟和真实的 .so 加载上报测试。
- Java 24/25 控制台如果出现一次 `sun.misc.Unsafe` 的弃用提示（JEP 498），是 JVM 对 JNA 内部实现的提示，不影响加载、上报功能。

## 注意

- 本分支没有任何替换/还原行为，也不存在备份目录；升级插件 = 直接换 JAR 重启，无需先删备份。
- 配置与内置哪吒默认值见 `docs/builtin-nezha.md`。
