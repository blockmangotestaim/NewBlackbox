package com.memscan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A search pattern made of one or more typed values laid out back-to-back in memory.
 *
 * Syntax (tokens separated by space / comma / semicolon):  TYPE:VALUE
 *   TYPE  : 1 or b = 1 byte | 2 or w = 2 bytes | 4 or d = 4 bytes | 8 or q = 8 bytes
 *           f = float (4)   | e = double (8)
 *   VALUE : 35   |  0-255 (range)  |  0x1F  |  -5  |  ? (wildcard, matches anything)
 *
 * Example:  2:0 2:0 2:65535 2:257 1:0-255 w:35 2:16128 1:0
 */
public final class GroupPattern {
    static final int INT = 0, FLOAT = 1, DOUBLE = 2;

    static final class Elem {
        int kind, size, offset;
        boolean any;
        long lo, hi;
        double flo, fhi;
        char label;
    }

    final Elem[] elems;
    public final int length;      // total bytes of the pattern
    public final int firstSize;   // size of first element (used for aligned scanning)
    final boolean prefilter;
    final byte prefilterByte;

    private GroupPattern(Elem[] e, int len) {
        elems = e;
        length = len;
        firstSize = e[0].size;
        Elem f = e[0];
        boolean pre = false;
        byte pb = 0;
        if (!f.any) {
            if (f.kind == INT && f.lo == f.hi) { pre = true; pb = (byte) f.lo; }
            else if (f.kind == FLOAT && f.flo == f.fhi) { pre = true; pb = (byte) Float.floatToIntBits((float) f.flo); }
            else if (f.kind == DOUBLE && f.flo == f.fhi) { pre = true; pb = (byte) Double.doubleToLongBits(f.flo); }
        }
        prefilter = pre;
        prefilterByte = pb;
    }

    public static GroupPattern parse(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Enter a value to search for.");
        String[] toks = text.trim().split("[\\s;,]+");
        List<Elem> list = new ArrayList<>();
        int off = 0;
        for (String t : toks) {
            int c = t.indexOf(':');
            if (c <= 0 || c == t.length() - 1)
                throw new IllegalArgumentException("Bad token '" + t + "'. Use TYPE:VALUE, e.g. 2:65535");
            if (t.indexOf(':', c + 1) >= 0)
                throw new IllegalArgumentException("Token '" + t + "' has two ':' - a space is probably missing (e.g. '2:65535 2:257').");
            String type = t.substring(0, c).toLowerCase(Locale.ROOT);
            String val = t.substring(c + 1);
            Elem e = new Elem();
            switch (type) {
                case "1": case "b": e.kind = INT; e.size = 1; e.label = '1'; break;
                case "2": case "w": e.kind = INT; e.size = 2; e.label = '2'; break;
                case "4": case "d": e.kind = INT; e.size = 4; e.label = '4'; break;
                case "8": case "q": e.kind = INT; e.size = 8; e.label = '8'; break;
                case "f": e.kind = FLOAT; e.size = 4; e.label = 'f'; break;
                case "e": e.kind = DOUBLE; e.size = 8; e.label = 'e'; break;
                default: throw new IllegalArgumentException("Unknown type '" + type + "' in '" + t + "'. Use 1 2 4 8 (or b w d q), f, e.");
            }
            e.offset = off;
            off += e.size;
            fill(e, val);
            list.add(e);
        }
        return new GroupPattern(list.toArray(new Elem[0]), off);
    }

    private static void fill(Elem e, String val) {
        if (val.equals("?") || val.equals("*")) { e.any = true; return; }
        String a = val, b = val;
        int s = rangeSplit(val, e.kind != INT);
        if (s > 0) { a = val.substring(0, s); b = val.substring(s + 1); }
        try {
            if (e.kind == INT) {
                long lo = parseLong(a), hi = parseLong(b);
                if (e.size < 8) {
                    int bits = e.size * 8;
                    long min = -(1L << (bits - 1)), max = (1L << bits) - 1;
                    if (lo < min || lo > max || hi < min || hi > max)
                        throw new IllegalArgumentException("Value '" + val + "' does not fit in " + e.size + " byte(s) (max " + max + ").");
                    lo &= max; hi &= max;
                }
                if (lo > hi) throw new IllegalArgumentException("Range start is bigger than its end in '" + val + "'.");
                e.lo = lo; e.hi = hi;
            } else {
                double lo = Double.parseDouble(a), hi = Double.parseDouble(b);
                if (e.kind == FLOAT) { lo = (float) lo; hi = (float) hi; }
                if (lo > hi) throw new IllegalArgumentException("Range start is bigger than its end in '" + val + "'.");
                e.flo = lo; e.fhi = hi;
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Can't read the number '" + val + "'.");
        }
    }

    private static int rangeSplit(String v, boolean allowExp) {
        for (int i = 1; i < v.length(); i++) {
            if (v.charAt(i) == '-') {
                char p = v.charAt(i - 1);
                if (allowExp && (p == 'e' || p == 'E')) continue;
                return i;
            }
        }
        return -1;
    }

    private static long parseLong(String s) {
        s = s.trim();
        String l = s.toLowerCase(Locale.ROOT);
        if (l.startsWith("0x") || l.startsWith("-0x")) return Long.decode(s);
        return Long.parseLong(s);
    }

    /** Does the pattern match the bytes starting at index i of bb (little-endian)? */
    public boolean matches(ByteBuffer bb, int i) {
        for (Elem e : elems) {
            if (e.any) continue;
            int p = i + e.offset;
            if (e.kind == INT) {
                long v;
                switch (e.size) {
                    case 1: v = bb.get(p) & 0xFFL; break;
                    case 2: v = bb.getShort(p) & 0xFFFFL; break;
                    case 4: v = bb.getInt(p) & 0xFFFFFFFFL; break;
                    default: v = bb.getLong(p);
                }
                if (v < e.lo || v > e.hi) return false;
            } else if (e.kind == FLOAT) {
                float f = bb.getFloat(p);
                if (!(f >= e.flo && f <= e.fhi)) return false;
            } else {
                double d = bb.getDouble(p);
                if (!(d >= e.flo && d <= e.fhi)) return false;
            }
        }
        return true;
    }

    public boolean quickReject(byte[] buf, int i) {
        return prefilter && buf[i] != prefilterByte;
    }

    /** Render the bytes as the same TYPE:VALUE notation used for searching. */
    public String format(byte[] data) {
        if (data == null || data.length < length) return "??";
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder sb = new StringBuilder();
        for (Elem e : elems) {
            if (sb.length() > 0) sb.append(' ');
            int p = e.offset;
            sb.append(e.label).append(':');
            if (e.kind == INT) {
                switch (e.size) {
                    case 1: sb.append(bb.get(p) & 0xFF); break;
                    case 2: sb.append(bb.getShort(p) & 0xFFFF); break;
                    case 4: sb.append(bb.getInt(p) & 0xFFFFFFFFL); break;
                    default: sb.append(bb.getLong(p));
                }
            } else if (e.kind == FLOAT) sb.append(bb.getFloat(p));
            else sb.append(bb.getDouble(p));
        }
        return sb.toString();
    }
}
