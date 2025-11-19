EMS Glass OS (Medicine Checker)
EMS Glass OS is an Android application designed for smart glasses, assisting Emergency Medical Services (EMS) personnel in rapidly identifying pharmaceutical products using AI.

<div align="center"> <img src="app/src/main/res/drawable/test_image_1.jpg" width="45%" /> <img src="app/src/main/res/drawable/test_image_2.jpg" width="45%" /> </div>

⚡ Key Features
AI Vision: Instantly identifies medicines and bottles using the Google Gemini 2.5 Flash model.

Voice Output (TTS): Automatically reads out key information (Name, Dosage, Side Effects) for hands-free operation.

Industrial UI: High-contrast color scheme (Yellow/Orange/Cyan on Black) optimized for visibility on optical head-mounted displays.

Smart Layout: Forced landscape mode with a 2:1 aspect ratio to fit smart glass field-of-view.

🛠 Tech Stack
Language: Kotlin

UI: Jetpack Compose (Material3)

AI: Google Gemini API

Camera: CameraX

Networking: Ktor Client

🚀 Quick Start
Clone the repository.

Add API Key: Open MainActivity.kt and replace GEMINI_API_KEY with your own key.

Run on an Android device (min SDK 24).

⚠️ Disclaimer
For assistance only. AI results may be inaccurate. Always verify medicine labels and consult professional protocols before administration.
