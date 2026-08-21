<div align="center">
  <img src="docs/icon.png" alt="App 图标" width="100" />
  <h1>RikkaHub Jude</h1>

  [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/rikkahub/rikkahub)
  [![Ask DeepWiki](https://img.shields.io/badge/zread.ai-blue?style=flat&logo=readthedocs)](https://zread.ai/rikkahub/rikkahub)

一个支持切换不同供应商进行对话的原生 Android LLM 聊天客户端 🤖💬

点击加入 Discord 服务器 👉 [【RikkaHub】](https://discord.gg/9weBqxe5c4)

[English](README.md) | [繁體中文](README_ZH_TW.md) | 简体中文
</div>

> [!IMPORTANT]
> 这是基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的**非官方社区维护 fork**，不是 RikkaHub 官方版本。
> 本仓库保留上游 Git 历史，并持续维护自动滚动摘要、上下文压缩、朋友圈和匿名提问箱等功能。
> 可下载版本以本 fork 的 [Releases 页面](https://github.com/Lin-chpin/rikkahub-Jude/releases) 为准。

<div align="center">
  <img src="docs/img/chat.png" alt="聊天界面" width="150" />
  <img src="docs/img/desktop.png" alt="模型选择器" width="450" />
</div>

## 🚀 下载本 fork

🔗 **[前往 GitHub Releases 下载 APK](https://github.com/Lin-chpin/rikkahub-Jude/releases)**

如果只是安装应用，请不要点击 **Code → Download ZIP**，那里下载的是源代码，不是 APK。

默认 Debug 构建为 universal APK。安装前请先备份应用数据。由于本 fork 与官方应用可能使用不同签名，Android 可能不允许直接覆盖安装；如果提示签名冲突，请先导出备份，再考虑卸载已有版本。

需要官方版本时，请前往 [RikkaHub 上游项目](https://github.com/rikkahub/rikkahub)。

## 🌿 本 fork 维护和新增的内容

- **自动滚动摘要与上下文压缩**：保留最近消息，持续压缩较早的可见历史，并支持查看、编辑摘要和配置目标长度。
- **长对话分块压缩**：超长历史和已有摘要会按容量分块处理，避免一次请求携带过多内容。
- **独立压缩 API 与模型**：压缩任务可以单独配置 OpenAI 兼容接口、API Key、模型、Chat Completions 路径或 Responses API。
- **独立 OCR API 与模型**：OCR 可以使用单独的 OpenAI 兼容接口和模型，不影响普通聊天供应商。
- **分段 TTS 控制**：支持按段落朗读回复，并提供仅朗读引用、仅朗读英文和生成后自动播放等选项。
- **使用统计与提醒**：提供本地应用使用统计和可配置的使用时长提醒。
- **朋友圈**：按助手隔离的动态时间线，支持 AI 点赞、评论、删除、日期筛选和手动刷新诊断。
- **匿名提问箱**：按助手隔离的匿名问题，支持延迟 AI 回答、用户一次性回答以及 AI 后续评论。
- **备份与发布维护**：兼容较新的备份格式，恢复本地文件，提供请求日志、universal Debug APK 打包和优先检查 universal 包的更新逻辑。

除 Release 说明特别标注外，本 fork 仍保留下方列出的 RikkaHub 上游功能。

## 🧭 仓库归属与贡献说明

本仓库由 **[Lin-chpin](https://github.com/Lin-chpin)** 维护，是个人/社区 fork，不是官方 RikkaHub 仓库，也不表示代码库的每一部分都是维护者从零编写的。

本仓库保留了历史 Git 提交。因此，GitHub 在查看文件历史或提交作者时，可能显示 `RikkaHub Public Release` 等上游或继承的提交身份。这些名称只代表对应的 Git 元数据，不代表本仓库的所有者或维护者。上游项目及其贡献者的原始工作仍应得到相应署名；本仓库实际维护范围见上面的“本 fork 维护和新增的内容”。

为避免混淆：

- **上游项目：** [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)
- **本 fork：** [Lin-chpin/rikkahub-Jude](https://github.com/Lin-chpin/rikkahub-Jude)
- **本 fork 维护者：** [Lin-chpin](https://github.com/Lin-chpin)

## 💖 赞助商

<div align="center">
  <img src="app/src/main/assets/icons/aihubmix-color.svg" alt="Aihubmix" width="50" />
  <p style="font-size: 16px; font-weight: bold;">Aihubmix</p>
  <p style="font-size: 14px;">感谢 <a href="https://aihubmix.com?aff=pG7r">aihubmix.com</a> 的资金支持。我们推荐使用 aihubmix 作为全球主流模型的一站式服务平台。（OpenAI、Claude、Google Gemini、DeepSeek、Qwen 以及数百种其他模型）。</p>
</div>

## ✨ 功能特色

- 🎨 Material You 设计和 🌙 深色模式
- 🔄 多 AI 供应商支持：自定义 API / URL / 模型（兼容 OpenAI、Google、Anthropic 等接口）
- 🖼️ 多模态输入支持（图片、文本、PDF、Docx）
- 🖥️ Web 多端访问
- 🛠️ MCP 支持
- 📝 Markdown 渲染（代码高亮、LaTeX 公式、表格、Mermaid）
- 🪾 消息分支
- 🔍 搜索能力（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity 等）
- 🧩 Prompt 变量（模型名称、时间等）
- 🤳 供应商二维码导入和导出
- 🤖 Agent 自定义
- 🧠 类 ChatGPT 记忆功能
- 📝 AI 翻译
- 🌐 自定义 HTTP 请求头和请求体
- 💌 Silly Tavern 角色卡导入

## ✨ 贡献

本项目使用 [Android Studio](https://developer.android.com/studio) 开发，欢迎提交 PR。

技术栈：

- [Kotlin](https://kotlinlang.org/)（开发语言）
- [Koin](https://insert-koin.io/)（依赖注入）
- [Jetpack Compose](https://developer.android.com/jetpack/compose)（UI 框架）
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)（偏好数据存储）
- [Room](https://developer.android.com/training/data-storage/room)（数据库）
- [Coil](https://coil-kt.github.io/coil/)（图片加载）
- [Material You](https://m3.material.io/)（UI 设计）
- [Navigation Compose](https://developer.android.com/develop/ui/compose/navigation)（导航）
- [Okhttp](https://square.github.io/okhttp/)（HTTP 客户端）
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)（JSON 序列化）
- [compose-icons/lucide](https://composeicons.com/icon-libraries/lucide)（图标库）

> [!TIP]
> 你需要在 `app` 文件夹下添加 `google-services.json` 文件才能构建应用。

> [!IMPORTANT]
> 以下 PR 将被拒绝：
> 1. 翻译相关修改，例如添加新语言或更新现有翻译；
> 2. 添加新功能，本项目对功能方向有明确取舍；
> 3. 大规模重构以及由 AI 生成的改动。

## 💰 捐赠

* [Patreon](https://patreon.com/rikkahub)
* [爱发电](https://afdian.com/a/reovo)

## ⭐ Star History

如果喜欢这个项目，欢迎点个 Star ⭐

[![Star History Chart](https://api.star-history.com/svg?repos=re-ovo/rikkahub&type=Date)](https://star-history.com/#re-ovo/rikkahub&Date)

## 📄 许可证

[License](LICENSE)
