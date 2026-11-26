package com.iishko.selfhost;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public final class TarExtractor {

    public interface Symlinker {
        void symlink(String target, String link) throws Exception;
        void hardlink(String existing, String link) throws Exception;
    }

    public static void extractFromGzip(InputStream in, File dest, Symlinker sym) throws Exception {
        GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(in, 1 << 16));
        try {
            extract(gz, dest, sym);
        } finally {
            gz.close();
        }
    }

    private static void extract(InputStream in, File dest, Symlinker sym) throws Exception {
        if (!dest.exists() && !dest.mkdirs()) throw new IOException("cannot create " + dest);
        byte[] hdr = new byte[512];
        String paxPath = null, paxLink = null;
        long paxSize = -1;
        String gnuName = null, gnuLink = null;

        while (true) {
            int n = readFully(in, hdr);
            if (n == 0) break;
            if (n < 512) throw new IOException("short tar header: " + n);
            if (isZero(hdr)) break;

            String name = str(hdr, 0, 100);
            String modeStr = str(hdr, 100, 8);
            String sizeStr = str(hdr, 124, 12);
            char type = (char) (hdr[156] & 0xff);
            String link = str(hdr, 157, 100);
            String prefix = str(hdr, 345, 155);
            long size = parseOctal(sizeStr);

            if (gnuName != null) { name = gnuName; gnuName = null; }
            if (gnuLink != null) { link = gnuLink; gnuLink = null; }
            if (paxPath != null) { name = paxPath; paxPath = null; }
            if (paxLink != null) { link = paxLink; paxLink = null; }
            if (paxSize >= 0) { size = paxSize; paxSize = -1; }

            if (type == 'L') { gnuName = readString(in, size); continue; }
            if (type == 'K') { gnuLink = readString(in, size); continue; }
            if (type == 'x') {
                byte[] d = readData(in, size);
                String[] vals = parsePax(d);
                paxPath = vals[0];
                paxLink = vals[1];
                if (vals[2] != null) paxSize = Long.parseLong(vals[2]);
                continue;
            }
            if (type == 'g') { skipData(in, size); continue; }

            String full = name;
            if (prefix != null && prefix.length() > 0) full = prefix + "/" + name;
            full = full.replace('\\', '/');
            while (full.startsWith("./")) full = full.substring(2);
            while (full.startsWith("/")) full = full.substring(1);

            File out = safe(dest, full);
            int mode = (int) parseOctal(modeStr);
            if (out == null) { skipData(in, size); continue; }

            if (type == '5') {
                out.mkdirs();
                out.setExecutable((mode & 0111) != 0, false);
                skipData(in, size);
            } else if (type == '2') {
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                if (out.exists() || isSymlink(out)) out.delete();
                sym.symlink(link, out.getAbsolutePath());
            } else if (type == '1') {
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                if (out.exists()) out.delete();
                String l = link;
                while (l.startsWith("./")) l = l.substring(2);
                while (l.startsWith("/")) l = l.substring(1);
                File existing = safe(dest, l);
                try {
                    sym.hardlink(existing.getAbsolutePath(), out.getAbsolutePath());
                } catch (Exception e) {
                    if (existing != null && existing.isFile()) copyFile(existing, out);
                }
            } else if (type == '0' || type == 0) {
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                writeFile(in, out, size);
                out.setExecutable((mode & 0111) != 0, false);
            } else {
                skipData(in, size);
            }
        }
    }

    private static boolean isSymlink(File f) {
        try {
            File p = f.getParentFile();
            File c = new File(p.getCanonicalFile(), f.getName());
            return !c.getCanonicalFile().equals(c.getAbsoluteFile());
        } catch (Exception e) {
            return false;
        }
    }

    private static String[] parsePax(byte[] d) {
        String path = null, link = null, size = null;
        String s = new String(d, StandardCharsets.UTF_8);
        int i = 0;
        while (i < s.length()) {
            int sp = s.indexOf(' ', i);
            if (sp < 0) break;
            int len;
            try { len = Integer.parseInt(s.substring(i, sp)); } catch (Exception e) { break; }
            if (len <= 0 || i + len > s.length()) break;
            String rec = s.substring(sp + 1, i + len - 1);
            int eq = rec.indexOf('=');
            if (eq > 0) {
                String k = rec.substring(0, eq);
                String v = rec.substring(eq + 1);
                if (k.equals("path")) path = v;
                else if (k.equals("linkpath")) link = v;
                else if (k.equals("size")) size = v;
            }
            i += len;
        }
        return new String[]{path, link, size};
    }

    private static File safe(File dest, String rel) throws IOException {
        if (rel.length() == 0) return null;
        if (rel.contains("..")) return null;
        File out = new File(dest, rel);
        String base = dest.getCanonicalPath() + File.separator;
        String can = out.getCanonicalPath();
        if (!can.equals(dest.getCanonicalPath()) && !can.startsWith(base)) return null;
        return out;
    }

    private static void writeFile(InputStream in, File out, long size) throws IOException {
        FileOutputStream fos = new FileOutputStream(out);
        try {
            byte[] buf = new byte[1 << 16];
            long left = size;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) throw new IOException("unexpected EOF in " + out);
                fos.write(buf, 0, r);
                left -= r;
            }
        } finally {
            fos.close();
        }
        skipPadding(in, size);
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = fis.read(buf)) > 0) fos.write(buf, 0, r);
        } finally {
            fis.close();
            fos.close();
        }
        dst.setExecutable(src.canExecute(), false);
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > Integer.MAX_VALUE) throw new IOException("too large");
        byte[] d = new byte[(int) size];
        int off = 0;
        while (off < d.length) {
            int r = in.read(d, off, d.length - off);
            if (r < 0) throw new IOException("unexpected EOF");
            off += r;
        }
        skipPadding(in, size);
        return d;
    }

    private static String readString(InputStream in, long size) throws IOException {
        byte[] d = readData(in, size);
        int end = d.length;
        for (int i = 0; i < d.length; i++) if (d[i] == 0) { end = i; break; }
        return new String(d, 0, end, StandardCharsets.UTF_8);
    }

    private static void skipData(InputStream in, long size) throws IOException {
        skipPadding(in, size);
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        while (pad > 0) {
            long s = in.skip(pad);
            if (s <= 0) {
                if (in.read() < 0) return;
                s = 1;
            }
            pad -= s;
        }
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long parseOctal(String s) {
        s = s.trim();
        if (s.length() == 0) return 0;
        try { return Long.parseLong(s, 8); } catch (Exception e) { return 0; }
    }
}
