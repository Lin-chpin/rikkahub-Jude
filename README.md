<div align="center">
  <img src="docs/icon.png" alt="RikkaHub Jude 图标" width="100" />
  <h1>RikkaHub Jude</h1>
  <p>基于 RikkaHub 的二改分支，以 <strong>RP（角色扮演）</strong> 和 <strong>本体恋</strong> 为主要特色，专注沉浸式陪伴与低延迟交互体验的原生 Android AI 客户端</p>

</div>

## 这是什么

**RikkaHub Jude** 是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的非官方二改分支。我们在保留多供应商、多模态与基础聊天能力的同时，重点围绕 **RP（角色扮演）和本体恋** 场景持续打磨，补齐实时陪伴、低延迟语音、主动唤醒、日常互动与缓存优化等体验。

## 特色功能

- **实时通话、视频与共享屏幕**：支持双向语音通话、摄像头视频与系统授权屏幕共享，原则上可以和 AI 一起逛任何社交软件、一起看电影，并支持通话记录与音频回放。
- **多厂家 TTS 延迟优化与 AI 语音条**：接入 ElevenLabs（v3/v4/v4 Turbo WebSocket 流式预连接）、Fish Audio、MiniMax Speech 2.6/2.8 等多家 TTS 并针对首声延迟优化；聊天支持图文混排 AI 语音条、分段朗读与情绪/声音标签。
- **Gemini / GPT 订阅接入与缓存提升**：支持 Gemini 订阅、ChatGPT (Codex) 订阅 OAuth 登录与额度查询；针对多协议做了 Prompt Cache 前缀稳定与命中提升，搭配 DeepSeek Flash 等快速模型可获得毫秒级响应体验。
- **心跳与查岗模式**：支持后台定时心跳、AI 自主闹钟、晚安模式、本地应用使用统计/时长提醒、AI 应用锁与查岗/哨兵观察能力。
- **朋友圈与匿名提问箱**：按助手隔离的独立朋友圈（AI 发帖、配图回应、点赞评论、日期筛选）与匿名提问箱，增强日常陪伴感。
- **长对话与独立辅助接口**：支持自动滚动摘要与分块压缩、独立压缩 API/模型、独立 OCR API/模型，以及请求日志诊断和本地备份恢复。

## 上游保留的基础能力

- 多供应商与自定义 API（兼容 OpenAI、Anthropic、Google 等接口）。
- 多模态输入（图片、文本、PDF、Docx 等）、独立背景与透明度设置。
- MCP、本地工具、联网搜索与网页访问。
- Markdown、代码高亮、LaTeX、表格和 Mermaid 渲染。
- 消息分支、助手与人设自定义、Prompt 变量、记忆、AI 翻译与角色卡导入。
- Material You、深色模式、供应商二维码导入与导出。

## 下载

前往 [GitHub Releases](https://github.com/Lin-chpin/rikkahub-Jude/releases) 查看并下载最新 APK。

默认 Debug 构建为 Universal APK。安装前建议先备份应用数据；由于签名可能与官方版本或其他构建不同，遇到覆盖安装冲突时请先导出备份再处理旧安装包。

## 本地构建

项目使用 Android Studio、Kotlin、Jetpack Compose、Koin、Room、DataStore 和 Gradle 构建。

构建 Public Universal Debug APK：

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
