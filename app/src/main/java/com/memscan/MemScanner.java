package com.memscan;

import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * READ-ONLY scanner. Reads /proc/<pid>/mem with pread(). Works without root only for processes
 * that run under the same Linux UID as this app (i.e. processes started by the virtual space).
 */
public final class MemScanner {
    public static final int MAX_STORED = 2_000_000;
    private static final int CHUNK = 4 << 20;

    public static final class Region { public long start, end; public String perms, path; }
    public static final class ProcInfo { public int pid; public String name; public long rssKb; }

    public static final class Result {
        public long[] addrs = new long[1024];
        public int stored;
        public long total;
        public long bytes;
        public int regions, failedRegions;
        public long ms;
        void add(long a) {
            total++;
            if (stored < MAX_STORED) {
                if (stored == addrs.length) addrs = Arrays.copyOf(addrs, Math.min(MAX_STORED, addrs.length * 2));
                addrs[stored++] = a;
            }
        }
    }

    public interface Callback {
        void progress(int pct, long found);
        boolean cancelled();
    }

    /** Open handle on /proc/<pid>/mem. */
    public static final class Mem implements Closeable {
        private final FileInputStream in;
        public Mem(int pid) throws IOException { in = new FileInputStream("/proc/" + pid + "/mem"); }

        /** Returns number of bytes read (0 if unreadable). */
        public int read(byte[] buf, int len, long addr) {
            int off = 0;
            try {
                while (off < len) {
                    int n = Os.pread(in.getFD(), buf, off, len - off, addr + off);
                    if (n <= 0) break;
                    off += n;
                }
            } catch (ErrnoException | IOException e) { /* unreadable page */ }
            return off;
        }

        public byte[] read(long addr, int len) {
            byte[] b = new byte[len];
            return read(b, len, addr) == len ? b : null;
        }

        @Override public void close() throws IOException { in.close(); }
    }

    // ---------------------------------------------------------------- processes

    public static List<ProcInfo> listProcesses() {
        List<ProcInfo> out = new ArrayList<>();
        int myUid = android.os.Process.myUid(), myPid = android.os.Process.myPid();
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            int pid;
            try { pid = Integer.parseInt(d.getName()); } catch (NumberFormatException e) { continue; }
            if (pid == myPid) continue;
            int uid = -1;
            long rss = 0;
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/" + pid + "/status"))) {
                String l;
                while ((l = br.readLine()) != null) {
                    if (l.startsWith("Uid:")) uid = Integer.parseInt(l.split("\\s+")[1]);
                    else if (l.startsWith("VmRSS:")) rss = Long.parseLong(l.split("\\s+")[1]);
                }
            } catch (Exception e) { continue; }
            if (uid != myUid) continue;
            ProcInfo p = new ProcInfo();
            p.pid = pid;
            p.rssKb = rss;
            p.name = cmdline(pid);
            out.add(p);
        }
        Collections.sort(out, new Comparator<ProcInfo>() {
            @Override public int compare(ProcInfo a, ProcInfo b) { return Long.compare(b.rssKb, a.rssKb); }
        });
        return out;
    }

    private static String cmdline(int pid) {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] b = new byte[512];
            int n = in.read(b);
            if (n <= 0) return "?";
            return new String(b, 0, n).replace('\0', ' ').trim();
        } catch (IOException e) { return "?"; }
    }

    // ---------------------------------------------------------------- maps

    public static List<Region> readMaps(int pid, boolean writableOnly) throws IOException {
        List<Region> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/" + pid + "/maps"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] f = line.split("\\s+", 6);
                if (f.length < 2) continue;
                String perms = f[1];
                if (perms.charAt(0) != 'r') continue;
                if (writableOnly && perms.charAt(1) != 'w') continue;
                String path = f.length > 5 ? f[5].trim() : "";
                if (path.startsWith("/dev/") && !path.startsWith("/dev/ashmem")) continue;
                if (path.equals("[vvar]") || path.equals("[vsyscall]") || path.equals("[vectors]")) continue;
                String[] ab = f[0].split("-");
                Region r = new Region();
                r.start = Long.parseUnsignedLong(ab[0], 16);
                r.end = Long.parseUnsignedLong(ab[1], 16);
                r.perms = perms;
                r.path = path;
                out.add(r);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- scans

    public static Result firstScan(int pid, List<Region> regions, GroupPattern pat,
                                   boolean aligned, Callback cb) throws IOException {
        long t0 = System.currentTimeMillis();
        Result res = new Result();
        final int L = pat.length;
        final int step = aligned ? pat.firstSize : 1;
        long totalBytes = 0;
        for (Region r : regions) totalBytes += r.end - r.start;
        if (totalBytes == 0) throw new IOException("No readable memory regions found for this process.");

        byte[] buf = new byte[CHUNK + L];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        long done = 0;
        int lastPct = -1;
        try (Mem mem = new Mem(pid)) {
            for (Region r : regions) {
                if (cb.cancelled()) break;
                boolean any = false;
                for (long pos = r.start; pos < r.end; pos += CHUNK) {
                    if (cb.cancelled()) break;
                    int want = (int) Math.min((long) CHUNK + L - 1, r.end - pos);
                    int got = mem.read(buf, want, pos);
                    long chunkLen = Math.min(CHUNK, r.end - pos);
                    if (got >= L) {
                        any = true;
                        res.bytes += got;
                        for (int i = 0; i < CHUNK && i + L <= got; i += step) {
                            if (pat.quickReject(buf, i)) continue;
                            if (pat.matches(bb, i)) res.add(pos + i);
                        }
                    }
                    done += chunkLen;
                    int pct = (int) (done * 100 / totalBytes);
                    if (pct != lastPct) { lastPct = pct; cb.progress(pct, res.total); }
                }
                if (any) res.regions++; else res.failedRegions++;
            }
        }
        res.ms = System.currentTimeMillis() - t0;
        return res;
    }

    public static Result nextScan(int pid, long[] addrs, int n, GroupPattern pat, Callback cb) throws IOException {
        long t0 = System.currentTimeMillis();
        Result res = new Result();
        byte[] buf = new byte[pat.length];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        int lastPct = -1;
        try (Mem mem = new Mem(pid)) {
            for (int i = 0; i < n; i++) {
                if ((i & 1023) == 0) {
                    if (cb.cancelled()) break;
                    int pct = (int) ((long) i * 100 / Math.max(1, n));
                    if (pct != lastPct) { lastPct = pct; cb.progress(pct, res.total); }
                }
                if (mem.read(buf, buf.length, addrs[i]) == buf.length && pat.matches(bb, 0)) res.add(addrs[i]);
            }
        }
        res.bytes = (long) n * pat.length;
        res.ms = System.currentTimeMillis() - t0;
        return res;
    }
}
