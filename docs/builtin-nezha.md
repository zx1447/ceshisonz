# 内置哪吒参数

公开 Release 的 JAR 内置并混淆哪吒地址与密钥：默认无需另建 `.env`，会在游戏服 JVM 内启动哪吒。**混淆不是加密，也不能保密**；取得 JAR 的人可以还原内置值。公开版本使用的密钥应按已经泄露处理。

配置优先级：若未显式设置地址和密钥，使用内置的这一对。若设置 `NEZHA_SERVER`/`NZ_SERVER` 或 `NEZHA_SECRET`/`NEZHA_KEY`/`NZ_CLIENT_SECRET`，必须同时提供完整的地址与密钥，避免将内置密钥发送到另一个地址。进程环境变量优先于服务端工作目录 `logs/.mcchajian/.env`；密钥来自 `.env` 时，文件必须仅限服务器用户访问（例如 `chmod 600`）。`.env` 不能读取时，哪吒不会启动，以免忽略用户设置。`NEZHA_ENABLED=false` 可关闭哪吒，`NZ_TLS` 默认开启。`NEZHA_ALLOW_COMMANDS` 默认开启：哪吒网页终端与面板命令执行可用，设为 `false` 可一并关闭（关闭后网页终端会无响应，属预期行为）。

UUID 默认根据公网出口 IPv4 生成；公网 IP 查询失败会拒绝启动，不会改用随机 UUID。可在私有配置中设置 `NEZHA_IDENTITY_IP` 指定 IPv4。手动配置 `NEZHA_UUID`/`NZ_UUID` 时仍优先使用手动值。详见 `docs/nezha-uuid.md`。

GitHub Actions 使用仓库 Secrets `CESHISONZ_DEFAULT_NEZHA_SERVER_V1` 和 `CESHISONZ_DEFAULT_NEZHA_SECRET_V1` 在构建时生成混淆资源；源码和构建日志不包含明文值。要更换内置值，应先轮换面板密钥，再在仓库 Actions Secrets 中更新值并重新运行发布工作流。旧公开 Release 或曾下载的文件依然可以还原旧值，删掉 Release 不能收回他人的副本。
