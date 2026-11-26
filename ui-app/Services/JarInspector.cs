using System.IO;
using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;

namespace JarToDllBuilder.Services;

public sealed record JarInspectionResult(
    int ClassCount,
    int MaxClassMajorVersion,
    string? DetectedMinecraftVersion,
    string? ModId,
    long FileSizeBytes);

public static class JarInspector
{
    public static JarInspectionResult Inspect(string jarPath)
    {
        var fileInfo = new FileInfo(jarPath);
        int classCount = 0;
        int maxMajor = 0;
        string? detectedMc = null;
        string? modId = null;

        using var archive = ZipFile.OpenRead(jarPath);
        foreach (var entry in archive.Entries)
        {
            if (entry.FullName.EndsWith(".class", StringComparison.OrdinalIgnoreCase)
                && !entry.FullName.EndsWith("module-info.class", StringComparison.OrdinalIgnoreCase)
                && !entry.FullName.StartsWith("META-INF/versions/", StringComparison.OrdinalIgnoreCase))
            {
                classCount++;
                try
                {
                    using var stream = entry.Open();
                    byte[] header = new byte[8];
                    int read = stream.Read(header, 0, 8);
                    if (read == 8 && header[0] == 0xCA && header[1] == 0xFE && header[2] == 0xBA && header[3] == 0xBE)
                    {
                        int major = (header[6] << 8) | header[7];
                        if (major > maxMajor)
                        {
                            maxMajor = major;
                        }
                    }
                }
                catch
                {
                    // Ignore unreadable entry headers during inspection
                }
            }
            else if (entry.FullName.Equals("META-INF/mods.toml", StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    using var reader = new StreamReader(entry.Open(), Encoding.UTF8);
                    string toml = reader.ReadToEnd();
                    if (toml.Contains("1.16.5") || toml.Contains("[1.16") || toml.Contains("loaderVersion=\"[36"))
                    {
                        detectedMc = "1.16.5";
                    }
                    else if (toml.Contains("1.21.4") || toml.Contains("[1.21") || toml.Contains("loaderVersion=\"[54") || toml.Contains("loaderVersion=\"[52"))
                    {
                        detectedMc = "1.21.4";
                    }

                    var idMatch = Regex.Match(toml, @"modId\s*=\s*""([^""]+)""");
                    if (idMatch.Success)
                    {
                        modId = idMatch.Groups[1].Value;
                    }
                }
                catch
                {
                }
            }
        }

        // Fallback version heuristic from classfile major version
        if (detectedMc == null && maxMajor > 0)
        {
            if (maxMajor <= 52)
            {
                detectedMc = "1.16.5";
            }
            else if (maxMajor >= 61)
            {
                detectedMc = "1.21.4";
            }
        }

        return new JarInspectionResult(classCount, maxMajor, detectedMc, modId, fileInfo.Length);
    }

    public static string FormatJavaVersion(int majorVersion) => majorVersion switch
    {
        <= 0 => "Unknown",
        52 => "Java 8",
        53 => "Java 9",
        55 => "Java 11",
        60 => "Java 16",
        61 => "Java 17",
        65 => "Java 21",
        _ => $"Class v{majorVersion}"
    };
}
