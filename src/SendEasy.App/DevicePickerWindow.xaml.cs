using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using SendEasy.Core;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

namespace SendEasy.App;

/// <summary>בחירת מכשיר מצומד לפתיחת צ'אט (גם אם כבר יש שיחה איתו).</summary>
public partial class DevicePickerWindow : Wpf.Ui.Controls.FluentWindow
{
    public sealed record Item(ulong Address, string Name, string Sub);

    readonly Settings _settings;
    public Item? Chosen { get; private set; }

    public DevicePickerWindow(Settings settings)
    {
        _settings = settings;
        InitializeComponent();
        Status.Text = "טוען מכשירים מצומדים...";
        Loaded += async (_, _) => await LoadAsync();
    }

    async System.Threading.Tasks.Task LoadAsync()
    {
        var items = new List<Item>();
        try
        {
            var infos = await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelectorFromPairingState(true));
            foreach (var i in infos)
            {
                using var dev = await BluetoothDevice.FromIdAsync(i.Id);
                if (dev == null || dev.BluetoothAddress == 0) continue;
                var name = _settings.DisplayName(dev.BluetoothAddress, string.IsNullOrWhiteSpace(dev.Name) ? "מכשיר ללא שם" : dev.Name);
                var kind = dev.ClassOfDevice.MajorClass == BluetoothMajorClass.Computer ? "מחשב"
                         : dev.ClassOfDevice.MajorClass == BluetoothMajorClass.Phone ? "טלפון" : "מכשיר";
                items.Add(new Item(dev.BluetoothAddress, name, kind + (ChatLog.Load(dev.BluetoothAddress, 1).Count > 0 ? " · יש שיחה" : "")));
            }
        }
        catch (Exception ex) { Status.Text = "לא ניתן לטעון מכשירים: " + ex.Message; return; }
        List.ItemsSource = items.OrderBy(x => x.Name).ToList();
        Status.Visibility = items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (items.Count == 0) Status.Text = "אין מכשירים מצומדים. צמד מכשיר דרך חלון השליחה.";
    }

    void List_SelectionChanged(object s, SelectionChangedEventArgs e) => OkBtn.IsEnabled = List.SelectedItem != null;
    void List_DoubleClick(object s, MouseButtonEventArgs e) { if (List.SelectedItem != null) Ok_Click(s, e); }

    void Ok_Click(object s, RoutedEventArgs e)
    {
        Chosen = List.SelectedItem as Item;
        if (Chosen != null) DialogResult = true;
    }

    void Cancel_Click(object s, RoutedEventArgs e) => DialogResult = false;
}