/*
 * (C) Copyright 2006-2007 Nuxeo SAS (http://nuxeo.com/) and contributors.
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
 *
 * $Id: TestMemoryDirectory.java 30381 2008-02-20 20:12:09Z gracinet $
 */

package org.nuxeo.ecm.directory.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.directory.BaseSession;
import org.nuxeo.ecm.directory.DirectoryException;
import org.nuxeo.runtime.test.NXRuntimeTestCase;

/**
 * @author Florent Guillaume
 *
 */
public class TestMemoryDirectory extends NXRuntimeTestCase {

    MemoryDirectory memDir;

    MemoryDirectorySession dir;

    DocumentModel entry;

    static final String SCHEMA_NAME = "myschema";

    @Before
    public void setUp() throws Exception {
        super.setUp();

        deployBundle("org.nuxeo.ecm.core.schema");
        deployContrib("org.nuxeo.ecm.directory.core.tests", "test-schema.xml");

        Set<String> schemaSet = new HashSet<String>(Arrays.asList("i", "pw",
                "a", "int", "b"));
        memDir = new MemoryDirectory("mydir", SCHEMA_NAME, schemaSet, "i", "pw");
        dir = (MemoryDirectorySession) memDir.getSession();
        Map<String, Object> e1 = new HashMap<String, Object>();
        e1.put("i", "1");
        e1.put("pw", "secr");
        e1.put("a", "AAA");
        e1.put("b", "BCD");
        e1.put("int", 3);
        e1.put("x", "XYZ"); // shouldn't be put in storage
        entry = dir.createEntry(e1);
    }

    @Test
    public void testSchemaIntrospection() throws Exception {
        MemoryDirectory md = new MemoryDirectory("adir", SCHEMA_NAME, "i", "pw");
        assertEquals(new HashSet<String>(Arrays.asList("i", "pw", "a", "int",
                "b", "x")), md.schemaSet);
    }

    @Test
    public void testCreate() throws Exception {
        // created in setUp
        assertEquals("1", entry.getProperty(SCHEMA_NAME, "i"));
        assertEquals("secr", entry.getProperty(SCHEMA_NAME, "pw"));
        assertEquals("AAA", entry.getProperty(SCHEMA_NAME, "a"));
        assertEquals("BCD", entry.getProperty(SCHEMA_NAME, "b"));
        assertNull(entry.getProperty(SCHEMA_NAME, "x"));

        // create one with the same id, must fail
        Map<String, Object> e2 = new HashMap<String, Object>();
        e2.put("i", "1");
        try {
            entry = dir.createEntry(e2);
            fail("Should raise an error, entry already exists");
        } catch (DirectoryException e) {
        }
    }

    @Test
    public void testCreateFromModel() throws Exception {
        DocumentModel entry = BaseSession.createEntryModel(null, SCHEMA_NAME,
                null, null);
        entry.setProperty(SCHEMA_NAME, "i", "yo");

        assertNull(dir.getEntry("yo"));
        dir.createEntry(entry);
        assertNotNull(dir.getEntry("yo"));

        // create one with existing same id, must fail
        entry.setProperty(SCHEMA_NAME, "i", "1");
        try {
            entry = dir.createEntry(entry);
            fail("Should raise an error, entry already exists");
        } catch (DirectoryException e) {
        }
    }

    @Test
    public void testHasEntry() throws Exception {
        assertTrue(dir.hasEntry("1"));
        assertFalse(dir.hasEntry("foo"));
    }

    @Test
    public void testAuthenticate() throws Exception {
        assertTrue(dir.authenticate("1", "secr"));
        assertFalse(dir.authenticate("1", "haha"));
        assertFalse(dir.authenticate("2", "any"));
    }

    @Test
    public void testGetEntry() throws Exception {
        DocumentModel entry = dir.getEntry("1");
        assertEquals("AAA", entry.getProperty(SCHEMA_NAME, "a"));
        assertNull(dir.getEntry("no-such-entry"));
    }

    @Test
    public void testGetEntries() throws Exception {
        Map<String, Object> e2 = new HashMap<String, Object>();
        e2.put("i", "2");
        entry = dir.createEntry(e2);
        DocumentModelList l = dir.getEntries();
        assertEquals(2, l.size());
        assertEquals("1", l.get(0).getId());
        assertEquals("2", l.get(1).getId());
    }

    @Test
    public void testUpdateEntry() throws Exception {
        DocumentModel e = dir.getEntry("1");
        assertEquals("BCD", e.getProperty(SCHEMA_NAME, "b"));
        e.setProperty(SCHEMA_NAME, "b", "babar");
        dir.updateEntry(e);
        e = dir.getEntry("1");
        assertEquals("babar", e.getProperty(SCHEMA_NAME, "b"));

        String id = "no-such-entry";
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("i", id);
        DocumentModel entry = BaseSession.createEntryModel(null,
                SCHEMA_NAME, id, map);
        try {
            dir.updateEntry(entry);
        } catch (DirectoryException de) {
            assertEquals("UpdateEntry failed: entry 'no-such-entry' not found",
                    de.getMessage());
        }
    }

    @Test
    public void testDeleteEntry() throws Exception {
        DocumentModelList l = dir.getEntries();
        assertEquals(1, l.size());
        dir.deleteEntry("1");
        l = dir.getEntries();
        assertEquals(0, l.size());
    }

    @Test
    public void testQuery() throws Exception {
        Map<String, Object> e2 = new HashMap<String, Object>();
        e2.put("i", "2");
        e2.put("pw", "guess");
        e2.put("a", "AAA222");
        e2.put("b", "BCD");
        dir.createEntry(e2);

        Map<String, Serializable> filter = new HashMap<String, Serializable>();
        DocumentModelList entries;
        DocumentModel e;

        // empty filter means everything (like getEntries)
        entries = dir.query(filter);
        assertNotNull(entries);
        assertEquals(2, entries.size());
        // filter with no known field is same as empty
        filter.put("bobo", "bibi");
        entries = dir.query(filter);
        assertNotNull(entries);
        assertEquals(2, entries.size());

        // no result
        filter.clear();
        filter.put("a", "gaga");
        entries = dir.query(filter);
        assertEquals(0, entries.size());

        // no fulltext
        filter.put("a", "A");
        entries = dir.query(filter);
        assertEquals(0, entries.size());

        // simple query
        filter.put("a", "AAA");
        entries = dir.query(filter);
        assertEquals(1, entries.size());

        e = entries.get(0);
        assertEquals("1", e.getId());
        assertEquals("BCD", e.getProperty(SCHEMA_NAME, "b"));

        // add unknown field
        filter.put("bobo", "bibi");
        entries = dir.query(filter);
        assertEquals(1, entries.size());
        assertEquals("1", entries.get(0).getId());

        // two criteria
        filter.clear();
        filter.put("a", "AAA");
        filter.put("b", "BCD");
        entries = dir.query(filter);
        assertEquals(1, entries.size());

        e = entries.get(0);
        assertEquals("1", e.getId());
        assertEquals("secr", e.getProperty(SCHEMA_NAME, "pw"));

        // query not matching although each criterion matches one entry
        filter.put("a", "AAA");
        filter.put("pw", "guess");
        entries = dir.query(filter);
        assertEquals(0, entries.size());
    }

    @Test
    public void testQueryFts() throws Exception {
        Map<String, Serializable> filter = new HashMap<String, Serializable>();
        Set<String> fulltext = new HashSet<String>();

        // trying to cheat
        filter.put("a", "*");
        assertEquals(0, dir.query(filter, fulltext).size());

        // fulltext
        filter.clear();
        fulltext.add("b");
        // only initial match
        filter.put("b", "c");
        assertEquals(0, dir.query(filter, fulltext).size());
        // lowercase initial match
        filter.put("b", "b");
        assertEquals(1, dir.query(filter, fulltext).size());

        // 2nd criterion not matching
        filter.put("a", "a");
        assertEquals(0, dir.query(filter, fulltext).size());

        // 2nd criterion matching
        filter.put("a", "AAA");
        assertEquals(1, dir.query(filter, fulltext).size());

        // 2nd criterion matching as fulltext
        filter.put("a", "a");
        fulltext.add("a");
        assertEquals(1, dir.query(filter, fulltext).size());

        // empty filter marked fulltext should match all
        filter.clear();
        fulltext.clear();
        filter.put("a", "");
        fulltext.add("a");
        assertEquals(1, dir.query(filter, fulltext).size());
    }

    @Test
    public void testGetProjection() throws Exception {
        List<String> list;
        Map<String, Serializable> filter = new HashMap<String, Serializable>();
        Map<String, Object> e2 = new HashMap<String, Object>();
        e2.put("i", "2");
        e2.put("pw", "guess");
        e2.put("a", "AAA222");
        e2.put("b", "BCD");
        dir.createEntry(e2);

        // empty filter
        list = dir.getProjection(filter, "a");
        assertEquals(2, list.size());

        // XXX test projection on unknown column

        // simple query
        filter.put("a", "AAA");
        list = dir.getProjection(filter, "b");
        assertEquals(1, list.size());
        assertEquals("BCD", list.get(0));

        // add unknown field
        filter.put("bobo", "bibi");
        list = dir.getProjection(filter, "a");
        assertEquals(1, list.size());
        assertEquals("AAA", list.get(0));

        // two criteria
        filter.clear();
        filter.put("a", "AAA");
        filter.put("b", "BCD");
        list = dir.getProjection(filter, "a");
        assertEquals(1, list.size());
        assertEquals("AAA", list.get(0));

        // query not matching although each criterion matches one entry
        filter.put("a", "AAA");
        filter.put("pw", "guess");
        list = dir.getProjection(filter, "a");
        assertEquals(0, list.size());
    }

    protected static List<String> entryIds(List<DocumentModel> entries) {
        List<String> ids = new ArrayList<String>(entries.size());
        for (DocumentModel entry : entries) {
            ids.add(entry.getId());
        }
        return ids;
    }

    // actually tests AbstractDirectory.orderEntry
    @Test
    public void testOrderBy() throws Exception {
        Map<String, Object> e2 = new HashMap<String, Object>();
        e2.put("i", "2");
        e2.put("pw", "guess");
        e2.put("a", "ZZZ");
        e2.put("b", "AAA");
        dir.createEntry(e2);

        Map<String, Serializable> filter = Collections.emptyMap();
        Set<String> fulltext = Collections.emptySet();
        Map<String, String> orderBy = new LinkedHashMap<String, String>();

        // our data:
        // 1 -> AAA, BCD
        // 2 -> ZZZ, AAA

        // a

        orderBy.clear();
        orderBy.put("a", "asc");
        DocumentModelList entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("1", "2"), entryIds(entries));

        orderBy.put("a", "desc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("2", "1"), entryIds(entries));

        // b

        orderBy.clear();
        orderBy.put("b", "asc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("2", "1"), entryIds(entries));

        orderBy.put("b", "desc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("1", "2"), entryIds(entries));

        // a then b

        orderBy.clear();
        orderBy.put("a", "asc");
        orderBy.put("b", "asc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("1", "2"), entryIds(entries));

        orderBy.clear();
        orderBy.put("a", "asc");
        orderBy.put("b", "desc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("1", "2"), entryIds(entries));

        orderBy.clear();
        orderBy.put("a", "desc");
        orderBy.put("b", "asc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("2", "1"), entryIds(entries));

        orderBy.clear();
        orderBy.put("a", "desc");
        orderBy.put("b", "desc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("2", "1"), entryIds(entries));

        // b then a

        orderBy.clear();
        orderBy.put("b", "asc");
        orderBy.put("a", "asc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("2", "1"), entryIds(entries));

        orderBy.clear();
        orderBy.put("b", "asc");
        orderBy.put("a", "desc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("2", "1"), entryIds(entries));

        orderBy.clear();
        orderBy.put("b", "desc");
        orderBy.put("a", "asc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("1", "2"), entryIds(entries));

        orderBy.clear();
        orderBy.put("b", "desc");
        orderBy.put("a", "desc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("1", "2"), entryIds(entries));

        // with an equality case

        DocumentModel entry = dir.getEntry("2");
        entry.setProperty(SCHEMA_NAME, "a", "AAA");
        dir.updateEntry(entry);

        // our data:
        // 1 -> AAA, BCD
        // 2 -> AAA, AAA

        orderBy.clear();
        orderBy.put("a", "asc");
        orderBy.put("b", "asc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("2", "1"), entryIds(entries));

        orderBy.clear();
        orderBy.put("a", "asc");
        orderBy.put("b", "desc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("1", "2"), entryIds(entries));

        orderBy.clear();
        orderBy.put("a", "desc");
        orderBy.put("b", "asc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("2", "1"), entryIds(entries));

        orderBy.clear();
        orderBy.put("a", "desc");
        orderBy.put("b", "desc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("1", "2"), entryIds(entries));

        // check number ordering

        entry = dir.getEntry("1");
        entry.setProperty(SCHEMA_NAME, "int", 2);
        dir.updateEntry(entry);
        entry = dir.getEntry("2");
        entry.setProperty(SCHEMA_NAME, "int", 10);
        dir.updateEntry(entry);

        orderBy.clear();
        orderBy.put("a", "asc");
        entries = dir.query(filter, fulltext, orderBy);
        assertEquals(Arrays.asList("1", "2"), entryIds(entries));
    }

}
