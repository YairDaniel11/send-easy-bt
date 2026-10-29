using System;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Threading;
using System.Windows;
using Wpf.Ui.Appearance;
using Wpf.Ui.Markup;

namespace SendEasy.App;

static class Program
{
    const string PipeName = "SendEasyBT.pipe";

    [STAThread]
    static void Main(string[] args)
    {
        if (args.Contains("-Embedding")) { ShellServer.Run(); return; } // מופעל על ידי Windows לפריט בתפריט הקליק הימני

        var listIdx = Array.IndexOf(args, "--list");
        var listFile = listIdx >= 0 && listIdx + 1 < args.Length ? args[listIdx + 1] : null;
        var paths = args.Where(a => !a.StartsWith("--") && a != listFile && (File.Exists(a) || Directory.Exists(a))).ToList();
        if (listIdx >= 0 && listIdx + 1 < args.Length && File.Exists(args[listIdx + 1]))
        {
            paths.AddRange(File.ReadAllLines(args[listIdx + 1]).Where(p => File.Exists(p) || Directory.Exists(p)));
            try { File.Delete(args[listIdx + 1]); } catch { }
        }

        if (args.Contains("--install") || args.Contains("--uninstall"))
        {
            bool install = args.Contains("--install");
            try
            {
                if (install) Installer.Install(modern: args.Contains("--modern")); else Installer.Uninstall();
                if (!args.Contains("--quiet"))
                    MessageBox.Show(install
                        ? "התוכנה הותקנה. מעכשיו יופיע בקליק הימני על כל קובץ ותיקייה: \"שליחה קלה - BT\".\nב-Windows 11: תחת \"הצג אפשרויות נוספות\"."
                        : "התוכנה הוסרה.",
                        Installer.AppName, MessageBoxButton.OK, MessageBoxImage.Information,
                        MessageBoxResult.OK, MessageBoxOptions.RtlReading | MessageBoxOptions.RightAlign);
                if (install) System.Diagnostics.Process.Start(Installer.InstalledExe, "--background");
            }
            catch (Exception ex)
            {
                MessageBox.Show("הפעולה נכשלה: " + ex.Message, Installer.AppName, MessageBoxButton.OK, MessageBoxImage.Error);
            }
            return;
        }

        using var mutex = new Mutex(true, "SendEasyBT.SingleInstance", out bool first);
        if (!first)
        {
            // כבר רץ: מעבירים לו את הנתיבים ויוצאים
            try
            {
                using var c = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
                c.Connect(3000);
                using var w = new StreamWriter(c) { AutoFlush = true };
                foreach (var p in paths) w.WriteLine(p);
            }
            catch { }
            return;
        }

        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        app.Resources.MergedDictionaries.Add(new ThemesDictionary { Theme = ApplicationTheme.Light });
        app.Resources.MergedDictionaries.Add(new ControlsDictionary());
        ApplicationThemeManager.ApplySystemTheme();

        using var tray = new TrayApp();
        tray.ListenForPaths(PipeName);
        if (paths.Count > 0 || !args.Contains("--background")) tray.OpenSend(paths);
        if (args.Contains("--chat")) tray.OpenChat();
        if (args.Contains("--history")) tray.OpenHistory();
        if (args.Contains("--settings")) _ = app.Dispatcher.BeginInvoke(tray.ShowSettings);
        if (args.Contains("--about")) _ = app.Dispatcher.BeginInvoke(tray.ShowAbout);
        app.Run();
    }
}
