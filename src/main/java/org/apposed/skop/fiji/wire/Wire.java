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
package org.apposed.skop.fiji.wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading the JSON that skop sends.
 * <p>
 * Appose hands back a {@link Map} of {@link Object}s, so every field access on
 * the far side of the boundary is a cast that could fail. These helpers make
 * the failure say which field and what was there instead, because the
 * alternative is a {@code ClassCastException} from somewhere in a spec tree
 * fifty ops long.
 * <p>
 * Numbers get their own treatment: a JSON parser is free to hand back an
 * {@code Integer}, a {@code Long} or a {@code BigDecimal} for the same
 * literal, and skop's {@code 2.0} default is a {@code float} on one side and
 * whatever fits on the other. So numbers are read through {@link Number}
 * rather than by casting to the type that happened to arrive.
 *
 * @author Curtis Rueden
 */
public final class Wire {

	private Wire() {
		// Prevent instantiation of utility class.
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> asMap(Object value, String what) {
		if (!(value instanceof Map)) throw wrong(what, "an object", value);
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> asList(Object value, String what) {
		if (!(value instanceof List)) throw wrong(what, "an array", value);
		return (List<Object>) value;
	}

	/** The named field as a nested object, or an empty map if it is absent. */
	public static Map<String, Object> map(Map<String, Object> data, String key) {
		Object value = data.get(key);
		if (value == null) return Collections.emptyMap();
		return asMap(value, key);
	}

	/** The named field as an array, or an empty list if it is absent. */
	public static List<Object> list(Map<String, Object> data, String key) {
		Object value = data.get(key);
		if (value == null) return Collections.emptyList();
		return asList(value, key);
	}

	/** The named field as a list of nested objects. */
	public static List<Map<String, Object>> maps(Map<String, Object> data, String key) {
		List<Object> raw = list(data, key);
		List<Map<String, Object>> result = new ArrayList<>(raw.size());
		for (Object item : raw) result.add(asMap(item, key + " element"));
		return result;
	}

	/** The named field as a string, which must be present. */
	public static String string(Map<String, Object> data, String key) {
		String value = optionalString(data, key);
		if (value == null) throw new IllegalArgumentException(
			"Missing required field '" + key + "' in " + data.keySet());
		return value;
	}

	/** The named field as a string, or null if it is absent or JSON null. */
	public static String optionalString(Map<String, Object> data, String key) {
		Object value = data.get(key);
		if (value == null) return null;
		if (!(value instanceof String)) throw wrong(key, "a string", value);
		return (String) value;
	}

	public static boolean bool(Map<String, Object> data, String key, boolean fallback) {
		Object value = data.get(key);
		if (value == null) return fallback;
		if (!(value instanceof Boolean)) throw wrong(key, "a boolean", value);
		return (Boolean) value;
	}

	public static int integer(Map<String, Object> data, String key, int fallback) {
		Object value = data.get(key);
		if (value == null) return fallback;
		return toInt(value, key);
	}

	/** One element of a JSON array of integers. */
	public static int toInt(Object value, String what) {
		if (!(value instanceof Number)) throw wrong(what, "a number", value);
		return ((Number) value).intValue();
	}

	/** A JSON array of integers, as an int array. */
	public static int[] ints(Map<String, Object> data, String key) {
		List<Object> raw = list(data, key);
		int[] result = new int[raw.size()];
		for (int i = 0; i < result.length; i++) result[i] = toInt(raw.get(i), key);
		return result;
	}

	/**
	 * A JSON array of integers where an element may be null, as an Integer array.
	 * <p>
	 * The one place this matters is {@code AdaptationPlan.mapping}, whose null
	 * means "this optional slot is left empty" -- which zero would silently be
	 * read as axis 0.
	 */
	public static Integer[] boxedInts(Map<String, Object> data, String key) {
		List<Object> raw = list(data, key);
		Integer[] result = new Integer[raw.size()];
		for (int i = 0; i < result.length; i++) {
			Object item = raw.get(i);
			result[i] = item == null ? null : toInt(item, key);
		}
		return result;
	}

	/** A JSON array of strings. */
	public static String[] strings(Map<String, Object> data, String key) {
		List<Object> raw = list(data, key);
		String[] result = new String[raw.size()];
		for (int i = 0; i < result.length; i++) {
			Object item = raw.get(i);
			if (!(item instanceof String)) throw wrong(key + " element", "a string", item);
			result[i] = (String) item;
		}
		return result;
	}

	/** A defensive copy of a JSON object, with its insertion order kept. */
	public static Map<String, Object> copy(Map<String, Object> data) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(data));
	}

	private static IllegalArgumentException wrong(String what, String expected, Object value) {
		String actual = value == null ? "null" : value.getClass().getSimpleName();
		return new IllegalArgumentException(
			"Expected " + what + " to be " + expected + ", but it was " + actual +
			": " + value);
	}
}
