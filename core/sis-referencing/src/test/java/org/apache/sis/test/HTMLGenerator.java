/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sis.test;

import java.util.Deque;
import java.util.ArrayDeque;
import java.util.Locale;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import org.opengis.util.InternationalString;
import org.apache.sis.util.CharSequences;
import org.apache.sis.util.Deprecable;


/**
 * Base class of all classes used to generate HTML pages to be published on
 * the <a href="http://sis.apache.org/">http://sis.apache.org/</a> web site.
 *
 * <p>This class creates files in the current default directory. It is user's responsibility
 * to move the files to the appropriate Apache SIS {@code "content/"} site directory.</p>
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.6
 * @version 0.6
 * @module
 */
public abstract class HTMLGenerator implements java.io.Closeable {
    /**
     * The encoding of the files to generate.
     */
    private static final String ENCODING = "UTF-8";

    /**
     * The language to use for the reports to generate.
     *
     * @see #toLocalizedString(InternationalString)
     */
    protected static final Locale LOCALE = Locale.US;

    /**
     * The number of space to add or remove in the {@linkplain #margin}
     * when new HTML elements are opened or closed.
     */
    private static final int INDENTATION = 2;

    /**
     * Where to write the HTML page.
     */
    private final BufferedWriter out;

    /**
     * The spaces to write in the margin before every new line.
     * The number of spaces will increase by the indentation amount when new elements are opened.
     *
     * @see #INDENTATION
     */
    private String margin = "";

    /**
     * HTML tags currently opened.
     */
    private final Deque<String> openedTags;

    /**
     * Creates a new instance which will write in the given file.
     * This constructor immediately writes the HTML header up to the {@code <body>} line, inclusive.
     *
     * @param  filename The name of the file where to write.
     * @param  title The document title.
     * @throws IOException if the file can not be created (e.g. because it already exists).
     */
    protected HTMLGenerator(final String filename, final String title) throws IOException {
        final File file = new File(filename);
        if (file.exists()) {
            throw new IOException("File " + file.getAbsolutePath() + " already exists.");
        }
        openedTags = new ArrayDeque<String>();
        out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), ENCODING));
        out.write("<!DOCTYPE html>");
        out.newLine();
        out.write("<!--");
        out.newLine();
        out.write("  This page is automatically generated by the following class in the test directory:");
        out.newLine();
        out.write("  ");
        out.write(getClass().getCanonicalName());
        out.newLine();
        out.write("-->");
        out.newLine();
        openTag("html");
        final int head = openTag("head");
        out.write(margin);
        out.write("<meta charset=\"" + ENCODING + "\"/>");
        out.newLine();
        println("title", title);
        openTag("style type=\"text/css\" media=\"all\"");
        println("@import url(\"./reports.css\");");
        closeTags(head);
        openTag("body");
    }

    /**
     * Escapes the {@code &}, {@code <} and {@code >} characters.
     *
     * @param  text The text to escape, or {@code null}.
     * @return The escaped text, or {@code null} if the given text was null.
     */
    protected static CharSequence escape(CharSequence text) {
        text = CharSequences.replace(text, "&", "&amp;");
        text = CharSequences.replace(text, "<", "&lt;");
        text = CharSequences.replace(text, ">", "&gt;");
        return text;
    }

    /**
     * Return the given HTML tag without the attributes. For example if {@code tag} is
     * {@code "table class='param'"}, then this method returns only {@code "table"}.
     */
    private static String omitAttributes(String tag) {
        final int s = tag.indexOf(' ');
        if (s >= 0) {
            tag = tag.substring(0, s);
        }
        return tag;
    }

    /**
     * Opens a new HTML tag and increase the indentation.
     *
     * @param  tag The HTML tag without brackets (e.g. {@code "h2"}).
     * @return The value to give to {@link #closeTags(int)} for closing the tags.
     * @throws IOException if an error occurred while writing to the file.
     */
    protected final int openTag(final String tag) throws IOException {
        out.write(margin);
        out.write('<');
        out.write(tag);
        out.write('>');
        out.newLine();
        margin = CharSequences.spaces(margin.length() + INDENTATION).toString();
        final int openedTag = openedTags.size();
        openedTags.add(omitAttributes(tag));
        return openedTag;
    }

    /**
     * Closes the last HTML tag if it is equals to the given element, and opens a new tag on the same line.
     *
     * @param  tag The HTML tag without brackets (e.g. {@code "h2"}).
     * @throws IOException if an error occurred while writing to the file.
     */
    protected final void reopenTag(final String tag) throws IOException {
        final String tagWithoutAttributes = omitAttributes(tag);
        if (openedTags.getLast().equals(tagWithoutAttributes)) {
            out.write(CharSequences.spaces(margin.length() - INDENTATION).toString());
            out.write("</");
            out.write(tagWithoutAttributes);
            out.write("><");
            out.write(tag);
            out.write('>');
            out.newLine();
        } else {
            openTag(tag);
        }
    }

    /**
     * Closes the HTML tag identified by the given number, together will all child tags.
     *
     * @param  openedTag The value returned by the {@link #openTag(String)} matching the tag to close.
     * @throws IOException if an error occurred while writing to the file.
     */
    protected final void closeTags(final int openedTag) throws IOException {
        while (openedTags.size() != openedTag) {
            margin = CharSequences.spaces(margin.length() - INDENTATION).toString();
            out.write(margin);
            out.write("</");
            out.write(openedTags.removeLast());
            out.write('>');
            out.newLine();
        }
    }

    /**
     * Writes the given text in the given HTML element.
     * The {@code &}, {@code <} and {@code >} characters are <strong>not</strong> escaped.
     * For escaping those characters, invoke <code>println(tag, {@linkplain #escape(CharSequence) escape}(value))</code>.
     *
     * @param  tag The HTML tag without brackets (e.g. {@code "h1"}).
     * @param  value The text to write, or {@code null} for none.
     * @throws IOException if an error occurred while writing to the file.
     */
    protected final void println(final String tag, final CharSequence value) throws IOException {
        out.write(margin);
        out.write('<');
        out.write(tag);
        out.write('>');
        if (value != null) {
            out.write(value.toString());
        }
        out.write("</");
        final int s = tag.indexOf(' ');
        out.write(tag, 0, (s >= 0) ? s : tag.length());
        out.write('>');
        out.newLine();
    }

    /**
     * Writes the given text on its own line, then write EOL sequence.
     * The {@code &}, {@code <} and {@code >} characters are <strong>not</strong> escaped.
     * For escaping those characters, invoke <code>println({@linkplain #escape(CharSequence) escape}(value))</code>.
     *
     * @param  value The text to write, or {@code null} if none.
     * @throws IOException if an error occurred while writing to the file.
     */
    protected final void println(final CharSequence value) throws IOException {
        if (value != null) {
            out.write(margin);
            out.write(value.toString());
            out.newLine();
        }
    }

    /**
     * Closes the HTML generator.
     *
     * @throws IOException if an error occurred while closing the output file.
     */
    @Override
    public void close() throws IOException {
        closeTags(0);
        out.close();
    }

    /**
     * Returns the localized version of the given string, or {@code null} if none.
     *
     * @param  text The text to localize, or {@code null}.
     * @return The localized test, or {@code null}.
     *
     * @see #LOCALE
     */
    protected static String toLocalizedString(final InternationalString text) {
        return (text != null) ? text.toString(LOCALE) : null;
    }

    /**
     * Returns {@code true} if the given object is deprecated.
     *
     * @param  object The object to test.
     * @return {@code true} if the given object is deprecated.
     */
    protected static boolean isDeprecated(final Object object) {
        return (object instanceof Deprecable) && ((Deprecable) object).isDeprecated();
    }
}
