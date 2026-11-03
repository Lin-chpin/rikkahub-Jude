<div align="center">
  <img src="docs/icon.png" alt="RikkaHub Jude 图标" width="100" />
  <h1>RikkaHub Jude</h1>
  <p>一个面向多模型、多供应商和长期对话的原生 Android AI 客户端</p>

  [![GitHub Releases](https://img.shields.io/github/v/release/Lin-chpin/rikkahub-Jude?display_name=tag)](https://github.com/Lin-chpin/rikkahub-Jude/releases)
  [![License](https://img.shields.io/github/license/Lin-chpin/rikkahub-Jude)](LICENSE)

  简体中文 | [繁體中文](README_ZH_TW.md)
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="聊天界面" width="150" />
  <img src="docs/img/desktop.png" alt="模型选择器" width="450" />
</div>

## 这是什么

RikkaHub Jude 是一个原生 Android AI 聊天客户端。它支持接入不同的模型供应商，使用文字、图片和文档进行对话，也可以通过 Web 端访问同一套本地数据。

我们从 RikkaHub 的基础能力出发，持续维护自己的使用体验和功能方向。重点放在长期对话、语音交互、个人信息整理以及本地可控的自动化能力上。

本项目是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的非官方 fork。上游作者和贡献者的原始工作仍然保留署名，本仓库的维护范围以本文档和 Release 说明为准。

## 我们增加和持续维护的功能

### 长对话和上下文

- 自动摘要和上下文压缩，支持查看、编辑摘要及单独配置压缩模型。
- OCR 模型和接口可单独配置。

### 语音和通话

- 支持主动 AI 语音通话、实时语音识别，以及摄像头和屏幕画面识别。
- 支持通话记录、翻译和语音回放。
- 支持聊天语音朗读、AI 语音条，以及 ElevenLabs 和 MiniMax 语音功能。

### 朋友圈和匿名提问箱

- 提供朋友圈和匿名提问箱，支持 AI 发布与互动。

### 用量和日常提醒

- 提供应用使用统计、时长提醒和应用用量锁。

### 自主唤醒

- 支持按计划在后台唤醒助手并发送主动消息。

### 备份、更新和维护体验

- 兼容上游备份格式，可恢复设置、会话和本地文件。
- 支持应用内更新、请求日志和问题排查。

## 上游保留的基础能力

- 多供应商和自定义 API，兼容 OpenAI、Anthropic、Google 等常见接口。
- 多模态输入，支持图片、文本、PDF、Docx 等内容。
- MCP、本地工具、网页搜索和 Web 访问。
- Markdown、代码高亮、LaTeX、表格和 Mermaid 渲染。
- 消息分支、助手自定义、Prompt 变量、记忆、AI 翻译和角色卡导入。
- Material You、深色模式、供应商二维码导入与导出。

## 下载

前往 [GitHub Releases](https://github.com/Lin-chpin/rikkahub-Jude/releases) 下载 APK。

当前版本为 `v1.1.8`。默认 Debug 包是 Universal APK。安装前建议先备份应用数据。由于签名可能不同，Android 可能不允许直接覆盖官方版本或其他构建版本，遇到签名冲突时需要先导出数据再处理旧安装包。

## 本地构建

项目使用 Android Studio、Kotlin、Jetpack Compose、Koin、Room、DataStore 和 Gradle 构建。

构建 public Universal Debug APK

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-universal-debug-apk.ps1
```

构建前需要在 `app` 目录放置 `google-services.json`。产物位于 `app/build/outputs/apk/public/debug/`。

## 仓库归属

- 上游项目：[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)
- 本项目：[Lin-chpin/rikkahub-Jude](https://github.com/Lin-chpin/rikkahub-Jude)
- 维护者：[Lin-chpin](https://github.com/Lin-chpin)

本仓库保留上游历史提交。GitHub 文件历史中出现的上游提交身份只代表对应的 Git 元数据，不代表本项目的所有者发生变化。

## 许可证

[License](LICENSE)
