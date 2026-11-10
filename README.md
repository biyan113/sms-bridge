# SMS Bridge

把 vivo 手机收到的短信验证码同步到 Mac：Mac 弹出系统通知，并自动把验证码复制到剪贴板。仅供个人使用，手机和 Mac 在同一个 Wi‑Fi 下即可，不需要服务器、账号或 Root。

```
vivo 收到短信 → SMS Bridge App 提取验证码 → 局域网 POST → Mac 通知 + 剪贴板
```

| 目录 | 内容 |
| --- | --- |
| `android/` | Android Studio 工程（Kotlin，运行时零第三方依赖） |
| `mac/sms_bridge_server.py` | Mac 接收端（只用 Python 标准库） |

## 下载

从 [Releases](https://github.com/biyan113/sms-bridge/releases) 下载 `sms-bridge-test.apk` 和 `sms-bridge-mac.zip`。Android 8.0+；Mac 端需要 Python 3 和系统 OpenSSL（生成自签名证书）。

当前为公开测试版：APK 使用 CI 临时 debug 签名。每次 CI 构建的签名可能不同，更新时可能需要卸载旧版；卸载会清除配置。暂未配置生产签名密钥。

## 一、启动 Mac 接收端

```bash
python3 mac/sms_bridge_server.py --tls     # 推荐：HTTPS
python3 mac/sms_bridge_server.py           # 明文 HTTP，只用于初步测试
```

启动后会打印要填进手机 App 的三项：**Mac 地址、Token、证书指纹**（仅 `--tls` 有）。

- Token 首次运行自动生成，保存在 `~/.config/sms-bridge/token`；也可用 `--token` 或环境变量 `SMS_BRIDGE_TOKEN` 指定。
- 端口默认 8765，用 `--port` 修改。`--no-copy` 表示只通知、不写剪贴板。
- 自签名证书首次 `--tls` 时生成在 `~/.config/sms-bridge/`，指纹固定不变。
- 通知由 `osascript` 发出。如果没有弹出：系统设置 → 通知 → “脚本编辑器”，打开允许通知；并确认没有开专注模式。
- macOS 首次运行可能询问“是否允许 Python 接受传入网络连接”，选允许。

## 二、安装 Android App

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开 `android/` 目录运行。

打开 App：

1. 点 **打开短信权限**。顶部会显示实际授权状态。
2. 填入 Mac 地址、Token、证书指纹（HTTP 测试时指纹留空）。
3. 点 **测试连接**（自动保存配置）：Mac 应弹出“验证码 123456”通知，剪贴板变成 `123456`。这一步只验证网络和认证，与短信无关。

## 三、测试顺序

1. **普通短信**：用另一部手机发一条“你好”。App 的“最近事件”应显示 `短信 ✅ / 验证码 ⚠️ 未识别 / 发送 ✅`，Mac 弹出短信内容通知。
2. **带验证码格式的普通短信**：用另一部手机发“验证码 384756”。应显示 `验证码 ✅ 38••••`，Mac 剪贴板为 `384756`。
3. **真实验证码短信**：登录任意平台触发验证码。

如何判断问题出在哪：

| 现象 | 结论 |
| --- | --- |
| 第 1 步“最近事件”里什么都没有 | 广播没送到 App：权限、自启动或后台限制（见下） |
| 第 1、2 步正常，第 3 步什么都没有 | 系统的验证码保护拦截了，不是代码问题，本工具不会尝试绕过 |
| `短信 ✅` 但 `验证码 ⚠️ 未识别` | 提取规则没覆盖这种短信格式，改 `CodeExtractor.kt` 并补一条单元测试 |
| `发送 ❌` | 看后面的错误信息：地址/Wi‑Fi、Token 不一致、指纹不匹配等 |

## 四、vivo / OriginOS 注意事项

- **权限授不了**：侧载的 App 申请短信权限可能被“受限制的设置”拦住。系统设置 → 应用 → SMS Bridge → 右上角菜单 → 允许受限制的设置，再回 App 授权。
- **App 不在前台时收不到**：在 i 管家 / 应用设置里给 SMS Bridge 打开“自启动”，后台耗电设为“允许后台高耗电 / 不限制”。从最近任务里划掉 App 在部分系统上等同于强行停止，之后要手动打开一次才会恢复接收。
- **验证码保护**：信息 App 的设置里如果有“验证码安全保护”一类的开关，打开时第三方 App 读不到验证码短信。是否关闭由你自己决定。
- 新版 Android 对验证码短信的限制在收紧，本工程 `targetSdk = 35`，升级 targetSdk 前请重新做第 3 步测试。

## 五、调试

```bash
adb logcat -s ActivityManager:W BroadcastQueue:W | grep -i smsbridge   # 广播是否被系统拦截
curl http://<Mac IP>:8765/ping                                          # 接收端是否可达（--tls 时用 https 加 -k）
cd android && ./gradlew testDebugUnitTest                               # 验证码提取规则的单元测试
```

## 六、安全设计

- **认证**：Token 不在网络上传输。App 用 Token 对“时间戳 + 正文”做 HMAC‑SHA256 签名，Mac 验签，并拒绝超过 5 分钟或重复的请求。
- **加密**：`--tls` 模式下全程 HTTPS，App 只信任指纹匹配的那张证书。明文 HTTP 模式下短信内容在局域网里可被嗅探，只应用于测试。
- **日志**：Mac 日志和 App 的“最近事件”都只保留打码后的验证码（前两位），不保存短信正文。
- **最小权限**：App 只申请 `RECEIVE_SMS` 和 `INTERNET`；不读历史短信，不需要成为默认短信应用，不用无障碍权限。

## 协议

`POST /sms`，请求头 `X-Timestamp`（毫秒时间戳）、`X-Signature`（`HMAC-SHA256(token, "<timestamp>." + body)` 的十六进制），正文：

```json
{"sender": "10690000", "body": "短信全文", "code": "384756", "receivedAt": 1794313416000, "test": false}
```

`code` 为 `null` 表示未识别出验证码。


## 开发与自动发布

构建环境：JDK 17、Android SDK 35，Gradle Wrapper 已包含在仓库中。

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

GitHub Actions 使用官方 `actions/setup-java` 和 Gradle `setup-gradle`，Action 固定到提交 SHA。推送 main、Pull Request 和手动触发时执行测试、Lint、APK 构建及 Python 语法检查。推送 `v*` 标签后，检查通过才会发布预发布版 Release，附 APK、Mac ZIP 和 SHA-256 校验值。

```bash
git tag v0.1.0
git push origin v0.1.0
```

后续发布前请同步修改 `android/app/build.gradle.kts` 的 `versionCode` / `versionName`，并更新 CHANGELOG。

## 开源许可

[MIT License](LICENSE)，Copyright © 2026 biyan113。
