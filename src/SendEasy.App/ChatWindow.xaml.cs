using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using SendEasy.Core;

namespace SendEasy.App;

enum MsgKind { Text, Voice, File }
enum MsgStatus { Sent, Delivered, Read, Failed }

sealed record Msg(bool Mine, string Sender, DateTime Time, MsgKind Kind, string Text = "", int Seconds = 0,
    long Size = 0, MsgStatus Status = MsgStatus.Read, string Path = "");

sealed class Conv
{
    public ulong Address { get; init; }
    public required string Name { get; set; }
    public required string Kind { get; init; }
    public bool Online { get; init; }
    public Color Color { get; init; }
    public List<Msg> Messages { get; } = new();
}

/// <summary>חלון הצ'אט: הודעות וקבצים מול האפליקציה בטלפון, בשני סגנונות (ווטסאפ וגוגל צ'אט).</summary>
public partial class ChatWindow : Wpf.Ui.Controls.FluentWindow
{
    sealed record Palette(string ChatBg, string Header, string Side, string Mine, string Theirs, string Text,
        string Sub, string Accent, string Input, string Composer, string Border);

    static readonly Palette WaLight = new("#EFE7DE", "#F0F2F5", "#FFFFFF", "#D9FDD3", "#FFFFFF", "#111B21", "#667781", "#00A884", "#FFFFFF", "#F0F2F5", "#E9EDEF");
    static readonly Palette WaDark = new("#0B141A", "#202C33", "#111B21", "#005C4B", "#202C33", "#E9EDEF", "#8696A0", "#00A884", "#2A3942", "#202C33", "#222D34");
    static readonly Palette GcLight = new("#FFFFFF", "#FFFFFF", "#F8FAFD", "#FFFFFF", "#FFFFFF", "#1F1F1F", "#5F6368", "#0B57D0", "#F0F4F9", "#FFFFFF", "#E3E3E3");
    static readonly Palette GcDark = new("#1F1F1F", "#1F1F1F", "#282A2C", "#1F1F1F", "#1F1F1F", "#E3E3E3", "#9AA0A6", "#A8C7FA", "#2D2F31", "#1F1F1F", "#3C4043");

    readonly Settings _settings;
    readonly VoiceCapture _voice = new();
    System.Threading.CancellationTokenSource? _sendCts;
    readonly DispatcherTimer _recTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    bool _recording;
    readonly System.Windows.Media.MediaPlayer _player = new();
    string? _playingPath;
    readonly List<Conv> _convs = new();
    Conv _cur = null!;
    bool _wa = true;
    Palette _p = WaLight;

    public ChatWindow(Settings settings)
    {
        _settings = settings;
        InitializeComponent();
        LoadConversations();
        _wa = _settings.ChatStyle == 0;
        ActionBtn.Content = "\uE720";
        ActionBtn.ToolTip = "הקלטת הודעה קולית";
        Activated += (_, _) => SendReadReceipts();
        HookPaste();
        _recTimer.Tick += (_, _) => RecordLabel.Text = _voice.IsPaused
            ? $"⏸ מושהה  {Fmt(_voice.ElapsedSeconds)}   (כפתור ההשהיה: המשך, כפתור השליחה: שלח)"
            : $"● מקליט  {Fmt(_voice.ElapsedSeconds)}   (כפתור ההשהיה: השהה, כפתור השליחה: שלח)";
        ConvList.ItemContainerStyle = ConvItemStyle();
        ApplyStyle();
        if (_convs.Count > 0) ConvList.SelectedIndex = 0;
        else HeaderName.Text = "אין שיחות עדיין. הודעה מהטלפון או מכשיר מועדף יפתחו שיחה.";
    }

    /// <summary>שם מכשיר לטיני (כמו Qin 1s+) נעטף כך שלא יתהפך בתוך ממשק עברי.</summary>
    static string Disp(string name) => System.Text.RegularExpressions.Regex.IsMatch(name, @"[\u0590-\u05FF]") ? name : TrayApp.Ltr(name);

    static string Fmt(int s) => $"{s / 60}:{s % 60:00}";
    static SolidColorBrush B(string hex) => new((Color)ColorConverter.ConvertFromString(hex));
    static bool IsDark() => Wpf.Ui.Appearance.ApplicationThemeManager.GetAppTheme() == Wpf.Ui.Appearance.ApplicationTheme.Dark;

    // ---- שיחות אמיתיות (מהיסטוריית הצ'אט) ----

    static Color ColorFor(ulong address)
    {
        Color[] pal =
        {
            Color.FromRgb(0x00, 0x96, 0x88), Color.FromRgb(0x5E, 0x35, 0xB1), Color.FromRgb(0xE6, 0x51, 0x00),
            Color.FromRgb(0x0B, 0x57, 0xD0), Color.FromRgb(0xC2, 0x18, 0x5B), Color.FromRgb(0x2E, 0x7D, 0x32),
        };
        return pal[(int)(address % (ulong)pal.Length)];
    }

    static Msg ToMsg(ChatEntry e) => e.Kind == "voice"
        ? new Msg(e.Mine, e.Mine ? "אני" : e.Device, e.Time, MsgKind.Voice, e.Path ?? "", ChatLog.VoiceSeconds(e.Text), 0,
            e.Status == "failed" ? MsgStatus.Failed : e.Status == "read" ? MsgStatus.Read : e.Status == "delivered" ? MsgStatus.Delivered : MsgStatus.Sent)
        : new(e.Mine, e.Mine ? "אני" : e.Device, e.Time,
        e.Kind == "file" ? MsgKind.File : MsgKind.Text, e.Text, 0, e.Size,
        e.Status == "failed" ? MsgStatus.Failed : e.Status == "read" ? MsgStatus.Read : e.Status == "delivered" ? MsgStatus.Delivered : MsgStatus.Sent, e.Path ?? "");

    Conv MakeConv(ulong address, string name)
    {
        var c = new Conv
        {
            Address = address, Kind = "",
            Name = _settings.DisplayName(address, string.IsNullOrWhiteSpace(name) ? "מכשיר " + address.ToString("X12") : name),
            Color = ColorFor(address),
        };
        c.Messages.AddRange(ChatLog.Load(address).Select(ToMsg));
        return c;
    }

    void LoadConversations()
    {
        var map = new Dictionary<ulong, Conv>();
        foreach (var (addr, name, _) in ChatLog.Conversations()) map[addr] = MakeConv(addr, name);
        foreach (var f in _settings.Favorites)
            if (f.Address != 0 && !map.ContainsKey(f.Address)) map[f.Address] = MakeConv(f.Address, f.Name);
        _convs.Clear();
        _convs.AddRange(map.Values.OrderByDescending(c => c.Messages.LastOrDefault()?.Time ?? DateTime.MinValue));
    }

    Conv Ensure(ulong address, string name)
    {
        var c = _convs.FirstOrDefault(x => x.Address == address);
        if (c == null) { c = MakeConv(address, name); _convs.Insert(0, c); }
        return c;
    }

    void Reload(Conv c)
    {
        c.Messages.Clear();
        c.Messages.AddRange(ChatLog.Load(c.Address).Select(ToMsg));
    }

    /// <summary>הודעה או קובץ חדש הגיעו: רענון השיחה.</summary>
    public void Refresh(ulong address, string name)
    {
        var c = Ensure(address, name);
        Reload(c);
        RebuildConvList();
        if (_cur?.Address == address) RenderMessages();
        else if (_cur == null) Open(address, name);
    }

    /// <summary>פותח את השיחה עם מכשיר (ויוצר אותה אם אין).</summary>
    public void Open(ulong address, string name)
    {
        var c = Ensure(address, name);
        RebuildConvList();
        var idx = _convs.IndexOf(c);
        if (idx >= 0) ConvList.SelectedIndex = idx;
    }
    // ---- עיצוב ----

    /// <summary>הסגנון (ווטסאפ או גוגל צ'אט) נבחר בהגדרות. נקרא אחרי שמירת ההגדרות.</summary>
    public void ReloadStyle()
    {
        _wa = _settings.ChatStyle == 0;
        ApplyStyle();
    }

    void ApplyStyle()
    {
        bool dark = IsDark();
        _p = _wa ? (dark ? WaDark : WaLight) : (dark ? GcDark : GcLight);
        SidePane.Background = B(_p.Side);
        SidePane.BorderBrush = B(_p.Border);
        ChatPane.Background = B(_p.ChatBg);
        HeaderBar.Background = B(_p.Header);
        HeaderBar.BorderBrush = B(_p.Border);
        HeaderBar.BorderThickness = new Thickness(0, 0, 0, 1);
        Composer.Background = B(_p.Composer);
        InputBox.Background = B(_p.Input);
        InputBox.BorderBrush = B(_p.Border);
        InputBox.BorderThickness = new Thickness(_wa ? 0 : 1);
        foreach (var t in new TextBlock[] { HeaderName, SideTitle }) t.Foreground = B(_p.Text);
        HeaderStatus.Foreground = B(_p.Sub);
        Input.Foreground = B(_p.Text);
        ActionBtn.Background = B(_p.Accent);
        ActionBtn.Foreground = Brushes.White;
        ActionBtn.Template = RoundButton();
        AttachBtn.Foreground = B(_p.Sub);
        ChatPane.Background = B(_p.ChatBg);
        RebuildConvList();
        if (_cur != null) { ShowHeader(); RenderMessages(); }
    }

    /// <summary>פריט ברשימת השיחות: רקע עדין בהעברת עכבר ובבחירה (במקום הצבע הכהה של ברירת המחדל).</summary>
    Style ConvItemStyle()
    {
        var st = new Style(typeof(ListBoxItem));
        var tpl = new ControlTemplate(typeof(ListBoxItem));
        var bd = new FrameworkElementFactory(typeof(Border)) { Name = "bd" };
        bd.SetValue(Border.CornerRadiusProperty, new CornerRadius(10));
        bd.SetValue(Border.PaddingProperty, new Thickness(8, 0, 8, 0));
        bd.SetValue(Border.MarginProperty, new Thickness(0, 1, 0, 1));
        bd.SetValue(Border.BackgroundProperty, Brushes.Transparent);
        var cp = new FrameworkElementFactory(typeof(ContentPresenter));
        bd.AppendChild(cp);
        tpl.VisualTree = bd;
        var sel = new Trigger { Property = ListBoxItem.IsSelectedProperty, Value = true };
        sel.Setters.Add(new Setter(Border.BackgroundProperty, new SolidColorBrush(Color.FromArgb(40, 120, 120, 120)), "bd"));
        var hov = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
        hov.Setters.Add(new Setter(Border.BackgroundProperty, new SolidColorBrush(Color.FromArgb(22, 120, 120, 120)), "bd"));
        tpl.Triggers.Add(hov); tpl.Triggers.Add(sel);
        st.Setters.Add(new Setter(Control.TemplateProperty, tpl));
        st.Setters.Add(new Setter(Control.HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch));
        return st;
    }

    static ControlTemplate RoundButton()
    {
        var t = new ControlTemplate(typeof(Button));
        var f = new FrameworkElementFactory(typeof(Border));
        f.SetValue(Border.CornerRadiusProperty, new CornerRadius(22));
        f.SetBinding(Border.BackgroundProperty, new System.Windows.Data.Binding("Background") { RelativeSource = System.Windows.Data.RelativeSource.TemplatedParent });
        var cp = new FrameworkElementFactory(typeof(ContentPresenter));
        cp.SetValue(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Center);
        cp.SetValue(FrameworkElement.VerticalAlignmentProperty, VerticalAlignment.Center);
        f.AppendChild(cp);
        t.VisualTree = f;
        return t;
    }

    // ---- רשימת שיחות ----

    Border Avatar(string name, Color color, double size)
    {
        return new Border
        {
            Width = size, Height = size, CornerRadius = new CornerRadius(size / 2), Background = new SolidColorBrush(color),
            Child = new TextBlock
            {
                Text = name.TrimStart('ה', '-', ' ').Substring(0, 1).ToUpperInvariant(), Foreground = Brushes.White,
                FontWeight = FontWeights.SemiBold, FontSize = size * 0.42,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            },
        };
    }

    void RebuildConvList()
    {
        var sel = ConvList.SelectedIndex;
        ConvList.Items.Clear();
        foreach (var c in _convs)
        {
            var last = c.Messages.LastOrDefault();
            var g = new Grid { Margin = new Thickness(4, 8, 4, 8) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition());
            g.Children.Add(Avatar(c.Name, c.Color, 46));
            var sp = new StackPanel { Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(sp, 1);
            var top = new Grid();
            top.ColumnDefinitions.Add(new ColumnDefinition());
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            top.Children.Add(new TextBlock { Text = Disp(c.Name), FontWeight = FontWeights.SemiBold, Foreground = B(_p.Text), TextTrimming = TextTrimming.CharacterEllipsis });
            var tm = new TextBlock { Text = last == null ? "" : last.Time.ToString("HH:mm"), FontSize = 11, Foreground = B(_p.Sub) };
            Grid.SetColumn(tm, 1); top.Children.Add(tm);
            sp.Children.Add(top);
            sp.Children.Add(new TextBlock
            {
                Text = last == null ? "" : (last.Mine ? "את/ה: " : "") + Preview(last),
                FontSize = 12.5, Foreground = B(_p.Sub), TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 2, 0, 0),
            });
            g.Children.Add(sp);
            var item = new ListBoxItem { Content = g, Tag = c, HorizontalContentAlignment = HorizontalAlignment.Stretch };
            var del = new MenuItem { Header = "מחק שיחה", Tag = c };
            del.Click += DeleteConv_Click;
            item.ContextMenu = new ContextMenu { FlowDirection = FlowDirection.RightToLeft, Items = { del } };
            ConvList.Items.Add(item);
        }
        if (sel >= 0 && sel < ConvList.Items.Count) ConvList.SelectedIndex = sel;
    }

    static string Preview(Msg m) => m.Kind switch
    {
        MsgKind.Voice => "🎤 הודעה קולית " + Fmt(m.Seconds),
        MsgKind.File => "📄 " + m.Text,
        _ => Emoticons.ToGlyphs(m.Text),
    };

    void ConvList_SelectionChanged(object s, SelectionChangedEventArgs e)
    {
        if (ConvList.SelectedItem is ListBoxItem { Tag: Conv c }) { _cur = c; ShowHeader(); RenderMessages(); }
    }

    void ShowHeader()
    {
        HeaderName.Text = Disp(_cur.Name);
        HeaderStatus.Text = PeerConnections.IsConnected(_cur.Address) ? "מחובר בבלוטוס" : "ההודעות נשלחות דרך האפליקציה בטלפון";
        HeaderInitial.Text = _cur.Name.TrimStart('ה', '-', ' ').Substring(0, 1).ToUpperInvariant();
        HeaderAvatar.Background = new SolidColorBrush(_cur.Color);
        var hdel = new MenuItem { Header = "מחק שיחה", Tag = _cur };
        hdel.Click += DeleteConv_Click;
        HeaderBar.ContextMenu = new ContextMenu { FlowDirection = FlowDirection.RightToLeft, Items = { hdel } };
    }

    /// <summary>מחיקת שיחה (קליק ימני על שיחה ברשימה).</summary>
    void DeleteConv_Click(object sender, RoutedEventArgs e)
    {
        if (((FrameworkElement)sender).Tag is not Conv c) return;
        if (!ConfirmDialog.Ask(this, "למחוק את השיחה?", "כל ההודעות וההקלטות בשיחה עם " + Disp(c.Name) + " יימחקו. קבצים שהתקבלו בתיקיית הקבלה לא יימחקו.", "מחק", "ביטול")) return;
        StopPlayback();
        ChatLog.Delete(c.Address);
        c.Messages.Clear();
        bool keep = _settings.Favorites.Any(f => f.Address == c.Address); // מועדף נשאר ברשימה, ריק
        if (!keep) _convs.Remove(c);
        if (_cur == c) _cur = null!;
        RebuildConvList();
        if (_convs.Count > 0) ConvList.SelectedIndex = 0;
        else { MsgPanel.Children.Clear(); HeaderName.Text = "אין שיחות עדיין. הודעה מהטלפון או מכשיר מועדף יפתחו שיחה."; HeaderStatus.Text = ""; }
    }

    /// <summary>צ'אט חדש: בחירת מכשיר מצומד ופתיחת השיחה איתו (גם אם כבר קיימת).</summary>
    void NewChat_Click(object s, RoutedEventArgs e)
    {
        var dlg = new DevicePickerWindow(_settings) { Owner = this };
        if (dlg.ShowDialog() != true || dlg.Chosen is not { } item) return;
        Open(item.Address, item.Name);
        Input.Focus();
    }

    // ---- הודעות ----

    void RenderMessages()
    {
        MsgPanel.Children.Clear();
        MsgPanel.Children.Add(DateChip("היום"));
        Msg? prev = null;
        foreach (var m in _cur.Messages)
        {
            MsgPanel.Children.Add(_wa ? WaMessage(m) : GcMessage(m, prev));
            prev = m;
        }
        Dispatcher.BeginInvoke(() => Scroller.ScrollToEnd(), DispatcherPriority.Background);
        SendReadReceipts();
    }

    FrameworkElement DateChip(string text)
    {
        return new Border
        {
            HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 6, 0, 8),
            Padding = new Thickness(12, 3, 12, 3), CornerRadius = new CornerRadius(_wa ? 8 : 12),
            Background = _wa ? B(_p.Header) : B(_p.Input),
            Child = new TextBlock { Text = text, FontSize = 12, Foreground = B(_p.Sub), FlowDirection = FlowDirection.RightToLeft },
        };
    }

    string Ticks(Msg m) => m.Status == MsgStatus.Failed ? "⚠ לא נשלח" : m.Status == MsgStatus.Sent ? "✓" : "✓✓";
    Brush TickBrush(Msg m) => m.Status == MsgStatus.Failed ? B("#D64545") : m.Status == MsgStatus.Read ? B("#1B9E4B") : B(_p.Sub);

    // סגנון ווטסאפ: בועות
    FrameworkElement WaMessage(Msg m)
    {
        var body = new StackPanel { FlowDirection = FlowDirection.RightToLeft };
        body.Children.Add(BuildContent(m, 0));
        var footer = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 2, 0, 0), FlowDirection = FlowDirection.LeftToRight };
        footer.Children.Add(new TextBlock { Text = m.Time.ToString("HH:mm"), FontSize = 11, Foreground = B(_p.Sub) });
        if (m.Mine) footer.Children.Add(new TextBlock { Text = " " + Ticks(m), FontSize = 11, Foreground = TickBrush(m) });
        body.Children.Add(footer);
        return new Border
        {
            Background = B(m.Mine ? _p.Mine : _p.Theirs),
            CornerRadius = m.Mine ? new CornerRadius(12, 12, 2, 12) : new CornerRadius(12, 12, 12, 2),
            Padding = new Thickness(11, 7, 11, 5), MaxWidth = 470, MinWidth = 90,
            Margin = new Thickness(18, 2, 18, 2),
            HorizontalAlignment = m.Mine ? HorizontalAlignment.Right : HorizontalAlignment.Left,
            Child = body,
            Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 2, ShadowDepth = 1, Opacity = 0.12 },
        };
    }

    // סגנון גוגל צ'אט: שורה שטוחה עם אווטאר
    FrameworkElement GcMessage(Msg m, Msg? prev)
    {
        bool grouped = prev != null && prev.Mine == m.Mine && (m.Time - prev.Time).TotalMinutes < 6;
        var row = new Grid { Margin = new Thickness(22, grouped ? 1 : 12, 22, 1), FlowDirection = FlowDirection.RightToLeft };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(44) });
        row.ColumnDefinitions.Add(new ColumnDefinition());
        if (!grouped)
            row.Children.Add(Avatar(m.Mine ? "אני" : _cur.Name, m.Mine ? Color.FromRgb(0x0B, 0x57, 0xD0) : _cur.Color, 34));
        var col = new StackPanel();
        Grid.SetColumn(col, 1);
        if (!grouped)
        {
            var head = new StackPanel { Orientation = Orientation.Horizontal };
            head.Children.Add(new TextBlock { Text = m.Mine ? "אני" : _cur.Name, FontWeight = FontWeights.SemiBold, Foreground = B(_p.Text) });
            head.Children.Add(new TextBlock { Text = "  " + m.Time.ToString("HH:mm"), FontSize = 12, Foreground = B(_p.Sub), VerticalAlignment = VerticalAlignment.Center });
            col.Children.Add(head);
        }
        col.Children.Add(BuildContent(m, 1));
        if (m.Mine && m.Status != MsgStatus.Sent && !grouped) { }
        row.Children.Add(col);
        return row;
    }

    FrameworkElement BuildContent(Msg m, int style)
    {
        switch (m.Kind)
        {
            case MsgKind.Voice: return VoiceBubble(m, style);
            case MsgKind.File: return FileBubble(m, style);
            default:
                var tb = new TextBlock
                {
                    TextWrapping = TextWrapping.Wrap, FontSize = 14.5, Foreground = B(_p.Text),
                    Margin = new Thickness(0, style == 1 ? 2 : 0, 0, 0),
                };
                Emoticons.Fill(tb, m.Text, 14.5);
                return tb;
        }
    }

    static bool IsImage(string name) => System.IO.Path.GetExtension(name).ToLowerInvariant() is ".jpg" or ".jpeg" or ".png" or ".gif" or ".bmp" or ".webp";

    /// <summary>תמונה ממוזערת בבועה; לחיצה פותחת צפייה מוגדלת.</summary>
    FrameworkElement? ImageBubble(Msg m)
    {
        if (!IsImage(m.Text) || string.IsNullOrEmpty(m.Path) || !System.IO.File.Exists(m.Path)) return null;
        try
        {
            var bmp = new System.Windows.Media.Imaging.BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
            bmp.DecodePixelWidth = 360;
            bmp.UriSource = new Uri(m.Path);
            bmp.EndInit();
            var img = new Image { Source = bmp, MaxWidth = 300, MaxHeight = 300, Stretch = Stretch.Uniform, Cursor = Cursors.Hand, ToolTip = "לחץ להגדלה" };
            img.Clip = new RectangleGeometry(new Rect(0, 0, 300, 300), 8, 8);
            var path = m.Path;
            img.MouseLeftButtonUp += (_, _) => new ImageViewWindow(path, this).Show();
            return img;
        }
        catch { return null; }
    }

    FrameworkElement FileBubble(Msg m, int style)
    {
        if (ImageBubble(m) is { } image) { image.Margin = new Thickness(0, 2, 0, 2); return image; }
        var g = new Grid { Margin = new Thickness(0, 2, 0, 0) };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition());
        var icon = new Border
        {
            Width = 40, Height = 40, CornerRadius = new CornerRadius(8), Background = B(_p.Accent),
            Child = new TextBlock { Text = "📄", FontSize = 20, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
        };
        g.Children.Add(icon);
        var sp = new StackPanel { Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
        sp.Children.Add(new TextBlock { Text = m.Text, FontWeight = FontWeights.SemiBold, Foreground = B(_p.Text), TextTrimming = TextTrimming.CharacterEllipsis });
        var meta = new StackPanel { Orientation = Orientation.Horizontal };
        meta.Children.Add(new TextBlock { Text = TrayApp.FormatSize(m.Size), FontSize = 12, Foreground = B(_p.Sub), FlowDirection = FlowDirection.LeftToRight });
        meta.Children.Add(new TextBlock { Text = "  ·  נשמר בתיקיית הקבלה", FontSize = 12, Foreground = B(_p.Sub) });
        sp.Children.Add(meta);
        Grid.SetColumn(sp, 1); g.Children.Add(sp);
        if (style == 1)
            return new Border
            {
                BorderBrush = B(_p.Border), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(12),
                Padding = new Thickness(10), Margin = new Thickness(0, 4, 0, 0), MaxWidth = 380, HorizontalAlignment = HorizontalAlignment.Left, Child = g,
            };
        g.MinWidth = 240;
        return g;
    }

    FrameworkElement VoiceBubble(Msg m, int style)
    {
        var line = new StackPanel { Orientation = Orientation.Horizontal, FlowDirection = FlowDirection.LeftToRight, Margin = new Thickness(0, 2, 0, 2) };
        var play = new Border
        {
            Width = 36, Height = 36, CornerRadius = new CornerRadius(18), Background = B(_p.Accent), Cursor = Cursors.Hand,
            Child = new TextBlock { Text = "▶", Foreground = Brushes.White, FontSize = 14, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
        };
        var bars = new List<Rectangle>();
        var wave = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(10, 0, 10, 0) };
        var rnd = new Random(m.Seconds * 31 + (m.Mine ? 7 : 3));
        for (int i = 0; i < 32; i++)
        {
            var r = new Rectangle { Width = 3, Height = 6 + rnd.Next(0, 20), Margin = new Thickness(1, 0, 1, 0), RadiusX = 1.5, RadiusY = 1.5, Fill = B(_p.Sub), VerticalAlignment = VerticalAlignment.Center, Opacity = 0.55 };
            bars.Add(r); wave.Children.Add(r);
        }
        var dur = new TextBlock { Text = Fmt(m.Seconds), FontSize = 12, Foreground = B(_p.Sub), VerticalAlignment = VerticalAlignment.Center };
        line.Children.Add(play); line.Children.Add(wave); line.Children.Add(dur);

        // ניגון אמיתי של הקובץ; העמודות נצבעות לפי מיקום הניגון
        DispatcherTimer? t = null;
        void Reset()
        {
            t?.Stop(); t = null;
            ((TextBlock)play.Child).Text = "▶";
            foreach (var b in bars) { b.Fill = B(_p.Sub); b.Opacity = 0.55; }
        }
        play.MouseLeftButtonUp += (_, _) =>
        {
            bool same = _playingPath == m.Text && t != null;
            StopPlayback();
            Reset();
            if (same) return;
            if (string.IsNullOrEmpty(m.Text) || !System.IO.File.Exists(m.Text)) { ShowError("קובץ ההקלטה לא נמצא"); return; }
            _playingPath = m.Text;
            _player.Open(new Uri(m.Text));
            _player.Play();
            ((TextBlock)play.Child).Text = "■";
            double total = Math.Max(1, m.Seconds);
            t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(120) };
            t.Tick += (_, _) =>
            {
                int filled = (int)Math.Min(bars.Count, _player.Position.TotalSeconds / total * bars.Count);
                for (int i = 0; i < bars.Count; i++)
                {
                    bool on = i < filled;
                    bars[i].Fill = on ? B(_p.Accent) : B(_p.Sub);
                    bars[i].Opacity = on ? 1 : 0.55;
                }
                if (_playingPath != m.Text || _player.Position.TotalSeconds >= total + 0.4) { Reset(); if (_playingPath == m.Text) StopPlayback(); }
            };
            t.Start();
        };        if (style == 1)
            return new Border
            {
                Background = B(_p.Input), CornerRadius = new CornerRadius(22), Padding = new Thickness(8), Margin = new Thickness(0, 4, 0, 0),
                HorizontalAlignment = HorizontalAlignment.Left, Child = line,
            };
        return line;
    }

    // ---- שליחה ----

    bool _converting;

    // ---- תיבת הקלדה עשירה: אימוג'י כתמונות ----

    static void Collect(InlineCollection inlines, System.Text.StringBuilder sb)
    {
        foreach (var inl in inlines)
            switch (inl)
            {
                case Run r: sb.Append(r.Text); break;
                case InlineUIContainer c when c.Tag is string code: sb.Append(code); break;
                case LineBreak: sb.Append('\n'); break;
                case Span sp: Collect(sp.Inlines, sb); break;
            }
    }

    /// <summary>הטקסט שבתיבה, כשכל אייקון מוחלף בקוד שלו (כך הוא נשלח ונשמר).</summary>
    string GetInputCodes()
    {
        var sb = new System.Text.StringBuilder();
        bool first = true;
        foreach (var block in Input.Document.Blocks.OfType<Paragraph>())
        {
            if (!first) sb.Append('\n');
            first = false;
            Collect(block.Inlines, sb);
        }
        return sb.ToString();
    }

    bool InputIsEmpty => string.IsNullOrWhiteSpace(GetInputCodes());

    void ClearInput()
    {
        Input.Document.Blocks.Clear();
        Input.Document.Blocks.Add(new Paragraph());
    }

    InlineUIContainer MakeEmoticon(Emoticons.Item item, TextPointer? pos = null) =>
        new(Emoticons.Icon(item.Kind, 22), pos) { Tag = item.Code, BaselineAlignment = BaselineAlignment.Center };

    void InsertEmoticon(Emoticons.Item item)
    {
        var pos = Input.CaretPosition.GetInsertionPosition(LogicalDirection.Forward);
        var c = MakeEmoticon(item, pos);
        Input.CaretPosition = c.ElementEnd;
    }

    /// <summary>מחליף קודים שהוקלדו (:) :( :+1: :-1: :pray:) באייקון.</summary>
    void ConvertCodesInInput()
    {
        foreach (var para in Input.Document.Blocks.OfType<Paragraph>().ToList())
            foreach (var run in para.Inlines.OfType<Run>().ToList())
            {
                if (!Emoticons.HasCode(run.Text)) continue;
                var parts = Emoticons.Split(run.Text);
                if (!parts.Any(p => p.Item != null)) continue;
                Inline? last = null;
                foreach (var (text, item) in parts)
                {
                    Inline inl = item == null ? new Run(text) : MakeEmoticon(item);
                    para.Inlines.InsertBefore(run, inl);
                    last = inl;
                }
                para.Inlines.Remove(run);
                if (last != null) Input.CaretPosition = last.ElementEnd;
            }
    }

    void Input_TextChanged(object s, TextChangedEventArgs e)
    {
        if (!_converting)
        {
            // :( וכו' שהוקלדו הופכים מיד לאייקון (נשמרים כקוד בעת השליחה)
            _converting = true;
            try { ConvertCodesInInput(); } finally { _converting = false; }
        }
        if (_recording) return;
        bool empty = InputIsEmpty && _pastedImage == null;
        ActionBtn.Content = empty ? "\uE720" : "\uE724";
        ActionBtn.ToolTip = empty ? "הקלטת הודעה קולית" : "שלח";
    }

    void Input_PreviewKeyDown(object s, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && !Keyboard.IsKeyDown(Key.LeftShift) && !Keyboard.IsKeyDown(Key.RightShift))
        {
            e.Handled = true;
            SendText();
        }
    }

    void CancelSend_Click(object s, RoutedEventArgs e)
    {
        CancelSendBtn.IsEnabled = false;
        _sendCts?.Cancel();
        CancelSendBtn.IsEnabled = true;
    }

    /// <summary>שולח לטלפון אישור קריאה על הודעות נכנסות שמוצגות עכשיו (הוא יראה וי ירוק).</summary>
    async void SendReadReceipts()
    {
        var c = _cur;
        if (c == null || !IsActive) return;
        var unread = ChatLog.Unread(c.Address);
        if (unread.Count == 0) return;
        if (await PeerConnections.SendReadAsync(c.Address, unread.Select(u => u.MsgId)))
            foreach (var u in unread) ChatLog.MarkRead(c.Address, u.MsgId);
    }

    void Emoji_Click(object s, RoutedEventArgs e)
    {
        var menu = Emoticons.BuildMenu(item =>
        {
            InsertEmoticon(item);
            Input.Focus();
        });
        menu.PlacementTarget = EmojiBtn;
        menu.Placement = System.Windows.Controls.Primitives.PlacementMode.Top;
        menu.IsOpen = true;
    }

    void Update(Conv c)
    {
        RebuildConvList();
        if (c == _cur) { RenderMessages(); ShowHeader(); }
    }

    void ShowError(string text) => HeaderStatus.Text = "⚠ " + text;

    async void SendText()
    {
        var conv = _cur;
        if (conv == null) return;
        var text = Emoticons.ToCodes(GetInputCodes()).Trim();
        var pending = _pastedImage;
        if (text.Length == 0 && pending == null) return;
        if (pending != null)
        {
            ClearPending();
            if (!await SendChatFileAsync(conv, pending)) return;
            if (text.Length == 0) return;
        }
        ClearInput();
        var entry = new ChatEntry { Mine = true, Kind = "text", Text = text, Device = conv.Name };
        conv.Messages.Add(new Msg(true, "אני", entry.Time, MsgKind.Text, text, Status: MsgStatus.Sent));
        Update(conv);

        bool ok = false; string err = "";
        try
        {
            var s = await PeerConnections.GetAsync(conv.Address);
            if (s == null) err = "האפליקציה לא פתוחה בטלפון";
            else { var r = await s.SendTextWithIdAsync(text); ok = r.Ok; entry.MsgId = r.Id; }
            if (!ok && err.Length == 0) err = "לא התקבל אישור מהטלפון";
        }
        catch (Exception ex) { err = ex.Message; }

        entry.Status = ok ? "delivered" : "failed";
        ChatLog.Add(conv.Address, entry);
        Reload(conv);
        Update(conv);
        if (!ok) ShowError(err);
    }

    async void Attach_Click(object s, RoutedEventArgs e)
    {
        if (_recording) { await EndRecordingAsync(send: false); return; }
        var conv = _cur;
        if (conv == null) return;
        var d = new Microsoft.Win32.OpenFileDialog { Title = "בחר תמונות לשליחה", Multiselect = true,
            Filter = "תמונות|*.jpg;*.jpeg;*.png;*.gif;*.bmp;*.webp|כל הקבצים|*.*" };
        if (d.ShowDialog(this) != true) return;
        var rejected = d.FileNames.Where(f => !IsImage(f)).ToList();
        if (rejected.Count > 0)
            ConfirmDialog.Tell(this, "לא ניתן לשלוח קבצים מסוג זה", "לא ניתן לשלוח קבצים מסוג זה דרך הצ'אט - רק קבצי תמונה.", warning: true);
        foreach (var path in d.FileNames.Where(IsImage))
            if (!await SendChatFileAsync(conv, path)) break;
    }

    /// <summary>שולח תמונה אחת מהצ'אט, עם התקדמות וביטול. מחזיר false אם צריך לעצור (כשל או ביטול).</summary>
    async Task<bool> SendChatFileAsync(Conv conv, string path)
    {
        var name = System.IO.Path.GetFileName(path);
        long size = 0;
        try { size = new System.IO.FileInfo(path).Length; } catch { }
        var entry = new ChatEntry { Mine = true, Kind = "file", Text = name, Size = size, Path = path, Device = conv.Name };
        conv.Messages.Add(new Msg(true, "אני", entry.Time, MsgKind.File, name, Size: size, Status: MsgStatus.Sent, Path: path));
        Update(conv);
        // התקדמות וביטול: קובץ כבד אפשר לעצור באמצע
        var cts = _sendCts = new System.Threading.CancellationTokenSource();
        CancelSendBtn.Visibility = Visibility.Visible;
        var started = DateTime.Now;
        var prog = new Progress<long>(b =>
        {
            int pct = size > 0 ? (int)(b * 100 / size) : 0;
            double secs = Math.Max((DateTime.Now - started).TotalSeconds, 0.5);
            HeaderStatus.Text = $"שולח {TrayApp.Ltr(name)}... {pct}%  ·  {TrayApp.Ltr(TrayApp.FormatSize((long)(b / secs)) + "/s")}";
        });
        bool ok = false, cancelled = false; string err = "";
        try
        {
            var peer = await PeerConnections.GetAsync(conv.Address, cts.Token);
            if (peer == null) err = "האפליקציה לא פתוחה בטלפון";
            else
            {
                await using var fs = new System.IO.FileStream(path, System.IO.FileMode.Open, System.IO.FileAccess.Read, System.IO.FileShare.Read, 64 * 1024, true);
                var r = await peer.SendFileAsync(fs, name, size, prog, cts.Token, fromChat: true);
                ok = r == PeerSendResult.Ok;
                cancelled = r == PeerSendResult.Cancelled;
                if (!ok && !cancelled) err = r == PeerSendResult.Rejected ? "הטלפון סירב לקבל את הקובץ" : "השליחה נכשלה";
            }
        }
        catch (OperationCanceledException) { cancelled = true; }
        catch (Exception ex) { err = ex.Message; }
        _sendCts = null;
        CancelSendBtn.Visibility = Visibility.Collapsed;
        if (cancelled)
        {
            // שליחה שבוטלה לא נשמרת בשיחה
            Reload(conv);
            Update(conv);
            HeaderStatus.Text = "השליחה בוטלה";
            return false;
        }
        entry.Status = ok ? "delivered" : "failed";
        ChatLog.Add(conv.Address, entry);
        Reload(conv);
        Update(conv);
        if (!ok) { ShowError(err); return false; }
        return true;
    }

    // ---- הדבקת תמונה (Ctrl+V) ----

    string? _pastedImage;

    /// <summary>מחבר טיפול בהדבקה לתיבת ההקלדה: תמונה מהלוח (או קובץ תמונה שהועתק) הופכת לתמונה ממתינה לשליחה.</summary>
    void HookPaste()
    {
        DataObject.AddPastingHandler(Input, (s, e) =>
        {
            var data = e.DataObject;
            string? path = null;
            if (data.GetDataPresent(DataFormats.FileDrop) && data.GetData(DataFormats.FileDrop) is string[] files)
                path = files.FirstOrDefault(IsImage);
            else if (data.GetDataPresent(DataFormats.Bitmap) || Clipboard.ContainsImage())
                path = SaveClipboardImage();
            if (path == null) { e.FormatToApply = DataFormats.UnicodeText; return; } // טקסט רגיל: הדבקה בלי עיצוב
            e.CancelCommand();
            SetPendingImage(path);
        });
    }

    string? SaveClipboardImage()
    {
        try
        {
            var img = Clipboard.GetImage();
            if (img == null) return null;
            System.IO.Directory.CreateDirectory(ChatLog.ImageDir);
            var path = System.IO.Path.Combine(ChatLog.ImageDir, $"pasted-{DateTime.Now:yyyyMMdd-HHmmss}.png");
            var enc = new System.Windows.Media.Imaging.PngBitmapEncoder();
            enc.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(img));
            using (var fs = System.IO.File.Create(path)) enc.Save(fs);
            return path;
        }
        catch { return null; }
    }

    void SetPendingImage(string path)
    {
        _pastedImage = path;
        try
        {
            var bmp = new System.Windows.Media.Imaging.BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
            bmp.DecodePixelHeight = 180;
            bmp.UriSource = new Uri(path);
            bmp.EndInit();
            PendingImg.Source = bmp;
        }
        catch { }
        PendingPanel.Visibility = Visibility.Visible;
        Input_TextChanged(this, null!);
        Input.Focus();
    }

    void ClearPending()
    {
        _pastedImage = null;
        PendingImg.Source = null;
        PendingPanel.Visibility = Visibility.Collapsed;
        Input_TextChanged(this, null!);
    }

    void RemovePending_Click(object s, RoutedEventArgs e) => ClearPending();
    async void Action_Click(object s, RoutedEventArgs e)
    {
        if (_recording) { await EndRecordingAsync(send: true); return; }
        if (!InputIsEmpty || _pastedImage != null) { SendText(); return; }
        await StartRecordingAsync();
    }

    // ---- הודעות קוליות ----

    async void Pause_Click(object s, RoutedEventArgs e)
    {
        if (!_recording) return;
        if (_voice.IsPaused) { await _voice.ResumeAsync(); PauseBtn.Content = "\uE769"; PauseBtn.ToolTip = "השהה את ההקלטה"; }
        else { await _voice.PauseAsync(); PauseBtn.Content = "\uE768"; PauseBtn.ToolTip = "המשך הקלטה"; }
    }

    async Task StartRecordingAsync()
    {
        if (_cur == null) return;
        StopPlayback();
        var err = await _voice.StartAsync();
        if (err != null) { ShowError(err); return; }
        _recording = true;
        Input.Visibility = Visibility.Hidden;
        RecordLabel.Visibility = Visibility.Visible;
        RecordLabel.Text = "● מקליט  0:00   (כפתור ההשהיה: השהה, כפתור השליחה: שלח)";
        PauseBtn.Content = "\uE769"; PauseBtn.ToolTip = "השהה את ההקלטה"; PauseBtn.Visibility = Visibility.Visible;
        AttachBtn.Content = "\uE74D"; ActionBtn.Content = "\uE724"; ActionBtn.ToolTip = "שלח הודעה קולית";
        _recTimer.Start();
    }

    async Task EndRecordingAsync(bool send)
    {
        var conv = _cur;
        _recTimer.Stop(); _recording = false;
        PauseBtn.Visibility = Visibility.Collapsed;
        RecordLabel.Visibility = Visibility.Collapsed;
        Input.Visibility = Visibility.Visible;
        AttachBtn.Content = "\uE723";
        Input_TextChanged(this, null!);
        if (!send || conv == null) { await _voice.CancelAsync(); return; }
        var path = await _voice.StopAsync();
        if (path == null) { ShowError("ההקלטה קצרה מדי"); return; }
        var name = System.IO.Path.GetFileName(path);
        long size = new System.IO.FileInfo(path).Length;
        var entry = new ChatEntry { Mine = true, Kind = "voice", Text = name, Size = size, Path = path, Device = conv.Name };
        conv.Messages.Add(ToMsg(entry));
        Update(conv);
        bool ok = false; string err = "";
        try
        {
            var peer = await PeerConnections.GetAsync(conv.Address);
            if (peer == null) err = "האפליקציה לא פתוחה בטלפון";
            else
            {
                await using var fs = new System.IO.FileStream(path, System.IO.FileMode.Open, System.IO.FileAccess.Read, System.IO.FileShare.Read, 64 * 1024, true);
                var r = await peer.SendFileAsync(fs, name, size, null, System.Threading.CancellationToken.None, fromChat: true);
                ok = r == PeerSendResult.Ok;
                if (!ok) err = "השליחה נכשלה";
            }
        }
        catch (Exception ex) { err = ex.Message; }
        entry.Status = ok ? "delivered" : "failed";
        ChatLog.Add(conv.Address, entry);
        Reload(conv);
        Update(conv);
        if (!ok) ShowError(err);
    }

    void StopPlayback()
    {
        _player.Stop(); _playingPath = null;
    }
}