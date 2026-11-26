# Supreme Jar To Dll

A standalone Windows desktop tool that packs Minecraft Forge mod `.jar` files into injectable x64 Windows `.dll` binaries — with built-in support for both **Minecraft Forge 1.16.5** and **Minecraft Forge 1.21.4**, plus automatic dependency downloading.

---

## Features

- **Standalone Self-Contained `.exe` GUI** — Dark black-and-red interface (`Supreme Jar To Dll.exe` from [Releases](https://github.com/EdgarBro4/SupremeJarToDll/releases/latest)). Move it anywhere on your PC — no .NET installation or extra files required.
- **One-Click Upload & Drag-and-Drop** — Upload any Forge `.jar` mod, choose your output folder, set the output `.dll` name, and click **BUILD DLL**.
- **Minecraft 1.16.5 & 1.21.4 Support** — Dedicated runtime injectors and JAR converters for each version:
  - **1.16.5 (Forge 36.x / Java 8)**: Targets `TransformingClassLoader`, bridges `EventBus 4.x` (`ASMEventHandler.LOADER`), wires `FMLJavaModLoadingContext` + `DeferredWorkQueue` (`enqueueWork`), dispatches captured `ModEventBus` lifecycle listeners (`FMLCommonSetupEvent`, `FMLClientSetupEvent`, `FMLLoadCompleteEvent`), and filters out incompatible Java 9+ multi-release classes.
  - **1.21.4 (Forge 54.x / Java 21)**: Unlocks JPMS modules via `Unsafe` + `IMPL_LOOKUP`, registers packages into `SecureModuleClassLoader` (`packageToParentLoader`), switches `EventBus` from `ModLauncherFactory` to `ClassLoaderFactory`, wires `FMLJavaModLoadingContext` + `DeferredWorkQueue`, dispatches captured `ModEventBus` lifecycle listeners without duplication, and injects `LayeredDraw` HUD overlays.
- **Auto-Detects Mod Version** — Inspects `META-INF/mods.toml` and bytecode version on upload to suggest `1.16.5` or `1.21.4`.
- **Optional Extra Library JARs** — Attach additional dependency `.jar` libraries to bundle directly into the compiled `.dll`.
- **Automatic Dependency Installer** — Automatically detects installed tools (JDK, Visual Studio MSVC `cl.exe`, MinGW-w64 `g++`, or WSL `g++-mingw-w64-x86-64`). If missing, it automatically downloads and sets up a portable **Eclipse Temurin JDK 21** and portable **MinGW-w64 `g++`** (or installs WSL + `g++-mingw-w64-x86-64` if WSL mode is selected) before building.

---

## Quick Start (GUI)

1. Download **`Supreme Jar To Dll.exe`** from the [Latest Release](https://github.com/EdgarBro4/SupremeJarToDll/releases/latest).
2. Select your target version: **Minecraft 1.16.5 (Forge)** or **Minecraft 1.21.4 (Forge)**.
3. Click **Upload** (or drag and drop your `.jar` onto the window).
4. Choose your **Output Destination** folder and enter an **Output DLL Name**.
5. Click **BUILD DLL**.
   - Missing build tools (JDK / `g++` / WSL toolchain) will be downloaded automatically before compilation starts.
   - When finished, click **Open Output Folder** to locate your `.dll`.

> **Requirement:** Windows 10/11 (x64). No separate .NET runtime installation is required.

---

## Building From Source

### Build the GUI `.exe`

Requires the .NET SDK on Windows:

```powershell
dotnet publish ui-app\JarToDllBuilder.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:IncludeAllContentForSelfExtract=true -p:EnableCompressionInSingleFile=true -p:DebugType=none -p:DebugSymbols=false -o dist
```

This produces a single self-contained `dist/Supreme Jar To Dll.exe` with all native C++ and Java injector sources embedded inside.

### CLI Build (Optional)

You can also run the classic CLI pipeline directly without the GUI:

- **Windows (`build.bat`)**: Place your mod as `input.jar` in the root folder and run:
  ```bat
  build.bat
  ```
- **Linux / WSL (`Makefile`)**: Install `g++-mingw-w64-x86-64` and a JDK, place `input.jar` in the root folder, and run:
  ```bash
  make build
  ```
- **Headless via `.exe`**:
  ```powershell
  "dist\Supreme Jar To Dll.exe" --cli --jar path\to\mod.jar --out path\to\output --name mymod --mc 1.16.5
  ```

---

## Project Structure

```text
├── dist/
│   └── Supreme Jar To Dll.exe      # Standalone single-file Windows GUI app
├── injector-src/
│   ├── ForgeInjector.java          # Minecraft Forge 1.21.4 (Java 21) injector
│   └── 1.16.5/
│       └── ForgeInjector.java      # Minecraft Forge 1.16.5 (Java 8) injector
├── header-converter-src/
│   └── HeaderConverter.java        # Converts .class / .jar files into C++ headers
├── native-src/                     # C++ JNI DLL bootstrapper & JVM headers
├── ui-app/                         # C# WPF source code for Supreme Jar To Dll
├── build.bat                       # Windows CLI build script
└── Makefile                        # Linux/WSL CLI build script
```

---

## Notes & Limitations

- Supports **Minecraft Forge** mods (tested on **1.16.5** and **1.21.4**).
- Mods should not rely on dynamic `URLClassLoader` loading at runtime.
- Injection runtime logs are written to `%USERPROFILE%\jar-to-dll-log.txt` for easy troubleshooting.
