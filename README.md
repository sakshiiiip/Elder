ElderHelp is an accessibility-focused Android application designed to make smartphones easier and safer for elderly users and people who may have difficulty navigating complex mobile interfaces.

The app combines voice assistance, accessibility services, visual guidance, and simplified interactions to guide users step-by-step through common smartphone tasks.

🚀 Key Features
🎙️ Voice Interaction
Supports natural voice commands.
Provides spoken instructions using Text-to-Speech.
Commands such as:
“Repeat”
“Go back”
“Stop”
“What should I do next?”
🗣️ Hindi & English Support
Designed for voice interaction in both Hindi and English.
Makes instructions easier to understand for users who are more comfortable with regional languages.
👆 Smart UI Guidance
Uses Android Accessibility Services to identify relevant UI elements.
Places a visual guide/outline around the complete interactive element.
Uses an arrow/pointer to indicate where the user should tap.
Does not rely on unreliable individual-text highlighting.
🔊 Step-by-Step Assistance
Breaks complex tasks into simple steps.
Provides voice instructions along with visual guidance.
Detects screen changes and moves to the next relevant step.
📱 Accessibility-Based Interaction
Uses accessibility information such as:
Text
Content descriptions
Clickable elements
Screen bounds
Helps the assistant understand the structure of supported applications.
🧓 Elder-Friendly Design
Simple interaction flow.
Clear instructions.
Reduced dependence on small buttons and complicated navigation.
Designed with accessibility and ease of use as the primary goals.
🛠️ Technology Stack
Platform: Android
Language: Kotlin
UI: Jetpack Compose
Accessibility: Android AccessibilityService
Voice: Speech-to-Text + Text-to-Speech
AI/LLM: Used for understanding natural-language commands and generating guidance
Overlay: Android overlay/window system
Architecture: Modular Android architecture
🏗️ How It Works
              User
                │
                ▼
        🎙️ Voice Command
                │
                ▼
       Speech-to-Text (STT)
                │
                ▼
          Command / LLM
                │
                ▼
       Identify User Intent
                │
                ▼
      Accessibility Service
                │
                ▼
       Find Target UI Element
                │
                ▼
       Get Screen Bounds
                │
                ▼
       ┌──────────────────┐
       │ Visual Guidance  │
       │  → Target        │
       └──────────────────┘
                │
                ▼
          🔊 TTS Guidance
                │
                ▼
          User Interaction
                │
                ▼
        Next Step / Screen
📂 Project Structure
ElderHelp/
│
├── app/
│   └── src/
│       └── main/
│           ├── java/
│           │   └── ...
│           ├── res/
│           │   ├── drawable/
│           │   ├── mipmap/
│           │   └── values/
│           └── AndroidManifest.xml
│
├── gradle/
├── build.gradle
├── settings.gradle
└── README.md

The exact structure may vary depending on the current implementation.

🔄 Example User Flow
Example: Paying a Bill

User:

“I want to pay my electricity bill.”

ElderHelp:

🔊 “First, open Payments.”

The Accessibility Service identifies the Payments button and the app displays a visual pointer around the complete button.

After the user taps it:

🔊 “Now select Electricity.”

The system detects the next screen and guides the user to the next relevant element.

🎯 Problem We Are Solving

Modern smartphone applications often contain:

Small buttons
Complex navigation
Multiple menus
Unfamiliar icons
Dense information
Different interaction patterns

These interfaces can be difficult for elderly users to navigate independently.

ElderHelp aims to turn complex smartphone tasks into simple, guided conversations.

Instead of asking the user to understand the entire interface, the application focuses on:

“Tell me what you want to do, and I'll guide you through it.”

🔮 Future Scope
More Indian language support.
Personalized voice assistants.
Improved intent detection.
Support for more third-party applications.
Emergency assistance and SOS features.
Medication and appointment reminders.
Assistance with payments and digital services.
Improved accessibility for users with different disabilities.
Offline voice capabilities.
Personalized learning based on frequently performed tasks.
👥 Project Goal

ElderHelp is built with one central idea:

Technology should adapt to people, not force people to adapt to technology.

The project aims to make smartphones more accessible, understandable, and less intimidating for elderly users through voice-driven and context-aware assistance.

⚙️ Getting Started
Prerequisites
Android Studio
Android SDK
Kotlin
Android device or emulator
Required API/LLM credentials, if applicable
Installation
git clone <your-repository-url>
cd ElderHelp

Open the project in Android Studio, allow Gradle dependencies to sync, and run the application on an Android device/emulator.

Accessibility Permission

Since ElderHelp uses Android Accessibility Services, the required accessibility permission must be enabled manually:

Settings
   ↓
Accessibility
   ↓
ElderHelp
   ↓
Enable Accessibility Service

The exact settings path may vary depending on the Android device.

📌 Project Status

Current Stage: Working Prototype / Internal Evaluation

The current prototype focuses on demonstrating:

Voice-based interaction
Accessibility-based UI detection
Step-by-step guidance
Visual target indication
Text-to-Speech instructions
Elder-friendly interaction

ElderHelp — Making smartphones simpler, one step at a time. 💙
