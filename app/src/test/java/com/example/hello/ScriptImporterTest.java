package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Script import: plain text passthrough, minimal .docx extraction with
 * paragraph breaks, and graceful failure on garbage.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ScriptImporterTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private File scratch(String name, String content) throws Exception {
        File f = new File(ctx().getCacheDir(), name);
        OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), "UTF-8");
        try {
            w.write(content);
        } finally {
            w.close();
        }
        return f;
    }

    @Test
    public void plainTextPassesThrough() throws Exception {
        File f = scratch("script.txt", "hello world\nsecond line");
        ScriptImporter.Result r =
                ScriptImporter.read(ctx(), Uri.fromFile(f));
        assertTrue("ok=" + r.error, r.ok());
        assertFalse(r.docx);
        assertEquals("hello world\nsecond line", r.text);
    }

    @Test
    public void tinyFileKeepsEveryByte() throws Exception {
        // Shorter than the 4-byte sniff header: nothing may be lost.
        File f = scratch("tiny.txt", "hi");
        ScriptImporter.Result r =
                ScriptImporter.read(ctx(), Uri.fromFile(f));
        assertTrue("ok=" + r.error, r.ok());
        assertEquals("hi", r.text);
    }

    @Test
    public void blankFileIsNotOk() throws Exception {
        File f = scratch("blank.txt", "   \n\n  ");
        ScriptImporter.Result r =
                ScriptImporter.read(ctx(), Uri.fromFile(f));
        assertFalse(r.ok());
    }

    @Test
    public void missingFileFailsGracefully() {
        File f = new File(ctx().getCacheDir(), "nope-missing.txt");
        // noinspection ResultOfMethodCallIgnored
        f.delete();
        ScriptImporter.Result r =
                ScriptImporter.read(ctx(), Uri.fromFile(f));
        assertFalse(r.ok());
        assertTrue(r.error != null && !r.error.isEmpty());
    }

    @Test
    public void minimalDocxExtractsParagraphs() throws Exception {
        File f = new File(ctx().getCacheDir(), "script.docx");
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(f));
        try {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<document><body>"
                    + "<p><r><t>Hello</t></r><r><t> world</t></r></p>"
                    + "<p><r><t>Second line</t></r></p>"
                    + "</body></document>";
            zip.write(xml.getBytes("UTF-8"));
            zip.closeEntry();
        } finally {
            zip.close();
        }
        ScriptImporter.Result r =
                ScriptImporter.read(ctx(), Uri.fromFile(f));
        assertTrue("ok=" + r.error, r.ok());
        assertTrue(r.docx);
        assertTrue("text=" + r.text, r.text.contains("Hello world"));
        assertTrue("text=" + r.text, r.text.contains("Second line"));
        assertTrue("paragraph break lost: " + r.text,
                r.text.contains("\n"));
    }

    @Test
    public void blankDocxParagraphsSurviveAsBlankLines() throws Exception {
        File f = new File(ctx().getCacheDir(), "blanks.docx");
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(f));
        try {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<document><body>"
                    + "<p><r><t>First</t></r></p>"
                    + "<p></p>"
                    + "<p><r><t>Third</t></r></p>"
                    + "</body></document>";
            zip.write(xml.getBytes("UTF-8"));
            zip.closeEntry();
        } finally {
            zip.close();
        }
        ScriptImporter.Result r =
                ScriptImporter.read(ctx(), Uri.fromFile(f));
        assertTrue("ok=" + r.error, r.ok());
        assertTrue("blank paragraph collapsed: " + r.text,
                r.text.contains("First\n\nThird"));
    }

    @Test
    public void zipWithoutDocumentFailsGracefully() throws Exception {        File f = new File(ctx().getCacheDir(), "empty.docx");
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(f));
        try {
            zip.putNextEntry(new ZipEntry("other.txt"));
            zip.write("nope".getBytes("UTF-8"));
            zip.closeEntry();
        } finally {
            zip.close();
        }
        ScriptImporter.Result r =
                ScriptImporter.read(ctx(), Uri.fromFile(f));
        assertFalse(r.ok());
        assertTrue(r.docx);
    }
}
