# LuaTools + CloudRedirect Universal JavaSteam Addon

Drop-in, **zero-dependency** library (compiles against `android.jar` only) that adds LuaTools `.lua`/`.manifest` games, BetterSteamTools / OpenSteamTools & Steamworks runtime management, one-click game fixes, and automatic Google Drive CloudRedirect saves to **any** Android Steam/Wine emulator (BannerHub, GameNative, Pluvia, Winlator, etc.).

## Files (`src/main/java/com/luatools/javasteam/`)
- `LuaToolsStore.java` — parses `.lua` depot keys & manifest IDs, caches real Steam PICS metadata (`api.steamcmd.net`).
- `LuaToolsJavaSteamHook.java` — supplies extra AppIDs, depot decryption keys, and decrypted `in.dragonbra.javasteam.types.DepotManifest` objects via reflection.
- `LuaToolsCloudSync.java` — sandbox-safe pre-launch / post-exit Google Drive save sync over `ContentProvider` streams.
- `LuaToolsSteamEmu.java` — portable BetterSteamTools / OpenSteamTools pack manager (`OpenSteamTool.dll`, `dwmapi.dll`, `xinput1_4.dll`), `opensteamtool.toml` writer, and `ensureSteamworksRuntime()` (`steam_api64.dll` + `steam_appid.txt`). Uses `LuaToolsSteamEmu.HostAdapter` so host-specific paths stay out of the core library.
- `LuaToolsBridgeProvider.java` — exported `ContentProvider` receiving `.lua`, streamed `.manifest`, `fix_in` zips, `pack_in` BST/OST DLLs, and `set_steam_emu` / `bridge_status` IPC calls from **LuaTools Android**.

## Integration Guide

### 1. Register the provider in `AndroidManifest.xml`
```xml
<queries>
    <package android:name="com.luatools.companion" />
</queries>

<application ...>
    <provider
        android:name="com.luatools.javasteam.LuaToolsBridgeProvider"
        android:authorities="${applicationId}.bh_lua_bridge"
        android:exported="true" />
</application>
```
If your emulator needs custom paths or launch-script overrides (for example, pointing `resolveSteamClientDir()` to a real `steam.exe` directory in Winlator or patching `startSteam.bat`), subclass `LuaToolsBridgeProvider` and override:
- `resolveGameInstallDir(Context, int appId, String fallbackName)`
- `resolveSteamClientDir(Context)`
- `resolveSteamLuaPathWin(Context)`
- `onSteamClientAttached(Context, File steamClientDir, String mode)` / `onSteamClientDetached(Context, File steamClientDir)`
- `onFixDllOverrides(Context, int appId, Set<String> dllStems)`

### 2. Merge LuaTools games into your Steam library
When handling `LicenseListCallback` (or building your owned-app list):
```kotlin
val luaHook = LuaToolsJavaSteamHook.get(context)
val allAppIds = (steamOwnedAppIds + luaHook.luaAppIds).distinct()
```

### 3. Supply depot keys & manifests during download
Before calling `SteamApps.getDepotDecryptionKey(depotId, appId)`:
```kotlin
val localKey = luaHook.getDepotKey(depotId)
if (localKey != null) return localKey // avoids AccessDenied
```
Before calling `SteamContent.getManifestRequestCode(depotId, appId, manifestId)`:
```kotlin
val localManifest = luaHook.loadDecryptedDepotManifest(depotId, manifestId) as? DepotManifest
if (localManifest != null) return localManifest // avoids AccessDenied
```

### 4. Launch & automatic CloudRedirect saves
```kotlin
val isLua = luaHook.isLuaGame(appId)
val gseRemoteDir = File(winePrefix, "drive_c/users/steamuser/AppData/Roaming/GSE Saves/$appId/remote")

if (isLua) {
    useGoldbergOrFakeSteam = true
    LuaToolsSteamEmu.attachGameRuntime(context, appId, gameInstallDir)
    LuaToolsCloudSync.onPreLaunch(context, appId, gseRemoteDir)
}

// ... run game in Wine ...

if (isLua) {
    LuaToolsCloudSync.onPostExit(context, appId, gseRemoteDir)
}
```
The **LuaTools Android** companion app automatically detects your app via `${applicationId}.bh_lua_bridge`, pushes `.lua` + `.manifest` files, applies Online-Fix/game fixes, pushes BST/OST packs, and syncs saves to Google Drive with zero root or SAF prompts.
