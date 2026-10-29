using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace SendEasy.App;

/// <summary>צפייה בתמונה במסך מלא: גלגלת העכבר = זום, לחיצה כפולה = התאמה/גודל מלא, גרירה = הזזה, Esc = סגירה.</summary>
sealed class ImageViewWindow : Window
{
    readonly Image _img = new();
    readonly ScrollViewer _sv = new() { HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    readonly ScaleTransform _scale = new(1, 1);
    bool _fit = true;
    Point _drag;

    public ImageViewWindow(string path, Window? owner)
    {
        Title = System.IO.Path.GetFileName(path);
        Owner = owner;
        Width = 900; Height = 700;
        WindowStartupLocation = owner == null ? WindowStartupLocation.CenterScreen : WindowStartupLocation.CenterOwner;
        Background = new SolidColorBrush(Color.FromRgb(0x14, 0x14, 0x14));
        FlowDirection = FlowDirection.LeftToRight;

        var bmp = new BitmapImage();
        bmp.BeginInit();
        bmp.CacheOption = BitmapCacheOption.OnLoad;
        bmp.UriSource = new Uri(path);
        bmp.EndInit();
        _img.Source = bmp;
        _img.LayoutTransform = _scale;
        _img.Stretch = Stretch.Uniform;
        _img.HorizontalAlignment = HorizontalAlignment.Center;
        _img.VerticalAlignment = VerticalAlignment.Center;
        _sv.Content = _img;
        Content = _sv;

        PreviewMouseWheel += (_, e) => { Zoom(e.Delta > 0 ? 1.15 : 1 / 1.15); e.Handled = true; };
        MouseLeftButtonDown += (_, e) =>
        {
            if (e.ClickCount == 2) { ToggleFit(); return; }
            _drag = e.GetPosition(this); CaptureMouse();
        };
        MouseMove += (_, e) =>
        {
            if (!IsMouseCaptured) return;
            var p = e.GetPosition(this);
            _sv.ScrollToHorizontalOffset(_sv.HorizontalOffset - (p.X - _drag.X));
            _sv.ScrollToVerticalOffset(_sv.VerticalOffset - (p.Y - _drag.Y));
            _drag = p;
        };
        MouseLeftButtonUp += (_, _) => ReleaseMouseCapture();
        KeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape) Close();
            else if (e.Key is Key.Add or Key.OemPlus) Zoom(1.25);
            else if (e.Key is Key.Subtract or Key.OemMinus) Zoom(0.8);
            else if (e.Key == Key.Space) ToggleFit();
        };
    }

    void Zoom(double f)
    {
        if (_fit) { _fit = false; _img.Stretch = Stretch.None; _scale.ScaleX = _scale.ScaleY = 1; }
        _scale.ScaleX = _scale.ScaleY = Math.Clamp(_scale.ScaleX * f, 0.1, 8);
    }

    void ToggleFit()
    {
        _fit = !_fit;
        _img.Stretch = _fit ? Stretch.Uniform : Stretch.None;
        _scale.ScaleX = _scale.ScaleY = 1;
    }
}