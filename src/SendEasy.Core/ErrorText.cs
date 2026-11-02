using System.Net.Http;
using System.Net.Sockets;

namespace SendEasy.Core;

/// <summary>הודעות שגיאה בעברית למשתמש (במקום הטקסט האנגלי של .NET).</summary>
public static class ErrorText
{
    public static string Describe(Exception ex)
    {
        for (var e = ex; e != null; e = e.InnerException)
        {
            switch (e)
            {
                case TaskCanceledException or TimeoutException:
                    return "הפעולה ארכה זמן רב מדי. בדוק את חיבור האינטרנט ונסה שוב.";
                case HttpRequestException h:
                    return h.StatusCode switch
                    {
                        System.Net.HttpStatusCode.NotFound => "הקובץ לא נמצא בשרת.",
                        System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.TooManyRequests
                            => "השרת חסם את הבקשה זמנית. נסה שוב בעוד כמה דקות.",
                        null => "אין חיבור לשרת. בדוק את חיבור האינטרנט (או את המסנן) ונסה שוב.",
                        _ => "השרת החזיר שגיאה. נסה שוב מאוחר יותר.",
                    };
                case SocketException:
                    return "אין חיבור לרשת. בדוק את החיבור ונסה שוב.";
                case UnauthorizedAccessException:
                    return "אין הרשאה לגשת לקובץ או לתיקייה.";
                case DirectoryNotFoundException or FileNotFoundException:
                    return "הקובץ או התיקייה לא נמצאו.";
                case PathTooLongException:
                    return "נתיב הקובץ ארוך מדי.";
                case IOException io when io.HResult == unchecked((int)0x80070070):
                    return "אין מספיק מקום פנוי בדיסק.";
                case IOException:
                    return "שגיאת קריאה או כתיבה בקובץ. ייתכן שהקובץ בשימוש בתוכנה אחרת.";
                case System.Text.Json.JsonException:
                    return "התקבלה תשובה לא תקינה מהשרת.";
            }
        }
        // הודעה שכבר בעברית (שלנו) נשארת כמו שהיא; אחרת הודעה כללית
        var m = ex.Message;
        return System.Text.RegularExpressions.Regex.IsMatch(m, "[\u0590-\u05FF]") ? m : "אירעה שגיאה לא צפויה.";
    }
}
