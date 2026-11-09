# TG Launcher

TG Launcher is an Android Minecraft Java launcher built from and modified from the open-source NUX Launcher v1.1.6 codebase. The installed application identity, product interface, backend and admin console are branded TG Launcher.

## Upstream attribution and license
- Upstream source: https://github.com/IsraaDeveloper/nuxlabs
- Base release: `v1.1.6`
- License: GNU GPL-3.0. Upstream copyright notices, license texts and required attribution are retained. TG Launcher is an independent modified project and is not presented as the upstream NUX product.

## TG Launcher services
- Android application package: `com.tg.launcher`
- Admin control center: `https://tg-launcher-admin-habibrofikonazli.vercel.app`
- Backend database: Neon PostgreSQL
- Public app configuration: `/api/app/config`
- Admin-controlled settings are stored in Neon and exposed to the Android client through the uncached configuration endpoint. Feature visibility, maintenance mode and account registration policy are connected to this configuration.

## Build
- Android SDK 37
- Minimum Android SDK 26
- JDK 17
- Main build workflow: GitHub Actions (`.github/workflows/import-nux-source.yml`)

Changes should preserve upstream license compliance and TG Launcher branding.