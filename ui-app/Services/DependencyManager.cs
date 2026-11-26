using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Text.Json;

namespace JarToDllBuilder.Services;

public enum ToolchainPreference
{
    Auto,
    PortableMinGW,
    Msvc,
    WslMinGW
}

public enum DetectedCppKind
{
    None,
    Msvc,
    NativeMinGW,
    WslMinGW
}

public sealed class DependencyStatus
{
    public string? JavacPath { get; set; }
    public string? JavaPath { get; set; }
    public string JdkDisplay { get; set; } = "Not found";

    public DetectedCppKind CppKind { get; set; } = DetectedCppKind.None;
    public string? MsvcVcVarsPath { get; set; }
    public string? NativeGppPath { get; set; }
    public bool WslInstalled { get; set; }
    public bool WslHasDistro { get; set; }
    public bool WslHasMinGW { get; set; }
    public string CppDisplay { get; set; } = "Not found";

    public bool HasJdk => !string.IsNullOrEmpty(JavacPath) && !string.IsNullOrEmpty(JavaPath);
    public bool HasCpp => CppKind != DetectedCppKind.None;
}

public sealed class DependencyManager
{
    private static readonly HttpClient Http = CreateHttpClient();
    public static string ToolsRoot => ResolveToolsRoot();

    private static string ResolveToolsRoot()
    {
        string preferred = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "SupremeJarToDll",
            "tools");
        try
        {
            Directory.CreateDirectory(preferred);
            return preferred;
        }
        catch
        {
            string fallback = Path.Combine(Path.GetTempPath(), "SupremeJarToDll", "tools");
            Directory.CreateDirectory(fallback);
            return fallback;
        }
    }

    private static HttpClient CreateHttpClient()
    {
        var handler = new HttpClientHandler { AllowAutoRedirect = true };
        var client = new HttpClient(handler) { Timeout = TimeSpan.FromMinutes(20) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("SupremeJarToDll/2.0");
        return client;
    }

    public Task<DependencyStatus> DetectAsync(ToolchainPreference preference, Action<string>? log = null) =>
        Task.Run(async () =>
        {
            var status = new DependencyStatus();

            // 1. Detect JDK (javac + java)
            var (javac, java, jdkLabel) = FindJdk();
            status.JavacPath = javac;
            status.JavaPath = java;
            status.JdkDisplay = jdkLabel;

            // 2. Detect C++ compilers
            status.MsvcVcVarsPath = FindMsvcVcVars();
            status.NativeGppPath = FindNativeMinGW();

            var (wslInstalled, wslDistro, wslMingw) = await ProbeWslAsync();
            status.WslInstalled = wslInstalled;
            status.WslHasDistro = wslDistro;
            status.WslHasMinGW = wslMingw;

            SelectCppToolchain(status, preference);
            return status;
        });

    public static void SelectCppToolchain(DependencyStatus status, ToolchainPreference preference)
    {
        switch (preference)
        {
            case ToolchainPreference.Msvc:
                if (!string.IsNullOrEmpty(status.MsvcVcVarsPath))
                {
                    status.CppKind = DetectedCppKind.Msvc;
                    status.CppDisplay = "MSVC cl.exe (Visual Studio)";
                }
                else
                {
                    status.CppKind = DetectedCppKind.None;
                    status.CppDisplay = "MSVC not found (will use Portable MinGW g++)";
                }
                break;

            case ToolchainPreference.PortableMinGW:
                if (!string.IsNullOrEmpty(status.NativeGppPath))
                {
                    status.CppKind = DetectedCppKind.NativeMinGW;
                    status.CppDisplay = $"MinGW-w64 g++ ({Path.GetFileName(status.NativeGppPath)})";
                }
                else
                {
                    status.CppKind = DetectedCppKind.None;
                    status.CppDisplay = "MinGW-w64 g++ not installed (will auto-download)";
                }
                break;

            case ToolchainPreference.WslMinGW:
                if (status.WslHasMinGW)
                {
                    status.CppKind = DetectedCppKind.WslMinGW;
                    status.CppDisplay = "WSL x86_64-w64-mingw32-g++";
                }
                else if (status.WslHasDistro)
                {
                    status.CppKind = DetectedCppKind.None;
                    status.CppDisplay = "WSL ready — g++-mingw-w64 missing (will auto-install)";
                }
                else
                {
                    status.CppKind = DetectedCppKind.None;
                    status.CppDisplay = "WSL not set up (will install WSL + g++)";
                }
                break;

            case ToolchainPreference.Auto:
            default:
                if (!string.IsNullOrEmpty(status.MsvcVcVarsPath))
                {
                    status.CppKind = DetectedCppKind.Msvc;
                    status.CppDisplay = "MSVC cl.exe (Visual Studio)";
                }
                else if (!string.IsNullOrEmpty(status.NativeGppPath))
                {
                    status.CppKind = DetectedCppKind.NativeMinGW;
                    status.CppDisplay = $"MinGW-w64 g++ ({Path.GetFileName(status.NativeGppPath)})";
                }
                else if (status.WslHasMinGW)
                {
                    status.CppKind = DetectedCppKind.WslMinGW;
                    status.CppDisplay = "WSL x86_64-w64-mingw32-g++";
                }
                else
                {
                    status.CppKind = DetectedCppKind.None;
                    status.CppDisplay = "No C++ compiler found (will auto-download g++)";
                }
                break;
        }
    }

    public async Task<DependencyStatus> EnsureDependenciesAsync(
        ToolchainPreference preference,
        Action<string> log,
        Action<double, string> progress,
        CancellationToken ct)
    {
        Directory.CreateDirectory(ToolsRoot);
        var status = await DetectAsync(preference, log);

        // Step A: Ensure JDK
        if (!status.HasJdk)
        {
            log("JDK (javac/java) not found locally. Downloading portable Eclipse Temurin JDK 21...");
            await DownloadPortableJdkAsync(log, progress, ct);
            var (javac, java, jdkLabel) = FindJdk();
            status.JavacPath = javac;
            status.JavaPath = java;
            status.JdkDisplay = jdkLabel;
            if (!status.HasJdk)
            {
                throw new InvalidOperationException("Failed to install portable JDK. Please check your internet connection.");
            }
            log($"JDK ready: {status.JavacPath}");
        }
        else
        {
            log($"JDK detected: {status.JdkDisplay} ({status.JavacPath})");
        }

        // Step B: Ensure C++ Compiler
        if (!status.HasCpp)
        {
            if (preference == ToolchainPreference.WslMinGW)
            {
                await EnsureWslMinGWAsync(status, log, progress, ct);
                SelectCppToolchain(status, preference);
            }

            // For Auto / PortableMinGW / fallback if MSVC isn't present
            if (!status.HasCpp && preference != ToolchainPreference.WslMinGW)
            {
                log("C++ compiler not found for selected toolchain. Downloading portable MinGW-w64 (g++)...");
                await DownloadPortableMinGWAsync(log, progress, ct);
                status.NativeGppPath = FindNativeMinGW();
                SelectCppToolchain(status, preference == ToolchainPreference.Msvc ? ToolchainPreference.PortableMinGW : preference);
            }

            if (!status.HasCpp)
            {
                throw new InvalidOperationException("C++ compiler setup did not complete. Check the console output above.");
            }
        }
        else
        {
            log($"C++ compiler detected: {status.CppDisplay}");
        }

        return status;
    }

    private static (string? Javac, string? Java, string Label) FindJdk()
    {
        var candidates = new List<(string Dir, string Label)>();

        // 1. App portable JDK cache
        string portableJdkRoot = Path.Combine(ToolsRoot, "jdk");
        if (Directory.Exists(portableJdkRoot))
        {
            foreach (var javac in Directory.EnumerateFiles(portableJdkRoot, "javac.exe", SearchOption.AllDirectories))
            {
                string? binDir = Path.GetDirectoryName(javac);
                if (binDir != null)
                {
                    candidates.Add((binDir, "Portable Temurin JDK"));
                }
            }
        }

        // 2. JAVA_HOME
        string? javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrWhiteSpace(javaHome))
        {
            candidates.Add((Path.Combine(javaHome, "bin"), "JAVA_HOME JDK"));
        }

        // 3. JetBrains JBR (like build.bat)
        string jetbrainsRoot = @"C:\Program Files\JetBrains";
        if (Directory.Exists(jetbrainsRoot))
        {
            try
            {
                foreach (var dir in Directory.GetDirectories(jetbrainsRoot).OrderByDescending(d => d))
                {
                    candidates.Add((Path.Combine(dir, "jbr", "bin"), "JetBrains JBR"));
                }
            }
            catch { }
        }

        // 4. Standard Windows JDK install locations
        string[] standardRoots =
        {
            @"C:\Program Files\Eclipse Adoptium",
            @"C:\Program Files\AdoptOpenJDK",
            @"C:\Program Files\Microsoft",
            @"C:\Program Files\Java",
            @"C:\Program Files\Zulu",
            @"C:\Program Files\BellSoft",
            @"C:\Program Files\Amazon Corretto"
        };
        foreach (string root in standardRoots)
        {
            if (!Directory.Exists(root)) continue;
            try
            {
                foreach (var sub in Directory.GetDirectories(root).OrderByDescending(d => d))
                {
                    candidates.Add((Path.Combine(sub, "bin"), $"System JDK ({Path.GetFileName(sub)})"));
                }
            }
            catch { }
        }

        // 5. PATH
        string? pathEnv = Environment.GetEnvironmentVariable("PATH");
        if (!string.IsNullOrEmpty(pathEnv))
        {
            foreach (string entry in pathEnv.Split(';', StringSplitOptions.RemoveEmptyEntries))
            {
                candidates.Add((entry.Trim(), "System PATH JDK"));
            }
        }

        foreach (var (binDir, label) in candidates)
        {
            try
            {
                string javac = Path.Combine(binDir, "javac.exe");
                string java = Path.Combine(binDir, "java.exe");
                if (File.Exists(javac) && File.Exists(java))
                {
                    return (javac, java, label);
                }
            }
            catch { }
        }

        return (null, null, "Not installed (will auto-download)");
    }

    private static string? FindMsvcVcVars()
    {
        // Check vswhere.exe first
        string vswhere = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
            "Microsoft Visual Studio", "Installer", "vswhere.exe");
        if (File.Exists(vswhere))
        {
            try
            {
                var psi = new ProcessStartInfo(vswhere,
                    "-latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath")
                {
                    RedirectStandardOutput = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };
                using var proc = Process.Start(psi);
                if (proc != null)
                {
                    string installPath = proc.StandardOutput.ReadToEnd().Trim();
                    proc.WaitForExit(4000);
                    if (!string.IsNullOrEmpty(installPath))
                    {
                        string vcvars = Path.Combine(installPath, "VC", "Auxiliary", "Build", "vcvars64.bat");
                        if (File.Exists(vcvars))
                        {
                            return vcvars;
                        }
                    }
                }
            }
            catch { }
        }

        // Fallback direct scan (covers VS 18 BuildTools from build.bat + 2022/2019 editions)
        string[] vsRoots =
        {
            @"C:\Program Files (x86)\Microsoft Visual Studio",
            @"C:\Program Files\Microsoft Visual Studio"
        };
        foreach (string root in vsRoots)
        {
            if (!Directory.Exists(root)) continue;
            try
            {
                foreach (var yearDir in Directory.GetDirectories(root).OrderByDescending(d => d))
                {
                    foreach (var editionDir in Directory.GetDirectories(yearDir))
                    {
                        string vcvars = Path.Combine(editionDir, "VC", "Auxiliary", "Build", "vcvars64.bat");
                        if (File.Exists(vcvars))
                        {
                            return vcvars;
                        }
                    }
                }
            }
            catch { }
        }

        return null;
    }

    private static string? FindNativeMinGW()
    {
        string[] exeNames = { "x86_64-w64-mingw32-g++.exe", "g++.exe" };
        var searchDirs = new List<string>();

        // 1. Portable cache
        string portableMingw = Path.Combine(ToolsRoot, "mingw64");
        if (Directory.Exists(portableMingw))
        {
            foreach (string exeName in exeNames)
            {
                var found = Directory.EnumerateFiles(portableMingw, exeName, SearchOption.AllDirectories).FirstOrDefault();
                if (found != null)
                {
                    return found;
                }
            }
        }

        // 2. Common MinGW-w64 paths
        searchDirs.AddRange(new[]
        {
            @"C:\msys64\mingw64\bin",
            @"C:\msys64\ucrt64\bin",
            @"C:\mingw64\bin",
            @"C:\w64devkit\bin"
        });

        // 3. PATH
        string? pathEnv = Environment.GetEnvironmentVariable("PATH");
        if (!string.IsNullOrEmpty(pathEnv))
        {
            searchDirs.AddRange(pathEnv.Split(';', StringSplitOptions.RemoveEmptyEntries).Select(p => p.Trim()));
        }

        foreach (string dir in searchDirs)
        {
            foreach (string exeName in exeNames)
            {
                try
                {
                    string candidate = Path.Combine(dir, exeName);
                    if (File.Exists(candidate))
                    {
                        return candidate;
                    }
                }
                catch { }
            }
        }

        return null;
    }

    private static async Task<(bool Installed, bool HasDistro, bool HasMinGW)> ProbeWslAsync()
    {
        string wslExe = Path.Combine(Environment.SystemDirectory, "wsl.exe");
        if (!File.Exists(wslExe))
        {
            return (false, false, false);
        }

        try
        {
            var listResult = await RunSimpleProcessAsync(wslExe, "-l -q", 3500);
            string cleaned = listResult.Stdout.Replace("\0", "").Trim();
            bool hasDistro = listResult.ExitCode == 0 && !string.IsNullOrWhiteSpace(cleaned);
            if (!hasDistro)
            {
                return (true, false, false);
            }

            var whichResult = await RunSimpleProcessAsync(wslExe, "-e which x86_64-w64-mingw32-g++", 4000);
            bool hasMingw = whichResult.ExitCode == 0 && !string.IsNullOrWhiteSpace(whichResult.Stdout);
            return (true, true, hasMingw);
        }
        catch
        {
            return (true, false, false);
        }
    }

    private static async Task DownloadPortableJdkAsync(Action<string> log, Action<double, string> progress, CancellationToken ct)
    {
        const string adoptiumUrl = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse";
        string zipPath = Path.Combine(ToolsRoot, "temurin-jdk21.zip");
        string extractDir = Path.Combine(ToolsRoot, "jdk");

        progress(5, "Downloading Eclipse Temurin JDK 21...");
        log($"Fetching JDK 21 from Adoptium ({adoptiumUrl})...");
        await DownloadFileWithProgressAsync(adoptiumUrl, zipPath, p =>
        {
            progress(5 + p * 30, $"Downloading JDK 21... {(int)(p * 100)}%");
        }, ct);

        progress(36, "Extracting JDK 21...");
        log("Extracting portable JDK 21...");
        if (Directory.Exists(extractDir))
        {
            Directory.Delete(extractDir, true);
        }
        Directory.CreateDirectory(extractDir);
        await Task.Run(() => ZipFile.ExtractToDirectory(zipPath, extractDir, true), ct);
        TryDeleteFile(zipPath);
        progress(40, "JDK 21 installed.");
    }

    private static async Task DownloadPortableMinGWAsync(Action<string> log, Action<double, string> progress, CancellationToken ct)
    {
        progress(42, "Resolving latest MinGW-w64 (WinLibs) release...");
        log("Querying GitHub API for latest WinLibs MinGW-w64 x86_64 release...");

        string? downloadUrl = null;
        string? assetName = null;

        using (var resp = await Http.GetAsync("https://api.github.com/repos/brechtsanders/winlibs_mingw/releases/latest", ct))
        {
            resp.EnsureSuccessStatusCode();
            string json = await resp.Content.ReadAsStringAsync(ct);
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.TryGetProperty("assets", out var assets))
            {
                string? fallbackUrl = null;
                string? fallbackName = null;
                foreach (var asset in assets.EnumerateArray())
                {
                    string name = asset.GetProperty("name").GetString() ?? "";
                    string url = asset.GetProperty("browser_download_url").GetString() ?? "";
                    if (!name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
                        continue;
                    if (!name.Contains("x86_64", StringComparison.OrdinalIgnoreCase))
                        continue;
                    if (!name.Contains("posix", StringComparison.OrdinalIgnoreCase))
                        continue;

                    // Prefer smaller non-LLVM UCRT build
                    if (!name.Contains("llvm", StringComparison.OrdinalIgnoreCase))
                    {
                        downloadUrl = url;
                        assetName = name;
                        break;
                    }
                    fallbackUrl ??= url;
                    fallbackName ??= name;
                }

                downloadUrl ??= fallbackUrl;
                assetName ??= fallbackName;
            }
        }

        if (string.IsNullOrEmpty(downloadUrl))
        {
            throw new InvalidOperationException("Could not find a Windows x86_64 .zip release from WinLibs MinGW-w64.");
        }

        string zipPath = Path.Combine(ToolsRoot, "mingw64.zip");
        string extractDir = Path.Combine(ToolsRoot, "mingw64");

        log($"Downloading {assetName}...");
        await DownloadFileWithProgressAsync(downloadUrl, zipPath, p =>
        {
            progress(45 + p * 40, $"Downloading MinGW-w64 g++... {(int)(p * 100)}%");
        }, ct);

        progress(86, "Extracting MinGW-w64 g++ toolchain...");
        log("Extracting MinGW-w64 g++ (this may take a moment)...");
        if (Directory.Exists(extractDir))
        {
            Directory.Delete(extractDir, true);
        }
        Directory.CreateDirectory(extractDir);
        await Task.Run(() => ZipFile.ExtractToDirectory(zipPath, extractDir, true), ct);
        TryDeleteFile(zipPath);
        progress(95, "MinGW-w64 g++ installed.");
        log("Portable MinGW-w64 g++ ready!");
    }

    private static async Task EnsureWslMinGWAsync(
        DependencyStatus status,
        Action<string> log,
        Action<double, string> progress,
        CancellationToken ct)
    {
        if (!status.WslHasDistro)
        {
            progress(45, "Installing WSL + Ubuntu (UAC prompt)...");
            log("WSL Linux distribution not found. Launching elevated 'wsl --install -d Ubuntu'...");
            var psi = new ProcessStartInfo("wsl.exe", "--install -d Ubuntu")
            {
                UseShellExecute = true,
                Verb = "runas"
            };
            using var proc = Process.Start(psi);
            if (proc != null)
            {
                await proc.WaitForExitAsync(ct);
            }
            var (inst, distro, mingw) = await ProbeWslAsync();
            status.WslInstalled = inst;
            status.WslHasDistro = distro;
            status.WslHasMinGW = mingw;

            if (!status.WslHasDistro)
            {
                throw new InvalidOperationException(
                    "WSL installation was initiated. If Windows requested a reboot or Ubuntu user setup, complete it and re-run Build (or switch Toolchain to 'Auto' / 'Portable MinGW-w64' to build immediately without rebooting).");
            }
        }

        if (!status.WslHasMinGW)
        {
            progress(60, "Installing g++-mingw-w64-x86-64 inside WSL...");
            log("Running inside WSL: apt-get update && apt-get install -y g++-mingw-w64-x86-64");
            int exitCode = await RunStreamingProcessAsync(
                "wsl.exe",
                "-u root bash -c \"DEBIAN_FRONTEND=noninteractive apt-get update && apt-get install -y g++-mingw-w64-x86-64\"",
                null,
                log,
                ct);
            if (exitCode != 0)
            {
                throw new InvalidOperationException($"Failed to install g++-mingw-w64-x86-64 in WSL (exit code {exitCode}).");
            }
            var (inst, distro, mingw) = await ProbeWslAsync();
            status.WslInstalled = inst;
            status.WslHasDistro = distro;
            status.WslHasMinGW = mingw;
            log("WSL g++-mingw-w64-x86-64 installed successfully!");
        }
    }

    private static async Task DownloadFileWithProgressAsync(string url, string destPath, Action<double> onProgress, CancellationToken ct)
    {
        using var resp = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
        resp.EnsureSuccessStatusCode();
        long? totalBytes = resp.Content.Headers.ContentLength;

        await using var input = await resp.Content.ReadAsStreamAsync(ct);
        await using var output = new FileStream(destPath, FileMode.Create, FileAccess.Write, FileShare.None, 81920, true);

        byte[] buffer = new byte[81920];
        long readTotal = 0;
        int read;
        DateTime lastReport = DateTime.MinValue;
        while ((read = await input.ReadAsync(buffer, ct)) > 0)
        {
            await output.WriteAsync(buffer.AsMemory(0, read), ct);
            readTotal += read;
            if (totalBytes.HasValue && totalBytes.Value > 0 && (DateTime.UtcNow - lastReport).TotalMilliseconds > 150)
            {
                onProgress((double)readTotal / totalBytes.Value);
                lastReport = DateTime.UtcNow;
            }
        }
        onProgress(1.0);
    }

    private static async Task<(int ExitCode, string Stdout)> RunSimpleProcessAsync(string fileName, string args, int timeoutMs)
    {
        var psi = new ProcessStartInfo(fileName, args)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        using var proc = Process.Start(psi);
        if (proc == null) return (-1, "");
        var stdoutTask = proc.StandardOutput.ReadToEndAsync();
        using var cts = new CancellationTokenSource(timeoutMs);
        try
        {
            await proc.WaitForExitAsync(cts.Token);
            return (proc.ExitCode, await stdoutTask);
        }
        catch
        {
            try { proc.Kill(true); } catch { }
            return (-1, "");
        }
    }

    public static async Task<int> RunStreamingProcessAsync(
        string fileName,
        string arguments,
        string? workingDir,
        Action<string> log,
        CancellationToken ct,
        IDictionary<string, string>? extraEnv = null)
    {
        var psi = new ProcessStartInfo(fileName, arguments)
        {
            WorkingDirectory = string.IsNullOrEmpty(workingDir) ? Environment.CurrentDirectory : workingDir,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        if (extraEnv != null)
        {
            foreach (var kv in extraEnv)
            {
                psi.Environment[kv.Key] = kv.Value;
            }
        }

        using var proc = new Process { StartInfo = psi };
        proc.OutputDataReceived += (_, e) => { if (!string.IsNullOrEmpty(e.Data)) log(e.Data); };
        proc.ErrorDataReceived += (_, e) => { if (!string.IsNullOrEmpty(e.Data)) log(e.Data); };

        proc.Start();
        proc.BeginOutputReadLine();
        proc.BeginErrorReadLine();

        try
        {
            await proc.WaitForExitAsync(ct);
        }
        catch (OperationCanceledException)
        {
            try { proc.Kill(true); } catch { }
            throw;
        }

        return proc.ExitCode;
    }

    private static void TryDeleteFile(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }
}
