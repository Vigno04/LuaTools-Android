# LuaTools Android

Android companion app for **LuaTools** — push `.lua` / `.manifest` games, apply game fixes, manage BetterSteamTools / OpenSteamTools packs, and sync Steam Cloud saves via **CloudRedirect** (Google Drive) to Android Wine/Steam emulators (**BannerHub**, **GameNative**, **Winlator**, etc.).

## Repository Layout
- `app/` — Android Jetpack Compose app (`com.luatools.companion`)
- `luatools-javasteam-addon/` — Portable, zero-dependency Java library (`com.luatools.javasteam`) for target emulators. See [luatools-javasteam-addon/README.md](luatools-javasteam-addon/README.md) for the integration guide.

## Building
```bash
./gradlew assembleDebug
```
Requires Android SDK 36 (and `libcloud_redirect_android.so` from [CloudRedirect](https://github.com/Vigno04/CloudRedirect) releases in `app/src/main/jniLibs/`).
