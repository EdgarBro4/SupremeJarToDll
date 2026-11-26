using System.IO;
using System.Reflection;
using System.Text;

namespace JarToDllBuilder.Services;

public sealed record BuildRequest(
    string InputJarPath,
    string OutputDirectory,
    string OutputFileName,
    string TargetMinecraftVersion, // "1.16.5" or "1.21.4"
    ToolchainPreference Toolchain,
    IReadOnlyList<string> ExtraLibraryJars);

public sealed class DllBuilderService
{
    private readonly DependencyManager _depManager;

    public DllBuilderService(DependencyManager depManager)
    {
        _depManager = depManager;
    }

    public async Task<string> BuildAsync(
        BuildRequest request,
        Action<string> log,
        Action<double, string> progress,
        CancellationToken ct)
    {
        if (!File.Exists(request.InputJarPath))
        {
            throw new FileNotFoundException("Input JAR file does not exist.", request.InputJarPath);
        }

        Directory.CreateDirectory(request.OutputDirectory);
        string sanitizedName = request.OutputFileName.Trim();
        if (!sanitizedName.EndsWith(".dll", StringComparison.OrdinalIgnoreCase))
        {
            sanitizedName += ".dll";
        }
        string outputDllPath = Path.Combine(request.OutputDirectory, sanitizedName);

        // Step 0: Check and auto-download any missing dependencies
        progress(5, "Checking build dependencies...");
        log($"=== Starting JAR-to-DLL Build (Target: Minecraft Forge {request.TargetMinecraftVersion}) ===");
        var deps = await _depManager.EnsureDependenciesAsync(request.Toolchain, log, (p, msg) =>
        {
            progress(5 + p * 0.30, msg);
        }, ct);

        // Stage embedded native & Java build sources into an isolated temp directory
        string stageDir = Path.Combine(Path.GetTempPath(), "JarToDllStage_" + Guid.NewGuid().ToString("N")[..8]);
        try
        {
            progress(38, "Extracting embedded injector & native C++ sources...");
            log($"Preparing isolated workspace for Forge {request.TargetMinecraftVersion}...");
            ExtractBuildAssets(stageDir, request.TargetMinecraftVersion);

            string injectorJava = Path.Combine(stageDir, "injector-src", "ForgeInjector.java");
            string injectorClass = Path.Combine(stageDir, "injector-src", "ForgeInjector.class");
            string headerConverterDir = Path.Combine(stageDir, "header-converter-src");
            string headerConverterJava = Path.Combine(headerConverterDir, "HeaderConverter.java");
            string classesDir = Path.Combine(stageDir, "native-src", "classes");
            Directory.CreateDirectory(classesDir);

            string injectorHeader = Path.Combine(classesDir, "injector.h");
            string jarHeader = Path.Combine(classesDir, "jar.h");

            // [1/4] Compile ForgeInjector.java & HeaderConverter.java (Java 8 bytecode)
            progress(45, "[1/4] Compiling ForgeInjector & HeaderConverter...");
            log($"[1/4] Compiling Forge {request.TargetMinecraftVersion} injector and header converter (--release 8)...");

            int rc = await DependencyManager.RunStreamingProcessAsync(
                deps.JavacPath!,
                $"--release 8 -Xlint:-options \"{injectorJava}\"",
                stageDir, log, ct);
            if (rc != 0) throw new InvalidOperationException($"Failed to compile ForgeInjector.java (exit code {rc}).");

            rc = await DependencyManager.RunStreamingProcessAsync(
                deps.JavacPath!,
                $"--release 8 -Xlint:-options \"{headerConverterJava}\"",
                stageDir, log, ct);
            if (rc != 0) throw new InvalidOperationException($"Failed to compile HeaderConverter.java (exit code {rc}).");

            // [2/4] Convert ForgeInjector.class -> native-src/classes/injector.h
            progress(58, "[2/4] Generating native injector header...");
            log("[2/4] Converting ForgeInjector.class to native-src\\classes\\injector.h...");

            rc = await DependencyManager.RunStreamingProcessAsync(
                deps.JavaPath!,
                $"-cp \"{headerConverterDir}\" HeaderConverter injector \"{injectorClass}\" \"{injectorHeader}\"",
                stageDir, log, ct);
            if (rc != 0) throw new InvalidOperationException($"Failed to convert ForgeInjector.class (exit code {rc}).");

            // [3/4] Convert uploaded JAR (+ optional extra libraries) -> native-src/classes/jar.h
            progress(70, $"[3/4] Converting JAR for Minecraft {request.TargetMinecraftVersion}...");
            log($"[3/4] Converting \"{Path.GetFileName(request.InputJarPath)}\" to native-src\\classes\\jar.h (target={request.TargetMinecraftVersion})...");

            var hcArgs = new StringBuilder();
            hcArgs.Append($"-cp \"{headerConverterDir}\" HeaderConverter input-jar \"{request.InputJarPath}\" \"{jarHeader}\" --target={request.TargetMinecraftVersion}");
            foreach (string libJar in request.ExtraLibraryJars)
            {
                if (File.Exists(libJar))
                {
                    hcArgs.Append($" \"--lib={libJar}\"");
                }
            }

            rc = await DependencyManager.RunStreamingProcessAsync(deps.JavaPath!, hcArgs.ToString(), stageDir, log, ct);
            if (rc != 0) throw new InvalidOperationException($"Failed to convert input JAR (exit code {rc}).");

            // [4/4] Compile Native x64 Windows DLL
            progress(82, $"[4/4] Compiling {sanitizedName} ({deps.CppDisplay})...");
            log($"[4/4] Compiling native DLL via {deps.CppDisplay}...");

            if (File.Exists(outputDllPath))
            {
                File.Delete(outputDllPath);
            }

            switch (deps.CppKind)
            {
                case DetectedCppKind.Msvc:
                    await CompileWithMsvcAsync(deps.MsvcVcVarsPath!, stageDir, outputDllPath, log, ct);
                    break;

                case DetectedCppKind.NativeMinGW:
                    await CompileWithNativeMinGWAsync(deps.NativeGppPath!, stageDir, outputDllPath, log, ct);
                    break;

                case DetectedCppKind.WslMinGW:
                    await CompileWithWslMinGWAsync(stageDir, outputDllPath, log, ct);
                    break;

                default:
                    throw new InvalidOperationException("No C++ compiler available.");
            }

            if (!File.Exists(outputDllPath))
            {
                throw new InvalidOperationException($"Compiler finished without producing output file: {outputDllPath}");
            }

            var outInfo = new FileInfo(outputDllPath);
            progress(100, $"Build complete: {sanitizedName}");
            log($"=== BUILD COMPLETE: {outputDllPath} ({outInfo.Length / 1024.0:F1} KB) ===");
            return outputDllPath;
        }
        finally
        {
            try
            {
                if (Directory.Exists(stageDir))
                {
                    Directory.Delete(stageDir, true);
                }
            }
            catch
            {
                // Ignore temp cleanup locks
            }
        }
    }

    private static async Task CompileWithMsvcAsync(string vcVarsPath, string stageDir, string outputDllPath, Action<string> log, CancellationToken ct)
    {
        string batPath = Path.Combine(stageDir, "_compile_msvc.bat");
        string script =
            "@echo off\r\n" +
            $"call \"{vcVarsPath}\" >nul\r\n" +
            "if errorlevel 1 exit /b 1\r\n" +
            $"cl /nologo /std:c++17 /O2 /MT /EHsc /LD /DUNICODE /D_UNICODE native-src\\main.cpp native-src\\injector.cpp native-src\\utils.cpp user32.lib /Fe:\"{outputDllPath}\"\r\n";

        await File.WriteAllTextAsync(batPath, script, Encoding.ASCII, ct);
        int rc = await DependencyManager.RunStreamingProcessAsync("cmd.exe", $"/c \"{batPath}\"", stageDir, log, ct);
        if (rc != 0)
        {
            throw new InvalidOperationException($"MSVC cl.exe compilation failed (exit code {rc}).");
        }

        // Remove MSVC sidecar .exp / .lib files placed next to the output DLL
        string baseNoExt = Path.Combine(Path.GetDirectoryName(outputDllPath)!, Path.GetFileNameWithoutExtension(outputDllPath));
        TryDelete(baseNoExt + ".exp");
        TryDelete(baseNoExt + ".lib");
    }

    private static async Task CompileWithNativeMinGWAsync(string gppPath, string stageDir, string outputDllPath, Action<string> log, CancellationToken ct)
    {
        string nativeDir = Path.Combine(stageDir, "native-src");
        string binDir = Path.GetDirectoryName(gppPath)!;
        string currentPath = Environment.GetEnvironmentVariable("PATH") ?? "";
        var env = new Dictionary<string, string>
        {
            ["PATH"] = binDir + ";" + currentPath
        };

        string args = $" -O2 main.cpp injector.cpp utils.cpp -shared -Wl,--dll -Wl,--exclude-all-symbols -static -static-libgcc -static-libstdc++ -luser32 -o \"{outputDllPath}\"";
        int rc = await DependencyManager.RunStreamingProcessAsync(gppPath, args.Trim(), nativeDir, log, ct, env);
        if (rc != 0)
        {
            throw new InvalidOperationException($"MinGW-w64 g++ compilation failed (exit code {rc}).");
        }
    }

    private static async Task CompileWithWslMinGWAsync(string stageDir, string outputDllPath, Action<string> log, CancellationToken ct)
    {
        string nativeDir = Path.Combine(stageDir, "native-src");
        string wslNativeDir = ToWslPath(nativeDir);
        string wslOutDll = ToWslPath(outputDllPath);

        string bashCmd =
            $"cd '{EscapeBashSingle(wslNativeDir)}' && " +
            "x86_64-w64-mingw32-g++ -O2 main.cpp injector.cpp utils.cpp -shared -Wl,--dll -Wl,--exclude-all-symbols " +
            $"-static -static-libgcc -static-libstdc++ -luser32 -o '{EscapeBashSingle(wslOutDll)}'";

        int rc = await DependencyManager.RunStreamingProcessAsync("wsl.exe", $"bash -c \"{bashCmd.Replace("\"", "\\\"")}\"", stageDir, log, ct);
        if (rc != 0)
        {
            throw new InvalidOperationException($"WSL MinGW-w64 compilation failed (exit code {rc}).");
        }
    }

    private static string ToWslPath(string winPath)
    {
        string full = Path.GetFullPath(winPath);
        if (full.Length >= 2 && full[1] == ':')
        {
            char drive = char.ToLowerInvariant(full[0]);
            string rest = full[2..].Replace('\\', '/');
            return $"/mnt/{drive}{rest}";
        }
        return full.Replace('\\', '/');
    }

    private static string EscapeBashSingle(string s) => s.Replace("'", "'\\''");

    private static void ExtractBuildAssets(string stageDir, string targetMinecraftVersion)
    {
        var asm = Assembly.GetExecutingAssembly();
        string injectorResource = targetMinecraftVersion == "1.16.5"
            ? "Assets/injector-src/1.16.5/ForgeInjector.java"
            : "Assets/injector-src/1.21.4/ForgeInjector.java";

        var mapping = new Dictionary<string, string>
        {
            ["Assets/native-src/main.cpp"] = Path.Combine("native-src", "main.cpp"),
            ["Assets/native-src/injector.cpp"] = Path.Combine("native-src", "injector.cpp"),
            ["Assets/native-src/injector.h"] = Path.Combine("native-src", "injector.h"),
            ["Assets/native-src/utils.cpp"] = Path.Combine("native-src", "utils.cpp"),
            ["Assets/native-src/utils.h"] = Path.Combine("native-src", "utils.h"),
            ["Assets/native-src/jvm/jni.h"] = Path.Combine("native-src", "jvm", "jni.h"),
            ["Assets/native-src/jvm/jni_md.h"] = Path.Combine("native-src", "jvm", "jni_md.h"),
            ["Assets/native-src/jvm/jvmti.h"] = Path.Combine("native-src", "jvm", "jvmti.h"),
            ["Assets/header-converter-src/HeaderConverter.java"] = Path.Combine("header-converter-src", "HeaderConverter.java"),
            [injectorResource] = Path.Combine("injector-src", "ForgeInjector.java")
        };

        foreach (var (resName, relTarget) in mapping)
        {
            using var stream = asm.GetManifestResourceStream(resName)
                ?? throw new InvalidOperationException($"Missing embedded build asset: {resName}");
            string dest = Path.Combine(stageDir, relTarget);
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            using var fs = new FileStream(dest, FileMode.Create, FileAccess.Write);
            stream.CopyTo(fs);
        }
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }
}
