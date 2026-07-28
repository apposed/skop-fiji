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

import net.imagej.Dataset;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

import org.scijava.convert.AbstractConverter;
import org.scijava.convert.Converter;
import org.scijava.plugin.Plugin;

/**
 * Lets a plain image be handed to a parameter that wants a labeling.
 * <p>
 * An op declaring a {@code labels} input gets an {@link ImgLabeling} item,
 * which is the right type and, on its own, an unusable one: a user who has
 * just opened a label image from disk has a {@link Dataset}, and the harvester
 * would offer them an empty list. Registering the conversion is what makes the
 * type choice free rather than a tax -- the widget offers Datasets, and this
 * turns the chosen one into a labeling on the way through.
 * <p>
 * A pixel value of 0 is background and a value of <em>i</em> is object
 * <em>i</em>, which is the convention every op here already follows. Nothing
 * is guessed beyond that: a non-integer image is rounded, because a user
 * pointing a labels parameter at a float image has said what they mean.
 *
 * @author Curtis Rueden
 */
@Plugin(type = Converter.class)
public class DatasetToLabeling extends
	AbstractConverter<Dataset, ImgLabeling<String, UnsignedShortType>>
{

	@Override
	@SuppressWarnings("unchecked")
	public <T> T convert(Object src, Class<T> dest) {
		if (!(src instanceof Dataset)) return null;
		Dataset dataset = (Dataset) src;
		return (T) Labelings.fromIndexImage(indexImage(dataset));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static RandomAccessibleInterval<UnsignedShortType> indexImage(
		Dataset dataset)
	{
		RandomAccessibleInterval raw = dataset.getImgPlus();
		if (dataset.firstElement() instanceof UnsignedShortType) return raw;
		// A view rather than a copy; ImgLabeling only ever reads it.
		return Converters.convert(
			(RandomAccessibleInterval<RealType<?>>) raw,
			(in, out) -> out.set((int) Math.round(in.getRealDouble())),
			new UnsignedShortType());
	}

	@Override
	public Class<Dataset> getInputType() {
		return Dataset.class;
	}

	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public Class<ImgLabeling<String, UnsignedShortType>> getOutputType() {
		return (Class) ImgLabeling.class;
	}
}
