# Project Manager Android App

A modern Android project management application built with Kotlin and Jetpack Compose, featuring swipe-based navigation, voice commands, and daily task summaries.

## Features

### 1. **Swipe-Based Project Navigation**
- Horizontally swipe through your projects using an intuitive card-based interface
- Each project card displays:
  - Project name and description
  - Total tasks and completion percentage
  - Visual progress indicator
  - Color-coded project themes

### 2. **Voice Commands**
- Control the app using natural voice commands
- Supported commands:
  - "Add project [name]" - Create a new project
  - "Create project [name]" - Alternative project creation
  - "Add task [description]" - Add a task to current project
  - "Complete task [name]" - Mark a task as complete
  - "Show summary" or "Daily summary" - Navigate to daily summary
  - "Next" - Navigate to next project
  - "Previous" or "Back" - Navigate to previous project

### 3. **Task Management**
- Create tasks with:
  - Task name and description
  - Deadline using date picker
  - Project association
- View tasks organized by project
- Mark tasks as complete with a single tap
- Visual indicators for:
  - Overdue tasks (red)
  - Tasks due today (primary color)
  - Completed tasks (strikethrough)

### 4. **Daily Summary**
- Dedicated screen showing:
  - All overdue tasks
  - Tasks due today
  - Task counts for quick overview
- Easily accessible via bottom navigation

### 5. **Daily Notifications**
- Automatic daily summary notification at 9:00 AM
- Shows count of overdue and today's tasks
- Tapping notification opens the daily summary screen

## Technical Architecture

### Tech Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material 3
- **Database**: Room (SQLite)
- **Architecture**: MVVM (Model-View-ViewModel)
- **Async**: Kotlin Coroutines + Flow
- **Navigation**: Compose Navigation + Accompanist Pager

### Project Structure
```
app/
├── data/
│   ├── model/          # Data models (Project, Task)
│   ├── dao/            # Room DAOs
│   └── database/       # Room database
├── repository/         # Data repository layer
├── viewmodel/          # ViewModels
├── ui/
│   ├── screens/        # Main screens
│   ├── components/     # Reusable UI components
│   └── theme/          # App theming
├── notification/       # Notification system
└── utils/              # Utilities (DateUtils, VoiceCommandProcessor)
```

### Key Components

#### Data Layer
- **Room Database**: Persistent local storage
- **Entities**: Project and Task with foreign key relationships
- **DAOs**: Reactive queries using Kotlin Flow

#### UI Layer
- **ProjectListScreen**: Swipeable horizontal pager for projects
- **DailySummaryScreen**: Overview of today's tasks
- **VoiceCommandButton**: Speech recognition integration
- **Dialogs**: Add/Edit project and task dialogs

#### Features
- **Voice Command Processing**: Natural language understanding
- **Notification Scheduler**: AlarmManager for daily reminders
- **Date Utilities**: Smart date formatting and comparison

## Building the App

### Requirements
- Android Studio Hedgehog or newer
- Minimum SDK: API 26 (Android 8.0)
- Target SDK: API 34 (Android 14)
- JDK 17

### Build Steps
1. Open the project in Android Studio
2. Sync Gradle files
3. Run the app on an emulator or physical device

```bash
./gradlew assembleDebug
```

## Permissions

The app requires the following permissions:
- **RECORD_AUDIO**: For voice command functionality
- **POST_NOTIFICATIONS**: For daily summary notifications (Android 13+)
- **SCHEDULE_EXACT_ALARM**: For precise notification timing

## Usage Guide

### Creating a Project
1. Tap the floating action button (+) on the Projects screen
2. Enter project name and optional description
3. Tap "Save"

### Adding Tasks
1. Navigate to a project by swiping
2. Tap "Add Task" button
3. Fill in task details and select deadline
4. Tap "Save"

### Using Voice Commands
1. Tap the microphone floating action button
2. Speak your command when prompted
3. The app will process and execute the command

### Viewing Daily Summary
1. Tap "Summary" in the bottom navigation
2. View overdue and today's tasks
3. Complete tasks by tapping the circle icon

## Future Enhancements

Potential features for future versions:
- Task priority levels
- Project sharing and collaboration
- Custom notification times
- Task categories and tags
- Calendar view
- Widget support
- Export/Import functionality
- Dark mode customization

## License

This project is available for educational and personal use.

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.
