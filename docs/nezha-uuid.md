# 哪吒 UUID

默认以游戏服 **公网出口 IPv4** 确定性生成哪吒 UUID；同一 IP 每次启动得到相同结果，不使用游戏端口。算法与 `mcchajianindex` 的 `generateIPBasedUUID` 在相同标准 IPv4 输入下兼容。UUID 在哪吒启动时计算；公网 IP 后来变化，要在下次启动哪吒后才会重新计算。

如果手动设置 `NEZHA_UUID` 或 `NZ_UUID`，仍优先使用该 UUID，不会查询公网 IP。若未设置 UUID，也可在服务器私有环境变量或 `logs/.mcchajian/.env` 中设置 `NEZHA_IDENTITY_IP`（兼容 `NZ_IDENTITY_IP`），值为不带端口的 IPv4 地址，这样不需要外部查询。进程环境变量优先于 `.env`；`.env` 含密钥时应限制为服务器用户可读写（例如 `chmod 600`）。

未设置以上选项时，插件通过 HTTPS 依次查询公网 IPv4 服务。服务提供商会看到请求的出口 IP；请求不包含哪吒地址、密钥或 UUID。该出口 IP 未必等于通过代理或 NAT 暴露的游戏入口 IP，如需指定身份请手动设置 `NEZHA_IDENTITY_IP`。只支持 IPv4 自动识别和 IPv4 手动身份；纯 IPv6 环境可显式设置 `NZ_UUID`。

无法获得合法公网 IPv4 时，哪吒不会启动，也不会退回随机 UUID；假人功能不受影响。请配置 `NEZHA_IDENTITY_IP` 或 `NZ_UUID` 后重新启动插件。插件不会在日志中输出 IP、UUID 或密钥。

同一 IP 的多个实例会得到相同 UUID；IP 变化会得到不同 UUID，面板可能把它当作新身份。切换此版本时，旧版随机 UUID 文件会保留但不再读取；如要沿用旧身份，可将旧 UUID 手动配置到 `NZ_UUID`。IP 派生的 UUID 不是加密或身份认证凭据，不能用来隐藏服务器 IP。
