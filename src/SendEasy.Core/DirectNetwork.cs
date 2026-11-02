using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security;

namespace SendEasy.Core;

/// <summary>
/// רשת ישירה: כשאין רשת משותפת, הטלפון פותח נקודה חמה זמנית והמחשב מתחבר אליה בפרופיל זמני דרך wlanapi (בלי הרשאות מנהל).
/// בסיום מוחקים את הפרופיל וחוזרים לרשת הקודמת. המפרט ב-PROTOCOL.md.
/// </summary>
public sealed class DirectNetwork
{
    public static readonly DirectNetwork Instance = new();

    readonly object _gate = new();
    string? _joined, _previous;
    Guid _iface;
    Timer? _safety;

    public bool Joined { get { lock (_gate) return _joined != null; } }

    /// <summary>יש מתאם וויפי שאפשר להתחבר איתו.</summary>
    public static bool HasWifiAdapter()
    {
        try { using var w = new Wlan(); return w.Interface() != null; }
        catch { return false; }
    }

    /// <summary>מתחבר לרשת ומחכה (עד 30 שניות) לכתובת IPv4. בכשל מנקה וחוזר לרשת הקודמת.</summary>
    public Task<bool> JoinAsync(string ssid, string pass) => Task.Run(async () =>
    {
        try
        {
            using var w = new Wlan();
            var iface = w.Interface() ?? throw new InvalidOperationException("אין מתאם וויפי");
            var current = w.CurrentProfile(iface);
            lock (_gate)
            {
                if (_joined == null && current != ssid) _previous = current;
                _iface = iface;
                _joined = ssid; // מסומן מראש, כדי ש-Leave ינקה גם אם החיבור נכשל באמצע
            }
            if (current != ssid || !HasAddress(iface))
            {
                w.SetProfile(iface, Profile(ssid, pass));
                w.Scan(iface);
                await Task.Delay(1500);
                w.Connect(iface, ssid);
            }
            for (int i = 0; i < 60; i++)
            {
                if (w.CurrentProfile(iface) == ssid && HasAddress(iface))
                {
                    // רשת זמנית: אם שכחו לסגור, חוזרים לרשת הקודמת אחרי 5 דקות
                    lock (_gate) { _safety?.Dispose(); _safety = new Timer(_ => Leave(), null, TimeSpan.FromMinutes(5), Timeout.InfiniteTimeSpan); }
                    return true;
                }
                await Task.Delay(500);
            }
        }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine("direct: join failed: " + ex.Message); }
        Leave();
        return false;
    });

    /// <summary>מוחק את הפרופיל הזמני וחוזר לרשת שהמחשב היה עליה.</summary>
    public void Leave()
    {
        string? ssid, previous;
        Guid iface;
        lock (_gate)
        {
            ssid = _joined; previous = _previous; iface = _iface;
            _joined = null; _previous = null;
            _safety?.Dispose(); _safety = null;
        }
        if (ssid == null) return;
        try
        {
            using var w = new Wlan();
            try { w.DeleteProfile(iface, ssid); } catch { }
            if (!string.IsNullOrEmpty(previous) && previous != ssid) w.Connect(iface, previous);
        }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine("direct: leave failed: " + ex.Message); }
    }

    static bool HasAddress(Guid iface)
    {
        var id = iface.ToString("B");
        var nic = NetworkInterface.GetAllNetworkInterfaces()
            .FirstOrDefault(n => string.Equals(n.Id, id, StringComparison.OrdinalIgnoreCase));
        return nic is { OperationalStatus: OperationalStatus.Up } &&
               nic.GetIPProperties().UnicastAddresses.Any(a =>
                   a.Address.AddressFamily == AddressFamily.InterNetwork && !a.Address.ToString().StartsWith("169.254."));
    }

    static string Profile(string ssid, string pass)
    {
        var name = SecurityElement.Escape(ssid);
        var key = SecurityElement.Escape(pass);
        return $"""
            <?xml version="1.0"?>
            <WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
              <name>{name}</name>
              <SSIDConfig><SSID><name>{name}</name></SSID></SSIDConfig>
              <connectionType>ESS</connectionType>
              <connectionMode>manual</connectionMode>
              <MSM><security>
                <authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption>
                <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>{key}</keyMaterial></sharedKey>
              </security></MSM>
            </WLANProfile>
            """;
    }

    /// <summary>עטיפה מינימלית ל-wlanapi.dll.</summary>
    sealed class Wlan : IDisposable
    {
        readonly IntPtr _h;

        public Wlan()
        {
            int err = WlanOpenHandle(2, IntPtr.Zero, out _, out _h);
            if (err != 0) throw new InvalidOperationException("WlanOpenHandle " + err);
        }

        public void Dispose() => WlanCloseHandle(_h, IntPtr.Zero);

        /// <summary>מתאם הוויפי (המחובר, אם יש כמה).</summary>
        public Guid? Interface()
        {
            if (WlanEnumInterfaces(_h, IntPtr.Zero, out var list) != 0) return null;
            try
            {
                int n = Marshal.ReadInt32(list);
                Guid? first = null;
                for (int i = 0; i < n; i++)
                {
                    var item = list + 8 + i * 532; // WLAN_INTERFACE_INFO: GUID, WCHAR[256], מצב
                    var g = Marshal.PtrToStructure<Guid>(item);
                    first ??= g;
                    if (Marshal.ReadInt32(item, 528) == 1) return g; // wlan_interface_state_connected
                }
                return first;
            }
            finally { WlanFreeMemory(list); }
        }

        /// <summary>שם הפרופיל של החיבור הנוכחי (wlan_intf_opcode_current_connection = 7; השם בהיסט 8).</summary>
        public string? CurrentProfile(Guid iface)
        {
            if (WlanQueryInterface(_h, ref iface, 7, IntPtr.Zero, out _, out var data, out _) != 0) return null;
            try { return Marshal.PtrToStringUni(data + 8); }
            finally { WlanFreeMemory(data); }
        }

        public void SetProfile(Guid iface, string xml)
        {
            int err = WlanSetProfile(_h, ref iface, 0, xml, null, true, IntPtr.Zero, out var reason);
            if (err != 0) err = WlanSetProfile(_h, ref iface, 2 /* למשתמש בלבד */, xml, null, true, IntPtr.Zero, out reason);
            if (err != 0) throw new InvalidOperationException($"WlanSetProfile {err} ({reason})");
        }

        public void Scan(Guid iface) => WlanScan(_h, ref iface, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);

        public void Connect(Guid iface, string profile)
        {
            var p = new CONNECTION_PARAMETERS { Mode = 0, Profile = profile, BssType = 1 };
            int err = WlanConnect(_h, ref iface, ref p, IntPtr.Zero);
            if (err != 0) throw new InvalidOperationException("WlanConnect " + err);
        }

        public void DeleteProfile(Guid iface, string profile) => WlanDeleteProfile(_h, ref iface, profile, IntPtr.Zero);

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct CONNECTION_PARAMETERS
        {
            public int Mode;
            [MarshalAs(UnmanagedType.LPWStr)] public string Profile;
            public IntPtr Ssid, BssidList;
            public int BssType;
            public uint Flags;
        }

        [DllImport("wlanapi.dll")] static extern int WlanOpenHandle(uint ver, IntPtr r, out uint negotiated, out IntPtr h);
        [DllImport("wlanapi.dll")] static extern int WlanCloseHandle(IntPtr h, IntPtr r);
        [DllImport("wlanapi.dll")] static extern int WlanEnumInterfaces(IntPtr h, IntPtr r, out IntPtr list);
        [DllImport("wlanapi.dll")] static extern void WlanFreeMemory(IntPtr p);
        [DllImport("wlanapi.dll")] static extern int WlanQueryInterface(IntPtr h, ref Guid iface, int op, IntPtr r, out int size, out IntPtr data, out int type);
        [DllImport("wlanapi.dll")] static extern int WlanScan(IntPtr h, ref Guid iface, IntPtr ssid, IntPtr ie, IntPtr r);
        [DllImport("wlanapi.dll", CharSet = CharSet.Unicode)] static extern int WlanSetProfile(IntPtr h, ref Guid iface, uint flags, string xml, string? sec, bool overwrite, IntPtr r, out uint reason);
        [DllImport("wlanapi.dll", CharSet = CharSet.Unicode)] static extern int WlanDeleteProfile(IntPtr h, ref Guid iface, string name, IntPtr r);
        [DllImport("wlanapi.dll")] static extern int WlanConnect(IntPtr h, ref Guid iface, ref CONNECTION_PARAMETERS p, IntPtr r);
    }
}
