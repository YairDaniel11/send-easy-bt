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
        if (cls.ToString() is "Progman" or "WorkerW" || fg == GetShellWindow())
        {
            try
            {
                // הקיצור עדיין לחוץ (למשל Ctrl+Alt+S): אם נשלח ^c עכשיו הוא יתפרש כ-Ctrl+Alt+C ולא יעתיק כלום
                WaitModifiersReleased(TimeSpan.FromSeconds(2));
                Clipboard.Clear();
                SendKeys.SendWait("^c");
                for (int i = 0; i < 12 && list.Count == 0; i++)
                {
                    Thread.Sleep(100);
                    if (Clipboard.ContainsFileDropList())
                        list.AddRange(Clipboard.GetFileDropList().Cast<string>());
                }
            }
            catch { }
        }
        return list;
    }

    static void WaitModifiersReleased(TimeSpan max)
    {
        var end = DateTime.UtcNow + max;
        // Shift, Ctrl, Alt, Win שמאל וימין
        int[] keys = { 0x10, 0x11, 0x12, 0x5B, 0x5C };
        while (DateTime.UtcNow < end && keys.Any(k => (GetAsyncKeyState(k) & 0x8000) != 0)) Thread.Sleep(20);
    }

    [DllImport("user32.dll")] static extern short GetAsyncKeyState(int vKey);
    [DllImport("user32.dll")] static extern IntPtr GetShellWindow();
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
}
