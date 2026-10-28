using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using SendEasy.Core;

namespace SendEasy.App;

enum MsgKind { Text, Voice, File }
enum MsgStatus { Sent, Delivered, Read }

sealed record Msg(bool Mine, string Sender, DateTime Time, MsgKind Kind, string Text = "", int Seconds = 0,
    long Size = 0, MsgStatus Status = MsgStatus.Read);

sealed class Conv
{
    public required string Name { get; init; }
    public required string Kind { get; init; }
    public bool Online { get; init; }
    public Color Color { get; init; }
    public List<Msg> Messages { get; } = new();
}

/// <summary>חלון הצ'אט. כרגע הדגמת עיצוב בלבד (ללא חיבור בלוטוס): שני סגנונות, ווטסאפ וגוגל צ'אט.</summary>
public partial class ChatWindow : Wpf.Ui.Controls.FluentWindow
{
    sealed record Palette(string ChatBg, string Header, string Side, string Mine, string Theirs, string Text,
        string Sub, string Accent, string Input, string Composer, string Border);

    static readonly Palette WaLight = new("#EFE7DE", "#F0F2F5", "#FFFFFF", "#D9FDD3", "#FFFFFF", "#111B21", "#667781", "#00A884", "#FFFFFF", "#F0F2F5", "#E9EDEF");
    static readonly Palette WaDark = new("#0B141A", "#202C33", "#111B21", "#005C4B", "#202C33", "#E9EDEF", "#8696A0", "#00A884", "#2A3942", "#202C33", "#222D34");
    static readonly Palette GcLight = new("#FFFFFF", "#FFFFFF", "#F8FAFD", "#FFFFFF", "#FFFFFF", "#1F1F1F", "#5F6368", "#0B57D0", "#F0F4F9", "#FFFFFF", "#E3E3E3");
    static readonly Palette GcDark = new("#1F1F1F", "#1F1F1F", "#282A2C", "#1F1F1F", "#1F1F1F", "#E3E3E3", "#9AA0A6", "#A8C7FA", "#2D2F31", "#1F1F1F", "#3C4043");

    readonly Settings _settings;
    readonly List<Conv> _convs = new();
    Conv _cur = null!;
    bool _wa = true;
    Palette _p = WaLight;
    bool _loading = true;

    // הקלטה (הדגמה)
    bool _recording;
    int _recSeconds;
    readonly DispatcherTimer _recTimer = new() { Interval = TimeSpan.FromSeconds(1) };

    public ChatWindow(Settings settings)
    {
        _settings = settings;
        InitializeComponent();
        BuildDemoData();
        _wa = _settings.ChatStyle == 0;
        (_wa ? StyleWa : StyleGc).IsChecked = true;
        _recTimer.Tick += (_, _) => { _recSeconds++; RecordLabel.Text = $"● מקליט  {Fmt(_recSeconds)}"; };
        _loading = false;
        ConvList.ItemContainerStyle = ConvItemStyle();
        ApplyStyle();
        ConvList.SelectedIndex = 0;
    }

    static string Fmt(int s) => $"{s / 60}:{s % 60:00}";
    static SolidColorBrush B(string hex) => new((Color)ColorConverter.ConvertFromString(hex));
    static bool IsDark() => Wpf.Ui.Appearance.ApplicationThemeManager.GetAppTheme() == Wpf.Ui.Appearance.ApplicationTheme.Dark;

    // ---- נתוני הדגמה ----

    void BuildDemoData()
    {
        var now = DateTime.Today.AddHours(9);
        var a = new Conv { Name = "Qin 1s+", Kind = "טלפון", Online = true, Color = Color.FromRgb(0x00, 0x96, 0x88) };
        a.Messages.AddRange(new[]
        {
            new Msg(false, "Qin 1s+", now.AddMinutes(12), MsgKind.Text, "בוקר טוב! שלחתי לך את התמונות מאתמול"),
            new Msg(true, "אני", now.AddMinutes(14), MsgKind.Text, "קיבלתי, תודה. הגיעו כולן?"),
            new Msg(false, "Qin 1s+", now.AddMinutes(15), MsgKind.File, "חתונה-תמונות.zip", Size: 48_300_000),
            new Msg(false, "Qin 1s+", now.AddMinutes(17), MsgKind.Voice, Seconds: 14),
            new Msg(true, "אני", now.AddMinutes(20), MsgKind.Text, "מעולה, אני מסדר אותן ואשלח לך חזרה את הנבחרות", Status: MsgStatus.Read),
            new Msg(true, "אני", now.AddMinutes(21), MsgKind.Voice, Seconds: 7, Status: MsgStatus.Delivered),
        });
        var b = new Conv { Name = "ה- S22 Ultra של יהודה", Kind = "טלפון", Online = false, Color = Color.FromRgb(0x5E, 0x35, 0xB1) };
        b.Messages.Add(new Msg(false, "יהודה", now.AddHours(-20), MsgKind.Text, "תזכיר לי לשלוח לך את המסמך"));
        var c = new Conv { Name = "LAPTOP-R9KQ5DGB", Kind = "מחשב", Online = false, Color = Color.FromRgb(0xE6, 0x51, 0x00) };
        c.Messages.Add(new Msg(true, "אני", now.AddDays(-2), MsgKind.Text, "נבדוק שוב מחר", Status: MsgStatus.Sent));
        _convs.AddRange(new[] { a, b, c });
    }

    // ---- עיצוב ----

    void Style_Checked(object s, RoutedEventArgs e)
    {
        if (_loading) return;
        _wa = StyleWa.IsChecked == true;
        _settings.ChatStyle = _wa ? 0 : 1;
        try { _settings.Save(); } catch { }
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
        StyleWa.Foreground = StyleGc.Foreground = B(_p.Text);
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
            top.Children.Add(new TextBlock { Text = c.Name, FontWeight = FontWeights.SemiBold, Foreground = B(_p.Text), TextTrimming = TextTrimming.CharacterEllipsis });
            var tm = new TextBlock { Text = last == null ? "" : last.Time.ToString("HH:mm"), FontSize = 11, Foreground = B(_p.Sub) };
            Grid.SetColumn(tm, 1); top.Children.Add(tm);
            sp.Children.Add(top);
            sp.Children.Add(new TextBlock
            {
                Text = last == null ? "" : (last.Mine ? "את/ה: " : "") + Preview(last),
                FontSize = 12.5, Foreground = B(_p.Sub), TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 2, 0, 0),
            });
            g.Children.Add(sp);
            ConvList.Items.Add(new ListBoxItem { Content = g, Tag = c, HorizontalContentAlignment = HorizontalAlignment.Stretch });
        }
        if (sel >= 0 && sel < ConvList.Items.Count) ConvList.SelectedIndex = sel;
    }

    static string Preview(Msg m) => m.Kind switch
    {
        MsgKind.Voice => "🎤 הודעה קולית " + Fmt(m.Seconds),
        MsgKind.File => "📄 " + m.Text,
        _ => m.Text,
    };

    void ConvList_SelectionChanged(object s, SelectionChangedEventArgs e)
    {
        if (ConvList.SelectedItem is ListBoxItem { Tag: Conv c }) { _cur = c; ShowHeader(); RenderMessages(); }
    }

    void ShowHeader()
    {
        HeaderName.Text = _cur.Name;
        HeaderStatus.Text = (_cur.Online ? "מחובר בבלוטוס" : "לא בטווח") + "  ·  הדגמת עיצוב: ההודעות עדיין לא נשלחות";
        HeaderInitial.Text = _cur.Name.TrimStart('ה', '-', ' ').Substring(0, 1).ToUpperInvariant();
        HeaderAvatar.Background = new SolidColorBrush(_cur.Color);
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

    string Ticks(Msg m) => m.Status == MsgStatus.Sent ? "✓" : "✓✓";
    Brush TickBrush(Msg m) => m.Status == MsgStatus.Read ? B("#53BDEB") : B(_p.Sub);

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
                return new TextBlock
                {
                    Text = m.Text, TextWrapping = TextWrapping.Wrap, FontSize = 14.5, Foreground = B(_p.Text),
                    Margin = new Thickness(0, style == 1 ? 2 : 0, 0, 0),
                };
        }
    }

    FrameworkElement FileBubble(Msg m, int style)
    {
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

        // הדגמת ניגון: הצבעת העמודות לאורך זמן
        DispatcherTimer? t = null;
        play.MouseLeftButtonUp += (_, _) =>
        {
            if (t != null) { t.Stop(); t = null; Reset(); return; }
            ((TextBlock)play.Child).Text = "⏸";
            int i = 0;
            t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(Math.Max(60, m.Seconds * 1000 / bars.Count)) };
            t.Tick += (_, _) =>
            {
                if (i < bars.Count) { bars[i].Fill = B(_p.Accent); bars[i].Opacity = 1; i++; }
                else { t!.Stop(); t = null; Reset(); }
            };
            t.Start();
        };
        void Reset()
        {
            ((TextBlock)play.Child).Text = "▶";
            foreach (var b in bars) { b.Fill = B(_p.Sub); b.Opacity = 0.55; }
        }
        if (style == 1)
            return new Border
            {
                Background = B(_p.Input), CornerRadius = new CornerRadius(22), Padding = new Thickness(8), Margin = new Thickness(0, 4, 0, 0),
                HorizontalAlignment = HorizontalAlignment.Left, Child = line,
            };
        return line;
    }

    // ---- שליחה (הדגמה) ----

    void Input_TextChanged(object s, TextChangedEventArgs e)
    {
        if (_recording) return;
        ActionBtn.Content = string.IsNullOrWhiteSpace(Input.Text) ? "🎤" : "➤";
        ActionBtn.ToolTip = string.IsNullOrWhiteSpace(Input.Text) ? "הקלטת הודעה קולית" : "שלח";
    }

    void Input_PreviewKeyDown(object s, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && !Keyboard.IsKeyDown(Key.LeftShift) && !Keyboard.IsKeyDown(Key.RightShift))
        {
            e.Handled = true;
            SendText();
        }
    }

    void SendText()
    {
        var text = Input.Text.Trim();
        if (text.Length == 0) return;
        AddMine(new Msg(true, "אני", DateTime.Now, MsgKind.Text, text, Status: MsgStatus.Sent));
        Input.Clear();
    }

    void AddMine(Msg m)
    {
        _cur.Messages.Add(m);
        RenderMessages();
        RebuildConvList();
        // הדגמת אישורי קבלה
        var idx = _cur.Messages.Count - 1;
        var conv = _cur;
        var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(900) };
        t.Tick += (_, _) =>
        {
            t.Stop();
            if (idx < conv.Messages.Count) { conv.Messages[idx] = conv.Messages[idx] with { Status = MsgStatus.Delivered }; if (conv == _cur) RenderMessages(); }
        };
        t.Start();
    }

    void Action_Click(object s, RoutedEventArgs e)
    {
        if (_recording) { StopRecording(send: true); return; }
        if (!string.IsNullOrWhiteSpace(Input.Text)) { SendText(); return; }
        _recording = true; _recSeconds = 0;
        Input.Visibility = Visibility.Hidden;
        RecordLabel.Visibility = Visibility.Visible;
        RecordLabel.Text = "● מקליט  0:00";
        AttachBtn.Content = "🗑"; ActionBtn.Content = "➤";
        _recTimer.Start();
    }

    void Attach_Click(object s, RoutedEventArgs e)
    {
        if (_recording) { StopRecording(send: false); return; }
        var d = new Microsoft.Win32.OpenFileDialog { Title = "בחר קובץ לשליחה" };
        if (d.ShowDialog(this) == true)
        {
            long size = 0;
            try { size = new System.IO.FileInfo(d.FileName).Length; } catch { }
            AddMine(new Msg(true, "אני", DateTime.Now, MsgKind.File, System.IO.Path.GetFileName(d.FileName), Size: size, Status: MsgStatus.Sent));
        }
    }

    void StopRecording(bool send)
    {
        _recTimer.Stop(); _recording = false;
        RecordLabel.Visibility = Visibility.Collapsed;
        Input.Visibility = Visibility.Visible;
        AttachBtn.Content = "📎";
        Input_TextChanged(this, null!);
        if (send && _recSeconds > 0)
            AddMine(new Msg(true, "אני", DateTime.Now, MsgKind.Voice, Seconds: _recSeconds, Status: MsgStatus.Sent));
    }
}
