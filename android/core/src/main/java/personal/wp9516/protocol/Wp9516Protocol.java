package personal.wp9516.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure Java codec for the captured WP9516 transport type 1. No Android APIs. */
public final class Wp9516Protocol {
    public static final String SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb";

    private Wp9516Protocol() {}

    public static byte[] frame(byte[] payload) {
        if (payload.length < 1 || payload.length > 255) {
            throw new IllegalArgumentException("Payload length must be 1..255");
        }
        byte[] result = new byte[payload.length + 5];
        result[0] = result[1] = (byte) 0xaa;
        result[2] = (byte) payload.length;
        System.arraycopy(payload, 0, result, 3, payload.length);
        result[result.length - 2] = result[result.length - 1] = 0x55;
        return result;
    }

    public static byte[] queryInfo() { return frame(new byte[] {1}); }
    public static byte[] queryReady() { return frame(new byte[] {(byte) 0xac}); }
    public static byte[] stop() { return frame(new byte[] {4}); }

    public static int u16(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }

    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private static void byteRange(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("Expected unsigned byte");
    }

    public static byte[] setup(int totalRows, int resumeRow, int token, int paperType, int density) {
        if (totalRows < 0 || totalRows > 65535 || resumeRow < 0 || resumeRow > totalRows) {
            throw new IllegalArgumentException("Invalid row range");
        }
        byteRange(token); byteRange(paperType); byteRange(density);
        byte[] payload = new byte[8];
        payload[0] = 2;
        put16(payload, 1, totalRows);
        put16(payload, 3, resumeRow);
        payload[5] = (byte) token;
        payload[6] = (byte) paperType;
        payload[7] = (byte) density;
        return frame(payload);
    }

    public static byte[] raster(int row, int width, int count, byte[] packedBlackBits) {
        if (row < 0 || row > 65535 || width <= 0 || width % 8 != 0 || count < 1 || count > 255
                || packedBlackBits.length != (long) (width / 8) * count) {
            throw new IllegalArgumentException("Invalid raster shape or row");
        }
        byte[] payload = new byte[packedBlackBits.length + 4];
        payload[0] = 3;
        put16(payload, 1, row);
        payload[3] = (byte) count;
        System.arraycopy(packedBlackBits, 0, payload, 4, packedBlackBits.length);
        return frame(payload);
    }

    /** One decoder per receive stream. Returns payloads without frame markers. */
    public static final class Decoder {
        private byte[] pending = new byte[0];

        public List<byte[]> accept(byte[] chunk) {
            byte[] all = Arrays.copyOf(pending, pending.length + chunk.length);
            System.arraycopy(chunk, 0, all, pending.length, chunk.length);
            List<byte[]> output = new ArrayList<byte[]>();
            int cursor = 0;
            while (cursor < all.length) {
                if ((all[cursor] & 255) != 0xaa) { cursor++; continue; }
                if (cursor + 1 >= all.length) break;
                if ((all[cursor + 1] & 255) != 0xaa) { cursor++; continue; }
                if (cursor + 2 >= all.length) break;
                int length = all[cursor + 2] & 255;
                if (length == 0) { cursor++; continue; }
                int end = cursor + length + 5;
                if (end > all.length) break;
                if (all[end - 2] != 0x55 || all[end - 1] != 0x55) { cursor++; continue; }
                output.add(Arrays.copyOfRange(all, cursor + 3, end - 2));
                cursor = end;
            }
            pending = Arrays.copyOfRange(all, cursor, all.length);
            return output;
        }
    }
}
