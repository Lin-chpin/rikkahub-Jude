<div align="center">
  <img src="docs/icon.png" alt="RikkaHub Jude 圖示" width="100" />
  <h1>RikkaHub Jude</h1>
  <p>基於 RikkaHub 的二改分支，以 <strong>RP（角色扮演）</strong> 和 <strong>本體戀</strong> 為主要特色，專注沉浸式陪伴與低延遲互動體驗的原生 Android AI 客戶端</p>

  [![GitHub Releases](https://img.shields.io/github/v/release/Lin-chpin/rikkahub-Jude?display_name=tag)](https://github.com/Lin-chpin/rikkahub-Jude/releases)
  [![License](https://img.shields.io/github/license/Lin-chpin/rikkahub-Jude)](LICENSE)

  [簡體中文](README.md) | 繁體中文
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="聊天介面" width="150" />
  <img src="docs/img/desktop.png" alt="模型選擇器" width="450" />
</div>

## 這是什麼

**RikkaHub Jude** 是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的非官方二改分支。我們在保留多供應商、多模態與基礎聊天能力的同時，重點圍繞 **RP（角色扮演）和本體戀** 場景持續打磨，補齊即時陪伴、低延遲語音、主動喚醒、日常互動與快取最佳化等體驗。

## 特色功能

- **即時通話、視訊與共享螢幕**：支援雙向語音通話、鏡頭視訊與系統授權螢幕共享，原則上可以和 AI 一起逛任何社群軟體、一起看電影，並支援通話紀錄與音訊回放。
- **多廠家 TTS 延遲最佳化與 AI 語音條**：接入 ElevenLabs（v3/v4/v4 Turbo WebSocket 串流預連線）、Fish Audio、MiniMax Speech 2.6/2.8 等多家 TTS 並針對首聲延遲最佳化；聊天支援圖文混排 AI 語音條、分段朗讀與情緒/聲音標籤。
- **Gemini / GPT 訂閱接入與快取提升**：支援 Gemini 訂閱、ChatGPT (Codex) 訂閱 OAuth 登入與額度查詢；針對多協議做了 Prompt Cache 前綴穩定與命中提升，搭配 DeepSeek Flash 等快速模型可獲得毫秒級回應體驗。
- **心跳與查崗模式**：支援背景定時心跳、AI 自主鬧鐘、晚安模式、本機應用程式使用統計/時長提醒、AI 應用程式鎖與查崗/哨兵觀察能力。
- **朋友圈與匿名提問箱**：按助手隔離的獨立朋友圈（AI 發文、配圖回應、按讚評論、日期篩選）與匿名提問箱，增強日常陪伴感。
- **長對話與獨立輔助介面**：支援自動捲動摘要與分塊壓縮、獨立壓縮 API/模型、獨立 OCR API/模型，以及請求日誌診斷與本機備份還原。

## 上游保留的基礎能力

- 多供應商與自訂 API（相容 OpenAI、Anthropic、Google 等介面）。
- 多模態輸入（圖片、文字、PDF、Docx 等）、獨立背景與透明度設定。
- MCP、本機工具、聯網搜尋與網頁存取。
- Markdown、程式碼高亮、LaTeX、表格和 Mermaid 渲染。
- 訊息分支、助手與人設自訂、Prompt 變數、記憶、AI 翻譯與角色卡匯入。
- Material You、深色模式、供應商 QR Code 匯入與匯出。

## 下載

前往 [GitHub Releases](https://github.com/Lin-chpin/rikkahub-Jude/releases) 查看並下載最新 APK。

預設 Debug 構建為 Universal APK。安裝前建議先備份應用程式資料；由於簽章可能與官方版本或其他構建不同，遇到覆蓋安裝衝突時請先匯出備份再處理舊安裝包。

## 本機構建

專案使用 Android Studio、Kotlin、Jetpack Compose、Koin、Room、DataStore 和 Gradle 構建。

構建 Public Universal Debug APK：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-universal-debug-apk.ps1
```

構建前需要在 `app` 資料夾放置 `google-services.json`。產物位於 `app/build/outputs/apk/public/debug/`。

## 倉庫歸屬

- 上游專案：[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)
- 本專案：[Lin-chpin/rikkahub-Jude](https://github.com/Lin-chpin/rikkahub-Jude)
- 維護者：[Lin-chpin](https://github.com/Lin-chpin)

本倉庫保留上游歷史提交。GitHub 檔案歷史中出現的上游提交身份只代表對應的 Git 元資料，不代表本專案的所有者發生變化。

## 授權條款

[License](LICENSE)
