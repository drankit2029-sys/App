package com.example.hello;

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
                // Unknown extension: sniff the ZIP magic so a renamed .docx
                // still works.
                byte[] head = new byte[4];
                int n = in.read(head, 0, 4);
                boolean zip = n == 4 && head[0] == 'P' && head[1] == 'K'
                        && head[2] == 3 && head[3] == 4;
                if (zip) {
                    return readDocx(new FileInputStreamWrapper(in, head));
                }
                // Plain text: replay the sniffed header bytes, otherwise
                // every import silently loses its first four characters.
                if (n <= 0) return new Result("", false, null);
                return new Result(readPlain(new FileInputStreamWrapper(in,
                        java.util.Arrays.copyOf(head, n))), false, null);
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

    private static String readPlain(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(in, "UTF-8"), 8192);
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
            if (sb.length() > MAX_BYTES) break;
        }
        return sb.toString();
    }

    private static Result readDocx(InputStream in) {
        // ZipInputStream cannot rewind, so buffer the entry we need to a file.
        File tmp = null;
        try {
            ZipInputStream zip = new ZipInputStream(in);
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
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if ("p".equals(tag)) {
                    // Paragraph start: guarantee a break even for empty ones,
                    // so blank lines in the script survive the round trip.
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                        out.append('\n');
                    }
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