using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;

namespace SendEasy.App;

// ממשקי COM של סייר הקבצים לפריט בתפריט ההקשר החדש של Windows 11.

[ComImport, Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IShellItem
{
    void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
    void GetParent(out IShellItem ppsi);
    [PreserveSig] int GetDisplayName(uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string ppszName);
    void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
    void Compare(IShellItem psi, uint hint, out int piOrder);
}

[ComImport, Guid("b63ea76d-1f85-456f-a19c-48159efa858b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IShellItemArray
{
    void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppvOut);
    void GetPropertyStore(int flags, ref Guid riid, out IntPtr ppv);
    void GetPropertyDescriptionList(IntPtr keyType, ref Guid riid, out IntPtr ppv);
    void GetAttributes(int attribFlags, uint sfgaoMask, out uint psfgaoAttribs);
    void GetCount(out uint pdwNumItems);
    void GetItemAt(uint dwIndex, out IShellItem ppsi);
    void EnumItems(out IntPtr ppenumShellItems);
}

[ComImport, Guid("a88826f8-186f-4987-aade-ea0cef8fbfe8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IEnumExplorerCommand
{
    [PreserveSig] int Next(uint celt, [MarshalAs(UnmanagedType.Interface)] out IExplorerCommand? pUICommand, out uint pceltFetched);
    [PreserveSig] int Skip(uint celt);
    [PreserveSig] int Reset();
    [PreserveSig] int Clone(out IEnumExplorerCommand ppenum);
}

[ComImport, Guid("a08ce4d0-fa25-44ab-b57c-c7b1c323e0b9"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IExplorerCommand
{
    [PreserveSig] int GetTitle(IShellItemArray? psiItemArray, [MarshalAs(UnmanagedType.LPWStr)] out string? ppszName);
    [PreserveSig] int GetIcon(IShellItemArray? psiItemArray, [MarshalAs(UnmanagedType.LPWStr)] out string? ppszIcon);
    [PreserveSig] int GetToolTip(IShellItemArray? psiItemArray, [MarshalAs(UnmanagedType.LPWStr)] out string? ppszInfotip);
    [PreserveSig] int GetCanonicalName(out Guid pguidCommandName);
    [PreserveSig] int GetState(IShellItemArray? psiItemArray, [MarshalAs(UnmanagedType.Bool)] bool fOkToBeSlow, out uint pCmdState);
    [PreserveSig] int Invoke(IShellItemArray? psiItemArray, IntPtr pbc);
    [PreserveSig] int GetFlags(out uint pFlags);
    [PreserveSig] int EnumSubCommands(out IEnumExplorerCommand? ppEnum);
}

[ComImport, Guid("00000001-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IClassFactory
{
    [PreserveSig] int CreateInstance(IntPtr pUnkOuter, ref Guid riid, out IntPtr ppvObject);
    [PreserveSig] int LockServer([MarshalAs(UnmanagedType.Bool)] bool fLock);
}

/// <summary>הפקודה עצמה: "שליחה קלה - BT" בתפריט הקליק הימני. רצה בתהליך נפרד, ולא בתוך הסייר.</summary>
[ComVisible(true), ClassInterface(ClassInterfaceType.None)]
sealed class SendExplorerCommand : IExplorerCommand
{
    public const string ClsidText = "6E3C5B6A-9A7F-4C0B-8F1E-5D2B7A41C9E3";
    const int S_OK = 0, E_NOTIMPL = unchecked((int)0x80004001);

    public int GetTitle(IShellItemArray? items, out string? name) { name = "שליחה קלה - BT (בלוטוס)"; return S_OK; }

    public int GetIcon(IShellItemArray? items, out string? icon)
    {
        icon = Environment.ProcessPath + ",0";
        return S_OK;
    }

    public int GetToolTip(IShellItemArray? items, out string? tip) { tip = null; return E_NOTIMPL; }
    public int GetCanonicalName(out Guid g) { g = new Guid(ClsidText); return S_OK; }
    public int GetState(IShellItemArray? items, bool slow, out uint state) { state = 0; return S_OK; } // ECS_ENABLED
    public int GetFlags(out uint flags) { flags = 0; return S_OK; }
    public int EnumSubCommands(out IEnumExplorerCommand? e) { e = null; return E_NOTIMPL; }

    public int Invoke(IShellItemArray? items, IntPtr pbc)
    {
        try
        {
            var paths = new List<string>();
            if (items != null)
            {
                items.GetCount(out uint n);
                for (uint i = 0; i < n; i++)
                {
                    items.GetItemAt(i, out var it);
                    if (it.GetDisplayName(0x80058000 /* SIGDN_FILESYSPATH */, out var p) == 0 && !string.IsNullOrEmpty(p))
                        paths.Add(p);
                }
            }
            if (paths.Count > 0) ShellServer.LaunchApp(paths);
        }
        catch { }
        ShellServer.ScheduleExit();
        return S_OK;
    }
}

[ComVisible(true), ClassInterface(ClassInterfaceType.None)]
sealed class ShellClassFactory : IClassFactory
{
    public int CreateInstance(IntPtr pUnkOuter, ref Guid riid, out IntPtr ppv)
    {
        ppv = IntPtr.Zero;
        if (pUnkOuter != IntPtr.Zero) return unchecked((int)0x80040110); // CLASS_E_NOAGGREGATION
        var obj = new SendExplorerCommand();
        var unk = Marshal.GetIUnknownForObject(obj);
        try { return Marshal.QueryInterface(unk, in riid, out ppv); }
        finally { Marshal.Release(unk); }
    }

    public int LockServer(bool fLock) => 0;
}

/// <summary>מצב "-Embedding": התוכנה מופעלת על ידי Windows רק כדי לשרת את התפריט, ויוצאת אחרי רגע.</summary>
static class ShellServer
{
    static readonly ManualResetEventSlim Done = new();
    static Timer? _idle;

    [DllImport("ole32.dll")] static extern int CoRegisterClassObject(ref Guid rclsid, IntPtr pUnk, uint dwClsContext, uint flags, out uint lpdwRegister);
    [DllImport("ole32.dll")] static extern int CoRevokeClassObject(uint reg);
    [DllImport("ole32.dll")] static extern int CoInitializeEx(IntPtr reserved, uint coInit);

    public static void Run()
    {
        CoInitializeEx(IntPtr.Zero, 0x0); // MTA
        var clsid = new Guid(SendExplorerCommand.ClsidText);
        var factory = new ShellClassFactory();
        var unk = Marshal.GetIUnknownForObject(factory);
        CoRegisterClassObject(ref clsid, unk, 0x4 /* LOCAL_SERVER */, 1 /* MULTIPLEUSE */, out var reg);
        // אם אף אחד לא פנה אלינו תוך דקה, יוצאים
        _idle = new Timer(_ => Done.Set(), null, TimeSpan.FromSeconds(60), Timeout.InfiniteTimeSpan);
        Done.Wait();
        CoRevokeClassObject(reg);
    }

    public static void ScheduleExit() => _idle?.Change(TimeSpan.FromSeconds(8), Timeout.InfiniteTimeSpan);

    /// <summary>מפעיל את התוכנה הראשית עם הקבצים. בהרבה קבצים משתמשים בקובץ רשימה (מגבלת אורך שורת פקודה).</summary>
    public static void LaunchApp(List<string> paths)
    {
        var exe = Environment.ProcessPath!;
        var psi = new ProcessStartInfo(exe) { UseShellExecute = false };
        if (paths.Sum(p => p.Length + 3) < 20000)
            foreach (var p in paths) psi.ArgumentList.Add(p);
        else
        {
            var f = Path.Combine(Path.GetTempPath(), "sendeasy-" + Guid.NewGuid().ToString("N") + ".lst");
            File.WriteAllLines(f, paths);
            psi.ArgumentList.Add("--list"); psi.ArgumentList.Add(f);
        }
        Process.Start(psi);
    }
}
