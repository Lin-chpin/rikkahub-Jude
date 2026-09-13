# RikkaHub 聊天记录适配器

这是一个独立的 Android 工具，用来把上游 RikkaHub 的备份文件转换成 RikkaHub 可识别的 `.rhk` 聊天记录包。

适配器只在手机本地读取和转换文件，不会打开、修改或覆盖 RikkaHub 应用本身的数据库。

## 使用方法

1. 从 GitHub Release 安装适配器 APK。
2. 在上游 RikkaHub 的“备份与恢复”页面导出 ZIP 备份。
3. 建议同时勾选“聊天记录”和“文件”。只有导出了聊天记录才会有数据库；只有导出了文件，图片、文档、技能和字体才会被完整替换。
4. 打开本适配器，选择上游 ZIP 备份。
5. 导出生成的 `.rhk` 文件。
6. 在 RikkaHub 中打开“设置 → 备份与恢复 → 从其他 APP 导入 → 导入转换后的 RikkaHub 数据”，选择 `.rhk` 文件。

导入新生成的 `.rhk` 后，RikkaHub 会用上游数据完整替换当前的助手、模型、API 配置、应用设置、聊天记录和受管理的附件文件。导出的 `.rhk` 包含 API 密钥和其他隐私设置，请不要分享给别人。

如果只勾选“文件”而没有勾选“聊天记录”，备份里不会有 `rikka_hub.db`，因此不能生成聊天恢复包。只勾选“聊天记录”时仍可转换，但因为没有 `settings.json` 和文件目录，RikkaHub 会按旧版兼容模式仅合并聊天记录，不会替换全量数据。

## 转换文件内容

适配器生成的 `.rhk` 本质上是一个 ZIP 文件，包含：

- `manifest.json`：格式版本、来源、数量和警告；
- `conversations.json`：转换后的聊天记录和消息节点；
- `settings.json`：助手、模型、服务商/API 配置和应用设置；
- `files/`：`upload`、`images`、`skills`、`fonts` 下的全部文件；
- `diagnostics.json`：读取到的数据库版本、表名、警告和错误；
- `attachments/`：能够从备份中找到的本地附件。

RikkaHub 端只依赖固定的 `rikkahub-transfer` 格式版本 1。带有 `settings.json` 的包会标记为完整恢复包；没有设置文件的旧包仍可被主 App 兼容导入，但只会合并聊天，不会替换应用设置。

遇到转换异常时，请把 `.rhk` 中的 `diagnostics.json` 内容发出来。它不包含聊天正文和 API 密钥，只用于判断数据库、设置文件、文件数量和哪一步跳过了数据；不要直接发送整个 `.rhk`。

## 本地构建

项目使用仓库规定的 Gradle 9.4.1 和 JDK 21。调试包命令：

```text
gradle -p import-adapter :app:assembleDebug
```

发布包必须使用固定的 Android release 签名密钥。密钥文件和密码只放在本地 `local.properties` 或 CI Secret 中，禁止提交到 GitHub。Android 应用更新要求后续发布继续使用同一签名密钥。

```properties
adapter.storeFile=path/to/import-adapter-release.jks
adapter.storePassword=...
adapter.keyAlias=...
adapter.keyPassword=...
```

发布包命令：

```text
gradle -p import-adapter :app:assembleRelease
```

生成的 `import-adapter/app/build/outputs/apk/release/app-release.apk` 可上传到 GitHub Release。适配器版本标签与 RikkaHub 主应用标签分开管理。
