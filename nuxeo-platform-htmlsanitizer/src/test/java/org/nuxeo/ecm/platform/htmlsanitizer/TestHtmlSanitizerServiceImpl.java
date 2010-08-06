/*
 * (C) Copyright 2006-2010 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.platform.htmlsanitizer;

import static org.junit.Assert.assertEquals;
import junit.framework.Assert;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import com.google.inject.Inject;

@RunWith(FeaturesRunner.class)
@Features(CoreFeature.class)
@Deploy({"org.nuxeo.ecm.platform.htmlsanitizer",
    "org.nuxeo.ecm.platform.htmlsanitizer.test:OSGI-INF/core-types-contrib.xml"})
public class TestHtmlSanitizerServiceImpl {

    public static final String BAD_HTML = "<b>foo<script>bar</script></b>";

    public static final String SANITIZED_HTML = "<b>foo</b>";

    // script tag is added here just to be sure sanitizer is not run
    public static final String WIKI_MARKUP = "<script></script>[image:http://server/path/image.jpg My Image]";

    @Inject
    CoreSession session;

    @Test
    public void sanitizeNote() throws Exception {
        DocumentModel doc = session.createDocumentModel("/", "n", "Note");
        doc.setProperty("note", "note", BAD_HTML);
        doc = session.createDocument(doc);
        String note = (String) doc.getProperty("note", "note");
        assertEquals(SANITIZED_HTML, note);
        session.save();
        doc.setProperty("note", "note", BAD_HTML);
        doc = session.saveDocument(doc);
        note = (String) doc.getProperty("note", "note");
        assertEquals(SANITIZED_HTML, note);
    }

    @Test
    public void sanitizeWebPage() throws Exception {

        // Html page that must be sanitized
        DocumentModel doc = session.createDocumentModel("/", "wp", "WebPage");
        doc.setPropertyValue("webp:content", BAD_HTML);
        doc.setPropertyValue("webp:isRichtext", true);
        doc = session.createDocument(doc);
        String webpage = (String) doc.getPropertyValue("webp:content");
        assertEquals(SANITIZED_HTML, webpage);
        session.save();

        // Wiki page that must not be sanitized
        DocumentModel doc2 = session.createDocumentModel("/", "wp2", "WebPage");
        doc2.setPropertyValue("webp:content", WIKI_MARKUP);
        doc2.setPropertyValue("webp:isRichtext", false);
        doc2 = session.createDocument(doc2);
        String webpage2 = (String) doc2.getPropertyValue("webp:content");
        assertEquals(WIKI_MARKUP, webpage2);
        session.save();

        DocumentModel doc3 = session.createDocumentModel("/", "wp3", "WebPage");
        doc3.setPropertyValue("webp:content", BAD_HTML);
        doc3.setPropertyValue("webp:isRichtext", false);
        doc3 = session.createDocument(doc3);
        String webpage3 = (String) doc3.getPropertyValue("webp:content");
        assertEquals(BAD_HTML, webpage3);
        session.save();

        DocumentModel doc4 = session.createDocumentModel("/", "wp4", "WebPage");
        doc4.setPropertyValue("webp:content", WIKI_MARKUP);
        doc4.setPropertyValue("webp:isRichtext", true);
        doc4 = session.createDocument(doc4);
        String webpage4 = (String) doc4.getPropertyValue("webp:content");
        Assert.assertFalse(WIKI_MARKUP.equals(webpage4));
        session.save();
    }

}
