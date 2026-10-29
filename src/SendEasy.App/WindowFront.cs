using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace SendEasy.App;

/// <summary>מביא חלון לחזית גם כשהתהליך אינו בחזית (למשל אחרי קליק ימני בסייר). Windows חוסם גניבת פוקוס, ולכן משתמשים בהשוואת תורי קלט.</summary>
static class WindowFront
{
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a, uint b, bool attach);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();

    public static void Bring(Window w)
    {
        try
        {
            if (w.WindowState == WindowState.Minimized) w.WindowState = WindowState.Normal;
            if (!w.IsVisible) w.Show();
            w.Activate();
            var h = new WindowInteropHelper(w).Handle;
            var fg = GetForegroundWindow();
            uint fgThread = fg == IntPtr.Zero ? 0 : GetWindowThreadProcessId(fg, out _);
            uint me = GetCurrentThreadId();
            bool attached = fgThread != 0 && fgThread != me && AttachThreadInput(me, fgThread, true);
            SetForegroundWindow(h);
            w.Topmost = true; w.Topmost = false;
            w.Focus();
            if (attached) AttachThreadInput(me, fgThread, false);
        }
        catch { }
    }
}