# Terminal Launcher v2

Native Android home screen launcher — terminal UI, real app list.

## Commands
- `run <app>` — launch app by name
- `delete <app>` — uninstall app
- `list` — show installed apps (toast)
- `open launcher.setting` — settings panel (bg color, text color)
- `clear` — clear input
- `help` — show commands

## Features
- Reads ALL installed apps via PackageManager
- Tap to launch, long-press for custom context menu (open / uninstall)
- Search bar filters apps live as you type
- Custom color picker: HSV sliders + hex input + presets
- All popups are custom-built (no system dialogs)
- Settings persist via SharedPreferences
