path = r'd:\Project\antigravity\NUX-LAUNCHER\Launcher-Android-Final\app\src\main\java\com\israadev\nuxlauncher\ui\screens\SettingsScreen.kt'

with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

replacements = [
    ('text = "MOUSE & IN-GAME TOUCH CONTROLS"', 'text = tr("settings.section_mouse")'),
    ('text = "Mouse mode settings for Minecraft menus (Inventory, Pause Menu, Chat, Crafting)."', 'text = tr("settings.mouse_desc")'),
    ('text = "VIRTUAL CONTROLS CUSTOMIZATION"', 'text = tr("settings.virtual_controls_title")'),
    ('text = "Customize button layout, size, opacity, hotbar scroll, and Minecraft key mappings."', 'text = tr("settings.virtual_controls_desc")'),
    ('Text("OPEN GUI EDITOR",', 'Text(tr("settings.btn_open_gui_editor"),'),
    ('text = "SLIDE (Trackpad)"', 'text = tr("settings.mouse_mode_slide_title")'),
    ('text = "Swipe finger to guide virtual cursor, tap screen to click."', 'text = tr("settings.mouse_mode_slide_desc")'),
    ('text = "CLICK (Direct)"', 'text = tr("settings.mouse_mode_click_title")'),
    ('text = "Direct touch interaction where finger taps the screen."', 'text = tr("settings.mouse_mode_click_desc")'),
    ('text = "Camera Aim Sensitivity (Look Around)"', 'text = tr("settings.camera_sensitivity_label")'),
    ('text = "Virtual Cursor Sensitivity (Menus)"', 'text = tr("settings.mouse_sensitivity_label")'),
    ('text = "Virtual Cursor Size"', 'text = tr("settings.mouse_size_label")'),
    ('text = "Auto-Analyze on Game Crash"', 'text = tr("settings.ai_auto_analyze_label")'),
    ('text = "API KEY (OPTIONAL)"', 'text = tr("settings.ai_api_key_label")'),
    ('Text("TEST AI CONNECTION",', 'Text(tr("settings.btn_test_ai"),'),
    ('Text("TESTING...",', 'Text(tr("settings.btn_testing_ai"),'),
    ('Text("SAVE SETTINGS",', 'Text(tr("settings.btn_save_settings"),'),
    ('text = "SYSTEM STATUS & CAPABILITY"', 'text = tr("settings.ai_status_title")'),
    ('text = "What Can the AI Diagnose?"', 'text = tr("settings.ai_capabilities_title")'),
    ('Text("SIGN OUT (LOGOUT)",', 'Text(tr("sidebar.logout"),'),
    ('text = if (isUploadingAvatar) "UPLOADING..." else "CHANGE PROFILE PICTURE"', 'text = if (isUploadingAvatar) tr("common.loading") else tr("settings.btn_change_avatar")'),
    ('Text("SAVE USERNAME",', 'Text(tr("settings.btn_save_username"),'),
    ('text = "CHANGE NUX USERNAME"', 'text = tr("settings.card_change_username")'),
]

count = 0
for old, new in replacements:
    if old in content:
        content = content.replace(old, new)
        count += 1
    else:
        print(f"NOT FOUND: {old}")

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)

print(f"Batch replaced {count}/{len(replacements)} strings in SettingsScreen.kt")
