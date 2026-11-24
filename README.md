# LuaTools Android

Android companion app for **LuaTools** — push `.lua` / `.manifest` games, apply online & community game fixes, manage BetterSteamTools / OpenSteamTools packs, and sync Steam Cloud saves via **CloudRedirect** (Google Drive) to Android Wine/Steam emulators (**BannerHub**, **GameNative**, **Winlator**, etc.).

## Integrated Emulators
- **[Modified BannerHub (Vigno04/bannerhub)](https://github.com/Vigno04/bannerhub)** — Android Steam/Wine emulator integrating the `luatools-javasteam-addon` library (`BhLuaBridgeProvider`, `BhLuaSteamEmu`, `BhLuaCloudHook`).
- **[Modified GameNative (Vigno04/GameNative)](https://github.com/Vigno04/GameNative)** — Open-source Android Steam/Wine emulator integrating `luatools-javasteam-addon` (`GameNativeLuaBridgeProvider`, `SteamService`, `GameDownloadService`) with automatic upstream release syncing.

## Repositories
- `app/` — Android Jetpack Compose app (`com.luatools.companion`)
- `luatools-javasteam-addon/` — Portable, zero-dependency Java library (`com.luatools.javasteam`) for target emulators. See [luatools-javasteam-addon/README.md](luatools-javasteam-addon/README.md) for the integration guide.
- **[LuaTools-Fixes (Vigno04/LuaTools-Fixes)](https://github.com/Vigno04/LuaTools-Fixes)** — Community & Wine/FEX compatibility fixes index (`fixes.json`) loaded alongside LuaTools API fixes in the **Fixes** tab.

## Building
```bash
./gradlew assembleDebug
```
Requires Android SDK 36 (and `libcloud_redirect_android.so` from [CloudRedirect](https://github.com/Vigno04/CloudRedirect) releases in `app/src/main/jniLibs/`).
