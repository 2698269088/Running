import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * 离线工具：把 MovementSync 为 1.21.11 生成的 collision_shapes.bin
 * 按「方块名 + 属性值组合」重映射到本插件目标版本（26.1.2）的协议状态 ID 上，
 * 产出一份状态数与 blocks.json 完全对齐的 MSCS v1 表。
 *
 * 用法： java RemapShapes.java <目标blocks.json> <来源blocks.json> <来源bin> <输出bin>
 */
public class RemapShapes {
    static final int MAGIC = 0x4D534353; // "MSCS"
    static final int VERSION = 1;

    public static void main(String[] args) throws Exception {
        String targetJson = "C:/Users/26982/Desktop/MOD/Soul Rekindling/Running/tools/blocks_2612.json";
        String sourceJson = "C:/Users/26982/Desktop/MOD/Soul Rekindling/Running/tools/blocks_12111.json";
        String sourceBin = "C:/Users/26982/Desktop/MOD/Soul Rekindling/Running/MovementSync-main/src/main/resources/collision_shapes.bin";
        String outBin = "C:/Users/26982/Desktop/MOD/Soul Rekindling/Running/src/main/resources/collision_shapes.bin";
        if (args.length >= 4) {
            targetJson = args[0];
            sourceJson = args[1];
            sourceBin = args[2];
            outBin = args[3];
        }

        List<Block> target = loadBlocks(targetJson);
        List<Block> source = loadBlocks(sourceJson);
        int targetStates = maxStateId(target) + 1;
        int sourceStates = maxStateId(source) + 1;
        System.out.println("target blocks=" + target.size() + " states=" + targetStates);
        System.out.println("source blocks=" + source.size() + " states=" + sourceStates);

        Map<String, Block> sourceByName = new LinkedHashMap<>();
        for (Block b : source) sourceByName.put(b.name, b);

        // 解码来源表：每个状态一组盒（float[count*6]），null 表示无碰撞
        byte[] data = Files.readAllBytes(Paths.get(sourceBin));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        if (bb.getInt() != MAGIC || bb.getInt() != VERSION) throw new IOException("来源表格式不符");
        int sc = bb.getInt();
        if (sc != sourceStates) throw new IOException("来源表状态数 " + sc + " 与来源 blocks.json " + sourceStates + " 不符");
        float[][] sourceBoxes = new float[sc][];
        int off = 12;
        for (int s = 0; s < sc; s++) {
            int c = data[off] & 0xFF;
            off++;
            float[] box = null;
            if (c > 0) {
                box = new float[c * 6];
                for (int i = 0; i < c * 6; i++) {
                    box[i] = bb.getFloat(off + i * 4);
                }
            }
            sourceBoxes[s] = box;
            off += c * 24;
        }
        if (off != data.length) throw new IOException("来源表尾部多余 " + (data.length - off) + " 字节");

        float[][] out = new float[targetStates][];
        boolean[] filled = new boolean[targetStates];
        int direct = 0, remapped = 0, defaulted = 0;
        List<String> missingBlock = new ArrayList<>();
        List<String> diffDims = new ArrayList<>();
        List<String> stateCountDiff = new ArrayList<>();

        for (Block tb : target) {
            int tCount = (int) (tb.maxStateId - tb.minStateId + 1);
            Block sb = sourceByName.get(tb.name);
            if (sb == null) {
                missingBlock.add(tb.name + " bbox=" + tb.boundingBox + " states=" + tCount);
                if ("empty".equals(tb.boundingBox)) {
                    // 原版 .noCollision()：无碰撞就是正确解
                    for (int i = 0; i < tCount; i++) filled[(int) tb.minStateId + i] = true;
                    continue;
                }
                if (tb.name.startsWith("potted_") && tCount == 1) {
                    // 新增的花盆变体：花盆碰撞与所含植物无关，取自 potted_dandelion
                    Block pot = sourceByName.get("potted_dandelion");
                    if (pot != null && pot.maxStateId == pot.minStateId) {
                        out[(int) tb.minStateId] = sourceBoxes[(int) pot.minStateId];
                        filled[(int) tb.minStateId] = true;
                        remapped++;
                        System.out.println("  POTSUB " + tb.name + " <- potted_dandelion");
                        continue;
                    }
                }
                // 其余情况留空（未填充），由下面的统计暴露出来，必须人工确认
                continue;
            }
            if (sb.propsEqual(tb)) {
                for (int i = 0; i < tCount; i++) {
                    int sId = (int) sb.minStateId + i;
                    int tId = (int) tb.minStateId + i;
                    if (sId <= sb.maxStateId) {
                        out[tId] = sourceBoxes[sId];
                        filled[tId] = true;
                        direct++;
                    }
                }
                continue;
            }
            diffDims.add(tb.name);
            if (tCount != (int) (sb.maxStateId - sb.minStateId + 1)) {
                stateCountDiff.add(tb.name + " target=" + tCount + " source=" + (sb.maxStateId - sb.minStateId + 1));
            }
            // 属性定义不同：按「属性名->值」组合建立来源索引，再逐状态查表
            Map<String, Integer> keyToSource = new HashMap<>();
            int sCount = (int) (sb.maxStateId - sb.minStateId + 1);
            for (int i = 0; i < sCount; i++) {
                keyToSource.put(sb.stateKey(i), (int) sb.minStateId + i);
            }
            // 来源形状是否与状态无关（多数方块如此）：若是则可直接兜底
            float[] first = sourceBoxes[(int) sb.minStateId];
            boolean uniform = true;
            for (int i = 0; i < sCount; i++) {
                if (!sameBoxes(first, sourceBoxes[(int) sb.minStateId + i])) {
                    uniform = false;
                    break;
                }
            }
            for (int i = 0; i < tCount; i++) {
                int tId = (int) tb.minStateId + i;
                Integer sid = keyToSource.get(tb.stateKey(i));
                if (sid != null) {
                    out[tId] = sourceBoxes[sid];
                    remapped++;
                } else if (uniform) {
                    out[tId] = first;
                    remapped++;
                } else {
                    out[tId] = first;
                    defaulted++;
                    if (defaulted <= 20) {
                        System.out.println("  FALLBACK " + tb.name + " offset=" + i + " " + tb.stateKey(i));
                    }
                }
                filled[tId] = true;
            }
        }

        int holes = 0;
        for (int s = 0; s < targetStates; s++) {
            if (!filled[s]) {
                holes++;
                if (holes <= 20) System.out.println("  UNFILLED id=" + s);
            }
        }
        System.out.println("direct=" + direct + " remapped=" + remapped + " defaulted=" + defaulted
                + " unfilled=" + holes);
        System.out.println("missingTargetBlock(" + missingBlock.size() + ")=" + missingBlock);
        System.out.println("dimsDiffer(" + diffDims.size() + ")=" + diffDims);
        System.out.println("stateCountDiff=" + stateCountDiff);
        if (holes > 0) throw new IOException("存在未覆盖的状态，拒绝输出");

        // 写出
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
        ByteBuffer head = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        head.putInt(MAGIC).putInt(VERSION).putInt(targetStates);
        bos.write(head.array());
        int totalBoxes = 0;
        for (int s = 0; s < targetStates; s++) {
            float[] box = out[s];
            int count = box == null ? 0 : box.length / 6;
            bos.write(count);
            if (count == 0) continue;
            totalBoxes += count;
            ByteBuffer c = ByteBuffer.allocate(count * 24).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < box.length; i++) c.putFloat(box[i]);
            bos.write(c.array());
        }
        byte[] result = bos.toByteArray();
        Files.write(Paths.get(outBin), result);
        System.out.println("wrote " + outBin + " states=" + targetStates + " boxes=" + totalBoxes
                + " bytes=" + result.length);
        long distinct = 0;
        for (float[] b : out) if (b != null) distinct++;
        System.out.println("statesWithCollision=" + distinct);
    }

    static boolean sameBoxes(float[] a, float[] b) {
        if (a == null || b == null) return a == b;
        return Arrays.equals(a, b);
    }

    static int maxStateId(List<Block> blocks) {
        int v = -1;
        for (Block b : blocks) v = Math.max(v, (int) b.maxStateId);
        return v;
    }

    // ------------------------------------------------------------------
    // blocks.json 解析
    // ------------------------------------------------------------------

    static class Prop {
        final String name;
        final String[] values;

        Prop(String name, String[] values) {
            this.name = name;
            this.values = values;
        }
    }

    static class Block {
        String name;
        String boundingBox;
        long minStateId;
        long maxStateId;
        List<Prop> props = new ArrayList<>();
        int[] strides = new int[0];

        int stateCount() {
            int n = 1;
            for (Prop p : props) n *= p.values.length;
            return props.isEmpty() ? 1 : n;
        }

        boolean propsEqual(Block o) {
            if (props.size() != o.props.size()) return false;
            for (int i = 0; i < props.size(); i++) {
                Prop a = props.get(i), b = o.props.get(i);
                if (!a.name.equals(b.name)) return false;
                if (!Arrays.equals(a.values, b.values)) return false;
            }
            return true;
        }

        /** 把状态偏移量解码成「属性名=值」组合键（最后一个属性变化最快，与插件 StateDims 一致） */
        String stateKey(int offset) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < props.size(); i++) {
                int v = offset / strides[i] % props.get(i).values.length;
                sb.append(props.get(i).name).append('=').append(props.get(i).values[v]).append(';');
            }
            return sb.toString();
        }
    }

    static List<Block> loadBlocks(String path) throws Exception {
        String raw = new String(Files.readAllBytes(Paths.get(path)));
        List<Block> out = new ArrayList<>();
        for (String obj : splitObjects(raw)) {
            String n = strField(obj, "name");
            Long mn = numField(obj, "minStateId");
            Long mx = numField(obj, "maxStateId");
            if (n == null || mn == null) continue;
            Block b = new Block();
            b.name = n;
            b.minStateId = mn;
            b.maxStateId = mx == null ? mn : mx;
            b.boundingBox = strField(obj, "boundingBox");
            parseProps(obj, b);
            // 计算步长：最后一个属性变化最快
            b.strides = new int[b.props.size()];
            int stride = 1;
            for (int i = b.props.size() - 1; i >= 0; i--) {
                b.strides[i] = stride;
                stride *= b.props.get(i).values.length;
            }
            int expect = b.stateCount();
            if (expect != b.maxStateId - b.minStateId + 1) {
                System.out.println("  WARN 属性积与状态数不符: " + b.name + " product=" + expect
                        + " range=" + (b.maxStateId - b.minStateId + 1));
            }
            out.add(b);
        }
        return out;
    }

    static void parseProps(String obj, Block b) {
        int vi = findValue(obj, "states");
        if (vi < 0 || obj.charAt(vi) != '[') return;
        int end = matchBracket(obj, vi);
        for (String po : splitObjects(obj.substring(vi + 1, end))) {
            String pn = strField(po, "name");
            if (pn == null) continue;
            String[] values = strArray(po, "values");
            if (values == null) {
                Long nv = numField(po, "num_values");
                int n = nv == null ? 2 : nv.intValue();
                values = new String[n];
                for (int i = 0; i < n; i++) values[i] = String.valueOf(i);
            }
            b.props.add(new Prop(pn, values));
        }
    }

    // ------------------------------------------------------------------
    // 极简 JSON 扫描
    // ------------------------------------------------------------------

    /** 返回原始串中所有「深度 0」花括号对象的子串 */
    static List<String> splitObjects(String raw) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; }
            else if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) { out.add(raw.substring(start, i + 1)); start = -1; }
            }
        }
        return out;
    }

    /** 返回 i 处 '[' 对应的 ']' 下标 */
    static int matchBracket(String s, int i) {
        int depth = 0;
        boolean inStr = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    static String[] strArray(String obj, String key) {
        int s = findValue(obj, key);
        if (s < 0 || obj.charAt(s) != '[') return null;
        int e = matchBracket(obj, s);
        if (e < 0) return null;
        List<String> out = new ArrayList<>();
        int i = s + 1;
        while (i < e) {
            if (obj.charAt(i) == '"') {
                int j = obj.indexOf('"', i + 1);
                if (j < 0) break;
                out.add(obj.substring(i + 1, j));
                i = j + 1;
            } else i++;
        }
        return out.toArray(new String[0]);
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
                int v = skipWs(obj, j + 1);
                // 跳过更深层的键值内部匹配：值位置必须是本对象的直接值
                return v;
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
