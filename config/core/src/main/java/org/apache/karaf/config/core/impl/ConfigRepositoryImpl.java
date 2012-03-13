/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.config.core.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;

import org.apache.felix.fileinstall.ArtifactInstaller;
import org.apache.felix.utils.properties.Properties;
import org.apache.karaf.config.core.ConfigRepository;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigRepositoryImpl implements ConfigRepository {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigRepositoryImpl.class);
    private static final String FILEINSTALL_FILE_NAME="felix.fileinstall.filename";

    private static final String PID_FILTER="(service.pid=%s*)";
    private static final String FILE_PREFIX="file:";
    private static final String CONFIG_SUFFIX=".cfg";
    private static final String FACTORY_SEPARATOR = "-";

    private ConfigurationAdmin configAdmin;
    
    private File storage;
    private List<ArtifactInstaller> artifactInstallers;

    public ConfigRepositoryImpl(File storage, ConfigurationAdmin configAdmin, List<ArtifactInstaller> artifactInstallers) {
        this.storage = storage;
        this.configAdmin = configAdmin;
        this.artifactInstallers = artifactInstallers;
    }
    
    /**
     * <p>
     * Returns the Configuration object of the given (felix fileinstall) file name.
     * </p>
     * @param fileName
     * @return
     */
    @SuppressWarnings("rawtypes")
    public Configuration findConfigurationByFileName(String fileName) throws IOException, InvalidSyntaxException {
        if (fileName != null && fileName.contains(FACTORY_SEPARATOR)) {
            String factoryPid = fileName.substring(0, fileName.lastIndexOf(FACTORY_SEPARATOR));
            String absoluteFileName = FILE_PREFIX +storage.getAbsolutePath() + File.separator + fileName + CONFIG_SUFFIX;
            Configuration[] configurations = this.configAdmin.listConfigurations(String.format(PID_FILTER, factoryPid));
            if (configurations != null) {
                for (Configuration configuration : configurations) {
                    Dictionary dictionary = configuration.getProperties();
                    if (dictionary != null) {
                        String fileInstallFileName = (String) dictionary.get(FILEINSTALL_FILE_NAME);
                        if (absoluteFileName.equals(fileInstallFileName)) {
                            return configuration;
                        }
                    }
                }
            }
        }
        return null;
    }

    /* (non-Javadoc)
     * @see org.apache.karaf.shell.config.impl.ConfigRepository#update(java.lang.String, java.util.Dictionary, boolean)
     */
    @Override
    @SuppressWarnings("rawtypes")
    public void update(String pid, Dictionary props, boolean bypassStorage) throws IOException {
        if (!bypassStorage && storage != null) {
            persistConfiguration(pid, props);
        } else {
            updateConfiguration(pid, props);
        }
    }
    
    /* (non-Javadoc)
     * @see org.apache.karaf.shell.config.impl.ConfigRepository#update(java.lang.String, java.util.Dictionary)
     */
    @Override
    @SuppressWarnings("rawtypes")
    public void update(String pid, Dictionary props) throws IOException {
        update(pid, props, false);
    }

    /**
     * Persists configuration to storage.
     * @param admin
     * @param pid
     * @param props
     * @throws IOException
     */
    @SuppressWarnings("rawtypes")
    private void persistConfiguration(String pid, Dictionary props) throws IOException {
        File storageFile = new File(storage, pid + ".cfg");
        Configuration cfg = this.configAdmin.getConfiguration(pid, null);
        if (cfg != null && cfg.getProperties() != null) {
            Object val = cfg.getProperties().get(FILEINSTALL_FILE_NAME);
            if (val instanceof String) {
                if (((String) val).startsWith("file:")) {
                    val = ((String) val).substring("file:".length());
                }
                storageFile = new File((String) val);
            }
        }
        Properties p = new Properties(storageFile);
        for (Enumeration keys = props.keys(); keys.hasMoreElements();) {
            Object key = keys.nextElement();
            if (shouldPersist(key)) {
                p.put((String) key, (String) props.get(key));
            }
        }
        // remove "removed" properties from the file
        ArrayList<String> propertiesToRemove = new ArrayList<String>();
        for (Object key : p.keySet()) {
            if (props.get(key) == null
                    && shouldPersist(key)) {
                propertiesToRemove.add(key.toString());
            }
        }
        for (String key : propertiesToRemove) {
            p.remove(key);
        }
        // save the cfg file
        storage.mkdirs();
        p.save();
        updateFileInstall(storageFile);
    }
    
    /**
     * Trigger felix fileinstall to update the config so there is no delay till it polls the file
     * 
     * @param storageFile
     * @throws Exception
     */
    private void updateFileInstall(File storageFile) {
        if (artifactInstallers != null) {
            for (ArtifactInstaller installer : artifactInstallers) {
                if (installer.canHandle(storageFile)) {
                    try {
                        installer.update(storageFile);
                    } catch (Exception e) {
                        LOG.warn("Error updating config " + storageFile + " in felix fileinstall" + e.getMessage(), e);
                    }
                }
            }
        }
    }

    private boolean shouldPersist(Object key) {
        return !Constants.SERVICE_PID.equals(key)
                && !ConfigurationAdmin.SERVICE_FACTORYPID.equals(key)
                && !FILEINSTALL_FILE_NAME.equals(key);
    }

    /**
     * Updates the configuration to the {@link ConfigurationAdmin} service.
     * @param pid
     * @param props
     * @throws IOException
     */
    @SuppressWarnings("rawtypes")
    public void updateConfiguration(String pid, Dictionary props) throws IOException {
        Configuration cfg = this.configAdmin.getConfiguration(pid, null);
        if (cfg.getProperties() == null) {
            PidParts pidParts = parsePid(pid);
            if (pidParts.factoryPid != null) {
                cfg = this.configAdmin.createFactoryConfiguration(pidParts.pid, null);
            }
        }
        if (cfg.getBundleLocation() != null) {
            cfg.setBundleLocation(null);
        }
        cfg.update(props);
    }

    private PidParts parsePid(String sourcePid) {
        PidParts pidParts = new PidParts();
        int n = sourcePid.indexOf('-');
        if (n > 0) {
            pidParts.factoryPid = sourcePid.substring(n + 1);
            pidParts.pid = sourcePid.substring(0, n);
        } else {
            pidParts.pid = sourcePid;
        }
        return pidParts;
    }
    
    private class PidParts {
        String pid;
        String factoryPid;
    }
    
    /* (non-Javadoc)
     * @see org.apache.karaf.shell.config.impl.ConfigRepository#delete(java.lang.String)
     */
    @Override
    public void delete(String pid) throws Exception {
        Configuration configuration = this.configAdmin.getConfiguration(pid);
        configuration.delete();
        deleteStorage(pid);
    }
    
    protected void deleteStorage(String pid) throws Exception {
        if (storage != null) {
            File cfgFile = new File(storage, pid + ".cfg");
            cfgFile.delete();
        }
    }

    /* (non-Javadoc)
     * @see org.apache.karaf.shell.config.impl.ConfigRepository#getConfigProperties(java.lang.String)
     */
    @Override
    @SuppressWarnings("rawtypes")
    public Dictionary getConfigProperties(String pid) throws IOException, InvalidSyntaxException {
        if(pid != null && configAdmin != null) {
            Configuration configuration = this.configAdmin.getConfiguration(pid);
            if(configuration != null) {
                Dictionary props = configuration.getProperties();
                return (props != null) ? props : new Hashtable<String, String>();
            }
        }
        return null;
    }

    public ConfigurationAdmin getConfigAdmin() {
        return this.configAdmin;
    }

}