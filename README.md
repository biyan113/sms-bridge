# SMS Bridge

English | [简体中文](README.zh-CN.md)

Forward SMS verification codes from a vivo Android phone to your Mac, display a system notification, and copy the code to the clipboard. Intended for personal use on the same Wi-Fi network. No hosted server, account, or root access required.

```text
Incoming SMS → Android app extracts code → LAN POST → Mac notification + clipboard
```

| Path | Contents |
| --- | --- |
| `android/` | Android Studio project in Kotlin, with no third-party runtime dependencies |
| `mac/sms_bridge_server.py` | Mac receiver using only the Python standard library |

## Download

Download `sms-bridge-test.apk` and `sms-bridge-mac.zip` from [Releases](https://github.com/biyan113/sms-bridge/releases). Requires Android 8.0+; the Mac receiver requires Python 3 and system OpenSSL to generate a self-signed certificate.

Tagged releases require a fixed release signing key and fail if signing is not configured. Regular CI builds use debug signing. To migrate from a debug build, uninstall it first; this clears app settings. Subsequent releases can update in place when signed with the same key and a higher `versionCode`.

## 1. Start the Mac receiver

```bash
python3 mac/sms_bridge_server.py --tls     # Recommended: HTTPS
python3 mac/sms_bridge_server.py           # Plain HTTP for initial testing only
```

The receiver prints the **Mac address, token, and certificate fingerprint** (fingerprint only with `--tls`) to enter in the Android app.

- A token is generated on first launch and saved to `~/.config/sms-bridge/token`. Override it with `--token` or `SMS_BRIDGE_TOKEN`.
- The default port is 8765; change it with `--port`. Use `--no-copy` to show notifications without updating the clipboard.
- The first TLS launch generates a self-signed certificate in `~/.config/sms-bridge/`. Its fingerprint stays the same while that certificate is retained.
- Notifications use `osascript`. If none appear, enable notifications for Script Editor in System Settings → Notifications and check Focus settings.
- If macOS asks whether Python may accept incoming connections, allow it.

## 2. Install the Android app

Install the downloaded APK, or build a debug version:

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also open `android/` in Android Studio and run the app.

In the app (currently using Chinese labels):

1. Tap **打开短信权限** (enable SMS permission). The top of the screen shows the actual permission status.
2. Enter the Mac address, token, and certificate fingerprint. Leave the fingerprint empty for HTTP testing.
3. Tap **测试连接** (test connection); this also saves settings. The Mac should display code `123456` and copy it to the clipboard. This checks networking and authentication independently of SMS reception.

## 3. Test SMS reception

1. Send an ordinary message such as “Hello” from another phone. Recent events should show SMS received, no code recognized, and delivery succeeded. The Mac should display the message notification.
2. Send “验证码 384756” from another phone. Recent events should show the masked code `38••••`, and the Mac clipboard should contain `384756`.
3. Request a real verification code from a service.

| Symptom | What to check |
| --- | --- |
| No recent event in step 1 | SMS permission, autostart, or background restrictions; see below |
| Steps 1 and 2 work, but step 3 produces no event | The system may block third-party access to verification messages; this tool does not bypass that protection |
| SMS received, but no code recognized | Add support for the message format in `CodeExtractor.kt` and add a unit test |
| Delivery failed | Read the error for address/Wi-Fi, token, or certificate fingerprint issues |

## 4. vivo / OriginOS settings

- **SMS permission blocked:** sideloaded apps may be subject to restricted settings. In System Settings → Apps → SMS Bridge → top-right menu, allow restricted settings, then grant permission in the app.
- **No reception in the background:** enable autostart in i Manager or app settings, and allow background power usage / unrestricted battery usage. On some devices, swiping the app away acts like force-stop; reopen it to resume reception.
- **Verification code protection:** some Messages settings prevent third-party apps from accessing verification messages. Whether to disable that setting is your choice.
- This project targets SDK 35. Repeat the real verification SMS test before raising the target SDK, since Android restrictions can change.

## 5. Debugging

```bash
# Check whether the system blocks SMS broadcasts
adb logcat -s ActivityManager:W BroadcastQueue:W | grep -i smsbridge
# Check receiver reachability (use https and -k for a self-signed TLS test)
curl http://<Mac-IP>:8765/ping
# Run code extraction unit tests
cd android && ./gradlew testDebugUnitTest
```

## 6. Security design

- **Authentication:** the token is not sent over the network. The app signs the timestamp and request body with HMAC-SHA256. The Mac verifies the signature and rejects requests older than five minutes or replayed requests.
- **Encryption:** `--tls` enables HTTPS, and the app trusts only the certificate matching the configured fingerprint. Plain HTTP exposes message contents to network observers and is intended only for testing.
- **Logs:** Mac logs and the app's recent events mask codes, showing only the first two characters, and do not retain message bodies.
- **Permissions:** the app requests only `RECEIVE_SMS` and `INTERNET`. It does not read SMS history, require the default SMS role, or use accessibility permissions.

## Protocol

`POST /sms` uses `X-Timestamp` (Unix timestamp in milliseconds) and `X-Signature` (hexadecimal `HMAC-SHA256(token, "<timestamp>." + body)`) headers, with a JSON body:

```json
{"sender": "10690000", "body": "Your verification code is 384756", "code": "384756", "receivedAt": 1794313416000, "test": false}
```

`code` is `null` when no verification code is recognized.

## Development and automated releases

Requires JDK 17 and Android SDK 35. The Gradle Wrapper is included.

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

GitHub Actions uses the official `actions/setup-java` and Gradle `setup-gradle` actions, pinned to commit SHAs. Pushes to `main`, pull requests, and manual runs execute tests, lint, APK builds, and Python syntax checks. Pushing a `v*` tag publishes a GitHub prerelease after checks pass, with the APK, Mac ZIP, and SHA-256 checksums.

```bash
git tag v0.1.0
git push origin v0.1.0
```

### Android release signing

For local builds, copy `android/keystore.properties.example` to `android/keystore.properties` and fill in the release keystore path, alias, and passwords:

```bash
cd android
./gradlew :app:assembleRelease
```

The signed APK is written to `android/app/build/outputs/apk/release/app-release.apk`. Keystores and password files are ignored by Git. Back them up securely and reuse the same key for future releases.

Tagged GitHub releases require these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded release keystore contents.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: signing key alias.
- `ANDROID_KEY_PASSWORD`: signing key password.

CI decodes the keystore into a temporary file and deletes it afterward. Local builds also support `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` environment variables, which take precedence over the properties file.

Before each release, update `versionCode` / `versionName` in `android/app/build.gradle.kts` and update the changelog.

## License

[MIT License](LICENSE). Copyright © 2026 biyan113.
