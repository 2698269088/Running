import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线工具：把协议方块状态 ID 反查为方块名。
 * 用法：java IdLookup.java &lt;blocks.json&gt; &lt;id&gt; [&lt;id&gt; ...]
 */
public class IdLookup {

    record Entry(String name, int min, int max) {
    }

    public static void main(String[] args) throws IOException {
        String path = args.length > 0 ? args[0] : "tools/blocks_2612.json";
        List<Entry> entries = load(Path.of(path));
        System.out.println("file=" + path + " blocks=" + entries.size()
                + " maxStateId=" + entries.get(entries.size() - 1).max());
        for (int i = 1; i < args.length; i++) {
            int id = Integer.parseInt(args[i]);
            Entry hit = null;
            for (Entry e : entries) {
                if (id >= e.min() && id <= e.max()) {
                    hit = e;
                    break;
                }
            }
            if (hit == null) {
                System.out.println("ID " + id + " = 超出表范围");
            } else {
                System.out.println("ID " + id + " = " + hit.name()
                        + "  [" + hit.min() + ".." + hit.max() + "] 偏移 " + (id - hit.min())
                        + "/" + (hit.max() - hit.min() + 1));
            }
        }
    }

    /** 从块起始处读取一个整数（跳过空白） */
    private static int readInt(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        int start = i;
        while (i < text.length() && Character.isDigit(text.charAt(i))) i++;
        return Integer.parseInt(text.substring(start, i));
    }

    private static List<Entry> load(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        List<Entry> out = new ArrayList<>();
        int p = 0;
        while (true) {
            int minAt = text.indexOf("\"minStateId\":", p);
            if (minAt < 0) break;
            int maxAt = text.indexOf("\"maxStateId\":", minAt);
            // 同一对象内 name 出现在 minStateId 之前，states[] 里的 name 在其后，故取最近的前一个
            int nameAt = text.lastIndexOf("\"name\": \"", minAt);
            if (maxAt < 0 || nameAt < 0) break;
            int nameStart = nameAt + 9;
            int nameEnd = text.indexOf('"', nameStart);
            String name = text.substring(nameStart, nameEnd);
            int min = readInt(text, minAt + 13);
            int max = readInt(text, maxAt + 13);
            out.add(new Entry(name, min, max));
            p = maxAt + 1;
        }
        out.sort((a, b) -> Integer.compare(a.min(), b.min()));
        return out;
    }
}
