import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * 诊断工具：解析 MovementSync 的 collision_shapes.bin，打印指定方块的逐状态权威碰撞盒。
 * 用法： java DumpShapes <blocks.json> <collision_shapes.bin> [name ...]
 */
public class DumpShapes {
    /** 每个方块打印的明细盒行数；0 = 只打印 RESULT 汇总行 */
    static int verbose = 14;

    public static void main(String[] args) throws Exception {
        String blocksJson = "C:/Users/26982/Desktop/MOD/Soul Rekindling/Running/src/main/resources/blocks.json";
        String bin = "C:/Users/26982/Desktop/MOD/Soul Rekindling/Running/MovementSync-main/src/main/resources/collision_shapes.bin";
        List<String> names = new ArrayList<>();
        if (args.length >= 2) {
            blocksJson = args[0];
            bin = args[1];
        }
        for (int i = 2; i < args.length; i++) names.add(args[i]);
        if (names.isEmpty()) {
            names = Arrays.asList("short_grass", "stone_slab", "oak_trapdoor", "lever", "oak_fence",
                "cobblestone_wall", "snow", "carpet", "white_bed", "farmland", "stone_pressure_plate",
                "oak_button", "glass_pane", "ladder", "chest", "cobweb", "big_dripleaf");
        }

        String raw = new String(Files.readAllBytes(Paths.get(blocksJson)));
        byte[] data = Files.readAllBytes(Paths.get(bin));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int magic = bb.getInt();
        int version = bb.getInt();
        int stateCount = bb.getInt();
        System.out.println("magic=" + Integer.toHexString(magic) + " version=" + version + " stateCount=" + stateCount);

        // 逐状态扫描偏移
        int[] offsets = new int[stateCount + 1];
        int[] counts = new int[stateCount];
        int off = 12;
        for (int s = 0; s < stateCount; s++) {
            offsets[s] = off;
            int c = data[off] & 0xFF;
            counts[s] = c;
            off += 1 + c * 24;
        }
        offsets[stateCount] = off;
        System.out.println("fileLen=" + data.length + " walkedEnd=" + off + " match=" + (data.length == off));

        // 极简 JSON 抽取：只取每个对象里的 name / minStateId / maxStateId / boundingBox / defaultState
        List<long[]> ranges = new ArrayList<>();
        List<String> bn = new ArrayList<>();
        List<String> bbox = new ArrayList<>();
        for (String obj : splitObjects(raw)) {
            String n = strField(obj, "name");
            Long mn = numField(obj, "minStateId");
            Long mx = numField(obj, "maxStateId");
            if (n == null || mn == null) continue;
            bn.add(n);
            ranges.add(new long[]{mn, mx == null ? mn : mx});
            bbox.add(strField(obj, "boundingBox"));
        }
        System.out.println("blocks=" + bn.size());

        for (String want : names) {
            if ("ALL".equals(want)) {
                verbose = 0;
                for (String n : bn) dumpOne(n, raw, data, ranges, bbox, bn, offsets, counts);
                continue;
            }
            dumpOne(want, raw, data, ranges, bbox, bn, offsets, counts);
        }
    }

    static void dumpOne(String want, String raw, byte[] data, List<long[]> ranges, List<String> bbox,
                        List<String> bn, int[] offsets, int[] counts) {
        {
            int idx = bn.indexOf(want);
            if (idx < 0) { System.out.println("--- " + want + " : NOT FOUND"); return; }
            long lo = ranges.get(idx)[0], hi = ranges.get(idx)[1];
            if (verbose > 0) {
                System.out.println("--- " + want + " bbox=" + bbox.get(idx) + " states=" + (hi - lo + 1) + " [" + lo + ".." + hi + "]");
            }
            int printed = 0, emptyCnt = 0;
            float maxLocalY = 0.0F;
            for (int s = (int) lo; s <= hi; s++) {
                if (counts[s] == 0) { emptyCnt++; continue; }
                {
                    int q = offsets[s] + 1;
                    for (int i = 0; i < counts[s]; i++) {
                        maxLocalY = Math.max(maxLocalY, beFloat(data, q + i * 24 + 16));
                    }
                }
                if (printed < verbose) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("   sid=").append(s).append(" +").append(s - lo).append(" boxes=").append(counts[s]);
                    int p = offsets[s] + 1;
                    for (int i = 0; i < counts[s]; i++) {
                        float x0 = beFloat(data, p), y0 = beFloat(data, p + 4), z0 = beFloat(data, p + 8);
                        float x1 = beFloat(data, p + 12), y1 = beFloat(data, p + 16), z1 = beFloat(data, p + 20);
                        sb.append("  [px ").append(px(x0)).append(' ').append(px(y0)).append(' ').append(px(z0))
                          .append(" -> ").append(px(x1)).append(' ').append(px(y1)).append(' ').append(px(z1)).append(']');
                        p += 24;
                    }
                    System.out.println(sb);
                    printed++;
                }
            }
            System.out.println("RESULT \"" + want + "\" bbox=" + bbox.get(idx) + " states=" + (hi - lo + 1)
                + " noCollision=" + emptyCnt + " withCollision=" + ((hi - lo + 1) - emptyCnt)
                + " maxLocalY=" + fmt(maxLocalY));
        }
    }

    static String fmt(float v) {
        return String.format("%.4f", v);
    }

    static String px(float v) {
        float p = v * 16f;
        if (Math.abs(p - Math.rint(p)) < 0.004f) return String.valueOf((int) Math.rint(p));
        return String.format("%.2f", p);
    }

    static float beFloat(byte[] d, int o) {
        return ByteBuffer.wrap(d, o, 4).order(ByteOrder.BIG_ENDIAN).getFloat();
    }

    static List<String> splitObjects(String raw) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) { out.add(raw.substring(start, i + 1)); start = -1; } }
        }
        return out;
    }

    static String strField(String obj, String key) {
        int s = findValue(obj, key);
        if (s < 0 || obj.charAt(s) != '"') return null;
        int e = obj.indexOf('"', s + 1);
        return e < 0 ? null : obj.substring(s + 1, e);
    }
    
    static Long numField(String obj, String key) {
        int s = findValue(obj, key);
        if (s < 0) return null;
        int e = s;
        while (e < obj.length() && (Character.isDigit(obj.charAt(e)) || obj.charAt(e) == '-' || obj.charAt(e) == '.')) e++;
        if (e == s) return null;
        return (long) Double.parseDouble(obj.substring(s, e));
    }
    
    /** 返回该对象中 key 对应值的起始下标（已跳过空白）；找不到返回 -1 */
    static int findValue(String obj, String key) {
        String k = "\"" + key + "\"";
        int i = obj.indexOf(k);
        while (i >= 0) {
            int j = skipWs(obj, i + k.length());
            if (j < obj.length() && obj.charAt(j) == ':') {
                return skipWs(obj, j + 1);
            }
            i = obj.indexOf(k, i + k.length());
        }
        return -1;
    }
    
    static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
}
