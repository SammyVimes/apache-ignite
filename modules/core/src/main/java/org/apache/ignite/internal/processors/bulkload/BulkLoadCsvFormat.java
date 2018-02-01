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

package org.apache.ignite.internal.processors.bulkload;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/** A placeholder for bulk load CSV format parser options. */
public class BulkLoadCsvFormat extends BulkLoadFormat {

    /** Line separator pattern. */
    @NotNull public static final Pattern DEFAULT_LINE_SEP_RE = Pattern.compile("[\r\n]+");

    /** Field separator pattern. */
    @NotNull public static final Pattern DEFAULT_FIELD_SEP_RE = Pattern.compile(",");

    /** Quote characters */
    @NotNull public static final String DEFAULT_QUOTE_CHARS = "\"";

    /** Default escape sequence start characters. */
    @Nullable public static final String DEFAULT_ESCAPE_CHARS = null;

    /** Line comment start pattern. */
    @Nullable public static final Pattern DEFAULT_COMMENT_CHARS = null;

    /** Format name. */
    public static final String NAME = "CSV";

    @Nullable private Pattern lineSeparatorRe;
    @Nullable private Pattern fieldSeparatorRe;
    @Nullable private String quoteChars;
    @Nullable private Pattern commentChars;
    @Nullable private String escapeChars;

    /**
     * Creates the format description with null values. SQL parser and executing code
     * configures the settings via setters.
     */
    public BulkLoadCsvFormat() {
    }

    /**
     * Returns the name of the format.
     *
     * @return The name of the format.
     */
    @Override public String name() {
        return NAME;
    }

    /**
     * Returns the line separator pattern.
     *
     * @return The line separator pattern.
     */
    public @Nullable Pattern lineSeparatorRe() {
        return lineSeparatorRe;
    }

    /**
     * Sets the line separator pattern.
     *
     * @param lineSeparatorRe The line separator pattern.
     */
    public void lineSeparatorRe(@Nullable Pattern lineSeparatorRe) {
        this.lineSeparatorRe = lineSeparatorRe;
    }

    /**
     * Returns the field separator pattern.
     *
     * @return The field separator pattern.
     */
    public @Nullable Pattern fieldSeparatorRe() {
        return fieldSeparatorRe;
    }

    /**
     * Sets the field separator pattern.
     *
     * @param fieldSeparatorRe The field separator pattern.
     */
    public void fieldSeparatorRe(@Nullable Pattern fieldSeparatorRe) {
        this.fieldSeparatorRe = fieldSeparatorRe;
    }

    /**
     * Returns the quote characters.
     *
     * @return The quote characters.
     */
    public @Nullable String quoteChars() {
        return quoteChars;
    }

    /**
     * Sets the quote characters.
     *
     * @param quoteChars The quote characters.
     */
    public void quoteChars(@Nullable String quoteChars) {
        this.quoteChars = quoteChars;
    }

    /**
     * Returns the line comment start pattern.
     *
     * @return The line comment start pattern.
     */
    public @Nullable Pattern commentChars() {
        return commentChars;
    }

    /**
     * Sets the line comment start pattern.
     *
     * @param commentChars The line comment start pattern.
     */
    public void commentChars(@Nullable Pattern commentChars) {
        this.commentChars = commentChars;
    }

    /**
     * Returns the escape characters.
     *
     * @return The escape characters.
     */
    public @Nullable String escapeChars() {
        return escapeChars;
    }

    /**
     * Sets the escape characters.
     *
     * @param escapeChars The escape characters.
     */
    public void escapeChars(@Nullable String escapeChars) {
        this.escapeChars = escapeChars;
    }
}
