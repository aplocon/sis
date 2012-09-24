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
package org.apache.sis.internal.taglet;

import java.io.File;
import java.util.Map;
import com.sun.javadoc.Tag;
import com.sun.javadoc.Doc;
import com.sun.javadoc.ClassDoc;
import com.sun.tools.doclets.Taglet;


/**
 * The <code>@module</code> tag. This tag expects no argument.
 *
 * @author  Martin Desruisseaux (IRD, Geomatys)
 * @since   0.3 (derived from geotk-2.2)
 * @version 0.3
 */
public final class Module implements Taglet {
    /**
     * SIS version to be referenced by this taglet.
     */
    private static final String VERSION = "0.3-incubating-SNAPSHOT";

    /**
     * Register this taglet.
     *
     * @param tagletMap the map to register this tag to.
     */
    public static void register(final Map<String,Taglet> tagletMap) {
       final Module tag = new Module();
       tagletMap.put(tag.getName(), tag);
    }

    /**
     * The base URL for Maven reports.
     */
    private static final String MAVEN_REPORTS_BASE_URL = "http://incubator.apache.org/sis/";

    /**
     * The base URL for Maven repository.
     * See <a href="http://www.apache.org/dev/repository-faq.html">ASF Jar Repositories</a>
     * for more information.
     */
    private static final String MAVEN_REPOSITORY_BASE_URL = "http://repository.apache.org/snapshots/";

    /**
     * The SIS module in which the <code>@module</code> taglet has been found.
     */
    private String module;

    /**
     * Constructs a default <code>@module</code> taglet.
     */
    private Module() {
        super();
    }

    /**
     * Returns the name of this custom tag.
     *
     * @return The tag name.
     */
    @Override
    public String getName() {
        return "module";
    }

    /**
     * Returns {@code false} since <code>@module</code> can not be used in overview.
     *
     * @return Always {@code false}.
     */
    @Override
    public boolean inOverview() {
        return false;
    }

    /**
     * Returns {@code true} since <code>@module</code> can be used in package documentation.
     *
     * @return Always {@code true}.
     */
    @Override
    public boolean inPackage() {
        return true;
    }

    /**
     * Returns {@code true} since <code>@module</code> can be used in type documentation
     * (classes or interfaces). This is actually its main target.
     *
     * @return Always {@code true}.
     */
    @Override
    public boolean inType() {
        return true;
    }

    /**
     * Returns {@code false} since <code>@module</code> can not be used in constructor
     *
     * @return Always {@code false}.
     */
    @Override
    public boolean inConstructor() {
        return false;
    }

    /**
     * Returns {@code false} since <code>@module</code> can not be used in method documentation.
     *
     * @return Always {@code false}.
     */
    @Override
    public boolean inMethod() {
        return false;
    }

    /**
     * Returns {@code false} since <code>@module</code> can not be used in field documentation.
     *
     * @return Always {@code false}.
     */
    @Override
    public boolean inField() {
        return false;
    }

    /**
     * Returns {@code false} since <code>@module</code> is not an inline tag.
     *
     * @return Always {@code false}.
     */
    @Override
    public boolean isInlineTag() {
        return false;
    }

    /**
     * Given the <code>Tag</code> representation of this custom tag, return its string representation.
     * The default implementation invokes the array variant of this method.
     *
     * @param tag The tag to format.
     * @return A string representation of the given tag.
     */
    @Override
    public String toString(final Tag tag) {
        return toString(new Tag[] {tag});
    }

    /**
     * Given an array of {@code Tag}s representing this custom tag, return its string
     * representation.
     *
     * @param tags The tags to format.
     * @return A string representation of the given tags.
     */
    @Override
    public String toString(final Tag[] tags) {
        if (tags==null || tags.length==0) {
            return "";
        }
        final StringBuilder buffer = new StringBuilder("\n<dt><b>Module:</b></dt>");
        for (int i=0; i<tags.length; i++) {
            final Tag tag = tags[i];
            File file = tag.position().file();
            module = file.getName();
            while (file != null) {
                file = file.getParentFile();
                if (file.getName().equals("src")) {
                    file = file.getParentFile();
                    if (file != null) {
                        module = file.getName();
                    }
                    break;
                }
            }
            buffer.append('\n').append(i==0 ? "<dd>" : "<br>")
                  .append("<table width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\">" +
                          "<tr>\n<td align=\"left\">");
            /*
             * Appends the module link.
             */
            openMavenReportLink(buffer);
            buffer.append("index.html\">").append(module).append("</a>");
            /*
             * Appends the "(download binary)" link.
             */
            buffer.append("\n<font size=\"-2\">(<a href=\"").append(MAVEN_REPOSITORY_BASE_URL)
                  .append("org/apache/sis/").append(module).append('/').append(VERSION).append('/')
                  .append("\">download</a>)</font>");
            /*
             * Appends the "View source code for this class" link.
             */
            buffer.append("\n</td><td align=\"right\">\n");
            final Doc holder = tag.holder();
            if (holder instanceof ClassDoc) {
                ClassDoc outer, doc = (ClassDoc) holder;
                while ((outer = doc.containingClass()) != null) {
                    doc = outer;
                }
                buffer.append(" &nbsp;&nbsp; ");
                openMavenReportLink(buffer);
                buffer.append("xref/").append(doc.qualifiedName())
                      .append(".html\">View source code for this class</a>");
            }
            buffer.append("\n</td></tr></table>");
        }
        return buffer.append("</dd>\n").toString();
    }

    /**
     * Opens a {@code <A HREF>} element toward the Maven report directory.
     * A trailing slash is included.
     *
     * @param buffer The buffer in which to write.
     */
    private void openMavenReportLink(final StringBuilder buffer) {
        buffer.append("<a href=\"").append(MAVEN_REPORTS_BASE_URL).append('/').append(module).append('/');
    }
}
