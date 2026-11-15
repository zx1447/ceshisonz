# ceshisonz — 假人 + 哪吒 .so

这是自定义 Paper 插件，不是官方 EssentialsX 功能实现。为兼容现有部署，`plugin.yml` 仍使用 `EssentialsX`、版本 `2.21.2`；插件 JAR 版本号仍是 `1.21.11`。

- 保留 `ceshia` 的内置假人：默认 2 人、14 秒复活、原英文名字和对话；假人设置仍可通过 `logs/.mcchajian/.env` 热重载。
- 不下载、不部署、不启动 Node、Cloudflare 隧道或它们的守护程序；也不会删除服务器上已有的 `app/`、Node、CF 或其他用户数据。
- 哪吒功能已配置时，插件在后台从本仓库的**对应 Release**下载 Linux `.so`，核对 JAR 内绑定的 SHA-256，然后通过 JNA 加载到**当前游戏服 JVM**。不启动单独的哪吒进程。当前提供 Linux glibc x86-64 和 ARM64，不保证 musl/Alpine 兼容。
- 更新功能是透明更新：明确启用、指定 URL 和 SHA-256，先备份当前插件，再把新 JAR 放入 Bukkit 更新目录，等下一次正常重启生效。不会在运行期间换成诱饵 JAR，不会自动重启服务器。

## 必须了解的风险

原生 `.so` 与 Minecraft 在同一 JVM：即使已移除已知的直接退出路径，原生崩溃、依赖冲突或 Go 运行时异常仍可能导致整个服务器退出。先在有备份的测试服验证，避免生产环境使用 `/reload`。插件停用会请求停止哪吒监控；Go 共享库本身会保留加载到 JVM 退出，停止超时会在日志中提示。

密钥**没有写入源码、JAR 或 `.so`**，启动时从服务器私有环境变量或 `.env` 读取并传给内存中的库，不作为进程命令行参数，也不生成明文哪吒临时配置文件。但服务器管理员、同用户进程或具有足够权限的人仍可能读取环境、配置或进程内存，不能承诺对服务器所有者隐藏。不要把真实密钥提交到公开仓库、Release 或日志；泄露后在面板轮换。

## 配置

在服务器已有的 `logs/.mcchajian/.env` 中加入以下字段，或通过服务器的私有环境变量注入。示例均为占位符，不是可用凭据。不要删除原有文件或其他字段。

```properties
NEZHA_ENABLED=true
NEZHA_SERVER=your-panel.example:443
NEZHA_SECRET=replace-with-your-private-agent-secret
NEZHA_TLS=true
# 可选；不填则自动创建并保存唯一 UUID 到 .runtime/ceshisonz-nezha.uuid
NEZHA_UUID=
# 仅监控默认禁用面板命令执行；确有需要且信任面板时可设置为 true
NEZHA_ALLOW_COMMANDS=false

FAKE_PLAYERS_ENABLED=true
FAKE_PLAYER_COUNT=2
FAKE_PLAYER_RESPAWN_SECONDS=14
FAKE_PLAYER_CHAT_ENABLED=true
FAKE_PLAYER_NAMES=
```

使用 `.env` 保存密钥时，应将文件权限设为 `600`；插件发现其可被其他用户或组读取/写入时会拒绝启动哪吒。环境变量优先于 `.env`，也兼容 `NZ_SERVER`、`NZ_CLIENT_SECRET`、`NZ_TLS`、`NZ_UUID`。只有假人设置支持热重载，哪吒设置更改需正常重启。若要沿用面板上的原有服务器记录，请配置该记录原来的 UUID；未设置时会生成新 UUID，面板可能将其识别为新设备。

哪吒自动更新和强制更新被禁用，避免原生库修改或退出游戏服；默认禁止面板远程命令及哪吒 NAT。启用 `NEZHA_ALLOW_COMMANDS=true` 后，受信任面板可以执行命令，可能产生系统子进程。由于配置只在内存中，面板远程修改/转移配置不作为持久配置支持。需要变更地址、密钥等参数时请修改服务器私有配置并正常重启。

## 透明插件更新（可选）

默认关闭。确认下载来源与 SHA-256 后，才在私有环境或 `.env` 中配置：

```properties
PLUGIN_UPDATE_ENABLED=true
PLUGIN_UPDATE_URL=https://example.invalid/path/to/verified-plugin.jar
PLUGIN_UPDATE_SHA256=replace-with-64-hex-characters
```

仅 HTTPS，必须提供准确的 SHA-256；只处理自身的插件 JAR，拒绝覆盖已有待更新文件。原 JAR 经校验后备份至 `logs/.mcchajian/.runtime/backup/`，新 JAR 放进 Bukkit 配置的更新目录，下一次正常重启加载。若需要再次更新，应更新 URL 和 SHA-256。不会伪装正在运行的代码。

## 构建和来源

GitHub Actions 在公开仓库中构建两种架构的 `.so` 和 JAR，在 CI 中以无真实密钥的回环模拟面板检查身份信息、监控上报及原生启动/停止，再创建带唯一标签的 Release。JAR 内记录当前 Release 的 `.so` 地址及散列；**通过模拟面板检查不等于连接到你的真实面板**，实际连接需在自己的面板确认。

假人源码来自 `zx1447/ceshia` 的 `b131e04`，功能代码未改。哪吒 `native/main.go` 基于 `zx1447/nxso` 的 `6cb39a85dd0528788aad3d1f46dfc004babd0a13`，构建时使用锁定的 Nezha Agent v2.3.0 提交 `84e61fca84661503d324ae6ab00ca88e280e00a2`；上游 Nezha Agent 的 Apache 2.0 许可证见 `native/LICENSE-nezhahq.txt`。本版本不将密钥写入公开源码。
