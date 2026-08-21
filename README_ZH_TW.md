<div align="center">
  <img src="docs/icon.png" alt="App 圖示" width="100" />
  <h1>RikkaHub Jude</h1>

  [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/rikkahub/rikkahub)
  [![Ask DeepWiki](https://img.shields.io/badge/zread.ai-blue?style=flat&logo=readthedocs)](https://zread.ai/rikkahub/rikkahub)

一個支援切換不同供應商進行對話的原生 Android LLM 聊天客戶端 🤖💬

點擊加入 Discord 伺服器 👉 [【RikkaHub】](https://discord.gg/9weBqxe5c4)

[English](README.md) | 繁體中文 | [简体中文](README_ZH_CN.md)
</div>

> [!IMPORTANT]
> 這是基於 [RikkaHub](https://github.com/rikkahub/rikkahub) 的**非官方社群維護 fork**，不是 RikkaHub 官方版本。
> 本倉庫保留上游 Git 歷史，並持續維護自動滾動摘要、上下文壓縮、朋友圈和匿名提問箱等功能。
> 可下載版本以本 fork 的 [Releases 頁面](https://github.com/Lin-chpin/rikkahub-Jude/releases) 為準。

<div align="center">
  <img src="docs/img/chat.png" alt="聊天介面" width="150" />
  <img src="docs/img/desktop.png" alt="模型選擇器" width="450" />
</div>

## 🚀 下載本 fork

🔗 **[前往 GitHub Releases 下載 APK](https://github.com/Lin-chpin/rikkahub-Jude/releases)**

如果只想安裝應用程式，請不要點擊 **Code → Download ZIP**，那裡下載的是原始碼，不是 APK。

預設 Debug 構建為 universal APK。安裝前請先備份應用程式資料。由於本 fork 與官方應用程式可能使用不同簽章，Android 可能不允許直接覆蓋安裝；如果提示簽章衝突，請先匯出備份，再考慮解除安裝已有版本。

需要官方版本時，請前往 [RikkaHub 上游專案](https://github.com/rikkahub/rikkahub)。

## 🌿 本 fork 維護和新增的內容

- **自動滾動摘要與上下文壓縮**：保留最近訊息，持續壓縮較早的可見歷史，並支援檢視、編輯摘要和設定目標長度。
- **長對話分塊壓縮**：超長歷史和既有摘要會按容量分塊處理，避免單次請求攜帶過多內容。
- **獨立壓縮 API 與模型**：壓縮任務可以單獨設定 OpenAI 相容介面、API Key、模型、Chat Completions 路徑或 Responses API。
- **獨立 OCR API 與模型**：OCR 可以使用單獨的 OpenAI 相容介面和模型，不影響一般聊天供應商。
- **分段 TTS 控制**：支援按段落朗讀回覆，並提供僅朗讀引用、僅朗讀英文和生成後自動播放等選項。
- **使用統計與提醒**：提供本機應用程式使用統計和可設定的使用時間提醒。
- **朋友圈**：按助手隔離的動態時間線，支援 AI 按讚、評論、刪除、日期篩選和手動重新整理診斷。
- **匿名提問箱**：按助手隔離的匿名問題，支援延遲 AI 回答、使用者一次性回答以及 AI 後續評論。
- **備份與發布維護**：相容較新的備份格式、恢復本機檔案，提供請求日誌、universal Debug APK 打包和優先檢查 universal 套件的更新邏輯。

除 Release 說明特別標註外，本 fork 仍保留下方列出的 RikkaHub 上游功能。

## 🧭 倉庫歸屬與貢獻說明

本倉庫由 **[Lin-chpin](https://github.com/Lin-chpin)** 維護，是個人/社群 fork，不是官方 RikkaHub 倉庫，也不表示程式碼庫的每一部分都是維護者從零編寫的。

本倉庫保留了歷史 Git 提交。因此，GitHub 在查看檔案歷史或提交作者時，可能顯示 `RikkaHub Public Release` 等上游或繼承的提交身份。這些名稱只代表對應的 Git 元資料，不代表本倉庫的所有者或維護者。上游專案及其貢獻者的原始工作仍應得到相應署名；本倉庫的實際維護範圍見上面的「本 fork 維護和新增的內容」。

為避免混淆：

- **上游專案：** [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)
- **本 fork：** [Lin-chpin/rikkahub-Jude](https://github.com/Lin-chpin/rikkahub-Jude)
- **本 fork 維護者：** [Lin-chpin](https://github.com/Lin-chpin)

## 💖 贊助商

<div align="center">
  <img src="app/src/main/assets/icons/aihubmix-color.svg" alt="Aihubmix" width="50" />
  <p style="font-size: 16px; font-weight: bold;">Aihubmix</p>
  <p style="font-size: 14px;">感謝 <a href="https://aihubmix.com?aff=pG7r">aihubmix.com</a> 的資金支持。我們推薦使用 aihubmix 作為全球主流模型的一站式服務平台。（OpenAI、Claude、Google Gemini、DeepSeek、Qwen 以及數百種其他模型）。</p>
</div>

## ✨ 功能特色

- 🎨 Material You 設計和 🌙 深色模式
- 🔄 多 AI 供應商支援：自訂 API / URL / 模型（相容 OpenAI、Google、Anthropic 等介面）
- 🖼️ 多模態輸入支援（圖片、文字、PDF、Docx）
- 🖥️ Web 多端存取
- 🛠️ MCP 支援
- 📝 Markdown 渲染（程式碼高亮、LaTeX 公式、表格、Mermaid）
- 🪾 訊息分支
- 🔍 搜尋能力（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity 等）
- 🧩 Prompt 變數（模型名稱、時間等）
- 🤳 供應商 QR Code 匯入和匯出
- 🤖 Agent 自訂
- 🧠 類 ChatGPT 記憶功能
- 📝 AI 翻譯
- 🌐 自訂 HTTP 請求標頭和請求本文
- 💌 Silly Tavern 角色卡匯入

## ✨ 貢獻

本專案使用 [Android Studio](https://developer.android.com/studio) 開發，歡迎提交 PR。

技術棧：

- [Kotlin](https://kotlinlang.org/)（開發語言）
- [Koin](https://insert-koin.io/)（依賴注入）
- [Jetpack Compose](https://developer.android.com/jetpack/compose)（UI 框架）
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)（偏好資料儲存）
- [Room](https://developer.android.com/training/data-storage/room)（資料庫）
- [Coil](https://coil-kt.github.io/coil/)（圖片載入）
- [Material You](https://m3.material.io/)（UI 設計）
- [Navigation Compose](https://developer.android.com/develop/ui/compose/navigation)（導覽）
- [Okhttp](https://square.github.io/okhttp/)（HTTP 客戶端）
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)（JSON 序列化）
- [compose-icons/lucide](https://composeicons.com/icon-libraries/lucide)（圖示庫）

> [!TIP]
> 你需要在 `app` 資料夾下添加 `google-services.json` 檔案才能構建應用程式。

> [!IMPORTANT]
> 以下 PR 將被拒絕：
> 1. 翻譯相關修改，例如新增語言或更新現有翻譯；
> 2. 新增功能，本專案對功能方向有明確取捨；
> 3. 大規模重構以及由 AI 產生的改動。

## 💰 捐贈

* [Patreon](https://patreon.com/rikkahub)
* [愛發電](https://afdian.com/a/reovo)

## ⭐ Star History

如果喜歡這個專案，歡迎給個 Star ⭐

[![Star History Chart](https://api.star-history.com/svg?repos=re-ovo/rikkahub&type=Date)](https://star-history.com/#re-ovo/rikkahub&Date)

## 📄 授權條款

[License](LICENSE)
