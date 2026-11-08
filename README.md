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

### 长期对话和上下文

- 自动滚动摘要和上下文压缩，减少长对话对模型上下文的占用。
- 超长历史按容量分块压缩，已有摘要也会参与后续整理。
- 支持单独配置压缩接口、模型、API Key，以及 Chat Completions 或 Responses API。
- 支持单独配置 OCR 接口和模型，不影响普通聊天使用的供应商。
- 摘要可以查看、编辑，并设置目标长度。

### 语音和通话

- AI 可以在同一条回复中混排文本和语音条，语音段按完整段落生成和播放。
- 普通聊天支持分段朗读、只朗读引用、只朗读英文和生成后自动播放。
- 支持主动语音通话、通话记录、通话时长、翻译和已保存音频回放。
- 支持 ElevenLabs v3 语音标签和 MiniMax Speech 2.8 整体情绪模式。
- 通话中的提示词、文本清洗、标签选择、TTS 队列和消息展示按职责拆分，方便继续维护。

### 朋友圈和匿名提问箱

- 朋友圈按助手隔离，每个助手拥有自己的动态时间线。
- 支持 AI 发布、点赞、评论、删除、日期筛选和刷新诊断。
- 匿名提问箱支持延迟回答、用户一次性回答和 AI 后续评论。

### 用量和日常提醒

- 提供本地应用使用统计和时长提醒。
- 支持用量锁控制，帮助限制指定应用的使用时间。
- 提醒服务、状态展示和恢复逻辑经过单独维护，减少后台状态卡死。

### 自主唤醒

- 可以让当前助手按计划在后台运行，并决定发送主动消息或跳过本次运行。
- 支持一次性、固定间隔、每日、工作日和时间窗随机计划。
- 支持晚安模式、失败分类、指数退避、运行诊断和只读测试。
- 只读测试会真实调用模型检查配置，但不会发送消息、写入会话、发送通知或改变正式调度。

### 备份、更新和维护体验

- 兼容较新的上游备份格式，恢复设置、会话和本地图片等文件。
- 请求日志提供更明确的阶段和失败原因，便于排查网络与模型问题。
- 应用内更新检查优先使用 universal APK，并提供下载失败后的重试和 Release 页面入口。
- 默认提供 public Universal Debug APK 构建流程，方便本地测试和安装。

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
