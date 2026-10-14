import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * 诊断工具：比较两个 blocks.json 的方块集合与状态 ID 排布。
 * 用法： java CompareBlocks.java <a.json> <b.json>
 */
public class CompareBlocks {
    public static void main(String[] args) throws Exception {
        String a = "C:/Users/26982/Desktop/MOD/Soul Rekindling/Running/target/classes/blocks.json";
        String b = "C:/Users/26982/Desktop/MOD/Soul Rekindling/Running/src/main/resources/blocks.json";
        if (args.length >= 2) { a = args[0]; b = args[1]; }

        Map<String, long[]> ma = load(a);
        Map<String, long[]> mb = load(b);
        System.out.println("A " + a);
        System.out.println("  blocks=" + ma.size() + " maxStateId=" + maxState(ma));
        System.out.println("B " + b);
        System.out.println("  blocks=" + mb.size() + " maxStateId=" + maxState(mb));

        List<String> onlyA = new ArrayList<>();
        for (String n : ma.keySet()) if (!mb.containsKey(n)) onlyA.add(n);
        List<String> onlyB = new ArrayList<>();
        for (String n : mb.keySet()) if (!ma.containsKey(n)) onlyB.add(n);
        System.out.println("onlyInA(" + onlyA.size() + ")=" + onlyA);
        System.out.println("onlyInB(" + onlyB.size() + ")=" + onlyB);

        int shift = 0, same = 0;
        List<String> firstShift = new ArrayList<>();
        for (String n : ma.keySet()) {
            long[] x = mb.get(n);
            if (x == null) continue;
            if (Arrays.equals(ma.get(n), x)) { same++; }
            else {
                shift++;
                if (firstShift.size() < 20) {
                    firstShift.add(n + " A" + ma.get(n)[0] + ".." + ma.get(n)[1]
                        + " B" + x[0] + ".." + x[1]);
                }
            }
        }
        System.out.println("identicalRange=" + same + " shiftedRange=" + shift);
        for (String s : firstShift) System.out.println("  DIFF " + s);
    }

    static long maxState(Map<String, long[]> m) {
        long v = -1;
        for (long[] r : m.values()) v = Math.max(v, r[1]);
        return v;
    }

    static Map<String, long[]> load(String path) throws Exception {
        String raw = new String(Files.readAllBytes(Paths.get(path)));
        Map<String, long[]> out = new LinkedHashMap<>();
        for (String obj : splitObjects(raw)) {
            String n = strField(obj, "name");
            Long mn = numField(obj, "minStateId");
            Long mx = numField(obj, "maxStateId");
            if (n == null || mn == null) continue;
            out.put(n, new long[]{mn, mx == null ? mn : mx});
        }
        return out;
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

    static int findValue(String obj, String key) {
        String k = "\"" + key + "\"";
        int i = obj.indexOf(k);
        while (i >= 0) {
            int j = skipWs(obj, i + k.length());
            if (j < obj.length() && obj.charAt(j) == ':') return skipWs(obj, j + 1);
            i = obj.indexOf(k, i + k.length());
        }
        return -1;
    }

    static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
}
