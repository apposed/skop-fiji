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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * The environment recipes this plugin carries, for a Fiji with no scikit-ops
 * checkout.
 * <p>
 * {@code bin/sync-envs.sh} copies scikit-ops' {@code envs/<id>/pixi.toml} into
 * this jar, and each recipe pins scikit-ops from GitHub. That pin is what a
 * worker imports when no checkout is put in front of it -- so a user's Fiji
 * runs exactly the scikit-ops the recipes name, and a developer's, with
 * {@code SKOP_CHECKOUT} set, runs their own checkout instead.
 * <p>
 * Appose builds from a file, not a resource, so the recipes are unpacked to
 * {@code ~/.skop/fiji/envs-<hash>/}. The hash is over their contents: a
 * plugin carrying new recipes unpacks beside the old ones rather than over
 * them, and an unchanged plugin finds its directory already there.
 */
public final class BundledEnvs {

	private static final String BASE = "/org/apposed/skop/fiji/envs/";

	private BundledEnvs() {}

	/** The environment IDs this plugin carries, in sync-envs order. */
	public static List<String> ids() throws IOException {
		List<String> ids = new ArrayList<>();
		try (InputStream in = open("index.txt");
			BufferedReader lines = new BufferedReader(new InputStreamReader(in,
				StandardCharsets.UTF_8)))
		{
			for (String line; (line = lines.readLine()) != null;) {
				if (!line.trim().isEmpty()) ids.add(line.trim());
			}
		}
		return ids;
	}

	/**
	 * Unpacks the recipes, once, and says where.
	 *
	 * @return a directory holding {@code <id>/pixi.toml} for every carried
	 *         environment: what {@link SkopRunner} takes as its envs directory.
	 */
	public static synchronized File unpack() throws IOException {
		List<String> ids = ids();
		List<String> files = new ArrayList<>();
		for (String id : ids) {
			files.add(id + "/pixi.toml");
			if (BundledEnvs.class.getResource(BASE + id + "/init.py") != null) {
				files.add(id + "/init.py");
			}
		}
		File home = new File(new File(System.getProperty("user.home"), ".skop"),
			"fiji");
		File dir = new File(home, "envs-" + hash(files));
		if (new File(dir, "index.txt").isFile()) return dir;

		// Written to a temporary sibling and renamed into place, so a Fiji
		// killed halfway leaves no half-unpacked directory that looks whole.
		File partial = new File(home, dir.getName() + ".partial");
		for (String file : files) {
			File target = new File(partial, file);
			target.getParentFile().mkdirs();
			try (InputStream in = open(file)) {
				Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		try (InputStream in = open("index.txt")) {
			Files.copy(in, new File(partial, "index.txt").toPath(),
				StandardCopyOption.REPLACE_EXISTING);
		}
		if (!partial.renameTo(dir) && !new File(dir, "index.txt").isFile()) {
			throw new IOException("Could not move " + partial + " to " + dir);
		}
		return dir;
	}

	private static InputStream open(String file) throws IOException {
		InputStream in = BundledEnvs.class.getResourceAsStream(BASE + file);
		if (in == null) {
			throw new IOException("This plugin carries no " + file +
				"; was it built without running bin/sync-envs.sh?");
		}
		return in;
	}

	private static String hash(List<String> files) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			for (String file : files) {
				digest.update(file.getBytes(StandardCharsets.UTF_8));
				try (InputStream in = open(file)) {
					byte[] buffer = new byte[8192];
					for (int n; (n = in.read(buffer)) > 0;) digest.update(buffer, 0, n);
				}
			}
			StringBuilder hex = new StringBuilder();
			byte[] bytes = digest.digest();
			for (int i = 0; i < 8; i++) hex.append(String.format("%02x", bytes[i]));
			return hex.toString();
		}
		catch (java.security.NoSuchAlgorithmException exc) {
			throw new IOException(exc);
		}
	}
}
