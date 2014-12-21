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
package org.apache.sis.util.logging;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Base class for objets having auto-checking abilities
 * and easy access to Bundle and logging function. 
 * @author Marc LE BIHAN
 */
abstract public class AbstractAutoChecker {
    /** Logger. */
    private Logger logger = Logging.getLogger(getClass().getSimpleName());

    /**
     * Format a resource bundle message.
     *
     * @param classForResourceBundleName class from which ResourceBundle name will be extracted.
     * @param key Message key.
     * @param args Message arguments.
     * @return Message.
     */
    final protected String format(Class<?> classForResourceBundleName, String key, Object... args) {
        Objects.requireNonNull(classForResourceBundleName, "Class from with the ResourceBundle name is extracted cannot be null.");
        Objects.requireNonNull(key, "Message key cannot be bull.");

        Class<?> candidateClass = classForResourceBundleName;
        MessageFormat format = null;
        
        // Find the key in the bundle having for name this class, or in one of its superclasses.
        do {
            try {
                ResourceBundle rsc = ResourceBundle.getBundle(candidateClass.getName());
                format = new MessageFormat(rsc.getString(key));
            }
            catch(MissingResourceException e) {
                candidateClass = candidateClass.getSuperclass();
            }
        }
        while(candidateClass != null && format == null);
        
        if (format == null) {
            String fmt = "Cannot find property key {0} in {1} properties file or any of its superclasses.";
            String message = MessageFormat.format(fmt, key, classForResourceBundleName.getName());
            throw new MissingResourceException(message, classForResourceBundleName.getName(), key);
        }
        else
            return format.format(args);
    }

    /**
     * Format a resource bundle message.
     *
     * @param key Message key.
     * @param args Message arguments.
     * @return Message.
     */
    final protected String format(String key, Object... args) {
        return format(getClass(), key, args);
    }

    /**
     * Format a resource bundle message and before returning it, log it.
     *
     * @param logLevel Log Level.
     * @param key Message key.
     * @param args Message arguments.
     * @return Message.
     */
    final protected String format(Level logLevel, String key, Object... args) {
        Objects.requireNonNull(logLevel, "The log level cannot be null.");
        
        String message = format(key, args);
        logger.log(logLevel, message);
        return(message);
    }

    /**
     * Format a resource bundle message and before returning it, log it.
     *
     * @param classForResourceBundleName class from which ResourceBundle name will be extracted.
     * @param logLevel Log Level.
     * @param key Message key.
     * @param args Message arguments.
     * @return Message.
     */
    final protected String format(Level logLevel, Class<?> classForResourceBundleName, String key, Object... args) {
        Objects.requireNonNull(logLevel, "The log level cannot be null.");
        
        String message = format(classForResourceBundleName, key, args);
        logger.log(logLevel, message);
        return(message);
    }

    /**
     * Logs (and take the time to format an entry log) only if the logger accepts the message.
     * @param logLevel Log level.
     * @param key Message key.
     * @param args Message arguments.
     */
    final protected void log(Level logLevel, String key, Object... args) {
        Objects.requireNonNull(logLevel, "The log level cannot be null.");

        if (logger.isLoggable(logLevel))
            format(logLevel, key, args);
    }
    
    /**
     * Return the class logger.
     * @return logger.
     */
    public Logger getLogger() {
        return logger;
    }
}
