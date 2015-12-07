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
package org.apache.sis.referencing.operation.transform;

import java.util.Arrays;
import javax.measure.unit.Unit;
import javax.measure.quantity.Angle;
import javax.measure.quantity.Length;
import org.opengis.util.FactoryException;
import org.opengis.parameter.ParameterDescriptor;
import org.opengis.parameter.ParameterDescriptorGroup;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.datum.Ellipsoid;
import org.opengis.referencing.operation.Matrix;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.MathTransformFactory;
import org.opengis.referencing.operation.TransformException;
import org.apache.sis.internal.referencing.provider.DatumShiftGridFile;
import org.apache.sis.internal.referencing.provider.FranceGeocentricInterpolation;
import org.apache.sis.internal.referencing.provider.Molodensky;
import org.apache.sis.internal.util.Constants;
import org.apache.sis.metadata.iso.citation.Citations;
import org.apache.sis.parameter.Parameters;
import org.apache.sis.parameter.ParameterBuilder;
import org.apache.sis.referencing.datum.DatumShiftGrid;
import org.apache.sis.util.resources.Errors;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.util.Debug;


/**
 * Transforms between two geographic CRS by performing geocentric translations interpolated from a grid file.
 * This transform is used mainly for <cite>"France geocentric interpolation"</cite> (ESPG:9655) datum shifts,
 * but Apache SIS implementation allows the use for other regions.
 *
 * <div class="section">Algorithm</div>
 * This class transforms two- or three- dimensional coordinates from a geographic CRS to another geographic CRS.
 * The changes between source and target coordinates are small (usually less than 400 metres), but vary for every
 * position. Those changes are provided in a {@linkplain DatumShiftGrid datum shift grid}, usually loaded from one
 * or two files.
 *
 * <p>Many datum shift grids like NADCON and NTv2 apply the changes directly on geographic coordinates.
 * This relatively simple case is handled by {@link InterpolatedTransform}.
 * But the {@code InterpolatedGeocentricTransform} algorithm uses the grid in a more complex way:</p>
 *
 * <ol>
 *   <li>Convert input geographic coordinate (λ,φ) to geocentric coordinate (X,Y,Z).</li>
 *   <li>Ask {@link DatumShiftGrid} for the offset to apply for coordinate (λ,φ).
 *       But instead of returning a (Δλ, Δφ) offset, the grid shall return a (ΔX, ΔY, ΔZ) offset.</li>
 *   <li>Convert the shifted geocentric coordinate (X+ΔX, Y+ΔY, Z+ΔZ) back to a geographic coordinate.</li>
 * </ol>
 *
 * <div class="note"><b>Source:</b> IGN document {@code NTG_88.pdf},
 * <cite>"Grille de paramètres de transformation de coordonnées"</cite>
 * at <a href="http://www.ign.fr">http://www.ign.fr</a>.
 * Note however that the signs of (ΔX, ΔY, ΔZ) values expected by this class are the opposite of the
 * signs used in NTG_88 document. This is because NTG_88 grid defines shifts from target to source,
 * while this class expects shifts from source to target.
 *
 * <p><b>Note:</b> this algorithm is not the same as a (theoretical) {@link EllipsoidToCentricTransform} →
 * {@link InterpolatedTransform} → (inverse of {@code EllipsoidToCentricTransform}) concatenation
 * because the {@code DatumShiftGrid} inputs are geographic coordinates even if the interpolated
 * grid values are in geocentric space.</p></div>
 *
 * <div class="section">Implementation</div>
 * While this transformation is conceptually defined as a translation in geocentric coordinates, the current
 * Apache SIS implementation rather uses the {@linkplain MolodenskyTransform Molodensy} (non-abridged) approximation
 * for performance reasons. Errors are less than 3 centimetres for the France geocentric interpolation.
 * By comparison, the finest accuracy reported in the grid file for France is 5 centimetres.
 *
 * @author  Simon Reynard (Geomatys)
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.7
 * @version 0.7
 * @module
 */
public class InterpolatedGeocentricTransform extends MolodenskyFormula {
    /**
     * Serial number for inter-operability with different versions.
     */
    private static final long serialVersionUID = -5691721806681489940L;

    /**
     * Parameter descriptor to use with the contextual parameters for the forward transformation.
     * We do not use the <cite>"France geocentric interpolation"</cite> (ESPG:9655) descriptor
     * because their "forward" transformation is our "inverse" transformation, and conversely.
     * The {@code DESCRIPTOR} defined here is non-standard, but allows this class to be used
     * for other geographic areas than France.
     */
    private static final ParameterDescriptorGroup DESCRIPTOR;

    /**
     * Parameter descriptor to use with the contextual parameters for the inverse transformation.
     * We do not use the <cite>"France geocentric interpolation"</cite> (ESPG:9655) descriptor
     * because it is specific to a single country, has hard-coded parameters and uses a sign
     * convention for (ΔX,ΔY,ΔZ) translations different than the one used in this class.
     * The {@code INVERSE} descriptor defined here is non-standard, but allows this class
     * to be used for other geographic areas than France.
     */
    private static final ParameterDescriptorGroup INVERSE;
    static {
        final ParameterBuilder builder = new ParameterBuilder()
                .setRequired(true).setCodeSpace(Citations.SIS, Constants.SIS);
        final ParameterDescriptor<?>[] param = new ParameterDescriptor<?>[] {
                Molodensky.DIMENSION,
                Molodensky.SRC_SEMI_MAJOR,
                Molodensky.SRC_SEMI_MINOR,
                Molodensky.TGT_SEMI_MAJOR,
                Molodensky.TGT_SEMI_MINOR,
                FranceGeocentricInterpolation.FILE
        };
        DESCRIPTOR = builder.addName("Geocentric interpolation").createGroup(param);
        INVERSE = builder.addName("Geocentric inverse interpolation").createGroup(param);
    }

    /**
     * The inverse of this interpolated geocentric transform.
     *
     * @see #inverse()
     */
    private final InterpolatedGeocentricTransform inverse;

    /**
     * Constructs the inverse of an interpolated geocentric transform.
     *
     * @param inverse The transform for which to create the inverse.
     * @param source  The source ellipsoid of the given {@code inverse} transform.
     * @param target  The target ellipsoid of the given {@code inverse} transform.
     */
    InterpolatedGeocentricTransform(final InterpolatedGeocentricTransform inverse, Ellipsoid source, Ellipsoid target) {
        super(inverse, source, target, INVERSE);
        this.inverse = inverse;
    }

    /**
     * Creates a transform from the specified parameters.
     * This {@code InterpolatedGeocentricTransform} class expects ordinate values in the following order and units:
     * <ol>
     *   <li>longitudes in <strong>radians</strong> relative to the prime meridian (usually Greenwich),</li>
     *   <li>latitudes in <strong>radians</strong>,</li>
     *   <li>optionally heights above the ellipsoid, in same units than the source ellipsoid axes.</li>
     * </ol>
     *
     * For converting geographic coordinates in degrees, {@code InterpolatedGeocentricTransform} instances
     * need to be concatenated with the following affine transforms:
     *
     * <ul>
     *   <li><cite>Normalization</cite> before {@code InterpolatedGeocentricTransform}:<ul>
     *     <li>Conversion of (λ,φ) from degrees to radians.</li>
     *   </ul></li>
     *   <li><cite>Denormalization</cite> after {@code InterpolatedGeocentricTransform}:<ul>
     *     <li>Conversion of (λ,φ) from radians to degrees.</li>
     *   </ul></li>
     * </ul>
     *
     * After {@code InterpolatedGeocentricTransform} construction,
     * the full conversion chain including the above affine transforms can be created by
     * <code>{@linkplain #getContextualParameters()}.{@linkplain ContextualParameters#completeTransform
     * completeTransform}(factory, this)}</code>.
     *
     * @param source      The source ellipsoid.
     * @param isSource3D  {@code true} if the source coordinates have a height.
     * @param target      The target ellipsoid.
     * @param isTarget3D  {@code true} if the target coordinates have a height.
     * @param grid        The grid of datum shifts from source to target datum.
     *                    The {@link DatumShiftGrid#interpolateInCell DatumShiftGrid.interpolateInCell(…)} method
     *                    shall compute (ΔX, ΔY, ΔZ) translations from <em>source</em> to <em>target</em> in the
     *                    unit of source ellipsoid axes.
     *
     * @see #createGeodeticTransformation(MathTransformFactory, Ellipsoid, boolean, Ellipsoid, boolean, DatumShiftGrid)
     */
    protected InterpolatedGeocentricTransform(final Ellipsoid source, final boolean isSource3D,
                                              final Ellipsoid target, final boolean isTarget3D,
                                              final DatumShiftGrid<Angle,Length> grid)
    {
        super(source, isSource3D,
              target, isTarget3D,
              grid.getCellMean(0),
              grid.getCellMean(1),
              grid.getCellMean(2),
              grid, false, DESCRIPTOR);

        final int dim = grid.getTranslationDimensions();
        if (dim != 3) {
            throw new MismatchedDimensionException(Errors.format(Errors.Keys.MismatchedDimension_3, "grid", 3, dim));
        }
        Object unit = "ratio";
        if (grid.isCellValueRatio() || (unit = grid.getTranslationUnit()) != source.getAxisUnit()) {
            throw new IllegalArgumentException(Errors.format(Errors.Keys.IllegalUnitFor_2, "translation", unit));
        }
        if (isSource3D || isTarget3D) {
            inverse = new Inverse(this, source, target);
        } else {
            inverse = new InterpolatedGeocentricTransform2D.Inverse(this, source, target);
        }
    }

    /**
     * Creates a transformation between two geographic CRS. This factory method combines the
     * {@code InterpolatedGeocentricTransform} instance with the steps needed for converting values between
     * degrees to radians. The transform works with input and output coordinates in the following units:
     *
     * <ol>
     *   <li>longitudes in <strong>degrees</strong> relative to the prime meridian (usually Greenwich),</li>
     *   <li>latitudes in <strong>degrees</strong>,</li>
     *   <li>optionally heights above the ellipsoid, in same units than the source ellipsoids axes.</li>
     * </ol>
     *
     * Note however that the given {@code grid} instance shall expect geographic coordinates (λ,φ)
     * in <strong>radians</strong>.
     *
     * @param factory     The factory to use for creating the transform.
     * @param source      The source ellipsoid.
     * @param isSource3D  {@code true} if the source coordinates have a height.
     * @param target      The target ellipsoid.
     * @param isTarget3D  {@code true} if the target coordinates have a height.
     * @param grid        The grid of datum shifts from source to target datum.
     *                    The {@link DatumShiftGrid#interpolateInCell DatumShiftGrid.interpolateInCell(…)} method
     *                    shall compute (ΔX, ΔY, ΔZ) translations from <em>source</em> to <em>target</em> in the
     *                    unit of source ellipsoid axes.
     * @return The transformation between geographic coordinates in degrees.
     * @throws FactoryException if an error occurred while creating a transform.
     */
    public static MathTransform createGeodeticTransformation(final MathTransformFactory factory,
            final Ellipsoid source, final boolean isSource3D,
            final Ellipsoid target, final boolean isTarget3D,
            final DatumShiftGrid<Angle,Length> grid) throws FactoryException
    {
        ArgumentChecks.ensureNonNull("grid", grid);
        final InterpolatedGeocentricTransform tr;
        if (isSource3D || isTarget3D) {
            tr = new InterpolatedGeocentricTransform(source, isSource3D, target, isTarget3D, grid);
        } else {
            tr = new InterpolatedGeocentricTransform2D(source, target, grid);
        }
        tr.inverse.context.completeTransform(factory, null);
        return tr.context.completeTransform(factory, tr);
    }

    /**
     * Invoked by constructor and by {@link #getParameterValues()} for setting all parameters other than axis lengths.
     *
     * @param pg         Where to set the parameters.
     * @param semiMinor  The semi minor axis length, in unit of {@code unit}.
     * @param unit       The unit of measurement to declare.
     * @param Δf         Ignored.
     */
    @Override
    final void completeParameters(final Parameters pg, final double semiMinor, final Unit<?> unit, double Δf) {
        super.completeParameters(pg, semiMinor, unit, Δf);
        if (pg != context) {
            Δf = Δfmod / semiMinor;
            pg.getOrCreate(Molodensky.AXIS_LENGTH_DIFFERENCE).setValue(Δa, unit);
            pg.getOrCreate(Molodensky.FLATTENING_DIFFERENCE) .setValue(Δf, Unit.ONE);
        }
        if (grid instanceof DatumShiftGridFile<?,?>) {
            ((DatumShiftGridFile<?,?>) grid).setFileParameters(pg);
        }
    }

    /**
     * Transforms the (λ,φ) or (λ,φ,<var>h</var>) coordinates between two geographic CRS,
     * and optionally returns the derivative at that location.
     *
     * @return {@inheritDoc}
     * @throws TransformException if the point can not be transformed or
     *         if a problem occurred while calculating the derivative.
     */
    @Override
    public Matrix transform(final double[] srcPts, final int srcOff,
                            final double[] dstPts, final int dstOff,
                            final boolean derivate) throws TransformException
    {
        final double[] vector = new double[3];
        final double λ = srcPts[srcOff];
        final double φ = srcPts[srcOff+1];
        grid.interpolateAtNormalized(λ, φ, vector);
        return transform(λ, φ, isSource3D ? srcPts[srcOff+2] : 0,
                dstPts, dstOff, vector[0], vector[1], vector[2], null, derivate);
    }

    /**
     * Transforms the (λ,φ) or (λ,φ,<var>h</var>) coordinates between two geographic CRS.
     * This method performs the same work than the above
     * {@link #transform(double[], int, double[], int, boolean) transform(…)} method,
     * but on an arbitrary amount of coordinates and without computing derivative.
     *
     * @throws TransformException if a point can not be transformed.
     */
    @Override
    public void transform(double[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) throws TransformException {
        int srcInc = isSource3D ? 3 : 2;
        int dstInc = isTarget3D ? 3 : 2;
        int offFinal = 0;
        double[] dstFinal  = null;
        if (srcPts == dstPts) {
            switch (IterationStrategy.suggest(srcOff, srcInc, dstOff, dstInc, numPts)) {
                case ASCENDING: {
                    break;
                }
                case DESCENDING: {
                    srcOff += (numPts-1) * srcInc;  srcInc = -srcInc;
                    dstOff += (numPts-1) * dstInc;  dstInc = -dstInc;
                    break;
                }
                default: {  // BUFFER_SOURCE, but also a reasonable default for any case.
                    srcPts = Arrays.copyOfRange(srcPts, srcOff, srcOff + numPts*srcInc);
                    srcOff = 0;
                    break;
                }
                case BUFFER_TARGET: {
                    dstFinal = dstPts;
                    offFinal = dstOff;
                    dstPts = new double[numPts * dstInc];
                    dstOff = 0;
                    break;
                }
            }
        }
        final double[] offset = new double[3];
        while (--numPts >= 0) {
            final double λ = srcPts[srcOff  ];
            final double φ = srcPts[srcOff+1];
            grid.interpolateAtNormalized(λ, φ, offset);
            transform(λ, φ, isSource3D ? srcPts[srcOff+2] : 0,
                      dstPts, dstOff, offset[0], offset[1], offset[2], null, false);
            srcOff += srcInc;
            dstOff += dstInc;
        }
        if (dstFinal != null) {
            System.arraycopy(dstPts, 0, dstFinal, offFinal, dstPts.length);
        }
    }

    /*
     * NOTE: we do not bother to override the methods expecting a 'float' array because those methods should
     *       be rarely invoked. Since there is usually LinearTransforms before and after this transform, the
     *       conversion between float and double will be handle by those LinearTransforms.   If nevertheless
     *       this MolodenskyTransform is at the beginning or the end of a transformation chain,  the methods
     *       inherited from the subclass will work (but may be slightly slower).
     */

    /**
     * Returns the inverse of this interpolated geocentric transform.
     * The source ellipsoid of the returned transform will be the target ellipsoid of this transform, and conversely.
     *
     * @return A transform from the target ellipsoid to the source ellipsoid of this transform.
     */
    @Override
    public MathTransform inverse() {
        return inverse;
    }





    /**
     * The inverse of the enclosing {@link InterpolatedGeocentricTransform}.
     * This transform applies an algorithm similar to the one documented in the enclosing class,
     * with the following differences:
     *
     * <ol>
     *   <li>First, target coordinates are estimated using the ({@link #tX}, {@link #tY}, {@link #tZ}) translation.</li>
     *   <li>A new ({@code ΔX}, {@code ΔY}, {@code ΔZ}) translation is interpolated at the geographic coordinates found
     *       in above step, and target coordinates are recomputed again using that new translation.</li>
     * </ol>
     *
     * @author  Martin Desruisseaux (Geomatys)
     * @since   0.7
     * @version 0.7
     * @module
     */
    static class Inverse extends InterpolatedGeocentricTransform {
        /**
         * Serial number for inter-operability with different versions.
         */
        private static final long serialVersionUID = -3520896803296425651L;

        /**
         * Constructs the inverse of an interpolated geocentric transform.
         *
         * @param inverse The transform for which to create the inverse.
         * @param source  The source ellipsoid of the given {@code inverse} transform.
         * @param target  The target ellipsoid of the given {@code inverse} transform.
         */
        Inverse(final InterpolatedGeocentricTransform inverse, final Ellipsoid source, final Ellipsoid target) {
           super(inverse, source, target);
        }

        /**
         * Transforms the (λ,φ) or (λ,φ,<var>h</var>) coordinates between two geographic CRS,
         * and optionally returns the derivative at that location.
         *
         * @return {@inheritDoc}
         * @throws TransformException if the point can not be transformed or
         *         if a problem occurred while calculating the derivative.
         */
        @Override
        public Matrix transform(final double[] srcPts, final int srcOff,
                                final double[] dstPts, final int dstOff,
                                final boolean derivate) throws TransformException
        {
            return transform(srcPts[srcOff], srcPts[srcOff+1], isSource3D ? srcPts[srcOff+2] : 0,
                             dstPts, dstOff, tX, tY, tZ, new double[3], derivate);
        }

        /**
         * Transforms the (λ,φ) or (λ,φ,<var>h</var>) coordinates between two geographic CRS.
         * This method performs the same work than the above
         * {@link #transform(double[], int, double[], int, boolean) transform(…)} method,
         * but on an arbitrary amount of coordinates and without computing derivative.
         *
         * @throws TransformException if a point can not be transformed.
         */
        @Override
        public void transform(double[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) throws TransformException {
            int srcInc = isSource3D ? 3 : 2;
            int dstInc = isTarget3D ? 3 : 2;
            int offFinal = 0;
            double[] dstFinal  = null;
            if (srcPts == dstPts) {
                switch (IterationStrategy.suggest(srcOff, srcInc, dstOff, dstInc, numPts)) {
                    case ASCENDING: {
                        break;
                    }
                    case DESCENDING: {
                        srcOff += (numPts-1) * srcInc;  srcInc = -srcInc;
                        dstOff += (numPts-1) * dstInc;  dstInc = -dstInc;
                        break;
                    }
                    default: {  // BUFFER_SOURCE, but also a reasonable default for any case.
                        srcPts = Arrays.copyOfRange(srcPts, srcOff, srcOff + numPts*srcInc);
                        srcOff = 0;
                        break;
                    }
                    case BUFFER_TARGET: {
                        dstFinal = dstPts;
                        offFinal = dstOff;
                        dstPts = new double[numPts * dstInc];
                        dstOff = 0;
                        break;
                    }
                }
            }
            final double[] offset = new double[3];
            while (--numPts >= 0) {
                transform(srcPts[srcOff], srcPts[srcOff+1], isSource3D ? srcPts[srcOff+2] : 0,
                          dstPts, dstOff, tX, tY, tZ, offset, false);
                srcOff += srcInc;
                dstOff += dstInc;
            }
            if (dstFinal != null) {
                System.arraycopy(dstPts, 0, dstFinal, offFinal, dstPts.length);
            }
        }
    }

    /**
     * Returns a description of the internal parameters of this {@code InterpolatedGeocentricTransform} transform.
     * The returned group contains parameters for the source ellipsoid semi-axis lengths and the differences between
     * source and target ellipsoid parameters.
     *
     * <div class="note"><b>Note:</b>
     * this method is mostly for {@linkplain org.apache.sis.io.wkt.Convention#INTERNAL debugging purposes}
     * since the isolation of non-linear parameters in this class is highly implementation dependent.
     * Most GIS applications will instead be interested in the {@linkplain #getContextualParameters()
     * contextual parameters}.</div>
     *
     * @return A description of the internal parameters.
     */
    @Debug
    @Override
    public ParameterDescriptorGroup getParameterDescriptors() {
        final ParameterDescriptor<?>[] param = new ParameterDescriptor<?>[] {
                Molodensky.DIMENSION,
                Molodensky.SRC_SEMI_MAJOR,
                Molodensky.SRC_SEMI_MINOR,
                Molodensky.AXIS_LENGTH_DIFFERENCE,
                Molodensky.FLATTENING_DIFFERENCE,
                FranceGeocentricInterpolation.FILE
        };
        return new ParameterBuilder().setRequired(true)
                .setCodeSpace(Citations.SIS, Constants.SIS)
                .addName(context.getDescriptor().getName()).createGroup(param);
    }
}
