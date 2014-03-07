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
package org.apache.sis.parameter;

import java.util.List;
import java.util.ArrayList;
import java.io.Serializable;
import org.opengis.parameter.ParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.parameter.ParameterDescriptor;
import org.opengis.parameter.ParameterDescriptorGroup;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.GeneralParameterDescriptor;
import org.opengis.parameter.ParameterNotFoundException;
import org.opengis.parameter.InvalidParameterCardinalityException;
import org.apache.sis.util.resources.Errors;
import org.apache.sis.util.ArgumentChecks;

import static org.apache.sis.referencing.IdentifiedObjects.isHeuristicMatchForName;

// Related to JDK7
import java.util.Objects;


/**
 * A group of related parameter values.
 *
 * <p>This class defines the following convenience methods:</p>
 * <ul>
 *   <li>{@link #parameter(String)} searches for a single parameter value of the given name.</li>
 *   <li>{@link #groups(String)} searches for all groups of the given name.</li>
 *   <li>{@link #addGroup(String)} for creating a new subgroup and adding it to the list of subgroups.</li>
 * </ul>
 *
 * <div class="note"><b>API note:</b> there is no <code>parameter<b><u>s</u></b>(String)</code> method
 * returning a list of parameter values because the ISO 19111 standard fixes the {@code ParameterValue}
 * {@linkplain DefaultParameterDescriptor#getMaximumOccurs() maximum occurrence} to 1.</div>
 *
 * @author  Martin Desruisseaux (IRD, Geomatys)
 * @since   0.4 (derived from geotk-2.0)
 * @version 0.4
 * @module
 *
 * @see DefaultParameterDescriptorGroup
 * @see DefaultParameterValue
 */
public class DefaultParameterValueGroup implements ParameterValueGroup, Serializable, Cloneable {
    /**
     * Serial number for inter-operability with different versions.
     */
    private static final long serialVersionUID = -1985309386356545126L;

    /**
     * Contains the descriptor and the {@linkplain #values() parameter values} for this group.
     *
     * <p>Consider this field as final. It is not for the purpose of {@link #clone()}.</p>
     */
    private ParameterValueList content;

    /**
     * Constructs a parameter group from the specified descriptor.
     *
     * @param descriptor The descriptor for this group.
     */
    public DefaultParameterValueGroup(final ParameterDescriptorGroup descriptor) {
        ArgumentChecks.ensureNonNull("descriptor", descriptor);
        final List<GeneralParameterDescriptor> parameters = descriptor.descriptors();
        final List<GeneralParameterValue> values = new ArrayList<>(parameters.size());
        for (final GeneralParameterDescriptor element : parameters) {
            for (int count=element.getMinimumOccurs(); --count>=0;) {
                values.add(element.createValue());
            }
        }
        content = new ParameterValueList(descriptor, values);
    }

    /**
     * Returns the abstract definition of this group of parameters.
     *
     * @return The abstract definition of this group of parameters.
     */
    @Override
    public ParameterDescriptorGroup getDescriptor() {
        return content.descriptor;
    }

    /**
     * Returns the values in this group. Changes in this list are reflected on this {@code ParameterValueGroup}.
     * The returned list supports the {@code add(…)} and {@code remove(…)} operations.
     */
    @Override
    public List<GeneralParameterValue> values() {
        return content;
    }

    /**
     * Returns the value in this group for the specified name.
     * This method performs the first applicable action in the following choices:
     *
     * <ul>
     *   <li>If this group contains a parameter value of the given name, then that parameter is returned.</li>
     *   <li>Otherwise if the {@linkplain #getDescriptor() descriptor} contains a definition for a parameter
     *       of the given name, then a new {@code ParameterValue} instance is
     *       {@linkplain DefaultParameterDescriptor#createValue() created}, added to this group then returned.</li>
     *   <li>Otherwise a {@code ParameterNotFoundException} is thrown.</li>
     * </ul>
     *
     * This convenience method provides a way to get and set parameter values by name. For example
     * the following idiom fetches a floating point value for the {@code "false_easting"} parameter:
     *
     * {@preformat java
     *     double value = parameter("false_easting").doubleValue();
     * }
     *
     * This method does not search recursively in subgroups. This is because more than one subgroup
     * may exist for the same {@linkplain ParameterDescriptorGroup descriptor}. The user have to
     * {@linkplain #groups(String) query all subgroups} and select explicitly the appropriate one.
     *
     * @param  name The name of the parameter to search for.
     * @return The parameter value for the given name.
     * @throws ParameterNotFoundException if there is no parameter value for the given name.
     *
     * @see Parameters#getOrCreate(ParameterDescriptor, ParameterValueGroup)
     */
    @Override
    public ParameterValue<?> parameter(final String name) throws ParameterNotFoundException {
        ArgumentChecks.ensureNonNull("name", name);
        final List<GeneralParameterValue> values = content.values;
        ParameterValue<?> fallback = null, ambiguity = null;
        for (final GeneralParameterValue value : values) {
            if (value instanceof ParameterValue<?>) {
                final GeneralParameterDescriptor descriptor = value.getDescriptor();
                if (isHeuristicMatchForName(descriptor, name)) {
                    if (name.equals(descriptor.getName().toString())) {
                        return (ParameterValue<?>) value;
                    } else if (fallback == null) {
                        fallback = (ParameterValue<?>) value;
                    } else {
                        ambiguity = (ParameterValue<?>) value;
                    }
                }
            }
        }
        if (fallback != null) {
            if (ambiguity == null) {
                return fallback;
            }
            throw new ParameterNotFoundException(Errors.format(Errors.Keys.AmbiguousName_3,
                    fallback.getDescriptor().getName(), ambiguity.getDescriptor().getName(), name), name);
        }
        /*
         * No existing parameter found. Check if an optional parameter exists.
         * If such a descriptor is found, create it, add it to the list of values
         * and returns it.
         */
        final GeneralParameterDescriptor descriptor = content.descriptor.descriptor(name);
        if (descriptor instanceof ParameterDescriptor<?>) {
            final ParameterValue<?> value = ((ParameterDescriptor<?>) descriptor).createValue();
            values.add(value);
            return value;
        }
        throw new ParameterNotFoundException(Errors.format(Errors.Keys.ParameterNotFound_2,
                content.descriptor.getName(), name), name);
    }

    /**
     * Returns all subgroups with the specified name.
     *
     * <p>This method do not create new groups: if the requested group is optional (i.e.
     * <code>{@linkplain DefaultParameterDescriptor#getMinimumOccurs() minimumOccurs} == 0</code>)
     * and no value were defined previously, then this method returns an empty set.</p>
     *
     * @param  name The name of the parameter to search for.
     * @return The set of all parameter group for the given name.
     * @throws ParameterNotFoundException If no descriptor was found for the given name.
     */
    @Override
    public List<ParameterValueGroup> groups(final String name) throws ParameterNotFoundException {
        ArgumentChecks.ensureNonNull("name", name);
        final List<ParameterValueGroup> groups = new ArrayList<>(4);
        for (final GeneralParameterValue value : content.values) {
            if (value instanceof ParameterValueGroup) {
                if (isHeuristicMatchForName(value.getDescriptor(), name)) {
                    groups.add((ParameterValueGroup) value);
                }
            }
        }
        /*
         * No groups were found. Check if the group actually exists (i.e. is declared in the
         * descriptor). If it doesn't exists, then an exception is thrown. If it exists (i.e.
         * it is simply an optional group not yet defined), then returns an empty list.
         */
        if (groups.isEmpty()) {
            final ParameterDescriptorGroup descriptor = content.descriptor;
            if (!(descriptor.descriptor(name) instanceof ParameterDescriptorGroup)) {
                throw new ParameterNotFoundException(Errors.format(
                        Errors.Keys.ParameterNotFound_2, descriptor.getName(), name), name);
            }
        }
        return groups;
    }

    /**
     * Creates a new subgroup of the specified name, and adds it to the list of subgroups.
     * The argument shall be the name of a {@linkplain DefaultParameterDescriptorGroup descriptor group}
     * which is a child of this group.
     *
     * <div class="note"><b>API note:</b>
     * There is no {@code removeGroup(String)} method. To remove a group, users shall inspect the
     * {@link #values()} list, decide which occurrences to remove if there is many of them for the
     * same name, and whether to iterate recursively into sub-groups or not.</div>
     *
     * @param  name The name of the parameter group to create.
     * @return A newly created parameter group for the given name.
     * @throws ParameterNotFoundException If no descriptor was found for the given name.
     * @throws InvalidParameterCardinalityException If this parameter group already contains the
     *         {@linkplain ParameterDescriptorGroup#getMaximumOccurs() maximum number of occurrences}
     *         of subgroups of the given name.
     */
    @Override
    public ParameterValueGroup addGroup(final String name)
            throws ParameterNotFoundException, InvalidParameterCardinalityException
    {
        final ParameterDescriptorGroup descriptor = content.descriptor;
        final GeneralParameterDescriptor child = descriptor.descriptor(name);
        if (!(child instanceof ParameterDescriptorGroup)) {
            throw new ParameterNotFoundException(Errors.format(
                    Errors.Keys.ParameterNotFound_2, descriptor.getName(), name), name);
        }
        final int count = content.count(child.getName());
        if (count >= child.getMaximumOccurs()) {
            throw new InvalidParameterCardinalityException(Errors.format(
                    Errors.Keys.TooManyOccurrences_2, count, name), name);
        }
        final ParameterValueGroup value = ((ParameterDescriptorGroup) child).createValue();
        content.values.add(value);
        return value;
    }

    /**
     * Compares the specified object with this parameter for equality.
     *
     * @param  object The object to compare to {@code this}.
     * @return {@code true} if both objects are equal.
     */
    @Override
    public boolean equals(final Object object) {
        if (object == this) {
            return true;
        }
        if (object != null && getClass() == object.getClass()) {
            final DefaultParameterValueGroup that = (DefaultParameterValueGroup) object;
            return Objects.equals(content.descriptor, that.content.descriptor) &&
                   Objects.equals(content.values,     that.content.values);
        }
        return false;
    }

    /**
     * Returns a hash value for this parameter.
     *
     * @return The hash code value. This value doesn't need to be the same
     *         in past or future versions of this class.
     */
    @Override
    public int hashCode() {
        return content.descriptor.hashCode() ^ content.values.hashCode();
    }

    /**
     * Returns a deep copy of this group of parameter values.
     * Included parameter values and subgroups are cloned recursively.
     *
     * @return A copy of this group of parameter values.
     */
    @Override
    @SuppressWarnings("unchecked")
    public DefaultParameterValueGroup clone() {
        final DefaultParameterValueGroup copy;
        try {
            copy = (DefaultParameterValueGroup) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
        copy.content = new ParameterValueList(content.descriptor, new ArrayList<>(content.values));
        final List<GeneralParameterValue> values = copy.content.values;
        for (int i=values.size(); --i>=0;) {
            values.set(i, values.get(i).clone());
        }
        return copy;
    }
}
