/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dolphinscheduler.server.worker.download.impl.file;

import org.apache.dolphinscheduler.common.utils.FileUtils;
import org.apache.dolphinscheduler.common.utils.OSUtils;
import org.apache.dolphinscheduler.server.entity.download.TaskResourceDownloadContext;
import org.apache.dolphinscheduler.server.worker.download.impl.AbstractResourceCache;
import org.slf4j.Logger;

/**
 * file resource cache
 */
public class FileResourceCache extends AbstractResourceCache {
    /**
     * get cache base directory
     * @param downloadContext download context
     * @return directory path string
     */
    @Override
    public String getCacheBaseDir(TaskResourceDownloadContext downloadContext) {
        return String.format("%s/cache/resources/%d", FileUtils.DATA_BASEDIR, downloadContext.getId());
    }

    /**
     * get resource download directory
     * @param downloadContext download context
     * @return directory path string
     */
    @Override
    public String getResourceDownloadDir(TaskResourceDownloadContext downloadContext) {
        return String.format("%s/download", getCacheBaseDir(downloadContext));
    }

    /**
     * get directories should not exist before download
     * @param downloadContext download context
     * @return directory path string array
     */
    @Override
    public String[] getDirectoriesShouldNotExist(TaskResourceDownloadContext downloadContext) {
        return new String[]{getResourceDownloadDir(downloadContext), getResourceUnpackBaseDir(downloadContext)};
    }

    /**
     * cache by download resource
     * @param downloadContext download context
     * @param logger logger
     */
    @Override
    public void cacheByDownloadedResource(TaskResourceDownloadContext downloadContext, Logger logger) {
        String readyDir = getResourceDownloadDir(downloadContext);
        String cacheDir = getCacheDir(downloadContext);

        logger.info("rename [{}] to [{}]", readyDir, cacheDir);
        FileUtils.renameDir(readyDir, cacheDir);
    }

    /**
     * make reference to cached resource
     * @param downloadContext download context
     * @param referenceParentPath reference parent path string
     * @param logger logger
     */
    @Override
    public void makeReference(TaskResourceDownloadContext downloadContext, String referenceParentPath, Logger logger) {
        String realReferenceParentPath = referenceParentPath + downloadContext.getpDirRelativePath();
        String realCachePath = getCacheDir(downloadContext) + downloadContext.getpDirRelativePath();
        if (OSUtils.isWindows()) {
            logger.info("copySubFilesToDir from [{}] to [{}]", realCachePath, realReferenceParentPath);
            FileUtils.copySubFilesToDir(realCachePath, realReferenceParentPath);
        } else {
            logger.info("linkSubFilesToDir from [{}] to [{}]", realCachePath, realReferenceParentPath);
            FileUtils.linkSubFilesToDir(realCachePath, realReferenceParentPath);
        }
    }

    /**
     * get resource unpack base directory
     * @param downloadContext download context
     * @return directory path string
     */
    private String getResourceUnpackBaseDir(TaskResourceDownloadContext downloadContext) {
        return getCacheBaseDir(downloadContext) + "/unpack";
    }
}