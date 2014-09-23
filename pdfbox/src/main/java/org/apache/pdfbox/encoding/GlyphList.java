/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pdfbox.encoding;

import java.net.URL;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.StringTokenizer;

/**
 * PostScript glyph list, maps glyph names to Unicode characters.
 */
public class GlyphList
{
    private static final Log LOG = LogFactory.getLog(GlyphList.class);
    public static final GlyphList DEFAULT;
    public static final GlyphList ZAPF_DINGBATS;

    static
    {
        try
        {
            DEFAULT = new GlyphList();

            // Loads the official glyph List based on adobes glyph list
            DEFAULT.loadGlyphs("org/apache/pdfbox/resources/glyphlist/glyphlist.properties");

            // Loads some additional glyph mappings
            DEFAULT.loadGlyphs("org/apache/pdfbox/resources/glyphlist/additional_glyphlist.properties");

            // Load an external glyph list file that user can give as JVM property
            try
            {
                String location = System.getProperty("glyphlist_ext");
                if (location != null)
                {
                    // not supported in 2.0, see PDFBOX-2379
                    throw new UnsupportedOperationException("glyphlist_ext is no longer supported, " +
                      "use GlyphList.DEFAULT.addGlyphs(Properties) instead");
                }
            }
            catch (SecurityException e)  // can occur on System.getProperty
            {
                // PDFBOX-1946 ignore and continue
            }

            // Zapf Dingbats has its own glyph list
            ZAPF_DINGBATS = new GlyphList();
            ZAPF_DINGBATS.loadGlyphs("org/apache/pdfbox/resources/glyphlist/zapf_dingbats.properties");
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private final Map<String, String> nameToUnicode = new HashMap<String, String>();
    private final Map<String, String> unicodeToName = new HashMap<String, String>();

    private GlyphList()
    {
    }

    private void loadGlyphs(String resourceName) throws IOException
    {
        URL url = GlyphList.class.getClassLoader().getResource(resourceName);
        if (url == null)
        {
            throw new MissingResourceException("Glyphlist not found: " + resourceName,
                    GlyphList.class.getName(), resourceName);
        }

        Properties properties = new Properties();
        properties.load(url.openStream());
        addGlyphs(properties);
    }

    /**
     * Adds a glyph list stored in a .properties file to this GlyphList.
     *
     * @param properties Glyphlist in the form Name=XXXX where X is Unicode hex.
     * @throws IOException if the properties could not be read
     */
    public synchronized void addGlyphs(Properties properties) throws IOException
    {
        Enumeration<?> names = properties.propertyNames();
        for (Object name : Collections.list(names))
        {
            String glyphName = name.toString();
            String unicodeValue = properties.getProperty(glyphName);
            StringTokenizer tokenizer = new StringTokenizer(unicodeValue, " ", false);
            StringBuilder value = new StringBuilder();
            while (tokenizer.hasMoreTokens())
            {
                int characterCode = Integer.parseInt(tokenizer.nextToken(), 16);
                value.append((char) characterCode);
            }
            String unicode = value.toString();

            if (nameToUnicode.containsKey(glyphName))
            {
                LOG.warn("duplicate value for " + glyphName + " -> " + unicode + " " +
                        nameToUnicode.get(glyphName));
            }
            else
            {
                nameToUnicode.put(glyphName, unicode);
            }

            // reverse mapping
            if (!unicodeToName.containsKey(unicode))
            {
                unicodeToName.put(unicode, glyphName);
            }
        }
    }

    /**
     * This will take a character code and get the name from the code.
     *
     * @param c Unicode character
     * @return PostScript glyph name, or ".notdef"
     */
    public String unicodeToName(char c)
    {
        String name = unicodeToName.get(Character.toString(c));
        if (name == null)
        {
            return ".notdef";
        }
        return name;
    }

    /**
     * Returns the Unicode character sequence for the given glyph name, or null if there isn't any.
     *
     * @param name PostScript glyph name
     * @return Unicode character(s), or null.
     */
    public String toUnicode(String name)
    {
        if (name == null)
        {
            return null;
        }

        String unicode = nameToUnicode.get(name);
        if (unicode == null)
        {
            // test if we have a suffix and if so remove it
            if (name.indexOf('.') > 0)
            {
                unicode = toUnicode(name.substring(0, name.indexOf('.')));
            }
            else if (name.startsWith("uni") && name.length() == 7)
            {
                // test for Unicode name in the format uniXXXX where X is hex
                int nameLength = name.length();
                StringBuilder uniStr = new StringBuilder();
                try
                {
                    for (int chPos = 3; chPos + 4 <= nameLength; chPos += 4)
                    {
                        int codePoint = Integer.parseInt(name.substring(chPos, chPos + 4), 16);
                        if (codePoint > 0xD7FF && codePoint < 0xE000)
                        {
                            LOG.warn("Unicode character name with disallowed code area: " + name);
                        }
                        else
                        {
                            uniStr.append((char) codePoint);
                        }
                    }
                    unicode = uniStr.toString();
                }
                catch (NumberFormatException nfe)
                {
                    LOG.warn("Not a number in Unicode character name: " + name);
                }
            }
            else if (name.startsWith("u") && name.length() == 5)
            {
                // test for an alternate Unicode name representation uXXXX
                try
                {
                    int codePoint = Integer.parseInt(name.substring(1), 16);
                    if (codePoint > 0xD7FF && codePoint < 0xE000)
                    {
                        LOG.warn("Unicode character name with disallowed code area: " + name);
                    }
                    else
                    {
                        unicode = String.valueOf((char) codePoint);
                    }
                }
                catch (NumberFormatException nfe)
                {
                    LOG.warn("Not a number in Unicode character name: " + name);
                }
            }
            nameToUnicode.put(name, unicode);
        }
        return unicode;
    }
}
