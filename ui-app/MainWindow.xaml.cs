using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using JarToDllBuilder.Services;
using Microsoft.Win32;

namespace JarToDllBuilder;

public partial class MainWindow : Window
{
    private readonly DependencyManager _depManager = new();
    private readonly DllBuilderService _builderService;
    private readonly List<string> _extraLibJars = new();

    private string? _selectedJarPath;
    private string? _lastBuiltDllPath;
    private bool _isBusy;
    private CancellationTokenSource? _buildCts;

    public MainWindow()
    {
        InitializeComponent();
        _builderService = new DllBuilderService(_depManager);

        TxtOutputDir.Text = Environment.CurrentDirectory;
        UpdateOutputPreview();
        Loaded += MainWindow_Loaded;
    }

    private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        AppendLog("Supreme Jar To Dll initialized.");
        await RefreshDependencyStatusAsync();
    }

    private ToolchainPreference GetSelectedToolchain()
    {
        if (CmbToolchain.SelectedItem is ComboBoxItem item && item.Tag is string tag)
        {
            return tag switch
            {
                "PortableMinGW" => ToolchainPreference.PortableMinGW,
                "Msvc" => ToolchainPreference.Msvc,
                "WslMinGW" => ToolchainPreference.WslMinGW,
                _ => ToolchainPreference.Auto
            };
        }
        return ToolchainPreference.Auto;
    }

    private string GetSelectedMcVersion() => RbVersion1165.IsChecked == true ? "1.16.5" : "1.21.4";

    private async Task RefreshDependencyStatusAsync()
    {
        var pref = GetSelectedToolchain();
        var status = await _depManager.DetectAsync(pref);

        Dispatcher.Invoke(() =>
        {
            TxtJdkStatus.Text = $"JDK: {status.JdkDisplay}";
            DotJdk.Fill = status.HasJdk
                ? (Brush)FindResource("StatusOk")
                : (Brush)FindResource("StatusWarn");

            TxtCppStatus.Text = $"Compiler: {status.CppDisplay}";
            DotCpp.Fill = status.HasCpp
                ? (Brush)FindResource("StatusOk")
                : (Brush)FindResource("StatusWarn");
        });
    }

    private void Header_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton == MouseButton.Left)
        {
            DragMove();
        }
    }

    private void BtnMinimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void BtnClose_Click(object sender, RoutedEventArgs e)
    {
        _buildCts?.Cancel();
        Close();
    }

    private void Window_DragOver(object sender, DragEventArgs e)
    {
        if (e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            var files = (string[]?)e.Data.GetData(DataFormats.FileDrop);
            if (files != null && files.Any(f => f.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)))
            {
                e.Effects = DragDropEffects.Copy;
                e.Handled = true;
                return;
            }
        }
        e.Effects = DragDropEffects.None;
        e.Handled = true;
    }

    private void Window_Drop(object sender, DragEventArgs e)
    {
        if (_isBusy) return;
        if (e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            var files = (string[]?)e.Data.GetData(DataFormats.FileDrop);
            string? jar = files?.FirstOrDefault(f => f.EndsWith(".jar", StringComparison.OrdinalIgnoreCase));
            if (!string.IsNullOrEmpty(jar))
            {
                LoadUploadedJar(jar);
            }
        }
    }

    private void BtnUpload_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Upload Minecraft Forge Mod (.jar)",
            Filter = "Java Archive (*.jar)|*.jar|All Files (*.*)|*.*",
            CheckFileExists = true
        };
        if (dlg.ShowDialog(this) == true)
        {
            LoadUploadedJar(dlg.FileName);
        }
    }

    private void LoadUploadedJar(string jarPath)
    {
        _selectedJarPath = jarPath;
        TxtSelectedJar.Text = Path.GetFileName(jarPath) + "  (" + Path.GetDirectoryName(jarPath) + ")";

        // Default output directory to the JAR's folder if user hasn't customized it away from cwd
        if (string.IsNullOrWhiteSpace(TxtOutputDir.Text))
        {
            TxtOutputDir.Text = Path.GetDirectoryName(jarPath) ?? Environment.CurrentDirectory;
        }

        // Suggest output name matching the uploaded JAR if still default "output"
        if (string.IsNullOrWhiteSpace(TxtOutputName.Text) || TxtOutputName.Text.Equals("output", StringComparison.OrdinalIgnoreCase))
        {
            TxtOutputName.Text = Path.GetFileNameWithoutExtension(jarPath);
        }

        try
        {
            var info = JarInspector.Inspect(jarPath);
            string javaLabel = JarInspector.FormatJavaVersion(info.MaxClassMajorVersion);
            string modTag = string.IsNullOrEmpty(info.ModId) ? "" : $" • ModID: {info.ModId}";
            string verHint = string.IsNullOrEmpty(info.DetectedMinecraftVersion) ? "" : $" • Detected: MC {info.DetectedMinecraftVersion}";

            TxtJarMeta.Text = $"{info.FileSizeBytes / 1024.0:F1} KB • {info.ClassCount} classes • {javaLabel}{modTag}{verHint}";
            AppendLog($"Uploaded JAR: {jarPath} ({info.ClassCount} classes, {javaLabel})");

            if (info.DetectedMinecraftVersion == "1.16.5")
            {
                RbVersion1165.IsChecked = true;
                AppendLog("Auto-selected target: Minecraft 1.16.5 (Forge) based on JAR metadata/bytecode.");
            }
            else if (info.DetectedMinecraftVersion == "1.21.4")
            {
                RbVersion1214.IsChecked = true;
                AppendLog("Auto-selected target: Minecraft 1.21.4 (Forge) based on JAR metadata/bytecode.");
            }
        }
        catch (Exception ex)
        {
            TxtJarMeta.Text = "JAR uploaded (could not inspect archive metadata)";
            AppendLog($"Warning inspecting JAR: {ex.Message}");
        }

        UpdateOutputPreview();
    }

    private void BtnAddLibs_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Select Extra Library JARs to Bundle",
            Filter = "Java Archive (*.jar)|*.jar",
            Multiselect = true,
            CheckFileExists = true
        };
        if (dlg.ShowDialog(this) == true)
        {
            foreach (string file in dlg.FileNames)
            {
                if (!_extraLibJars.Contains(file, StringComparer.OrdinalIgnoreCase))
                {
                    _extraLibJars.Add(file);
                }
            }
            UpdateExtraLibsDisplay();
        }
    }

    private void BtnClearLibs_Click(object sender, RoutedEventArgs e)
    {
        _extraLibJars.Clear();
        UpdateExtraLibsDisplay();
    }

    private void UpdateExtraLibsDisplay()
    {
        if (_extraLibJars.Count == 0)
        {
            TxtExtraLibs.Text = "Extra dependency library JARs: None (1.16.5/1.21.4 shaded libs auto-handled)";
            BtnClearLibs.Visibility = Visibility.Collapsed;
        }
        else
        {
            TxtExtraLibs.Text = $"Bundled library JARs ({_extraLibJars.Count}): " +
                                string.Join(", ", _extraLibJars.Select(Path.GetFileName));
            BtnClearLibs.Visibility = Visibility.Visible;
        }
    }

    private void BtnBrowseOut_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFolderDialog
        {
            Title = "Select Output Destination Folder",
            InitialDirectory = Directory.Exists(TxtOutputDir.Text) ? TxtOutputDir.Text : Environment.CurrentDirectory
        };
        if (dlg.ShowDialog(this) == true)
        {
            TxtOutputDir.Text = dlg.FolderName;
            UpdateOutputPreview();
        }
    }

    private void OutputFields_TextChanged(object sender, TextChangedEventArgs e) => UpdateOutputPreview();

    private void UpdateOutputPreview()
    {
        if (TxtOutputPreview == null) return;
        string dir = string.IsNullOrWhiteSpace(TxtOutputDir.Text) ? Environment.CurrentDirectory : TxtOutputDir.Text.Trim();
        string name = string.IsNullOrWhiteSpace(TxtOutputName.Text) ? "output" : TxtOutputName.Text.Trim();
        if (!name.EndsWith(".dll", StringComparison.OrdinalIgnoreCase))
        {
            name += ".dll";
        }
        TxtOutputPreview.Text = $"Final output: {Path.Combine(dir, name)}";
    }

    private async void CmbToolchain_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!IsLoaded) return;
        await RefreshDependencyStatusAsync();
    }

    private void VersionRadio_Checked(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        string ver = GetSelectedMcVersion();
        AppendLog($"Target version switched to Minecraft Forge {ver}.");
    }

    private async void BtnInstallDeps_Click(object sender, RoutedEventArgs e)
    {
        if (_isBusy) return;
        SetBusy(true);
        _buildCts = new CancellationTokenSource();
        try
        {
            var pref = GetSelectedToolchain();
            AppendLog($"Checking and installing build dependencies (Toolchain: {pref})...");
            await _depManager.EnsureDependenciesAsync(pref, AppendLog, UpdateProgress, _buildCts.Token);
            UpdateProgress(100, "All build dependencies are ready!");
            await RefreshDependencyStatusAsync();
        }
        catch (Exception ex)
        {
            AppendLog($"ERROR: {ex.Message}");
            UpdateProgress(0, "Dependency setup failed.");
            MessageBox.Show(this, ex.Message, "Dependency Error", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private async void BtnBuild_Click(object sender, RoutedEventArgs e)
    {
        if (_isBusy) return;

        if (string.IsNullOrWhiteSpace(_selectedJarPath) || !File.Exists(_selectedJarPath))
        {
            MessageBox.Show(this, "Please upload a .jar file first.", "No JAR Uploaded", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        string outDir = TxtOutputDir.Text.Trim();
        if (string.IsNullOrWhiteSpace(outDir))
        {
            MessageBox.Show(this, "Please choose an output destination folder.", "Missing Output Folder", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        string outName = TxtOutputName.Text.Trim();
        if (string.IsNullOrWhiteSpace(outName) || outName.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
        {
            MessageBox.Show(this, "Please enter a valid output DLL filename.", "Invalid Output Name", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        SetBusy(true);
        _lastBuiltDllPath = null;
        BtnOpenOutput.IsEnabled = false;
        _buildCts = new CancellationTokenSource();

        try
        {
            var req = new BuildRequest(
                _selectedJarPath,
                outDir,
                outName,
                GetSelectedMcVersion(),
                GetSelectedToolchain(),
                _extraLibJars.ToList());

            string builtPath = await _builderService.BuildAsync(req, AppendLog, UpdateProgress, _buildCts.Token);
            _lastBuiltDllPath = builtPath;
            BtnOpenOutput.IsEnabled = true;
            await RefreshDependencyStatusAsync();
        }
        catch (Exception ex)
        {
            AppendLog($"BUILD FAILED: {ex.Message}");
            UpdateProgress(0, "Build failed. See console log.");
            MessageBox.Show(this, ex.Message, "Build Failed", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void BtnOpenOutput_Click(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(_lastBuiltDllPath) && File.Exists(_lastBuiltDllPath))
        {
            Process.Start("explorer.exe", $"/select,\"{_lastBuiltDllPath}\"");
        }
        else if (Directory.Exists(TxtOutputDir.Text))
        {
            Process.Start("explorer.exe", $"\"{TxtOutputDir.Text}\"");
        }
    }

    private void BtnCopyLog_Click(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(TxtConsole.Text))
        {
            Clipboard.SetText(TxtConsole.Text);
        }
    }

    private void BtnClearLog_Click(object sender, RoutedEventArgs e) => TxtConsole.Clear();

    private void SetBusy(bool busy)
    {
        _isBusy = busy;
        BtnBuild.IsEnabled = !busy;
        BtnUpload.IsEnabled = !busy;
        BtnInstallDeps.IsEnabled = !busy;
        BtnBrowseOut.IsEnabled = !busy;
        BtnAddLibs.IsEnabled = !busy;
        BtnClearLibs.IsEnabled = !busy;
        RbVersion1165.IsEnabled = !busy;
        RbVersion1214.IsEnabled = !busy;
        CmbToolchain.IsEnabled = !busy;
        BtnBuild.Content = busy ? "BUILDING..." : "BUILD DLL";
    }

    private void UpdateProgress(double percent, string stage)
    {
        Dispatcher.Invoke(() =>
        {
            double clamped = Math.Clamp(percent, 0, 100);
            BuildProgressBar.Value = clamped;
            TxtProgressStage.Text = stage;
            TxtProgressPct.Text = $"{(int)clamped}%";
        });
    }

    private void AppendLog(string message)
    {
        Dispatcher.Invoke(() =>
        {
            string line = $"[{DateTime.Now:HH:mm:ss}] {message}{Environment.NewLine}";
            TxtConsole.AppendText(line);
            TxtConsole.ScrollToEnd();
        });
    }
}
