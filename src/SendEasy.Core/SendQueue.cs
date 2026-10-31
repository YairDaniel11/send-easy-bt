namespace SendEasy.Core;

/// <summary>תור שליחה גלובלי: שליחה אחת בכל רגע. בקשות נוספות ממתינות וישלחו מיד אחרי שהקודמת תסתיים.</summary>
public static class SendQueue
{
    static readonly SemaphoreSlim Gate = new(1, 1);
    static int _waiting;

    /// <summary>ממתין לתור (ומדווח למשתמש אם צריך להמתין). יש לשחרר את התוצאה בסיום השליחה.</summary>
    public static async Task<IDisposable> EnterAsync(Action<string> status, CancellationToken ct)
    {
        if (!await Gate.WaitAsync(0, ct))
        {
            int ahead = Interlocked.Increment(ref _waiting);
            try
            {
                status(ahead > 1
                    ? $"מתבצעת שליחה אחרת. הקובץ הזה בתור ({ahead} ממתינים) וישלח אוטומטית מיד כשיגיע תורו..."
                    : "מתבצעת שליחה אחרת. הקובץ הזה נוסף לרשימת ההמתנה וישלח אוטומטית מיד אחרי שהיא תסתיים...");
                await Gate.WaitAsync(ct);
            }
            finally { Interlocked.Decrement(ref _waiting); }
        }
        return new Releaser();
    }

    sealed class Releaser : IDisposable
    {
        int _done;
        public void Dispose() { if (Interlocked.Exchange(ref _done, 1) == 0) Gate.Release(); }
    }
}
