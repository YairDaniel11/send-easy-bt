using System;
using System.Diagnostics;
using System.Windows;
using System.Windows.Navigation;

namespace SendEasy.App;

/// <summary>אודות: גרסה, תיאור קצר וקישור למאגר ב-GitHub.</summary>
public partial class AboutWindow : Wpf.Ui.Controls.FluentWindow
{
    public AboutWindow()
    {
        InitializeComponent();
        var v = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0);
        VersionText.Text = "גרסה " + TrayApp.Ltr(v.ToString(3));
        try
        {
            var icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!);
            if (icon != null)
                Logo.Source = System.Windows.Interop.Imaging.CreateBitmapSourceFromHIcon(icon.Handle, Int32Rect.Empty,
                    System.Windows.Media.Imaging.BitmapSizeOptions.FromWidthAndHeight(64, 64));
        }
        catch { }
    }

    void Repo_Navigate(object s, RequestNavigateEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute = true }); } catch { }
        e.Handled = true;
    }

    void Close_Click(object s, RoutedEventArgs e) => Close();
}