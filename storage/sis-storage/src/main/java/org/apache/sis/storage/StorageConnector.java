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
package org.apache.sis.storage;

import java.util.Map;
import java.util.Iterator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.io.Reader;
import java.io.DataInput;
import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.sis.util.Debug;
import org.apache.sis.util.Classes;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.util.ObjectConverters;
import org.apache.sis.util.resources.Errors;
import org.apache.sis.internal.storage.Resources;
import org.apache.sis.internal.storage.io.IOUtilities;
import org.apache.sis.internal.storage.io.ChannelFactory;
import org.apache.sis.internal.storage.io.ChannelDataInput;
import org.apache.sis.internal.storage.io.ChannelImageInputStream;
import org.apache.sis.internal.storage.io.InputStreamAdapter;
import org.apache.sis.internal.util.Utilities;
import org.apache.sis.setup.OptionKey;


/**
 * Information for creating a connection to a {@link DataStore} in read and/or write mode.
 * {@code StorageConnector} wraps an input {@link Object}, which can be any of the following types:
 *
 * <ul>
 *   <li>A {@link java.nio.file.Path} or a {@link java.io.File} for a file or a directory.</li>
 *   <li>A {@link java.net.URI} or a {@link java.net.URL} to a distant resource.</li>
 *   <li>A {@link CharSequence} interpreted as a filename or a URL.</li>
 *   <li>A {@link java.nio.channels.Channel}, {@link DataInput}, {@link InputStream} or {@link Reader}.</li>
 *   <li>A {@link DataSource} or a {@link Connection} to a JDBC database.</li>
 *   <li>Any other {@code DataStore}-specific object, for example {@link ucar.nc2.NetcdfFile}.</li>
 * </ul>
 *
 * The {@link #getStorageAs(Class)} method provides the storage as an object of the given type, opening
 * the input stream if necessary. This class tries to open the stream only once - subsequent invocation
 * of {@code getStorageAs(…)} may return the same input stream.
 *
 * <p>This class is used only for discovery of a {@code DataStore} implementation capable to handle the input.
 * Once a suitable {@code DataStore} has been found, the {@code StorageConnector} instance is typically
 * discarded since each data store implementation will use their own input/output objects.</p>
 *
 * <p>Instances of this class are serializable if the {@code storage} object given at construction time
 * is serializable.</p>
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @version 0.8
 * @since   0.3
 * @module
 */
public class StorageConnector implements Serializable {
    /**
     * For cross-version compatibility.
     */
    private static final long serialVersionUID = 2524083964906593093L;

    /**
     * The default size of the {@link ByteBuffer} to be created.
     * Users can override this value by providing a value for {@link OptionKey#BYTE_BUFFER}.
     */
    static final int DEFAULT_BUFFER_SIZE = 4096;

    /**
     * The minimal size of the {@link ByteBuffer} to be created. This size is used only
     * for temporary buffers that are unlikely to be used for the actual reading process.
     */
    static final int MINIMAL_BUFFER_SIZE = 256;

    /**
     * Handler to {@code StorageConnector.createFoo()} methods associated to given storage types.
     * Each {@code createFoo()} method may be invoked once for opening an input stream, character
     * reader, database connection, <i>etc</i> from user-supplied path, URI, <i>etc</i>.
     *
     * @param  <T>  the type of input created by this {@code Opener} instance.
     */
    @FunctionalInterface
    private interface Opener<T> {
        /**
         * Invoked when first needed for creating an input of the requested type.
         * This method should invoke {@link #addView(Class, Object, Class, boolean, boolean)}
         * for caching the result before to return the view.
         */
        T open(StorageConnector c) throws Exception;
    }

    /** Helper method for {@link #OPENERS} static initialization. */
    private static <T> void add(final Class<T> type, final Opener<T> op) {
        if (OPENERS.put(type, op) != null) throw new AssertionError(type);
    }

    /**
     * List of types recognized by {@link #getStorageAs(Class)}, associated to the methods for opening stream
     * of those types. This map shall contain every types documented in {@link #getStorageAs(Class)} javadoc.
     */
    private static final Map<Class<?>, Opener<?>> OPENERS = new IdentityHashMap<>(12);
    static {
        add(String.class,           StorageConnector::createString);
        add(ByteBuffer.class,       StorageConnector::createByteBuffer);
        add(DataInput.class,        StorageConnector::createDataInput);
        add(ImageInputStream.class, StorageConnector::createImageInputStream);
        add(InputStream.class,      StorageConnector::createInputStream);
        add(Reader.class,           StorageConnector::createReader);
        add(Connection.class,       StorageConnector::createConnection);
        add(ChannelDataInput.class, (s) -> s.createChannelDataInput(false));    // Undocumented case (SIS internal)
        add(ChannelFactory.class,   (s) -> null);                               // Undocumented. Shall not cache.
        /*
         * ChannelFactory may have been created as a side effect of creating a ReadableByteChannel.
         * Caller should have asked for another type (e.g. InputStream) before to ask for that type.
         * Consequently null value for ChannelFactory shall not be cached since the actual value may
         * be computed later.
         */
    }

    /**
     * The input/output object given at construction time.
     *
     * @see #getStorage()
     */
    private final Object storage;

    /**
     * A name for the input/output object, or {@code null} if none.
     * This field is initialized only when first needed.
     */
    private transient String name;

    /**
     * The filename extension, or {@code null} if none.
     * This field is initialized only when first needed.
     */
    private transient String extension;

    /**
     * The options, created only when first needed.
     *
     * @see #getOption(OptionKey)
     * @see #setOption(OptionKey, Object)
     */
    private Map<OptionKey<?>, Object> options;

    /**
     * Views of {@link #storage} as instances of different types than the type of the object given to the constructor.
     * The {@code null} reference can appear in various places:
     * <ul>
     *   <li>A non-existent entry (equivalent to an entry associated to the {@code null} value) means that the value
     *       has not yet been computed.</li>
     *   <li>A {@linkplain Coupled#isValid valid entry} with {@link Coupled#view} set to {@code null} means the value
     *       has been computed and we have determined that {@link #getStorageAs(Class)} shall return {@code null} for
     *       that type.</li>
     *   <li>By convention, the {@code null} key is associated to the {@link #storage} value.</li>
     * </ul>
     *
     * @see #addView(Class, Object, Class, boolean, boolean)
     * @see #getView(Class)
     * @see #getStorageAs(Class)
     */
    private transient Map<Class<?>, Coupled> views;

    /**
     * Wraps an instance of @link InputStream}, {@link DataInput}, {@link Reader}, <i>etc.</i> together with additional
     * information about other objects that are coupled with the wrapped object. For example if a {@link Reader} is a
     * wrapper around the user-supplied {@link InputStream}, then those two objects will be wrapped in {@code Coupled}
     * instances together with information about how they are related
     *
     * One purpose of {@code Coupled} information is to keep trace of objects which will need to be closed by the
     * {@link StorageConnector#closeAllExcept(Object)} method  (for example an {@link InputStreamReader} wrapping
     * an {@link InputStream}).
     *
     * Another purpose is to determine which views need to be synchronized if {@link StorageConnector#storage} is
     * used independently. They are views that may advance {@code storage} position, but not in same time than the
     * {@link #view} position (typically because the view reads some bytes in advance and stores them in a buffer).
     * Such coupling may occur when the storage is an {@link InputStream}, an {@link java.io.OutputStream} or a
     * {@link java.nio.channels.Channel}. The coupled {@link #view} can be:
     *
     * <ul>
     *   <li>{@link Reader} that are wrappers around {@code InputStream}.</li>
     *   <li>{@link ChannelDataInput} when the channel come from an {@code InputStream}.</li>
     *   <li>{@link ChannelDataInput} when the channel has been explicitely given to the constructor.</li>
     * </ul>
     */
    private static final class Coupled {
        /**
         * The {@link StorageConnector#storage} viewed as another kind of object.
         * Supported types are:
         *
         * <ul>
         *   <li>{@link ByteBuffer}:
         *       A read-only view of the buffer over the first bytes of the stream.</li>
         *
         *   <li>{@link DataInput}:
         *       The input as a data input stream. Unless the {@link #storage} is already an instance of {@link DataInput},
         *       this entry will be given an instance of {@link ChannelImageInputStream} if possible rather than an arbitrary
         *       stream. In particular, we invoke the {@link ImageIO#createImageInputStream(Object)} factory method only in
         *       last resort because some SIS data stores will want to access the channel and buffer directly.</li>
         *
         *   <li>{@link ImageInputStream}:
         *       Same as {@code DataInput} if it can be casted, or {@code null} otherwise.</li>
         *
         *   <li>{@link InputStream}:
         *       If not explicitely provided, this is a wrapper around the above {@link ImageInputStream}.</li>
         *
         *   <li>{@link Reader}:
         *       If not explicitely provided, this is a wrapper around the above {@link InputStream}.</li>
         *
         *   <li>{@link Connection}:
         *       The storage object as a JDBC connection.</li>
         * </ul>
         */
        Object view;

        /**
         * The object that {@link #view} is wrapping. For example if {@code view} is an {@link InputStreamReader},
         * then {@code wrapperFor.view} is an {@link InputStream}. This field is {@code null} if {@link #view} ==
         * {@link StorageConnector#storage}.
         */
        final Coupled wrapperFor;

        /**
         * The other views that are consuming {@link #view}, or {@code null} if none. For each element in this array,
         * {@code wrappedBy[i].wrapperFor == this}.
         */
        private Coupled[] wrappedBy;

        /**
         * {@code true} if calls to {@link #reset()} should cascade to {@link #wrapperFor}.
         * This field can be {@code false} if any change in the position of {@link #view}
         * is immediately reflected in the position of {@link #wrapperFor}, and vis-versa.
         */
        final boolean cascadeOnReset;

        /**
         * {@code true} if after closing the {@link #view}, we need to also close the {@link #wrapperFor}.
         * This field should be {@code true} when the view is an {@link ImageInputStream} because Java I/O
         * {@link javax.imageio.stream.FileCacheImageInputStream#close()} does not close the underlying stream.
         * For most other kinds of view, this field should be {@code false}.
         */
        final boolean cascadeOnClose;

        /**
         * {@code true} if the position of {@link #view} is synchronized with the position of {@link #wrapperFor}.
         */
        boolean isValid;

        /**
         * Creates a wrapper for {@link StorageConnector#storage}. This constructor is used when we need to create
         * a {@code Coupled} instance for another view wrapping {@code storage}.
         */
        Coupled(final Object storage) {
            view           = storage;
            wrapperFor     = null;
            cascadeOnReset = false;
            cascadeOnClose = false;
            isValid        = true;
        }

        /**
         * Creates a wrapper for a view wrapping the given {@code Coupled} instance.
         * Caller is responsible to set the {@link #view} field after this constructor call.
         *
         * @param  wrapperFor      the object that {@link #view} will wrap, or {@code null} if none.
         * @param  cascadeOnReset  whether calls to {@link #reset()} shall cascade.
         * @param  cascadeOnClose  whether calls to {@link AutoCloseable#close()} shall cascade.
         */
        @SuppressWarnings("ThisEscapedInObjectConstruction")
        Coupled(final Coupled wrapperFor, final boolean cascadeOnReset, final boolean cascadeOnClose) {
            this.wrapperFor     = wrapperFor;
            this.cascadeOnReset = cascadeOnReset;
            this.cascadeOnClose = cascadeOnClose;
            if (wrapperFor != null) {
                final Coupled[] w = wrapperFor.wrappedBy;
                final int n = (w != null) ? w.length : 0;
                final Coupled[] e = new Coupled[n + 1];
                if (n != 0) System.arraycopy(w, 0, e, 0, n);
                e[n] = this;
                wrapperFor.wrappedBy = e;
            }
        }

        /**
         * Declares as invalid all unsynchronized {@code Coupled} instances which are used, directly or indirectly,
         * by this instance. This method is invoked before {@link StorageConnector#getStorageAs(Class)} returns a
         * view, in order to remember which views would need to be resynchronized if they are requested.
         */
        final void invalidateSources() {
            boolean sync = cascadeOnReset;
            for (Coupled c = wrapperFor; sync; c = c.wrapperFor) {
                c.isValid = false;
                sync = c.cascadeOnReset;
            }
        }

        /**
         * Declares as invalid all unsynchronized {@code Coupled} instances which are using, directly or indirectly,
         * this instance. This method is invoked before {@link StorageConnector#getStorageAs(Class)} returns a view,
         * in order to remember which views would need to be resynchronized if they are requested.
         */
        final void invalidateUsages() {
            if (wrappedBy != null) {
                for (final Coupled c : wrappedBy) {
                    if (c.cascadeOnReset) {
                        c.isValid = false;
                        c.invalidateUsages();
                    }
                }
            }
        }

        /**
         * Identifies the other views to <strong>not</strong> close if we don't want to close the {@link #view}
         * wrapped by this {@code Coupled}. This method identifies only the views that <em>use</em> this view;
         * it does not identify the views <em>used</em> by this view.
         *
         * This method is for {@link StorageConnector#closeAllExcept(Object)} internal usage.
         *
         * @param  toClose  the map where to write the list of views to not close.
         */
        final void protect(final Map<AutoCloseable,Boolean> toClose) {
            if (wrappedBy != null) {
                for (final Coupled c : wrappedBy) {
                    if (!c.cascadeOnClose) {
                        if (c.view instanceof AutoCloseable) {
                            toClose.put((AutoCloseable) c.view, Boolean.FALSE);
                        }
                        c.protect(toClose);
                    }
                }
            }
        }

        /**
         * Resets the position of all sources of the {@link #view}, then the view itself.
         *
         * @return {@code true} if some kind of reset has been performed.
         *         Note that it does means that the view {@link #isValid} is {@code true}.
         */
        final boolean reset() throws Exception {
            if (isValid) {
                return false;
            }
            isValid = true;             // Set first as a safety against infinite recursivity.
            /*
             * We need to reset the sources before to reset the view of this Coupled instance.
             * For example if this Coupled instance contains a ChannelDataInput, we need to
             * reset the underlying InputStream before to reset the ChannelDataInput.
             */
            if (cascadeOnReset) {
                wrapperFor.reset();
            }
            if (view instanceof InputStream) {
                /*
                 * Note on InputStream.reset() behavior documented in java.io:
                 *
                 *  - It does not discard the mark, so it is okay if reset() is invoked twice.
                 *  - If mark is unsupported, may either throw IOException or reset the stream
                 *    to an implementation-dependent fixed state.
                 */
                ((InputStream) view).reset();
            } else if (view instanceof Reader) {
                ((Reader) view).reset();
            } else if (view instanceof SeekableByteChannel) {
                /*
                 * This case should be rare. If it happen anyway, searches for a ChannelDataInput wrapping
                 * the channel, because it contains the original position (note: StorageConnector tries to
                 * instantiate ChannelDataInput in priority to all other types). If we don't find any, the
                 * original position is assumed to be zero (which is the case most of the time).
                 */
                long p = 0;
                if (wrappedBy != null) {
                    for (Coupled c : wrappedBy) {
                        if (c.view instanceof ChannelDataInput) {
                            p = ((ChannelDataInput) c.view).channelOffset;
                            break;
                        }
                    }
                }
                ((SeekableByteChannel) view).position(p);
            } else if (view instanceof ChannelDataInput) {
                /*
                 * ChannelDataInput can be recycled without the need to discard and recreate them.
                 */
                final ChannelDataInput input = (ChannelDataInput) view;
                input.buffer.limit(0);                                      // Must be after stream.reset().
                input.setStreamPosition(0);                                 // Must be after buffer.limit(0).
            } else {
                /*
                 * For any other kind of object, we don't know how to recycle them. Current implementation
                 * does nothing on the assumption that the object can be reused (example: NetcdfFile).
                 */
            }
            return true;
        }

        /**
         * Returns a string representation for debugging purpose.
         */
        @Debug
        @Override
        public String toString() {
            return Utilities.toString(getClass(),
                    "view",           Classes.getShortClassName(view),
                    "wrapperFor",     (wrapperFor != null) ? Classes.getShortClassName(wrapperFor.view) : null,
                    "cascadeOnReset", cascadeOnReset,
                    "cascadeOnClose", cascadeOnClose,
                    "isValid",        isValid);
        }
    }

    /**
     * Creates a new data store connection wrapping the given input/output object.
     * The object can be of any type, but the class javadoc lists the most typical ones.
     *
     * @param storage  the input/output object as a URL, file, image input stream, <i>etc.</i>.
     */
    public StorageConnector(final Object storage) {
        ArgumentChecks.ensureNonNull("storage", storage);
        this.storage = storage;
    }

    /**
     * Returns the option value for the given key, or {@code null} if none.
     *
     * @param  <T>  the type of option value.
     * @param  key  the option for which to get the value.
     * @return the current value for the given option, or {@code null} if none.
     */
    public <T> T getOption(final OptionKey<T> key) {
        ArgumentChecks.ensureNonNull("key", key);
        return key.getValueFrom(options);
    }

    /**
     * Sets the option value for the given key. The default implementation recognizes the following options:
     *
     * <ul>
     *   <li>{@link OptionKey#ENCODING}     for decoding characters in an input stream, if needed.</li>
     *   <li>{@link OptionKey#URL_ENCODING} for converting URL to URI or filename, if needed.</li>
     *   <li>{@link OptionKey#OPEN_OPTIONS} for specifying whether the data store shall be read only or read/write.</li>
     *   <li>{@link OptionKey#BYTE_BUFFER}  for allowing users to control the byte buffer to be created.</li>
     * </ul>
     *
     * @param <T>    the type of option value.
     * @param key    the option for which to set the value.
     * @param value  the new value for the given option, or {@code null} for removing the value.
     */
    public <T> void setOption(final OptionKey<T> key, final T value) {
        ArgumentChecks.ensureNonNull("key", key);
        options = key.setValueInto(options, value);
    }

    /**
     * Returns the input/output object given at construction time.
     * The object can be of any type, but the class javadoc lists the most typical ones.
     *
     * @return the input/output object as a URL, file, image input stream, <i>etc.</i>.
     * @throws DataStoreException if the storage object has already been used and can not be reused.
     *
     * @see #getStorageAs(Class)
     */
    public Object getStorage() throws DataStoreException {
        reset();
        return storage;
    }

    /**
     * Returns a short name of the input/output object. The default implementation performs
     * the following choices based on the type of the {@linkplain #getStorage() storage} object:
     *
     * <ul>
     *   <li>For {@link java.nio.file.Path}, {@link java.io.File}, {@link java.net.URI} or {@link java.net.URL}
     *       instances, this method uses dedicated API like {@link java.nio.file.Path#getFileName()}.</li>
     *   <li>For {@link CharSequence} instances, this method gets a string representation of the storage object
     *       and returns the part after the last {@code '/'} character or platform-dependent name separator.</li>
     *   <li>For instances of unknown type, this method builds a string representation using the class name.
     *       Note that the string representation of unknown types may change in any future SIS version.</li>
     * </ul>
     *
     * @return a short name of the storage object.
     */
    public String getStorageName() {
        if (name == null) {
            name = IOUtilities.filename(storage);
            if (name == null) {
                name = Classes.getShortClassName(storage);
            }
        }
        return name;
    }

    /**
     * Returns the filename extension of the input/output object. The default implementation performs
     * the following choices based on the type of the {@linkplain #getStorage() storage} object:
     *
     * <ul>
     *   <li>For {@link java.nio.file.Path}, {@link java.io.File}, {@link java.net.URI}, {@link java.net.URL} or
     *       {@link CharSequence} instances, this method returns the string after the last {@code '.'} character
     *       in the filename, provided that the {@code '.'} is not the first filename character. This may be an
     *       empty string if the filename has no extension, but never {@code null}.</li>
     *   <li>For instances of unknown type, this method returns {@code null}.</li>
     * </ul>
     *
     * @return the filename extension, or an empty string if none,
     *         or {@code null} if the storage is an object of unknown type.
     */
    public String getFileExtension() {
        if (extension == null) {
            extension = IOUtilities.extension(storage);
        }
        return extension;
    }

    /**
     * Returns the storage as a view of the given type if possible, or {@code null} otherwise.
     * The default implementation accepts the following types:
     *
     * <ul>
     *   <li>{@link String}:
     *     <ul>
     *       <li>If the {@linkplain #getStorage() storage} object is an instance of the {@link java.nio.file.Path},
     *           {@link java.io.File}, {@link java.net.URL}, {@link java.net.URI} or {@link CharSequence} types,
     *           returns the string representation of their path.</li>
     *
     *       <li>Otherwise this method returns {@code null}.</li>
     *     </ul>
     *   </li>
     *   <li>{@link ByteBuffer}:
     *     <ul>
     *       <li>If the {@linkplain #getStorage() storage} object can be obtained as described in bullet 2 of the
     *           {@code DataInput} section below, then this method returns the associated byte buffer.</li>
     *
     *       <li>Otherwise this method returns {@code null}.</li>
     *     </ul>
     *   </li>
     *   <li>{@link DataInput}:
     *     <ul>
     *       <li>If the {@linkplain #getStorage() storage} object is already an instance of {@code DataInput}
     *           (including the {@link ImageInputStream} and {@link javax.imageio.stream.ImageOutputStream} types),
     *           then it is returned unchanged.</li>
     *
     *       <li>Otherwise if the input is an instance of {@link java.nio.file.Path}, {@link java.io.File},
     *           {@link java.net.URI}, {@link java.net.URL}, {@link CharSequence}, {@link InputStream} or
     *           {@link java.nio.channels.ReadableByteChannel}, then an {@link ImageInputStream} backed by a
     *           {@link ByteBuffer} is created when first needed and returned.</li>
     *
     *       <li>Otherwise if {@link ImageIO#createImageInputStream(Object)} returns a non-null value,
     *           then this value is cached and returned.</li>
     *
     *       <li>Otherwise this method returns {@code null}.</li>
     *     </ul>
     *   </li>
     *   <li>{@link ImageInputStream}:
     *     <ul>
     *       <li>If the above {@code DataInput} can be created and casted to {@code ImageInputStream}, returns it.</li>
     *
     *       <li>Otherwise this method returns {@code null}.</li>
     *     </ul>
     *   </li>
     *   <li>{@link InputStream}:
     *     <ul>
     *       <li>If the {@linkplain #getStorage() storage} object is already an instance of {@link InputStream},
     *           then it is returned unchanged.</li>
     *
     *       <li>Otherwise if the above {@code ImageInputStream} can be created,
     *           returns a wrapper around that stream.</li>
     *
     *       <li>Otherwise this method returns {@code null}.</li>
     *     </ul>
     *   </li>
     *   <li>{@link Reader}:
     *     <ul>
     *       <li>If the {@linkplain #getStorage() storage} object is already an instance of {@link Reader},
     *           then it is returned unchanged.</li>
     *
     *       <li>Otherwise if the above {@code InputStream} can be created, returns an {@link InputStreamReader}
     *           using the encoding specified by {@link OptionKey#ENCODING} if any, or using the system default
     *           encoding otherwise.</li>
     *
     *       <li>Otherwise this method returns {@code null}.</li>
     *     </ul>
     *   </li>
     *   <li>{@link Connection}:
     *     <ul>
     *       <li>If the {@linkplain #getStorage() storage} object is already an instance of {@link Connection},
     *           then it is returned unchanged.</li>
     *
     *       <li>Otherwise if the storage is an instance of {@link DataSource}, then a connection is obtained
     *           when first needed and returned.</li>
     *
     *       <li>Otherwise this method returns {@code null}.</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * Multiple invocations of this method on the same {@code StorageConnector} instance will try
     * to return the same instance on a <cite>best effort</cite> basis. Consequently, implementations of
     * {@link DataStoreProvider#probeContent(StorageConnector)} methods shall not close the stream or
     * database connection returned by this method. In addition, those {@code probeContent(StorageConnector)}
     * methods are responsible for restoring the stream or byte buffer to its original position on return.
     *
     * @param  <T>   the compile-time type of the {@code type} argument.
     * @param  type  the desired type as one of {@code ByteBuffer}, {@code DataInput}, {@code Connection}
     *               class or other type supported by {@code StorageConnector} subclasses.
     * @return the storage as a view of the given type, or {@code null} if no view can be created for the given type.
     * @throws IllegalArgumentException if the given {@code type} argument is not a supported type.
     * @throws DataStoreException if an error occurred while opening a stream or database connection.
     *
     * @see #getStorage()
     * @see #closeAllExcept(Object)
     */
    public <T> T getStorageAs(final Class<T> type) throws IllegalArgumentException, DataStoreException {
        ArgumentChecks.ensureNonNull("type", type);
        /*
         * Verify if the cache contains an instance created by a previous invocation of this method.
         * Note that InputStream may need to be reseted if it has been used indirectly by other kind
         * of stream (for example a java.io.Reader). Example:
         *
         *    1) The storage specified at construction time is a java.nio.file.Path.
         *    2) getStorageAs(InputStream.class) opens an InputStream. Caller rewinds it after use.
         *    3) getStorageAs(Reader.class) wraps the InputStream. Caller rewinds the Reader after use,
         *       but invoking BufferedReader.reset() has no effect on the underlying InputStream.
         *    4) getStorageAs(InputStream.class) needs to rewind the InputStream itself since it was
         *       not done at step 3. However doing so invalidate the Reader, so we need to discard it.
         */
        Coupled value = getView(type);
        if (reset(value)) {
            return type.cast(value.view);               // null is a valid result.
        }
        /*
         * If the storage is already an instance of the requested type, returns the storage as-is.
         * We check if the storage needs to be reseted in the same way than in getStorage() method.
         */
        if (type.isInstance(storage)) {
            @SuppressWarnings("unchecked")
            final T view = (T) storage;
            reset();
            addView(type, view);
            return view;
        }
        /*
         * If the type is not one of the types listed in OPENERS, we delegate to ObjectConverter.
         * It may throw UnconvertibleObjectException (an IllegalArgumentException subtype) if the
         * given type is unrecognized. So the IllegalArgumentException documented in method javadoc
         * happen (indirectly) here.
         */
        final Opener<?> method = OPENERS.get(type);
        if (method == null) {
            final T view = ObjectConverters.convert(storage, type);
            addView(type, view);
            return view;
        }
        /*
         * No instance has been created previously for the requested type. Open the stream now.
         * Some types will need to reset the InputStream or Channel, but the decision of doing
         * so or not is left to openers. Result will be cached by the 'createFoo()' method.
         * Note that it may cache 'null' value if no stream of the given type can be created.
         */
        final Object view;
        try {
            view = method.open(this);
        } catch (DataStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new DataStoreException(Errors.format(Errors.Keys.CanNotOpen_1, getStorageName()), e);
        }
        return type.cast(view);
    }

    /**
     * Marks the current position of the given view. This method should be invoked at the beginning of
     * {@code createFoo()}} methods that may wrap {@link InputStream} in {@link InputStreamReader} or
     * other objects. It is caller's responsibility to {@link #reset(Coupled)} first, if needed.
     */
    private static void mark(final Object view) throws IOException {
        if (view instanceof InputStream) {
            ((InputStream) view).mark(DEFAULT_BUFFER_SIZE);
        } else if (view instanceof Reader) {
            ((Reader) view).mark(DEFAULT_BUFFER_SIZE);
        } else if (view instanceof ReadableByteChannel) {
            // TODO
        }
    }

    /**
     * Resets the given view. If the view is an instance of {@link InputStream}, {@link ReadableByteChannel} or
     * other objects that may be affected by views operations, this method will reset the storage position.
     * The view must have been previously marked by {@link #mark(Object)}.
     *
     * <p>This method is <strong>not</strong> a substitute for the requirement that users leave the
     * {@link #getStorageAs(Class)} return value in the same state as they found it. This method is
     * only for handling the cases where using a view has an indirect impact on another view.</p>
     *
     * <div class="note"><b>Rational:</b>
     * {@link DataStoreProvider#probeContent(StorageConnector)} contract requires that implementors reset the
     * input stream themselves. However if {@link ChannelDataInput} or {@link InputStreamReader} has been used,
     * then the user performed a call to {@link ChannelDataInput#reset()} (for instance), which did not reseted
     * the underlying input stream. So we need to perform the missing {@link InputStream#reset()} here, then
     * synchronize the {@code ChannelDataInput} position accordingly.</div>
     *
     * @param  c  container of the view to reset, or {@code null} if none.
     * @return {@code true} if the given view, after reset, is valid.
     *         Note that {@link Coupled#view} may be null and valid.
     */
    private boolean reset(final Coupled c) throws DataStoreException {
        final boolean done;
        if (c == null) {
            return false;
        } else try {
            done = c.reset();
        } catch (DataStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new ForwardOnlyStorageException(Resources.format(
                        Resources.Keys.StreamIsReadOnce_1, getStorageName()), e);
        }
        if (done) {
            c.invalidateSources();
            c.invalidateUsages();
        }
        return c.isValid;
    }

    /**
     * Resets the root {@link #storage} object.
     *
     * @throws DataStoreException if the storage can not be reseted.
     */
    private void reset() throws DataStoreException {
        if (views != null && !reset(views.get(null))) {
            throw new ForwardOnlyStorageException(Resources.format(
                        Resources.Keys.StreamIsReadOnce_1, getStorageName()));
        }
    }

    /**
     * Creates a view for the input as a {@link ChannelDataInput} if possible.
     * This is also a starting point for {@link #createDataInput()} and {@link #createByteBuffer()}.
     * This method is one of the {@link #OPENERS} methods and should be invoked at most once per
     * {@code StorageConnector} instance.
     *
     * @param  asImageInputStream  whether the {@code ChannelDataInput} needs to be {@link ChannelImageInputStream} subclass.
     * @throws IOException if an error occurred while opening a channel for the input.
     */
    private ChannelDataInput createChannelDataInput(final boolean asImageInputStream) throws IOException, DataStoreException {
        /*
         * Before to try to wrap an InputStream, mark its position so we can rewind if the user asks for
         * the InputStream directly. We need to reset because ChannelDataInput may have read some bytes.
         * Note that if mark is unsupported, the default InputStream.mark() implementation does nothing.
         */
        reset();
        mark(storage);
        /*
         * Following method call recognizes ReadableByteChannel, InputStream (with special case for FileInputStream),
         * URL, URI, File, Path or other types that may be added in future Apache SIS versions.
         * If the given storage is already a ReadableByteChannel, then the factory will return it as-is.
         */
        final ChannelFactory factory = ChannelFactory.prepare(storage,
                getOption(OptionKey.URL_ENCODING), false, getOption(OptionKey.OPEN_OPTIONS));
        if (factory == null) {
            return null;
        }
        /*
         * ChannelDataInput depends on ReadableByteChannel, which itself depends on storage
         * (potentially an InputStream). We need to remember this chain in 'Coupled' objects.
         */
        final String name = getStorageName();
        final ReadableByteChannel channel = factory.reader(name);
        addView(ReadableByteChannel.class, channel, null, factory.isCoupled(), false);
        ByteBuffer buffer = getOption(OptionKey.BYTE_BUFFER);       // User-supplied buffer.
        if (buffer == null) {
            buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);      // Default buffer if user did not specified any.
        }
        final ChannelDataInput asDataInput;
        if (asImageInputStream) {
            asDataInput = new ChannelImageInputStream(name, channel, buffer, false);
        } else {
            asDataInput = new ChannelDataInput(name, channel, buffer, false);
        }
        addView(ChannelDataInput.class, asDataInput, ReadableByteChannel.class, true, false);
        /*
         * Following is an undocumented mechanism for allowing some Apache SIS implementations of DataStore
         * to re-open the same channel or input stream another time, typically for re-reading the same data.
         */
        if (factory.canOpen()) {
            addView(ChannelFactory.class, factory);
        }
        return asDataInput;
    }

    /**
     * Creates a view for the input as a {@link DataInput} if possible. This method performs the choice
     * documented in the {@link #getStorageAs(Class)} method for the {@code DataInput} case. Opening the
     * data input may imply creating a {@link ByteBuffer}, in which case the buffer will be stored under
     * the {@code ByteBuffer.class} key together with the {@code DataInput.class} case.
     *
     * <p>This method is one of the {@link #OPENERS} methods and should be invoked at most once per
     * {@code StorageConnector} instance.</p>
     *
     * @throws IOException if an error occurred while opening a stream for the input.
     */
    private DataInput createDataInput() throws IOException, DataStoreException {
        /*
         * Gets or creates a ChannelImageInputStream instance if possible. We really need that specific
         * type because some SIS data stores will want to access directly the channel and the buffer.
         * We will fallback on the ImageIO.createImageInputStream(Object) method only in last resort.
         */
        Coupled c = getView(ChannelDataInput.class);
        final ChannelDataInput in;
        if (reset(c)) {
            in = (ChannelDataInput) c.view;
        } else {
            in = createChannelDataInput(true);                      // May be null.
        }
        final DataInput asDataInput;
        if (in != null) {
            c = getView(ChannelDataInput.class);                    // May have been added by createChannelDataInput(…).
            if (in instanceof DataInput) {
                asDataInput = (DataInput) in;
            } else {
                asDataInput = new ChannelImageInputStream(in);      // Upgrade existing instance.
                c.view = asDataInput;
            }
            views.put(DataInput.class, c);                          // Share the same Coupled instance.
        } else {
            reset();
            asDataInput = ImageIO.createImageInputStream(storage);
            addView(DataInput.class, asDataInput, null, true, true);
            /*
             * Note: Java Image I/O wrappers for Input/OutputStream do NOT close the underlying streams.
             * This is a complication for us. We could mitigate the problem by subclassing the standard
             * FileCacheImageInputStream and related classes, but we don't do that for now because this
             * code should never be executed for InputStream storage. Instead getChannelDataInput(true)
             * should have created a ChannelImageInputStream or ChannelDataInput.
             */
        }
        return asDataInput;
    }

    /**
     * Creates a {@link ByteBuffer} from the {@link ChannelDataInput} if possible, or from the
     * {@link ImageInputStream} otherwise. The buffer will be initialized with an arbitrary amount
     * of bytes read from the input. If this amount is not sufficient, it can be increased by a call
     * to {@link #prefetch()}.
     *
     * <p>This method is one of the {@link #OPENERS} methods and should be invoked at most once per
     * {@code StorageConnector} instance.</p>
     *
     * @throws IOException if an error occurred while opening a stream for the input.
     */
    private ByteBuffer createByteBuffer() throws IOException, DataStoreException {
        /*
         * First, try to create the ChannelDataInput if it does not already exists.
         * If successful, this will create a ByteBuffer companion as a side effect.
         */
        final ChannelDataInput c = getStorageAs(ChannelDataInput.class);
        ByteBuffer asByteBuffer = null;
        if (c != null) {
            asByteBuffer = c.buffer.asReadOnlyBuffer();
        } else {
            /*
             * If no ChannelDataInput has been created by the above code, get the input as an ImageInputStream and
             * read an arbitrary amount of bytes. Read only a small amount of bytes because, at the contrary of the
             * buffer created in createChannelDataInput(boolean), the buffer created here is unlikely to be used for
             * the reading process after the recognition of the file format.
             */
            final ImageInputStream in = getStorageAs(ImageInputStream.class);
            if (in != null) {
                in.mark();
                final byte[] buffer = new byte[MINIMAL_BUFFER_SIZE];
                final int n = in.read(buffer);
                in.reset();
                if (n >= 1) {
                    // Can not invoke asReadOnly() because 'prefetch()' need to be able to write in it.
                    asByteBuffer = ByteBuffer.wrap(buffer).order(in.getByteOrder());
                    asByteBuffer.limit(n);
                }
            }
        }
        addView(ByteBuffer.class, asByteBuffer);
        return asByteBuffer;
    }

    /**
     * Transfers more bytes from the {@link DataInput} to the {@link ByteBuffer}, if possible.
     * This method returns {@code true} on success, or {@code false} if input is not a readable
     * channel or stream, we have reached the end of stream, or the buffer is full.
     *
     * <p>This method is invoked when the amount of bytes in the buffer appears to be insufficient
     * for {@link DataStoreProvider#probeContent(StorageConnector)} purpose.</p>
     *
     * @return {@code true} on success.
     * @throws DataStoreException if an error occurred while reading more bytes.
     */
    final boolean prefetch() throws DataStoreException {
        try {
            /*
             * In most Apache SIS data store implementations, we use ChannelDataInput. If the object wrapped
             * by ChannelDataInput has not been used directly, then Coupled.isValid should be true.  In such
             * case, reset(c) does nothing and ChannelDataInput.prefetch() will read new bytes from current
             * channel position. Otherwise, a new read operation from the beginning will be required and we
             * can only hope that it will read more bytes than last time.
             */
            Coupled c = getView(ChannelDataInput.class);
            if (c != null) {
                reset(c);                   // Does nothing is c.isValid is true.
                return c.isValid && ((ChannelDataInput) c.view).prefetch() > 0;
            }
            /*
             * The above code is the usual case. The code below this point is the fallback used when only
             * an ImageInputStream was available. In such case, the ByteBuffer can only be the one created
             * by the above createByteBuffer() method, which is known to be backed by a writable array.
             */
            c = getView(ImageInputStream.class);
            if (reset(c)) {
                final ImageInputStream input = (ImageInputStream) c.view;
                c = getView(ByteBuffer.class);
                if (reset(c)) {                 // reset(c) as a matter of principle, but (c != null) would have worked.
                    final ByteBuffer buffer = (ByteBuffer) c.view;
                    final int p = buffer.limit();
                    final long mark = input.getStreamPosition();
                    input.seek(Math.addExact(mark, p));
                    final int n = input.read(buffer.array(), p, buffer.capacity() - p);
                    input.seek(mark);
                    if (n > 0) {
                        buffer.limit(p + n);
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            throw new DataStoreException(Errors.format(Errors.Keys.CanNotRead_1, getStorageName()), e);
        }
        return false;
    }

    /**
     * Creates an {@link ImageInputStream} from the {@link DataInput} if possible. This method simply
     * casts {@code DataInput} if such cast is allowed. Since {@link #createDataInput()} instantiates
     * {@link ChannelImageInputStream}, this cast is usually possible.
     *
     * <p>This method is one of the {@link #OPENERS} methods and should be invoked at most once per
     * {@code StorageConnector} instance.</p>
     */
    private ImageInputStream createImageInputStream() throws DataStoreException {
        final Class<DataInput> source = DataInput.class;
        final DataInput input = getStorageAs(source);
        if (input instanceof ImageInputStream) {
            views.put(ImageInputStream.class, views.get(source));               // Share the same Coupled instance.
            return (ImageInputStream) input;
        } else {
            addView(ImageInputStream.class, null);                              // Remember that there is no view.
            return null;
        }
    }

    /**
     * Creates an input stream from {@link ReadableByteChannel} if possible, or from {@link ImageInputStream}
     * otherwise.
     *
     * <p>This method is one of the {@link #OPENERS} methods and should be invoked at most once per
     * {@code StorageConnector} instance.</p>
     */
    private InputStream createInputStream() throws IOException, DataStoreException {
        final Class<DataInput> source = DataInput.class;
        final DataInput input = getStorageAs(source);
        if (input instanceof InputStream) {
            views.put(InputStream.class, views.get(source));                    // Share the same Coupled instance.
            return (InputStream) input;
        } else if (input instanceof ImageInputStream) {
            final InputStream in = new InputStreamAdapter((ImageInputStream) input);
            addView(InputStream.class, in, source, true, false);
            return in;
        } else {
            addView(InputStream.class, null);                                   // Remember that there is no view.
            return null;
        }
    }

    /**
     * Creates a character reader if possible.
     *
     * <p>This method is one of the {@link #OPENERS} methods and should be invoked at most once per
     * {@code StorageConnector} instance.</p>
     */
    private Reader createReader() throws IOException, DataStoreException {
        final InputStream input = getStorageAs(InputStream.class);
        if (input == null) {
            addView(Reader.class, null);                                        // Remember that there is no view.
            return null;
        }
        mark(input);
        final Charset encoding = getOption(OptionKey.ENCODING);
        final Reader in = (encoding != null) ? new InputStreamReader(input, encoding)
                                             : new InputStreamReader(input);
        /*
         * Current implementation does not wrap the above Reader in a BufferedReader because:
         *
         * 1) InputStreamReader already uses a buffer internally.
         * 2) InputStreamReader does not support mark/reset, which is a desired limitation for now.
         *    This is because reseting the Reader would not reset the underlying InputStream, which
         *    would cause other DataStoreProvider.probeContent(…) methods to fail if they try to use
         *    the InputStream. For now we let the InputStreamReader.mark() to throw an IOException,
         *    but we may need to provide our own subclass of BufferedReader in a future SIS version
         *    if mark/reset support is needed here.
         */
        addView(Reader.class, in, InputStream.class, true, false);
        return in;
    }

    /**
     * Creates a database connection if possible.
     *
     * <p>This method is one of the {@link #OPENERS} methods and should be invoked at most once per
     * {@code StorageConnector} instance.</p>
     */
    private Connection createConnection() throws SQLException {
        if (storage instanceof DataSource) {
            final Connection c = ((DataSource) storage).getConnection();
            addView(Connection.class, c, null, true, false);
            return c;
        }
        return null;
    }

    /**
     * Returns the storage as a path if possible, or {@code null} otherwise.
     *
     * <p>This method is one of the {@link #OPENERS} methods and should be invoked at most once per
     * {@code StorageConnector} instance.</p>
     */
    private String createString() {
        return IOUtilities.toString(storage);
    }

    /**
     * Adds the given view in the cache, without dependencies.
     *
     * @param  <T>   the compile-time type of the {@code type} argument.
     * @param  type  the view type.
     * @param  view  the view, or {@code null} if none.
     */
    private <T> void addView(final Class<T> type, final T view) {
        addView(type, view, null, false, false);
    }

    /**
     * Adds the given view in the cache together with information about its dependency.
     * For example {@link InputStreamReader} is a wrapper for a {@link InputStream}: read operations
     * from the later may change position of the former, and closing the later also close the former.
     *
     * @param  <T>             the compile-time type of the {@code type} argument.
     * @param  type            the view type.
     * @param  view            the view, or {@code null} if none.
     * @param  source          the type of input that {@code view} is wrapping, or {@code null} for {@link #storage}.
     * @param  cascadeOnReset  whether calls to {@link #reset(Coupled)} shall cascade.
     * @param  cascadeOnClose  whether calls to {@link AutoCloseable#close()} shall cascade.
     */
    private <T> void addView(final Class<T> type, final T view, final Class<?> source,
            final boolean cascadeOnReset, final boolean cascadeOnClose)
    {
        if (views == null) {
            views = new IdentityHashMap<>();
            views.put(null, new Coupled(storage));
        }
        Coupled c = views.get(type);
        if (c == null) {
            if (view == storage) {
                c = views.get(null);
                c.invalidateUsages();
            } else {
                c = new Coupled((cascadeOnReset | cascadeOnClose) ? views.get(source) : null, cascadeOnReset, cascadeOnClose);
                // Newly created objects are not yet used by anyone, so no need to invoke c.invalidateUsages().
            }
            views.put(type, c);
        } else {
            assert c.view == null || c.view == view : c;
            assert c.cascadeOnReset == cascadeOnReset && c.cascadeOnClose == cascadeOnClose : type;
            assert c.wrapperFor == ((cascadeOnReset | cascadeOnClose) ? views.get(source) : null) : c;
            c.invalidateUsages();
        }
        c.view = view;
        c.isValid = true;
        c.invalidateSources();
    }

    /**
     * Returns the view for the given type from the cache.
     * This method does <strong>not</strong> {@linkplain #reset(Coupled) reset} the view.
     *
     * @param  type  the view type, or {@code null} for the {@link #storage} container.
     * @return information associated to the given type. May be {@code null} if the view has never been
     *         requested before. {@link Coupled#view} may be {@code null} if the view has been requested
     *         and we determined that none can be created.
     */
    private Coupled getView(final Class<?> type) {
        return (views != null) ? views.get(type) : null;
    }

    /**
     * Closes all streams and connections created by this {@code StorageConnector} except the given view.
     * This method closes all objects created by the {@link #getStorageAs(Class)} method except the given {@code view}.
     * If {@code view} is {@code null}, then this method closes everything including the {@linkplain #getStorage()
     * storage} if it is closeable.
     *
     * <p>This method is invoked when a suitable {@link DataStore} has been found - in which case the view used
     * by the data store is given in argument to this method - or when no suitable {@code DataStore} has been
     * found - in which case the {@code view} argument is null.</p>
     *
     * <p>This {@code StorageConnector} instance shall not be used anymore after invocation of this method.</p>
     *
     * @param  view  the view to leave open, or {@code null} if none.
     * @throws DataStoreException if an error occurred while closing the stream or database connection.
     *
     * @see #getStorageAs(Class)
     * @see DataStoreProvider#open(StorageConnector)
     */
    public void closeAllExcept(final Object view) throws DataStoreException {
        if (views == null) {
            views = Collections.emptyMap();         // For blocking future usage of this StorageConnector instance.
            if (storage != view && storage instanceof AutoCloseable) try {
                ((AutoCloseable) storage).close();
            } catch (DataStoreException e) {
                throw e;
            } catch (Exception e) {
                throw new DataStoreException(e);
            }
            return;
        }
        /*
         * Create a list of all views to close. The boolean value is TRUE if the view should be closed, or FALSE
         * if the view should be protected (not closed). FALSE values shall have precedence over TRUE values.
         */
        final Map<AutoCloseable,Boolean> toClose = new IdentityHashMap<>(views.size());
        for (Coupled c : views.values()) {
            @SuppressWarnings("null")
            Object v = c.view;
            if (v != view) {
                if (v instanceof AutoCloseable) {
                    toClose.putIfAbsent((AutoCloseable) v, Boolean.TRUE);       // Mark 'v' as needing to be closed.
                }
            } else {
                /*
                 * If there is a view to not close, search for all views that are wrapper for the given view.
                 * Those wrappers shall not be closed. For example if the caller does not want to close the
                 * InputStream view, then we shall not close the InputStreamReader wrapper neither.
                 */
                c.protect(toClose);
                do {
                    v = c.view;
                    if (v instanceof AutoCloseable) {
                        toClose.put((AutoCloseable) v, Boolean.FALSE);          // Protect 'v' against closing.
                    }
                    c = c.wrapperFor;
                } while (c != null);
            }
        }
        /*
         * Trim the map in order to keep only the views to close.
         */
        for (final Iterator<Boolean> it = toClose.values().iterator(); it.hasNext();) {
            if (Boolean.FALSE.equals(it.next())) {
                it.remove();
            }
        }
        /*
         * The "AutoCloseable.close() is not indempotent" problem
         * ------------------------------------------------------
         * We will need a set of objects to close without duplicated values. For example the values associated to the
         * 'ImageInputStream.class' and 'DataInput.class' keys are often the same instance.  We must avoid duplicated
         * values because 'ImageInputStream.close()' is not indempotent,  i.e.  invoking their 'close()' method twice
         * will thrown an IOException.
         *
         * Generally speaking, all AutoCloseable instances are not guaranteed to be indempotent because this is not
         * required by the interface contract. Consequently we must be careful to not invoke the close() method on
         * the same instance twice (indirectly or indirectly). An exception to this rule is ImageInputStream, which
         * does not close its underlying stream. Those exceptions are identified by 'cascadeOnClose' set to 'true'.
         */
        if (!toClose.isEmpty()) {
            for (Coupled c : views.values()) {
                if (!c.cascadeOnClose && toClose.containsKey(c.view)) {     // Keep (do not remove) the "top level" view.
                    while ((c = c.wrapperFor) != null) {
                        toClose.remove(c.view);                             // Remove all views below the "top level" one.
                        if (c.cascadeOnClose) break;
                    }
                }
            }
        }
        views = Collections.emptyMap();         // For blocking future usage of this StorageConnector instance.
        /*
         * Now close all remaining items. Typically (but not necessarily) there is only one remaining item.
         * If an exception occurs, we will propagate it only after we are done closing all items.
         */
        DataStoreException failure = null;
        for (final AutoCloseable c : toClose.keySet()) {
            try {
                c.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = (e instanceof DataStoreException) ? (DataStoreException) e : new DataStoreException(e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Returns a string representation of this {@code StorageConnector} for debugging purpose.
     */
    @Debug
    @Override
    public String toString() {
        final StringBuilder buffer = new StringBuilder(40);
        buffer.append(Classes.getShortClassName(this)).append("[“").append(getStorageName()).append('”');
        if (options != null) {
            buffer.append(", options=").append(options);
        }
        return buffer.append(']').toString();
    }
}
