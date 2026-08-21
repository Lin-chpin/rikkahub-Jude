<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub Jude</h1>

  [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/rikkahub/rikkahub)
  [![Ask DeepWiki](https://img.shields.io/badge/zread.ai-blue?style=flat&logo=readthedocs)](https://zread.ai/rikkahub/rikkahub)

A native Android LLM chat client that supports switching between different providers for
conversations 🤖💬

Click to join our Discord server 👉 [【RikkaHub】](https://discord.gg/9weBqxe5c4)

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md) | English
</div>

> [!IMPORTANT]
> This is an **unofficial community-maintained fork** of [RikkaHub](https://github.com/rikkahub/rikkahub).
> It is not an official RikkaHub release. The fork keeps the upstream Git history while adding and maintaining
> features such as automatic rolling summaries, context compression, Moments, and the anonymous question box.
> The version shown on this fork's [Releases page](https://github.com/Lin-chpin/rikkahub-Jude/releases)
> is the source of truth for downloadable builds.

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

## 🚀 Download this fork

🔗 **[Download APK from GitHub Releases](https://github.com/Lin-chpin/rikkahub-Jude/releases)**

Do not use **Code → Download ZIP** if you only want to install the app; that downloads source code, not an APK.

The default debug build is a universal APK. Back up your data before installation. Because this fork and the
official app may use different signing certificates, Android might not allow installing one directly over the other.
If Android reports a signature conflict, export a backup before uninstalling any existing version.

For official builds, visit the [upstream RikkaHub project](https://github.com/rikkahub/rikkahub).

## 🌿 Maintained changes in this fork

- **Automatic rolling summaries and context compression**: keep recent messages while continuously compressing
  older visible history, with editable summaries and configurable target length.
- **Long-conversation chunking**: large histories and existing summaries are compressed in bounded chunks instead
  of being sent as one oversized request.
- **Dedicated compression API and model**: optionally use a separate OpenAI-compatible endpoint, API key, model,
  Chat Completions path, or Responses API for compression.
- **Dedicated OCR API and model**: OCR can use its own OpenAI-compatible endpoint and model without changing the
  normal chat provider.
- **Paragraph-level TTS controls**: play assistant replies paragraph by paragraph, with quoted-text-only,
  English-only, and automatic playback options.
- **Usage statistics and reminders**: local app-usage views and configurable duration reminders.
- **Moments**: an assistant-scoped social timeline with AI-generated likes, comments, deletion, date filtering,
  and manual refresh diagnostics.
- **Anonymous question box**: assistant-scoped anonymous questions, delayed AI answers, one-time user answers,
  and follow-up AI comments.
- **Backup and release maintenance**: compatibility fixes for newer backups, local file restoration, request logs,
  universal debug APK packaging, and update checks that prefer the universal package.

Unless a release note says otherwise, this fork retains the upstream RikkaHub features listed below.

## 🧭 Ownership and attribution

This repository is maintained by **[Lin-chpin](https://github.com/Lin-chpin)**. It is a personal/community
fork, not the official RikkaHub repository and not a claim that every part of the codebase was written from scratch by
the fork maintainer.

The repository preserves historical Git commits. Therefore, GitHub may display upstream or inherited commit identities
such as `RikkaHub Public Release` when showing file history or commit authors. Those names describe the corresponding
Git metadata; they do not identify the owner or maintainer of this fork. The upstream project and its contributors
retain credit for their original work. Changes listed in **Maintained changes in this fork** are the scope maintained
in this repository.

For clarity:

- **Upstream project:** [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)
- **This fork:** [Lin-chpin/rikkahub-Jude](https://github.com/Lin-chpin/rikkahub-Jude)
- **Fork maintainer:** [Lin-chpin](https://github.com/Lin-chpin)

## 💖 Sponsors

<div align="center">
  <img src="app/src/main/assets/icons/aihubmix-color.svg" alt="Aihubmix" width="50" />
  <p style="font-size: 16px; font-weight: bold;">Aihubmix</p>
  <p style="font-size: 14px;">Thanks to <a href="https://aihubmix.com?aff=pG7r">aihubmix.com</a> for their financial support. We recommend using aihubmix as a one-stop shop for mainstream models worldwide. (OpenAI, Claude, Google Gemini, DeepSeek, Qwen, and hundreds more).</p>
</div>

## ✨ Features

- 🎨 Material You Design and 🌙 Dark mode
- 🔄 Multiple AI Provider Support: custom API / URL / models (all OpenAI, Google, Anthropic compatible api)
- 🖼️ Multimodal input support (Image, Text Documentation, PDF, Docx)
- 🖥️ Web access for multi-platform use
- 🛠️ MCP support
- 📝 Markdown Rendering (with code highlighting, Latex formulas, tables, Mermaid)
- 🪾 Message Branching
- 🔍 Search capabilities (Exa, Tavily, Zhipu, LinkUp, Brave, Perplexity, etc.)
- 🧩 Prompt variables (model name, time, etc.)
- 🤳 QR code export and import for providers
- 🤖 Agent customization
- 🧠 ChatGPT-like memory feature
- 📝 AI Translation
- 🌐 Custom HTTP request headers and request bodies
- 💌 Silly Tavern character card import

## ✨ Contributing

This project is developed using [Android Studio](https://developer.android.com/studio). PRs are
welcome!

Technology stack documentation:

- [Kotlin](https://kotlinlang.org/) (Development language)
- [Koin](https://insert-koin.io/) (Dependency Injection)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI framework)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) (Preference data
  storage)
- [Room](https://developer.android.com/training/data-storage/room) (Database)
- [Coil](https://coil-kt.github.io/coil/) (Image loading)
- [Material You](https://m3.material.io/) (UI design)
- [Navigation Compose](https://developer.android.com/develop/ui/compose/navigation) (Navigation)
- [Okhttp](https://square.github.io/okhttp/) (HTTP client)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (JSON serialization)
- [compose-icons/lucide](https://composeicons.com/icon-libraries/lucide) (Icon library)

> [!TIP]
> You need a `google-services.json` file at `app` folder to build the app.

> [!IMPORTANT]  
> The following PRs will be rejected: 
> 1. Translation related changes, such as adding new languages or updating existing translations
> 2. Adding new features, this project is opinionated and will not accept pull requests for new features
> 3. Large-scale refactoring and changes generated by AI

## 💰 Donate

* [Patreon](https://patreon.com/rikkahub)
* [爱发电](https://afdian.com/a/reovo)

## ⭐ Star History

If you like this project, please give it a star ⭐

[![Star History Chart](https://api.star-history.com/svg?repos=re-ovo/rikkahub&type=Date)](https://star-history.com/#re-ovo/rikkahub&Date)

## 📄 License

[License](LICENSE)
