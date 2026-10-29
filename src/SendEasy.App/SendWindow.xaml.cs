using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using Windows.Devices.Enumeration;
using SendEasy.Core;

namespace SendEasy.App;

/// <summary>מכשיר בחלון הבחירה (מוצג ברשימה).</summary>
sealed class Dev
{
    public required string Id { get; init; }
    public string Name { get; set; } = "";
    public ulong Address { get; set; }
    public int Major { get; set; }
    public bool Paired { get; set; }
    public bool Connected { get; set; }
    public bool IsFavorite { get; set; }
    public DeviceInformation Info { get; set; } = null!;

    public string Icon => Major == 1 ? "💻" : Major == 2 ? "📱" : "📟";
    /// <summary>כינוי שנתן המשתמש (אם יש).</summary>
    public string? Alias { get; set; }
    public string OriginalName => Name.Length > 0 ? Name : "מכשיר ללא שם";
    public string DisplayName => string.IsNullOrWhiteSpace(Alias) ? OriginalName : Alias;
    public string Subtitle => (Major == 1 ? "מחשב" : Major == 2 ? "טלפון" : "מכשיר") + (Connected ? " · מחובר" : "");
    public string Star => IsFavorite ? "★" : "☆";
    public Visibility PairedVisibility => Paired ? Visibility.Visible : Visibility.Collapsed;
    public Visibility UnpairedVisibility => Paired ? Visibility.Collapsed : Visibility.Visible;
    public bool Relevant => (Major is 0 or 1 or 2 or 3 or 6) && (Name.Length > 0 || Major is 1 or 2);
}

public partial class SendWindow : Wpf.Ui.Controls.FluentWindow
{
    static readonly string[] Props =
    {
        "System.Devices.Aep.DeviceAddress", "System.Devices.Aep.IsConnected",
        "System.Devices.Aep.IsPaired", "System.Devices.Aep.Bluetooth.Cod.Major",
    };

    readonly List<string> _paths;
    readonly Settings _settings;
    readonly FavoriteDevice? _target; // שליחה ישירה למכשיר מועדף, בלי בחירה
    readonly Dictionary<string, Dev> _devs = new();
    Windows.Devices.Enumeration.DeviceWatcher? _watcher;
    CancellationTokenSource? _cts;
    bool _busy;
    bool _syncing;

    public SendWindow(IReadOnlyList<string> paths, Settings settings, FavoriteDevice? target = null)
    {
        _paths = paths.ToList();
        _settings = settings;
        _target = target;
        InitializeComponent();
        Closing += (_, _) => { _cts?.Cancel(); StopWatcher(); };
        Loaded += async (_, _) =>
        {
            if (_target != null) await SendDirectAsync();
            else StartWatcher();
        };
        UpdateSummary();
        if (_target != null)
        {
            DeviceScroll.Visibility = Visibility.Collapsed;
            AutoText.Visibility = Visibility.Visible;
            AutoText.Text = $"שולח אל {_target.Name}...";
            SendBtn.Content = "שלח";
            SendBtn.IsEnabled = false;
        }
    }

    Dev? Selected => (PairedList.SelectedItem ?? NearbyList.SelectedItem) as Dev;

    void RefreshButtons()
    {
        if (_target != null) return;
        SendBtn.IsEnabled = Selected != null && !_busy && _paths.Count > 0;
    }

    void UpdateSummary()
    {
        if (_paths.Count == 0)
        {
            WhatText.Text = "לא נבחרו קבצים";
            DetailText.Text = "גרור קבצים לכאן, או לחץ \"הוסף קבצים\".";
            RefreshButtons(); return;
        }
        long total = 0; bool hasDir = false;
        foreach (var p in _paths)
        {
            if (Directory.Exists(p)) { hasDir = true; continue; }
            try { total += new FileInfo(p).Length; } catch { }
        }
        WhatText.Text = _paths.Count == 1 ? Path.GetFileName(_paths[0].TrimEnd('\\')) : $"{_paths.Count} פריטים";
        DetailText.Text = hasDir ? "התיקייה תישלח כארכיון ZIP" : TrayApp.Ltr(TrayApp.FormatSize(total));
        RefreshButtons();
    }

    void AddBtn_Click(object sender, RoutedEventArgs e)
    {
        var d = new Microsoft.Win32.OpenFileDialog { Multiselect = true, Title = "בחר קבצים לשליחה" };
        if (d.ShowDialog(this) == true) { _paths.AddRange(d.FileNames); UpdateSummary(); }
    }

    // ---- גרירה ושחרור ----

    void Window_DragOver(object sender, DragEventArgs e)
    {
        e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) && !_busy ? DragDropEffects.Copy : DragDropEffects.None;
        e.Handled = true;
    }

    void Window_Drop(object sender, DragEventArgs e)
    {
        if (_busy || e.Data.GetData(DataFormats.FileDrop) is not string[] files) return;
        _paths.AddRange(files.Where(f => !_paths.Contains(f)));
        UpdateSummary();
    }

    // ---- בחירה ----

    void PairedList_SelectionChanged(object s, SelectionChangedEventArgs e)
    {
        if (_syncing) return;
        if (PairedList.SelectedItem != null) { _syncing = true; NearbyList.SelectedItem = null; _syncing = false; }
        RefreshButtons();
    }

    void NearbyList_SelectionChanged(object s, SelectionChangedEventArgs e)
    {
        if (_syncing) return;
        if (NearbyList.SelectedItem != null) { _syncing = true; PairedList.SelectedItem = null; _syncing = false; }
        RefreshButtons();
    }

    // ---- צימוד והסרת צימוד ----

    /// <summary>מצמד מכשיר קרוב שעוד לא מוצמד.</summary>
    async void Pair_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (((FrameworkElement)sender).Tag is not Dev d || d.Paired || _busy) return;
        ((FrameworkElement)sender).IsEnabled = false;
        try { await EnsurePairedAsync(d); }
        catch (Exception ex) { StatusText.Text = "הצימוד נכשל: " + ex.Message; }
        finally { ((FrameworkElement)sender).IsEnabled = true; }
    }

    /// <summary>מסיר את הצימוד של Windows עם המכשיר (גם מרשימת המועדפים). אפשר לצמד שוב בכל רגע.</summary>
    async void Unpair_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (((FrameworkElement)sender).Tag is not Dev d || !d.Paired || _busy) return;
        if (!ConfirmDialog.Ask(this, "להסיר את הצימוד?", "המכשיר " + TrayApp.Ltr(d.DisplayName) + " יוסר מהרשימה המוצמדת, ותצטרך לצמד אותו שוב כדי לשלוח אליו.", "הסר צימוד", "ביטול")) return;
        try
        {
            var res = await d.Info.Pairing.UnpairAsync();
            if (res.Status is DeviceUnpairingResultStatus.Unpaired or DeviceUnpairingResultStatus.AlreadyUnpaired)
            {
                d.Paired = false;
                var fav = _settings.Favorites.FirstOrDefault(f => f.Address == d.Address);
                if (fav != null)
                {
                    _settings.Favorites.Remove(fav);
                    if (_settings.DefaultDevice == d.Address) _settings.DefaultDevice = _settings.Favorites.FirstOrDefault()?.Address ?? 0;
                    try { _settings.Save(); } catch { }
                }
                StatusText.Text = "הצימוד הוסר: " + d.DisplayName;
                Rebuild();
            }
            else StatusText.Text = "הסרת הצימוד נכשלה: " + res.Status;
        }
        catch (Exception ex) { StatusText.Text = "הסרת הצימוד נכשלה: " + ex.Message; }
    }

    // ---- שינוי שם ----

    void Rename_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (((FrameworkElement)sender).Tag is not Dev d || d.Address == 0) return;
        var res = ConfirmDialog.Prompt(this, "שינוי שם למכשיר", "השם המקורי: " + TrayApp.Ltr(d.OriginalName) + ". השאר ריק כדי לחזור אליו.", d.Alias ?? d.OriginalName);
        if (res == null) return;
        var name = res.Trim();
        _settings.SetAlias(d.Address, name == d.OriginalName ? "" : name);
        var fav = _settings.Favorites.FirstOrDefault(f => f.Address == d.Address);
        if (fav != null) fav.Name = _settings.DisplayName(d.Address, d.OriginalName);
        try { _settings.Save(); } catch { }
        Rebuild();
    }

    // ---- מועדפים ----

    void Star_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (((FrameworkElement)sender).Tag is not Dev d || d.Address == 0) return;
        var fav = _settings.Favorites.FirstOrDefault(f => f.Address == d.Address);
        if (fav == null)
        {
            _settings.Favorites.Add(new FavoriteDevice { Name = d.DisplayName, Address = d.Address });
            if (_settings.DefaultDevice == 0) _settings.DefaultDevice = d.Address; // הראשון הופך לברירת מחדל
        }
        else
        {
            _settings.Favorites.Remove(fav);
            if (_settings.DefaultDevice == d.Address)
                _settings.DefaultDevice = _settings.Favorites.FirstOrDefault()?.Address ?? 0;
        }
        try { _settings.Save(); } catch { }
        Rebuild();
    }

    // ---- חיפוש מכשירים ----

    void StartWatcher()
    {
        var sel = "System.Devices.Aep.ProtocolId:=\"{e0cbf06c-cd8b-4647-bb8a-263b43f0f974}\"";
        _watcher = DeviceInformation.CreateWatcher(sel, Props, DeviceInformationKind.AssociationEndpoint);
        _watcher.Added += (_, i) => OnUi(() => Upsert(i));
        _watcher.Updated += (_, u) => OnUi(() => Update(u));
        _watcher.Removed += (_, r) => OnUi(() => { _devs.Remove(r.Id); Rebuild(); });
        _watcher.EnumerationCompleted += (_, _) => OnUi(() =>
        {
            Spinner.Visibility = Visibility.Collapsed;
            StatusText.Text = _devs.Values.Any(d => d.Relevant) ? "בחר מכשיר ולחץ שלח" : "לא נמצאו מכשירים. ודא שהבלוטוס פועל במכשיר השני.";
        });
        StatusText.Text = "מחפש מכשירים...";
        Spinner.Visibility = Visibility.Visible;
        _watcher.Start();
    }

    void StopWatcher()
    {
        try
        {
            if (_watcher != null && _watcher.Status is DeviceWatcherStatus.Started or DeviceWatcherStatus.EnumerationCompleted)
                _watcher.Stop();
        }
        catch { }
        _watcher = null;
    }

    void OnUi(Action a) => Dispatcher.BeginInvoke(a);

    static T? Prop<T>(IReadOnlyDictionary<string, object> p, string key) =>
        p.TryGetValue(key, out var v) && v is T t ? t : default;

    void Upsert(DeviceInformation i)
    {
        var d = new Dev { Id = i.Id, Info = i };
        Apply(d, i.Name, i.Properties, i.Pairing.IsPaired);
        _devs[i.Id] = d;
        Rebuild();
    }

    void Update(DeviceInformationUpdate u)
    {
        if (!_devs.TryGetValue(u.Id, out var d)) return;
        Apply(d, null, u.Properties, null);
        Rebuild();
    }

    static void Apply(Dev d, string? name, IReadOnlyDictionary<string, object> p, bool? paired)
    {
        if (name != null) d.Name = name;
        if (Prop<string>(p, "System.Devices.Aep.DeviceAddress") is { } a)
            try { d.Address = Convert.ToUInt64(a.Replace(":", ""), 16); } catch { }
        if (Prop<bool?>(p, "System.Devices.Aep.IsConnected") is { } c) d.Connected = c;
        if (Prop<bool?>(p, "System.Devices.Aep.IsPaired") is { } pr) d.Paired = pr;
        else if (paired is { } pp) d.Paired = pp;
        if (p.TryGetValue("System.Devices.Aep.Bluetooth.Cod.Major", out var m) && m != null)
            try { d.Major = Convert.ToInt32(m); } catch { }
    }

    void Rebuild()
    {
        var selId = Selected?.Id;
        _syncing = true;
        PairedList.Items.Clear(); NearbyList.Items.Clear();
        foreach (var d in _devs.Values.Where(d => d.Relevant))
        {
            d.IsFavorite = _settings.Favorites.Any(f => f.Address == d.Address && d.Address != 0);
            d.Alias = _settings.Alias(d.Address);
        }
        foreach (var d in _devs.Values.Where(d => d.Relevant).OrderByDescending(d => d.IsFavorite).ThenBy(d => d.DisplayName))
        {
            var list = d.Paired ? PairedList : NearbyList;
            list.Items.Add(d);
            if (d.Id == selId) list.SelectedItem = d;
        }
        _syncing = false;
        NoPairedText.Visibility = PairedList.Items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        NearbyTitle.Text = NearbyList.Items.Count > 0
            ? $"מכשירים נוספים בקרבת מקום ({NearbyList.Items.Count})" : "מכשירים נוספים בקרבת מקום";
        RefreshButtons();
    }

    // ---- שליחה ----

    async Task<bool> EnsurePairedAsync(Dev dev)
    {
        if (dev.Paired) return true;
        StatusText.Text = $"מצמד את {dev.DisplayName}... אשר בחלון של Windows ובמכשיר";
        var res = await dev.Info.Pairing.PairAsync();
        if (res.Status is DevicePairingResultStatus.Paired or DevicePairingResultStatus.AlreadyPaired)
        {
            dev.Paired = true; Rebuild(); return true;
        }
        StatusText.Text = "הצימוד נכשל: " + res.Status;
        return false;
    }

    async void SendBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_target != null) { await SendDirectAsync(); return; }
        if (Selected is not { } dev || _paths.Count == 0) return;
        if (dev.Address == 0) { StatusText.Text = "לא התקבלה כתובת למכשיר, נסה שוב בעוד רגע"; return; }
        await RunSendAsync(dev.Address, dev.DisplayName, async () =>
        {
            if (!await EnsurePairedAsync(dev)) return false;
            StopWatcher(); // סריקה מאטה את החיבור בזמן העברה
            Spinner.Visibility = Visibility.Collapsed;
            return true;
        });
    }

    void CancelBtn_Click(object sender, RoutedEventArgs e)
    {
        CancelBtn.IsEnabled = false;
        StatusText.Text = "מבטל...";
        _cts?.Cancel();
    }

    Task SendDirectAsync() => RunSendAsync(_target!.Address, _target.Name, () => Task.FromResult(true));

    async Task RunSendAsync(ulong address, string deviceName, Func<Task<bool>> prepare)
    {
        if (_busy) return;
        _busy = true; var cts = _cts = new CancellationTokenSource();
        CancelBtn.Visibility = Visibility.Visible; CancelBtn.IsEnabled = true;
        SendBtn.IsEnabled = false; AddBtn.IsEnabled = false; PairedList.IsEnabled = false; NearbyList.IsEnabled = false;
        var prog = new Progress<double>(f => Bar.Value = Math.Clamp(f * 1000, 0, 1000));
        var entry = new HistoryEntry
        {
            Sent = true, Device = deviceName,
            Name = _paths.Count == 1 ? Path.GetFileName(_paths[0].TrimEnd('\\')) : $"{_paths.Count} פריטים",
            Path = _paths.Count == 1 ? _paths[0] : Path.GetDirectoryName(_paths[0]),
            Size = _paths.Sum(p => { try { return File.Exists(p) ? new FileInfo(p).Length : 0L; } catch { return 0L; } }),
        };
        bool ok = false;
        try
        {
            if (!await prepare()) { entry.Ok = false; entry.Note = "הצימוד נכשל"; return; }
            await SendService.SendAsync(address, _paths, s => { StatusText.Text = s; if (_target != null) AutoText.Text = s; }, prog, _cts.Token);
            Bar.Value = Bar.Maximum;
            ok = true;
            History.Add(entry);
            await Task.Delay(1200);
            Close();
        }
        catch (Exception) when (cts.IsCancellationRequested)
        {
            entry.Ok = false; entry.Note = "בוטל";
            StatusText.Text = "השליחה בוטלה"; Bar.Value = 0;
            if (_target != null) AutoText.Text = "השליחה בוטלה";
            SendBtn.Content = _target != null ? "נסה שוב" : "שלח";
        }
        catch (Exception ex)
        {
            entry.Ok = false; entry.Note = ex.Message;
            StatusText.Text = "נכשל: " + ex.Message + "\nודא שהמכשיר דלוק, קרוב, והבלוטוס פעיל בו.";
            if (_target != null) AutoText.Text = "השליחה נכשלה";
            Bar.Value = 0;
            SendBtn.Content = "נסה שוב";
        }
        finally
        {
            _cts = null; _busy = false;
            if (!ok) History.Add(entry);
            if (IsLoaded)
            {
                CancelBtn.Visibility = Visibility.Collapsed;
                AddBtn.IsEnabled = true; PairedList.IsEnabled = true; NearbyList.IsEnabled = true;
                if (_target != null) SendBtn.IsEnabled = true; else RefreshButtons();
                if (_target == null && _watcher == null) StartWatcher();
            }
        }
    }
}
