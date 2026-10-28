using System.Text;
using Windows.Storage.Streams;

namespace SendEasy.Core;

/// <summary>כותרות OBEX שאנחנו מבינים.</summary>
internal sealed class ObexHeaders
{
    public uint? ConnectionId;
    public string? Name;
    public string? Type;
    public long? Length;
    public byte[]? Body;
}

internal static class ObexIo
{
    public const byte Continue = 0x90, Ok = 0xA0, BadRequest = 0xC0, Forbidden = 0xC3,
        UnsupportedType = 0xCF, NotImplemented = 0xD0;

    public static async Task<byte[]> ReadPacketAsync(DataReader r)
    {
        if (await r.LoadAsync(3) < 3) throw new IOException("החיבור נסגר");
        byte op = r.ReadByte();
        int len = (r.ReadByte() << 8) | r.ReadByte();
        if (len < 3) throw new IOException("חבילת OBEX לא תקינה");
        var packet = new byte[len];
        packet[0] = op; packet[1] = (byte)(len >> 8); packet[2] = (byte)len;
        if (len > 3)
        {
            if (await r.LoadAsync((uint)(len - 3)) < len - 3) throw new IOException("החיבור נסגר באמצע חבילה");
            var rest = new byte[len - 3];
            r.ReadBytes(rest);
            Array.Copy(rest, 0, packet, 3, rest.Length);
        }
        return packet;
    }

    public static async Task WriteAsync(DataWriter w, byte code, byte[]? payload = null)
    {
        payload ??= Array.Empty<byte>();
        int len = 3 + payload.Length;
        w.WriteByte(code); w.WriteByte((byte)(len >> 8)); w.WriteByte((byte)len);
        w.WriteBytes(payload);
        await w.StoreAsync();
    }

    public static ObexHeaders ParseHeaders(byte[] p, int start)
    {
        var h = new ObexHeaders();
        int i = start;
        while (i < p.Length)
        {
            byte id = p[i];
            switch (id >> 6)
            {
                case 0: // טקסט Unicode, אורך ב-2 בתים
                case 1: // רצף בתים, אורך ב-2 בתים
                {
                    int hl = (p[i + 1] << 8) | p[i + 2];
                    if (hl < 3 || i + hl > p.Length) throw new IOException("כותרת OBEX לא תקינה");
                    var data = p[(i + 3)..(i + hl)];
                    if (id == 0x01) h.Name = Encoding.BigEndianUnicode.GetString(data).TrimEnd('\0');
                    else if (id == 0x42) h.Type = Encoding.ASCII.GetString(data).TrimEnd('\0');
                    else if (id == 0x48 || id == 0x49) h.Body = data;
                    i += hl;
                    break;
                }
                case 2: i += 2; break; // ערך של בית אחד: id + ערך
                default:
                    if (id == 0xC3) h.Length = ((long)p[i + 1] << 24) | ((long)p[i + 2] << 16) | ((long)p[i + 3] << 8) | p[i + 4];
                    else if (id == 0xCB) h.ConnectionId = ((uint)p[i + 1] << 24) | ((uint)p[i + 2] << 16) | ((uint)p[i + 3] << 8) | p[i + 4];
                    i += 5;
                    break;
            }
        }
        return h;
    }
}
