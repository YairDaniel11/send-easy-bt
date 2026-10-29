using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using System.Windows.Shapes;

namespace SendEasy.App;

/// <summary>
/// חמישה אייקוני רגש בצ'אט. בפרוטוקול ובהיסטוריה הם נשמרים כקוד טקסט (תאימות לגרסאות ישנות ולאפליקציה בטלפון):
/// :) שמח, :( עצוב, :+1: לייק, :-1: דיסלייק, :pray: תודה. בהצגה הקודים מוחלפים באייקון מצויר.
/// </summary>
static class Emoticons
{
    public enum Kind { Happy, Sad, Like, Dislike, Thanks }

    public sealed record Item(string Code, string Glyph, string Name, Kind Kind);

    public static readonly Item[] All =
    {
        new(":)", "\U0001F642", "שמח", Kind.Happy),
        new(":(", "\U0001F61E", "עצוב", Kind.Sad),
        new(":+1:", "\U0001F44D", "לייק", Kind.Like),
        new(":-1:", "\U0001F44E", "דיסלייק", Kind.Dislike),
        new(":pray:", "\U0001F64F", "תודה", Kind.Thanks),
    };

    static readonly Regex CodeRx = new(@":\)|:\(|:\+1:|:-1:|:pray:", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    static Item? ByCode(string code) => All.FirstOrDefault(i => i.Code.Equals(code, StringComparison.OrdinalIgnoreCase));

    /// <summary>ממלא TextBlock בטקסט, כשכל קוד מוחלף באייקון מצויר בגודל הגופן.</summary>
    public static void Fill(TextBlock tb, string text, double fontSize)
    {
        tb.Inlines.Clear();
        int pos = 0;
        foreach (Match m in CodeRx.Matches(text))
        {
            if (IsInsideUrl(text, m.Index)) continue;
            if (m.Index > pos) tb.Inlines.Add(new Run(text[pos..m.Index]));
            var item = ByCode(m.Value);
            if (item == null) { tb.Inlines.Add(new Run(m.Value)); }
            else
            {
                var icon = Icon(item.Kind, fontSize * 1.35);
                icon.Margin = new Thickness(1, 0, 1, -fontSize * 0.25);
                tb.Inlines.Add(new InlineUIContainer(icon) { BaselineAlignment = BaselineAlignment.Center });
            }
            pos = m.Index + m.Length;
        }
        if (pos < text.Length) tb.Inlines.Add(new Run(text[pos..]));
    }

    static bool IsInsideUrl(string text, int index)
    {
        int ws = text.LastIndexOfAny(new[] { ' ', '\n', '\t' }, Math.Max(0, index - 1));
        var word = text[(ws < 0 ? 0 : ws + 1)..index];
        return word.Contains("://");
    }

    /// <summary>קודים → תווי אימוג'י (להצגה בתיבת ההקלדה ובתצוגות מקדימות, שהן טקסט רגיל).</summary>
    public static string ToGlyphs(string text) =>
        CodeRx.Replace(text, m => ByCode(m.Value)?.Glyph ?? m.Value);

    /// <summary>תווי אימוג'י → קודים (לפני השליחה).</summary>
    public static string ToCodes(string text)
    {
        foreach (var i in All) text = text.Replace(i.Glyph, i.Code);
        return text;
    }

    public static bool HasCode(string text) => CodeRx.IsMatch(text);

    // ---- תמונות (Noto Emoji, Apache 2.0) ----

    static readonly Dictionary<Kind, System.Windows.Media.Imaging.BitmapImage> Cache = new();

    static System.Windows.Media.Imaging.BitmapImage Bitmap(Kind kind)
    {
        if (Cache.TryGetValue(kind, out var b)) return b;
        b = new System.Windows.Media.Imaging.BitmapImage(new Uri($"pack://application:,,,/Assets/emoji/{kind.ToString().ToLowerInvariant()}.png"));
        b.Freeze();
        return Cache[kind] = b;
    }

    /// <summary>אייקון האימוג'י בגודל הנתון (פיקסלים לוגיים).</summary>
    public static FrameworkElement Icon(Kind kind, double size)
    {
        var img = new Image { Source = Bitmap(kind), Width = size, Height = size, Stretch = Stretch.Uniform };
        RenderOptions.SetBitmapScalingMode(img, BitmapScalingMode.HighQuality);
        return img;
    }

    /// <summary>מפצל טקסט לחלקי טקסט וקודים (לתיבת ההקלדה). Item != null = קוד אייקון.</summary>
    public static List<(string Text, Item? Item)> Split(string text)
    {
        var res = new List<(string, Item?)>();
        int pos = 0;
        foreach (Match m in CodeRx.Matches(text))
        {
            if (IsInsideUrl(text, m.Index)) continue;
            var item = ByCode(m.Value);
            if (item == null) continue;
            if (m.Index > pos) res.Add((text[pos..m.Index], null));
            res.Add((m.Value, item));
            pos = m.Index + m.Length;
        }
        if (pos < text.Length) res.Add((text[pos..], null));
        return res;
    }
    /// <summary>תפריט בחירת אייקון: מפעיל את onPick עם הקוד שנבחר.</summary>
    public static ContextMenu BuildMenu(Action<Item> onPick)
    {
        var menu = new ContextMenu { FlowDirection = FlowDirection.RightToLeft };
        foreach (var item in All)
        {
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            sp.Children.Add(Icon(item.Kind, 24));
            sp.Children.Add(new TextBlock { Text = "  " + item.Name + "  ", FontFamily = new FontFamily("Segoe UI"), FontSize = 14, VerticalAlignment = VerticalAlignment.Center });
            sp.Children.Add(new TextBlock { Text = item.Code, FlowDirection = FlowDirection.LeftToRight, FontFamily = new FontFamily("Segoe UI"), FontSize = 13, Opacity = 0.55, VerticalAlignment = VerticalAlignment.Center });
            var mi = new MenuItem { Header = sp };
            var it = item;
            mi.Click += (_, _) => onPick(it);
            menu.Items.Add(mi);
        }
        return menu;
    }
}