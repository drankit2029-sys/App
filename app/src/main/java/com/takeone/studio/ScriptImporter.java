package com.takeone.studio;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a script into plain text from a .txt or .docx picked by the user.
 *
 * <p>A .docx is a ZIP whose {@code word/document.xml} holds the text. We pull
 * {@code <w:t>} runs and treat {@code </w:p>} as a paragraph break, which
 * preserves the line structure a teleprompter needs. Anything we cannot parse
 * degrades to a readable dump rather than failing the import.
 */
public final class ScriptImporter {

    private static final String TAG = "ScriptImport";
    private static final String DOCX_ENTRY = "word/document.xml";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private ScriptImporter() {}

    /** Hard cap on decoded script length kept in memory and persisted. */
    private static final int MAX_CHARS = 256 * 1024;
    private static final int SCAN_BYTES = 4096;

    /** Result of an import, including why it failed when it did. */
    public static final class Result {
        public final String text;
        public final boolean docx;
        public final String error;

        Result(String text, boolean docx, String error) {
            this.text = text;
            this.docx = docx;
            this.error = error;
        }

        public boolean ok() {
            return text != null && !text.trim().isEmpty();
        }
    }

    public static Result read(Context ctx, Uri uri) {
        try {
            String name = queryName(ctx, uri);
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return new Result(null, false, "could not open file");
            try {
                if (name != null && name.toLowerCase(java.util.Locale.US)
                        .endsWith(".docx")) {
                    return readDocx(in);
                }
                byte[] head = new byte[8];
                int hn = 0;
                while (hn < head.length) {
                    int n = in.read(head, hn, head.length - hn);
                    if (n <= 0) break;
                    hn += n;
                }
                if (startsWith(head, hn, new byte[]{'%', 'P', 'D', 'F'})) {
                    return new Result(null, false,
                            "PDF is not supported - export the text first");
                }
                if (startsWith(head, hn, new byte[]{(byte) 0xD0, (byte) 0xCF,
                        0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1,
                        0x1A, (byte) 0xE1})) {
                    return new Result(null, false,
                            "Old .doc is not supported - save as .txt or .docx");
                }
                if (hn >= 4 && head[0] == 'P' && head[1] == 'K'
                        && head[2] == 3 && head[3] == 4) {
                    return readDocx(replay(in, head, hn));
                }
                if (hn <= 0) return new Result("", false, null);
                return new Result(readPlain(replay(in, head, hn)), false, null);
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {}
            }
        } catch (SecurityException e) {
            return new Result(null, false, "no permission to read that file");
        } catch (Exception e) {
            Log.w(TAG, "import failed", e);
            return new Result(null, false, "could not read that file");
        }
    }

    private static boolean startsWith(byte[] head, int hn, byte[] magic) {
        if (hn < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) return false;
        }
        return true;
    }

    private static java.io.InputStream replay(InputStream in, byte[] head,
                                              int hn) {
        return new FileInputStreamWrapper(in,
                java.util.Arrays.copyOf(head, hn));
    }

    private static boolean looksLikeUtf16(byte[] raw) {
        int n = Math.min(raw.length, 2048);
        int zeroEven = 0, zeroOdd = 0;
        for (int i = 0; i < n; i++) {
            if (raw[i] == 0) {
                if ((i & 1) == 0) zeroEven++; else zeroOdd++;
            }
        }
        return zeroEven > n / 4 || zeroOdd > n / 4;
    }

    private static boolean hasTooMuchBinary(byte[] raw) {
        int n = Math.min(raw.length, SCAN_BYTES);
        int nuls = 0;
        int ctrl = 0;
        for (int i = 0; i < n; i++) {
            int b = raw[i] & 0xFF;
            if (b == 0) nuls++;
            else if (b < 0x09 || (b > 0x0D && b < 0x20)) ctrl++;
        }
        return nuls * 16 > n || ctrl * 3 > n;
    }

    private static String readPlain(InputStream in) throws Exception {
        byte[] raw = readAllBounded(in, MAX_BYTES + 1);
        int off = 0;
        String charset;
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            charset = "UTF-16LE";
            off = 2;
        } else if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF) {
            charset = "UTF-16BE";
            off = 2;
        } else if (looksLikeUtf16(raw)) {
            charset = "UTF-16LE";
        } else {
            charset = "UTF-8";
        }
        if (!"UTF-16LE".equals(charset) && !"UTF-16BE".equals(charset)
                && hasTooMuchBinary(raw)) {
            throw new IllegalStateException("not a plain-text file");
        }
        String decoded = new String(raw, off, raw.length - off, charset);
        if ("UTF-8".equals(charset)) {
            int bad = 0;
            for (int i = 0; i < decoded.length(); i++) {
                if (decoded.charAt(i) == '\uFFFD') bad++;
            }
            if (bad * 50 > decoded.length()) {
                decoded = new String(raw, off, raw.length - off, "windows-1252");
            }
        }
        if (decoded.startsWith("\uFEFF")) {
            decoded = decoded.substring(1);
        }
        if (decoded.length() > MAX_CHARS) {
            decoded = decoded.substring(0, MAX_CHARS);
        }
        return decoded;
    }

    private static byte[] readAllBounded(InputStream in, int maxBytes)
            throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) {
                out.write(buf, 0, Math.max(0, n - (total - maxBytes)));
                break;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static Result readDocx(InputStream in) {
        // ZipInputStream cannot rewind, so buffer the entry we need to a file.
        File tmp = null;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (!DOCX_ENTRY.equals(entry.getName())) continue;
                tmp = File.createTempFile("script", ".docx");
                OutputStream out = new FileOutputStream(tmp);
                try {
                    int n;
                    int total = 0;
                    while ((n = zip.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                        if (total > MAX_BYTES) break;
                    }
                } finally {
                    try {
                        out.close();
                    } catch (Exception ignored) {}
                }
                break;
            }
            if (tmp == null || tmp.length() == 0) {
                return new Result(null, true, "not a Word document");
            }
            String text = parseDocumentXml(tmp);
            // noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return new Result(text, true, null);
        } catch (Exception e) {
            Log.w(TAG, "docx parse failed", e);
            return new Result(null, true, "could not read that Word file");
        } finally {
            if (tmp != null) {
                // noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** Walks document.xml, emitting paragraph breaks and text runs. */
    private static String parseDocumentXml(File docx) throws Exception {
        StringBuilder out = new StringBuilder();
        XmlPullParser p = android.util.Xml.newPullParser();
        // A4-13: the stream was never closed, leaking a descriptor on every
        // .docx import.
        try (java.io.InputStream fis = new FileInputStream(docx)) {
            p.setInput(fis, "UTF-8");
            return parseWith(p);
        }
    }

    private static String parseWith(XmlPullParser p) throws Exception {
        StringBuilder out = new StringBuilder();
        boolean firstPara = true;
        int event;
        try {
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if (tag != null) {
                    int colon = tag.indexOf(':');
                    if (colon >= 0) tag = tag.substring(colon + 1);
                }
                if ("p".equals(tag)) {
                    // Every paragraph after the first starts a new line,
                    // unconditionally: empty paragraphs (<w:p/>) then
                    // survive as blank lines instead of collapsing.
                    if (!firstPara) {
                        out.append('\n');
                    }
                    firstPara = false;
                } else if ("t".equals(tag)) {
                    // Text run begins; content arrives as TEXT events.
                    if (p.next() == XmlPullParser.TEXT) {
                        String t = p.getText();
                        if (t != null) out.append(t);
                    }
                } else if ("tab".equals(tag)) {
                    out.append('\t');
                } else if ("br".equals(tag)) {
                    out.append('\n');
                }
            }
        }
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            // A capped read of word/document.xml can end mid-tag; keep the
            // text decoded so far instead of failing the whole import.
            if (out.length() == 0) throw e;
        }
        return out.toString();
    }

    private static String queryName(Context ctx, Uri uri) {
        try {
            if ("content".equals(uri.getScheme())) {
                android.database.Cursor c = ctx.getContentResolver().query(
                        uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                        null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) return c.getString(0);
                    } finally {
                        c.close();
                    }
                }
            }
            return uri.getLastPathSegment();
        } catch (Exception e) {
            return uri.getLastPathSegment();
        }
    }

    /** Test hook for the sniffed-header replay stream. */
    static java.io.InputStream wrapForTest(java.io.InputStream src,
                                            byte[] head) {
        return new FileInputStreamWrapper(src, head);
    }

    /** Re-injects the magic bytes already consumed while sniffing. */
    private static final class FileInputStreamWrapper extends InputStream {
        private final InputStream src;
        private final byte[] head;
        private int pos;

        FileInputStreamWrapper(InputStream src, byte[] head) {
            this.src = src;
            this.head = head;
        }

        // A4-13: the buffered head is served purely by `pos < head.length`.
        // A one-shot `headDone` flag was set on the very first read() and
        // dropped every remaining sniffed byte when the caller did not
        // consume the whole header in one call.

        @Override
        public int read() throws java.io.IOException {
            if (pos < head.length) return head[pos++] & 0xFF;
            return src.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (len == 0) return 0;
            if (pos < head.length) {
                int n = Math.min(len, head.length - pos);
                System.arraycopy(head, pos, b, off, n);
                pos += n;
                if (n == len) return n;
                int r = src.read(b, off + n, len - n);
                return r < 0 ? n : n + r;
            }
            return src.read(b, off, len);
        }

        @Override
        public int available() throws java.io.IOException {
            return head.length - pos + src.available();
        }

        @Override
        public void close() throws java.io.IOException {
            src.close();
        }
    }
}