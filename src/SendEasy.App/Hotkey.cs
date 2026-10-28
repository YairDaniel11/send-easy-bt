using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace SendEasy.App;

/// <summary>קיצור מקשים כטקסט (למשל "Ctrl+Alt+B") והמרה למודיפיירים ומקש.</summary>
static class HotkeySpec
{
    public const uint Alt = 1, Ctrl = 2, Shift = 4, Win = 8;

    public static bool TryParse(string text, out uint mods, out Keys key)
    {
        mods = 0; key = Keys.None;
        foreach (var part in text.Split('+', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            switch (part.ToLowerInvariant())
            {
                case "ctrl": mods |= Ctrl; break;
                case "alt": mods |= Alt; break;
                case "shift": mods |= Shift; break;
                case "win": mods |= Win; break;
                default:
                    if (!Enum.TryParse(part, true, out key)) return false;
                    break;
            }
        }
        return key != Keys.None && mods != 0;
    }

    public static string Format(uint mods, Keys key)
    {
        var parts = new List<string>();
        if ((mods & Ctrl) != 0) parts.Add("Ctrl");
        if ((mods & Alt) != 0) parts.Add("Alt");
        if ((mods & Shift) != 0) parts.Add("Shift");
        if ((mods & Win) != 0) parts.Add("Win");
        parts.Add(key.ToString());
        return string.Join("+", parts);
    }
}

/// <summary>רושם כמה קיצורי מקשים גלובליים ומודיע איזה מהם נלחץ (לפי מזהה).</summary>
sealed class HotkeyManager : NativeWindow, IDisposable
{
    const int WM_HOTKEY = 0x0312;
    const uint NoRepeat = 0x4000;
    readonly HashSet<int> _ids = new();

    public event Action<int>? Pressed;

    public HotkeyManager() => CreateHandle(new CreateParams());

    /// <summary>רושם קיצור. מחזיר false אם הוא תפוס על ידי תוכנה אחרת או לא תקין.</summary>
    public bool Register(int id, string spec)
    {
        if (!HotkeySpec.TryParse(spec, out var mods, out var key)) return false;
        if (!RegisterHotKey(Handle, id, mods | NoRepeat, (uint)key)) return false;
        _ids.Add(id);
        return true;
    }

    public void UnregisterAll()
    {
        foreach (var id in _ids) UnregisterHotKey(Handle, id);
        _ids.Clear();
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WM_HOTKEY) Pressed?.Invoke(m.WParam.ToInt32());
        else base.WndProc(ref m);
    }

    public void Dispose() { UnregisterAll(); DestroyHandle(); }

    [DllImport("user32.dll", SetLastError = true)] static extern bool RegisterHotKey(IntPtr hWnd, int id, uint mods, uint vk);
    [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr hWnd, int id);
}
