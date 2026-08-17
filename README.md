# ElderHelp 👴📱

ElderHelp is an accessibility-focused Android application designed to make smartphones easier, safer, and more comfortable to use for elderly users and people who may find complex mobile interfaces difficult to navigate.

The application combines voice assistance, Android Accessibility Services, visual guidance, and simplified interactions to guide users step-by-step through common smartphone tasks.

## Features

- Voice Interaction: Allows users to interact with the application using natural voice commands.
- Hindi and English Support: Provides voice interaction and guidance in Hindi and English.
- Text-to-Speech: Gives clear spoken instructions to guide users through tasks.
- Step-by-Step Assistance: Breaks complex smartphone tasks into simple actions.
- Smart UI Guidance: Uses Android Accessibility Services to identify relevant interactive UI elements.
- Visual Target Guidance: Displays an outline and pointer around the complete target UI element instead of relying on individual text highlighting.
- Accessibility-Based Interaction: Uses accessibility information such as text, content descriptions, clickable properties, and screen bounds.
- Voice Commands: Supports commands such as "Repeat", "Go back", "Stop", and "What should I do next?"
- Elder-Friendly Design: Focuses on simple instructions, clear navigation, and reduced interaction complexity.

## Technology Stack

- Platform: Android
- Language: Kotlin
- UI: Jetpack Compose
- Accessibility: Android AccessibilityService
- Voice: Speech-to-Text (STT) and Text-to-Speech (TTS)
- AI/LLM: Natural-language understanding and task guidance
- Overlay: Android Window/Overlay System
- Architecture: Modular Android Architecture

## How It Works

User gives a voice command → Speech-to-Text converts the command into text → AI/LLM understands the user's intent → the required action is identified → Android AccessibilityService searches for the relevant UI element → the system obtains the element's screen bounds → a visual guide is displayed around the target element → voice instructions are provided → the user performs the action → the system detects the screen or content change → the next target is identified → the process continues until the task is completed.

## Example User Flow

Suppose the user says:

"I want to pay my electricity bill."

ElderHelp understands the request and responds:

"First, tap Payments."

The Accessibility Service identifies the Payments button and the application displays a visual guide around the complete interactive element with a pointer indicating where the user should tap.

After the user taps Payments, the application detects the new screen and provides the next instruction:

"Now select Electricity."

The system continues guiding the user through each step until the requested task is completed.

## Problem Statement

Modern smartphone applications often contain small buttons, complex navigation, multiple menus, unfamiliar icons, dense information, and different interaction patterns. These interfaces can be difficult for elderly users to understand and navigate independently.

ElderHelp aims to simplify this experience by allowing users to communicate what they want to accomplish and then guiding them through the process step-by-step using voice and visual assistance.

"Tell me what you want to do, and I'll guide you through it."

## Guidance System

The guidance system does not depend on individual text highlighting. Instead, ElderHelp identifies the complete interactive UI element using Android Accessibility Services and obtains its screen bounds. A visual overlay can then indicate the target element using a rectangle and pointer.

The basic flow is:

User voice command → Intent detection → Target identification → Accessibility node detection → Screen bounds → Visual guidance → Voice instruction → User interaction → Screen change detection → Next target.

If the required target cannot be found, the application does not guess a random location. Instead, it can provide a voice message such as:

"I couldn't find that option. Please try again."

## Accessibility Permission

ElderHelp requires Android Accessibility Service permission to identify and guide users toward interactive elements.

Users can enable the permission through:

Settings → Accessibility → ElderHelp → Enable Accessibility Service

The exact location may vary depending on the Android device manufacturer and Android version.

## Project Structure

ElderHelp/
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
├── gradle/
├── build.gradle
├── settings.gradle
└── README.md

The exact project structure may vary depending on the current implementation.

## Getting Started

### Prerequisites

- Android Studio
- Android SDK
- Kotlin
- Android device or emulator
- Required API/LLM credentials, if applicable

### Installation

Clone the repository:

git clone <your-repository-url>

Navigate to the project:

cd ElderHelp

Open the project in Android Studio, allow Gradle to sync all dependencies, and run the application on an Android device or emulator.

After installation, enable the required Accessibility Service permission.

## Current Status

Project Stage: Working Prototype / Internal Evaluation

The current prototype focuses on demonstrating:

- Voice-based interaction
- Hindi and English voice support
- Accessibility-based UI detection
- Step-by-step task guidance
- Visual target indication
- Text-to-Speech instructions
- Elder-friendly interaction

## Future Scope

- Support for additional Indian languages.
- More accurate natural-language understanding.
- Personalized voice assistants.
- Support for a wider range of third-party applications.
- Emergency assistance and SOS functionality.
- Medication and appointment reminders.
- Assistance with digital payments and online services.
- Improved accessibility for users with different disabilities.
- Offline voice capabilities.
- Personalized guidance based on frequently performed tasks.
- Better context awareness and task completion detection.

## Vision

ElderHelp is built around one simple idea:

"Technology should adapt to people, not force people to adapt to technology."

The goal is to make smartphones more accessible, understandable, and less intimidating by turning complex digital tasks into simple, guided conversations.

ElderHelp — Making smartphones simpler, one step at a time. 💙
