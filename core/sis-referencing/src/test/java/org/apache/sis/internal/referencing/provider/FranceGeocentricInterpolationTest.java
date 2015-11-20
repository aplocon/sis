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
package org.apache.sis.internal.referencing.provider;

import java.net.URL;
import java.net.URISyntaxException;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import org.opengis.geometry.Envelope;
import org.opengis.util.FactoryException;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.datum.Ellipsoid;
import org.opengis.referencing.operation.MathTransformFactory;
import org.opengis.referencing.operation.TransformException;
import org.apache.sis.internal.referencing.Formulas;
import org.apache.sis.internal.system.DefaultFactories;
import org.apache.sis.referencing.CommonCRS;
import org.apache.sis.referencing.datum.HardCodedDatum;
import org.apache.sis.referencing.operation.transform.MathTransformTestCase;
import org.apache.sis.test.DependsOnMethod;
import org.apache.sis.test.TestStep;
import org.junit.Test;

import static org.opengis.test.Assert.*;


/**
 * Tests {@link FranceGeocentricInterpolation}.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.7
 * @version 0.7
 * @module
 */
public final strictfp class FranceGeocentricInterpolationTest extends MathTransformTestCase {
    /**
     * Returns the sample point for a step in the example given by the NTG_88 guidance note.
     *
     * <blockquote><b>Source:</b>
     * <cite>"Grille de paramètres de transformation de coordonnées GRD3F97A"</cite>
     * version 1.0, April 1997 in <a href="http://www.ign.fr">http://www.ign.fr</a>
     * </blockquote>
     *
     * @param  step The step as a value from 1 to 3 inclusive.
     * @return The sample point at the given step.
     */
    private static double[] samplePoint(final int step) {
        switch (step) {
            case 1: return new double[] {
                         2 + (25 + 29.89599/60)/60, //  2°25′29.89599″  in RGF93
                        48 + (50 + 40.00502/60)/60  // 48°50′40.00502″
                    };
            case 2: return new double[] {           // Direction reversed compared to NTG_88 document.
                         168.253,                   // ΔX: Toward prime meridian
                          58.609,                   // ΔY: Toward 90° east
                        -320.170                    // ΔZ: Toward north pole
                    };
            case 3: return new double[] {
                         2 + (25 + 32.4187/60)/60,  //  2°25′32.4187″  in NTF
                        48 + (50 + 40.2441/60)/60   // 48°50′40.2441″
                    };
            default: throw new AssertionError(step);
        }
    }

    /**
     * Tests a small grid file with interpolations in geocentric coordinates.
     *
     * @throws URISyntaxException if the URL to the test file can not be converted to a path.
     * @throws IOException if an error occurred while loading the grid.
     * @throws FactoryException if an error occurred while computing the grid.
     */
    @Test
    public void testGrid() throws URISyntaxException, IOException, FactoryException {
        testGridAsShorts(testGridAsFloats());
    }

    /**
     * Tests a small grid file with interpolations in geocentric coordinates as {@code float} values.
     *
     * <p>This method is part of a chain.
     * The next method is {@link #testGridAsShorts(DatumShiftGridFile)}.</p>
     *
     * @return The loaded grid with values as {@code float}.
     * @throws URISyntaxException if the URL to the test file can not be converted to a path.
     * @throws IOException if an error occurred while loading the grid.
     * @throws FactoryException if an error occurred while computing the grid.
     */
    @TestStep
    private static DatumShiftGridFile testGridAsFloats() throws URISyntaxException, IOException, FactoryException {
        final URL url = FranceGeocentricInterpolationTest.class.getResource("GR3DF97A.txt");
        assertNotNull("Test file \"GR3DF97A.txt\" not found.", url);
        final Path file = Paths.get(url.toURI());
        final DatumShiftGridFile.Float grid;
        try (final BufferedReader in = Files.newBufferedReader(file)) {
            grid = FranceGeocentricInterpolation.load(in, file);
        }
        assertEquals("getCellValue",  168.196, grid.getCellValue(0, 2, 1), 0);
        assertEquals("getCellValue",   58.778, grid.getCellValue(1, 2, 1), 0);
        assertEquals("getCellValue", -320.127, grid.getCellValue(2, 2, 1), 0);
        verifyGrid(grid);
        return grid;
    }

    /**
     * Tests a small grid file with interpolations in geocentric coordinates as {@code short} values.
     *
     * <p>This method is part of a chain.
     * The previous method is {@link #testGridAsFloats()}.</p>
     *
     * @param  grid The grid created by {@link #testGridAsFloats()}.
     * @return The given grid, but compressed as {@code short} values.
     */
    @TestStep
    private static DatumShiftGridFile testGridAsShorts(DatumShiftGridFile grid) {
        grid = DatumShiftGridCompressed.compress((DatumShiftGridFile.Float) grid, new double[] {168, 60, -320}, 0.001);
        assertInstanceOf("Failed to compress 'float' values into 'short' values.", DatumShiftGridCompressed.class, grid);
        assertEquals("getCellValue",  168.196, ((DatumShiftGridCompressed) grid).getCellValue(0, 2, 1), 0);
        assertEquals("getCellValue",   58.778, ((DatumShiftGridCompressed) grid).getCellValue(1, 2, 1), 0);
        assertEquals("getCellValue", -320.127, ((DatumShiftGridCompressed) grid).getCellValue(2, 2, 1), 0);
        verifyGrid(grid);
        return grid;
    }

    /**
     * Verify the envelope and the interpolation performed by the given grid.
     */
    private static void verifyGrid(final DatumShiftGridFile grid) {
        final Envelope envelope = grid.getDomainOfValidity();
        assertEquals("xmin",  2.2 - 0.05, envelope.getMinimum(0), 1E-10);
        assertEquals("xmax",  2.5 + 0.05, envelope.getMaximum(0), 1E-10);
        assertEquals("ymin", 48.5 - 0.05, envelope.getMinimum(1), 1E-10);
        assertEquals("ymax", 49.0 + 0.05, envelope.getMaximum(1), 1E-10);
        assertEquals("shiftDimensions", 3, grid.getShiftDimensions());
        /*
         * Interpolate the (ΔX, ΔY, ΔZ) at a point.
         */
        final double[] point    = samplePoint(1);
        final double[] expected = samplePoint(2);
        final double[] offset   = new double[3];
        grid.offsetAt(point[0], point[1], offset);
        assertArrayEquals("(ΔX, ΔY, ΔZ)", expected, offset, 0.0005);
    }

    /**
     * Tests the {@link FranceGeocentricInterpolation#getOrLoad(Path, double[], double)} method and its cache.
     *
     * @throws URISyntaxException if the URL to the test file can not be converted to a path.
     * @throws FactoryException if an error occurred while computing the grid.
     */
    @Test
    @DependsOnMethod("testGrid")
    public void testGetOrLoad() throws URISyntaxException, FactoryException {
        final URL file = FranceGeocentricInterpolationTest.class.getResource("GR3DF97A.txt");
        assertNotNull("Test file \"GR3DF97A.txt\" not found.", file);
        final DatumShiftGridFile grid = FranceGeocentricInterpolation.getOrLoad(
                Paths.get(file.toURI()), new double[] {168, 60, -320}, 0.001);
        verifyGrid(grid);
        assertSame("Expected a cached value.", grid, FranceGeocentricInterpolation.getOrLoad(
                Paths.get(file.toURI()), new double[] {168, 60, -320}, 0.001));
    }

    /**
     * Creates the <cite>"France geocentric interpolation"</cite> transform.
     *
     * @param  file The grid to load.
     * @throws FactoryException if an error occurred while loading the grid.
     */
    private void create(final URL file) throws FactoryException {
        final Ellipsoid source = HardCodedDatum.NTF.getEllipsoid();     // Clarke 1880 (IGN)
        final Ellipsoid target = CommonCRS.ETRS89.ellipsoid();          // GRS 1980 ellipsoid
        final FranceGeocentricInterpolation provider = new FranceGeocentricInterpolation();
        final ParameterValueGroup values = provider.getParameters().createValue();
        values.parameter("src_semi_major").setValue(source.getSemiMajorAxis());
        values.parameter("src_semi_minor").setValue(source.getSemiMinorAxis());
        values.parameter("tgt_semi_major").setValue(target.getSemiMajorAxis());
        values.parameter("tgt_semi_minor").setValue(target.getSemiMinorAxis());
        values.parameter("Geocentric translations file").setValue(file);    // Automatic conversion from URL to Path.
        transform = provider.createMathTransform(DefaultFactories.forBuildin(MathTransformFactory.class), values);
        tolerance = Formulas.ANGULAR_TOLERANCE;
    }

    /**
     * Tests transformation of sample point from RGF93 to NTF.
     * We call this transformation "forward" because it uses the grid values directly,
     * without doing first an approximation followed by an iteration.
     *
     * @throws FactoryException if an error occurred while loading the grid.
     * @throws TransformException if an error occurred while transforming the coordinate.
     */
    @Test
    @DependsOnMethod("testGetOrLoad")
    public void testForwardTransform() throws FactoryException, TransformException {
        final URL file = FranceGeocentricInterpolationTest.class.getResource("GR3DF97A.txt");
        assertNotNull("Test file \"GR3DF97A.txt\" not found.", file);
        create(file);
        isInverseTransformSupported = false;
        verifyTransform(samplePoint(1), samplePoint(3));
        /*
         * Input:     2.424971108333333    48.84444583888889
         * Expected:  2.425671861111111    48.84451225
         * Actual:    2.4256718922236735   48.84451219111167
         */
    }
}
