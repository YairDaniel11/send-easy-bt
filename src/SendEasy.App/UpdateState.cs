using System;
using SendEasy.Core;

namespace SendEasy.App;

/// <summary>גרסה חדשה שנמצאה. נשמרת כאן כדי שכל החלונות (שליחה, צ'אט, מגש) יוכלו להציג הודעה ולפתוח את חלון העדכון.</summary>
static class UpdateState
{
    public static UpdateInfo? Available { get; private set; }
    public static event Action? Changed;

    public static void Set(UpdateInfo? info)
    {
        Available = info;
        Changed?.Invoke();
    }
}