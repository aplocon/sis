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
 *
 * This package contains documentation from OGC specifications.
 * Open Geospatial Consortium's work is fully acknowledged here.
 */
package org.apache.sis.metadata.iso.extent;

import java.util.Collection;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import org.opengis.metadata.extent.GeographicExtent;
import org.opengis.metadata.extent.SpatialTemporalExtent;


/**
 * Extent with respect to date/time and spatial boundaries.
 *
 * @author  Martin Desruisseaux (IRD, Geomatys)
 * @author  Touraïvane (IRD)
 * @author  Cédric Briançon (Geomatys)
 * @since   0.3 (derived from geotk-2.1)
 * @version 0.3
 * @module
 */
@XmlType(name = "EX_SpatialTemporalExtent_Type")
@XmlRootElement(name = "EX_SpatialTemporalExtent")
public class DefaultSpatialTemporalExtent extends DefaultTemporalExtent implements SpatialTemporalExtent {
    /**
     * Serial number for inter-operability with different versions.
     */
    private static final long serialVersionUID = 821702768255546660L;

    /**
     * The spatial extent component of composite
     * spatial and temporal extent.
     */
    private Collection<GeographicExtent> spatialExtent;

    /**
     * Constructs an initially empty spatial-temporal extent.
     */
    public DefaultSpatialTemporalExtent() {
    }

    /**
     * Returns a SIS metadata implementation with the same values than the given arbitrary
     * implementation. If the given object is {@code null}, then this method returns {@code null}.
     * Otherwise if the given object is already a SIS implementation, then the given object is
     * returned unchanged. Otherwise a new SIS implementation is created and initialized to the
     * property values of the given object, using a <cite>shallow</cite> copy operation
     * (i.e. properties are not cloned).
     *
     * @param  object The object to get as a SIS implementation, or {@code null} if none.
     * @return A SIS implementation containing the values of the given object (may be the
     *         given object itself), or {@code null} if the argument was null.
     */
    public static DefaultSpatialTemporalExtent castOrCopy(final SpatialTemporalExtent object) {
        if (object == null || object instanceof DefaultSpatialTemporalExtent) {
            return (DefaultSpatialTemporalExtent) object;
        }
        final DefaultSpatialTemporalExtent copy = new DefaultSpatialTemporalExtent();
        copy.shallowCopy(object);
        return copy;
    }

    /**
     * Returns the spatial extent component of composite spatial and temporal extent.
     *
     * @return The list of geographic extents (never {@code null}).
     */
    @Override
    @XmlElement(name = "spatialExtent", required = true)
    public synchronized Collection<GeographicExtent> getSpatialExtent() {
        return spatialExtent = nonNullCollection(spatialExtent, GeographicExtent.class);
    }

    /**
     * Sets the spatial extent component of composite spatial and temporal extent.
     *
     * @param newValues The new spatial extent.
     */
    public synchronized void setSpatialExtent(final Collection<? extends GeographicExtent> newValues) {
        spatialExtent = copyCollection(newValues, spatialExtent, GeographicExtent.class);
    }
}
