using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace SendEasy.App;

/// <summary>מחזיר את הקבצים והתיקיות שנבחרו כרגע בסייר הקבצים (או בשולחן העבודה).</summary>
static class ExplorerSelection
{
    public static List<string> GetSelectedPaths()
    {
        var fg = GetForegroundWindow();
        var list = new List<string>();
        try
        {
            dynamic shell = Activator.CreateInstance(Type.GetTypeFromProgID("Shell.Application")!)!;
            foreach (dynamic w in shell.Windows())
            {
                try
                {
                    if ((long)w.HWND != fg.ToInt64()) continue;
                    foreach (dynamic item in w.Document.SelectedItems())
                    {
                        string? p = item.Path;
                        if (!string.IsNullOrEmpty(p)) list.Add(p);
                    }
                }
                catch { }
            }
        }
        catch { }
        if (list.Count > 0) return list.Distinct().ToList();

        // שולחן העבודה לא מופיע ברשימת חלונות הסייר: מעתיקים את הבחירה ללוח וקוראים משם
        var cls = new StringBuilder(64);
        GetClassName(fg, cls, cls.Capacity);
        if (cls.ToString() is "Progman" or "WorkerW")
        {
            try
            {
                Clipboard.Clear();
                SendKeys.SendWait("^c");
                Thread.Sleep(250);
                if (Clipboard.ContainsFileDropList())
                    list.AddRange(Clipboard.GetFileDropList().Cast<string>());
            }
            catch { }
        }
        return list;
    }

    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
}
