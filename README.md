# TG Launcher

TG Launcher is an Android launcher for Minecraft Java Edition, independently maintained with a customized interface, integrated services, backend and administration console.

## Upstream attribution and license
- Open-source attribution: [Original upstream project repository](https://github.com/IsraaDeveloper/nuxlabs)
- Base release: `v1.1.6`
- License: GNU GPL-3.0. Upstream copyright notices, license texts and required attribution are retained. TG Launcher is an independent community project and is not an official product of any upstream project.

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
- Main build workflow: GitHub Actions (automated Android build and release)

Changes must preserve applicable open-source license notices and TG Launcher branding.