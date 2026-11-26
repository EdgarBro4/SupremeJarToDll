using System.IO;
using System.Windows;
using JarToDllBuilder.Services;

namespace JarToDllBuilder;

public partial class App : Application
{
    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        if (e.Args.Length > 0 && e.Args[0].Equals("--cli", StringComparison.OrdinalIgnoreCase))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            int code = await RunCliAsync(e.Args);
            Shutdown(code);
            return;
        }

        var window = new MainWindow();
        MainWindow = window;
        window.Show();
    }

    private static async Task<int> RunCliAsync(string[] args)
    {
        string? jar = null;
        string outDir = Environment.CurrentDirectory;
        string outName = "output";
        string mcVer = "1.21.4";
        string? logFile = null;
        var libs = new List<string>();

        for (int i = 1; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--jar" when i + 1 < args.Length: jar = args[++i]; break;
                case "--out" when i + 1 < args.Length: outDir = args[++i]; break;
                case "--name" when i + 1 < args.Length: outName = args[++i]; break;
                case "--mc" when i + 1 < args.Length: mcVer = args[++i]; break;
                case "--lib" when i + 1 < args.Length: libs.Add(args[++i]); break;
                case "--log" when i + 1 < args.Length: logFile = args[++i]; break;
            }
        }

        var logLines = new List<string>();
        void Log(string msg)
        {
            logLines.Add(msg);
            Console.WriteLine(msg);
        }

        try
        {
            if (string.IsNullOrEmpty(jar) || !File.Exists(jar))
            {
                Log("ERROR: Missing or nonexistent --jar argument.");
                return 2;
            }

            var info = JarInspector.Inspect(jar);
            Log($"Inspected JAR: classes={info.ClassCount}, maxMajor={info.MaxClassMajorVersion}, detectedMc={info.DetectedMinecraftVersion}, modId={info.ModId}");

            var deps = new DependencyManager();
            var builder = new DllBuilderService(deps);
            var req = new BuildRequest(jar, outDir, outName, mcVer, ToolchainPreference.Auto, libs);
            string built = await builder.BuildAsync(req, Log, (_, _) => { }, CancellationToken.None);
            Log($"SUCCESS: {built}");
            return 0;
        }
        catch (Exception ex)
        {
            Log($"FAILED: {ex}");
            return 1;
        }
        finally
        {
            if (!string.IsNullOrEmpty(logFile))
            {
                try { File.WriteAllLines(logFile, logLines); } catch { }
            }
        }
    }
}
