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
package org.apache.sis.feature;

import java.util.Map;
import java.util.HashMap;
import java.util.ConcurrentModificationException;
import org.opengis.metadata.maintenance.ScopeCode;
import org.opengis.metadata.quality.DataQuality;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.util.CorruptedObjectException;


/**
 * A feature in which only a small fraction of properties are expected to be provided. This implementation uses
 * a {@link Map} for its internal storage of properties. This consumes less memory than a plain array when we
 * know that the array may be long and likely to be full of {@code null} values.
 *
 * @author  Travis L. Pinney
 * @author  Johann Sorel (Geomatys)
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.5
 * @version 0.5
 * @module
 *
 * @see DenseFeature
 * @see DefaultFeatureType
 */
final class SparseFeature extends AbstractFeature {
    /**
     * For cross-version compatibility.
     */
    private static final long serialVersionUID = -4486200659005766093L;

    /**
     * A {@link #valuesKind} flag meaning that the {@link #properties} map contains raw values.
     */
    private static final byte VALUES = 0; // Must be zero, because we want it to be 'valuesKind' default value.

    /**
     * A {@link #valuesKind} flag meaning that the {@link #properties} map contains {@link Property} instances.
     */
    private static final byte PROPERTIES = 1;

    /**
     * A {@link #valuesKind} flag meaning that the {@link #properties} map is invalid.
     */
    private static final byte CORRUPTED = 2;

    /**
     * The properties (attributes, operations, feature associations) of this feature.
     *
     * Conceptually, values in this map are {@link Property} instances. However at first we will store
     * only the property <em>values</em>, and build the full {@code Property} objects only if they are
     * requested. The intend is to reduce the amount of allocated objects as much as possible, because
     * typical SIS applications may create a very large amount of features.
     *
     * @see #valuesKind
     */
    private final Map<String, Object> properties;

    /**
     * {@link #PROPERTIES} if the values in the {@link #properties} map are {@link Property} instances,
     * or {@link #VALUES} if the map contains only the "raw" property values.
     *
     * <p>This field is initially {@code VALUES}, and will be set to {@code PROPERTIES} only if at least
     * one {@code Property} instance has been requested. In such case, all property values will have been
     * wrapped into their appropriate {@code Property} instance.</p>
     */
    private byte valuesKind;

    /**
     * Creates a new feature of the given type.
     *
     * @param type Information about the feature (name, characteristics, <i>etc.</i>).
     */
    public SparseFeature(final DefaultFeatureType type) {
        super(type);
        properties = new HashMap<>();
    }

    /**
     * Returns the property (attribute, operation or association) of the given name.
     *
     * @param  name The property name.
     * @return The property of the given name.
     * @throws IllegalArgumentException If the given argument is not a property name of this feature.
     */
    @Override
    public Object getProperty(final String name) throws IllegalArgumentException {
        ArgumentChecks.ensureNonNull("name", name);
        /*
         * Wraps values in Property objects for all entries in the properties map,
         * if not already done. This operation is executed at most once per feature.
         */
        if (valuesKind != PROPERTIES) {
            if (!properties.isEmpty()) { // The map is typically empty when this method is first invoked.
                if (valuesKind != VALUES) {
                    throw new CorruptedObjectException(String.valueOf(getName()));
                }
                valuesKind = CORRUPTED;
                for (final Map.Entry<String, Object> entry : properties.entrySet()) {
                    final String key   = entry.getKey();
                    final Object value = entry.getValue();
                    if (entry.setValue(createProperty(key, value)) != value) {
                        throw new ConcurrentModificationException(key);
                    }
                }
            }
            valuesKind = PROPERTIES; // Set only on success.
        }
        return getPropertyInstance(name);
    }

    /**
     * Implementation of {@link #getProperty(String)} invoked when we know that the {@link #properties}
     * map contains {@code Property} instances (as opposed to their value).
     */
    private Property getPropertyInstance(final String name) throws IllegalArgumentException {
        assert valuesKind == PROPERTIES : valuesKind;
        Property property = (Property) properties.get(name);
        if (property == null) {
            property = createProperty(name);
            replace(name, null, property);
        }
        return property;
    }

    /**
     * Returns the value for the property of the given name.
     *
     * @param  name The property name.
     * @return The value for the given property, or {@code null} if none.
     * @throws IllegalArgumentException If the given argument is not an attribute or association name of this feature.
     */
    @Override
    public Object getPropertyValue(final String name) throws IllegalArgumentException {
        ArgumentChecks.ensureNonNull("name", name);
        final Object element = properties.get(name);
        if (element != null) {
            if (valuesKind == VALUES) {
                return element; // Most common case.
            } else if (element instanceof DefaultAttribute<?>) {
                return ((DefaultAttribute<?>) element).getValue();
            } else if (element instanceof DefaultAssociation) {
                return ((DefaultAssociation) element).getValue();
            } else if (valuesKind == PROPERTIES) {
                throw new IllegalArgumentException(unsupportedPropertyType(((Property) element).getName()));
            } else {
                throw new CorruptedObjectException(String.valueOf(getName()));
            }
        } else if (properties.containsKey(name)) {
            return null; // Null has been explicitely set.
        } else {
            return getDefaultValue(name);
        }
    }

    /**
     * Sets the value for the property of the given name.
     *
     * @param  name  The attribute name.
     * @param  value The new value for the given attribute (may be {@code null}).
     * @throws ClassCastException If the value is not assignable to the expected value class.
     * @throws IllegalArgumentException If the given value can not be assigned for an other reason.
     */
    @Override
    public void setPropertyValue(final String name, final Object value) throws IllegalArgumentException {
        ArgumentChecks.ensureNonNull("name", name);
        if (valuesKind == VALUES) {
            final Object previous = properties.put(name, value);
            /*
             * Slight optimisation:  if we replaced a previous value of the same class, then we can skip the
             * checks for name and type validity since those checks have been done previously. But if we add
             * a new value or a value of a different type, then we need to check the name and type validity.
             */
            if (previous == null || (value != null && previous.getClass() != value.getClass())) {
                final RuntimeException e = verifyValueType(name, value);
                if (e != null) {
                    replace(name, value, previous); // Restore the previous value.
                    throw e;
                }
            }
        } else if (valuesKind == PROPERTIES) {
            setPropertyValue(getPropertyInstance(name), value);
        } else {
            throw new CorruptedObjectException(String.valueOf(getName()));
        }
    }

    /**
     * Sets a value in the {@link #properties} map.
     *
     * @param name     The name of the property to set.
     * @param oldValue The old value, used for verification purpose.
     * @param newValue The new value.
     */
    private void replace(final String name, final Object oldValue, final Object newValue) {
        if (properties.put(name, newValue) != oldValue) {
            throw new ConcurrentModificationException(name);
        }
    }

    /**
     * Verifies if all current properties met the constraints defined by the feature type. This method returns
     * {@linkplain org.apache.sis.metadata.iso.quality.DefaultDataQuality#getReports() reports} for all invalid
     * properties, if any.
     */
    @Override
    public DataQuality quality() {
        if (valuesKind == VALUES) {
            final Validator v = new Validator(ScopeCode.FEATURE);
            for (final Map.Entry<String, Object> entry : properties.entrySet()) {
                v.validateAny(getPropertyType(entry.getKey()), entry.getValue());
            }
            return v.quality;
        }
        /*
         * Slower path when there is a possibility that user overridden the Property.quality() methods.
         */
        return super.quality();
    }

    /**
     * Returns a hash code value for this feature.
     *
     * @return A hash code value.
     */
    @Override
    public int hashCode() {
        return super.hashCode() + 37 * properties.hashCode();
    }

    /**
     * Compares this feature with the given object for equality.
     *
     * @return {@code true} if both objects are equal.
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        }
        if (super.equals(obj)) {
            return properties.equals(((SparseFeature) obj).properties);
        }
        return false;
    }
}