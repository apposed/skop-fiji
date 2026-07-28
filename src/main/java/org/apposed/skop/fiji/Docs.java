/*-
 * #%L
 * scikit-ops in Fiji: every op, as its own SciJava command.
 * %%
 * Copyright (C) 2026 scikit-ops developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.apposed.skop.fiji;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reading a Google-style docstring for the text a dialog shows.
 * <p>
 * An op's docstring is the only description it has, and it is written for a
 * reader of Python. Most of it transfers unchanged: the summary line becomes
 * the menu tooltip, and each {@code Args:} entry becomes the tooltip on one
 * widget. Nothing here reformats or reflows -- an op author's words are the
 * ones a user should see.
 *
 * @author Curtis Rueden
 */
public final class Docs {

	private Docs() {
		// Prevent instantiation of utility class.
	}

	/** Section headings a Google-style docstring may use. */
	private static final String[] SECTIONS = {
		"Args:", "Arguments:", "Returns:", "Yields:", "Raises:", "Note:",
		"Notes:", "Example:", "Examples:", "Attributes:", "References:"
	};

	/**
	 * The first line of a docstring: what a menu tooltip should say.
	 *
	 * @param doc the docstring, or null.
	 * @return the summary line, or null if there is no docstring.
	 */
	public static String summary(String doc) {
		if (doc == null) return null;
		String trimmed = doc.trim();
		if (trimmed.isEmpty()) return null;
		int end = trimmed.indexOf('\n');
		return (end < 0 ? trimmed : trimmed.substring(0, end)).trim();
	}

	/**
	 * The {@code Args:} entries, by parameter name.
	 * <p>
	 * An entry is {@code name: description}, and its description continues
	 * onto any following lines indented further than the name is. Continuation
	 * lines are joined with spaces, because a tooltip is one paragraph and the
	 * line breaks in the source are there for an 88-column file rather than
	 * for a reader.
	 *
	 * @param doc the docstring, or null.
	 * @return one description per documented parameter; empty if there is no
	 *         {@code Args:} section.
	 */
	public static Map<String, String> args(String doc) {
		Map<String, String> result = new LinkedHashMap<>();
		if (doc == null) return result;

		String[] lines = doc.split("\n", -1);
		int start = -1;
		for (int i = 0; i < lines.length; i++) {
			String stripped = lines[i].trim();
			if (stripped.equals("Args:") || stripped.equals("Arguments:")) {
				start = i + 1;
				break;
			}
		}
		if (start < 0) return result;

		String name = null;
		StringBuilder text = new StringBuilder();
		int nameIndent = -1;

		for (int i = start; i < lines.length; i++) {
			String line = lines[i];
			String stripped = line.trim();
			if (stripped.isEmpty()) continue;
			if (isSection(stripped)) break;

			int indent = line.length() - stripped.length();
			int colon = stripped.indexOf(':');
			boolean isEntry = colon > 0 && (nameIndent < 0 || indent <= nameIndent) &&
				isIdentifier(stripped.substring(0, colon));

			if (isEntry) {
				put(result, name, text);
				name = stripped.substring(0, colon);
				nameIndent = indent;
				text = new StringBuilder(stripped.substring(colon + 1).trim());
			}
			else if (name != null) {
				if (text.length() > 0) text.append(' ');
				text.append(stripped);
			}
		}
		put(result, name, text);
		return result;
	}

	private static void put(Map<String, String> into, String name,
		StringBuilder text)
	{
		if (name != null && text.length() > 0) into.put(name, text.toString());
	}

	private static boolean isSection(String line) {
		for (String section : SECTIONS) {
			if (line.equals(section)) return true;
		}
		return false;
	}

	/**
	 * Whether a string could be a Python parameter name.
	 * <p>
	 * This is what separates {@code "image: Image to threshold."} from a
	 * sentence in a previous entry that happens to contain a colon.
	 */
	private static boolean isIdentifier(String text) {
		if (text.isEmpty()) return false;
		if (!Character.isJavaIdentifierStart(text.charAt(0))) return false;
		for (int i = 1; i < text.length(); i++) {
			char c = text.charAt(i);
			if (!Character.isJavaIdentifierPart(c)) return false;
		}
		return true;
	}
}
