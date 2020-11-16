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
package org.apache.dolphinscheduler.server.worker.download.impl;

import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.utils.DateUtils;
import org.apache.dolphinscheduler.common.utils.FileUtils;
import org.apache.dolphinscheduler.common.utils.HadoopUtils;

import org.apache.dolphinscheduler.server.entity.download.TaskResourceDownloadContext;
import org.apache.dolphinscheduler.server.worker.download.IResourceCache;
import org.apache.dolphinscheduler.server.worker.download.ResourceCacheStatus;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 *  abstract resource cache
 */
public abstract class AbstractResourceCache implements IResourceCache {
    /**
     * cache dir name format(version format), use updateTime
     */
    private static String versionFormat = Constants.YYYYMMDDHHMMSS;

    /**
     * cache expired time(milliseconds)
     */
    private static long cacheExpiredMs = 7*24*3600*1000L;

    /**
     * cache min keep count when clean
     */
    private static int cacheMinKeepCount = 2;

    /**
     * lock for cache
     */
    private Lock lock;

    /**
     * condition for cache
     */
    private Condition condition;

    /**
     * cache status
     */
    private ResourceCacheStatus cacheStatus;

    public AbstractResourceCache() {
        this.lock = new ReentrantLock() ;
        this.condition = this.lock.newCondition();
        this.cacheStatus = ResourceCacheStatus.SUCCESS;
    }

    /**
     * make reference to cached resource
     *
     * @param downloadContext download context
     * @param referenceParentPath reference parent path string
     * @param logger logger
     */
    @Override
    public abstract void makeReference(TaskResourceDownloadContext downloadContext, String referenceParentPath, Logger logger);

    /**
     * cache resource
     *
     * @param downloadContext download context
     * @param logger logger
     */
    @Override
    public void cacheResource(TaskResourceDownloadContext downloadContext, Logger logger) {
        String cachePath = getCacheDir(downloadContext);

        ResourceCacheStatus afterStatus = ResourceCacheStatus.FAILED;
        lock.lock();
        switch (cacheStatus) {
            case CACHING:
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        if (condition.await(1, TimeUnit.MINUTES)) {
                            afterStatus = cacheStatus;
                            break;
                        } else {
                            logger.warn("condition await timeout(1 minutes)");
                        }
                    } catch (InterruptedException e) {
                        logger.warn("condition await is interrupted", e);
                        afterStatus = ResourceCacheStatus.FAILED;
                        Thread.currentThread().interrupt();
                    }
                }
                lock.unlock();
                break;
            case SUCCESS:
                File cacheFile = new File(cachePath);
                if (cacheFile.exists()) {
                    afterStatus = cacheStatus;
                    lock.unlock();
                    break;
                }
            case FAILED:
                cacheStatus = ResourceCacheStatus.CACHING;
                lock.unlock();

                boolean success = true;
                try {
                    beforeDownload(downloadContext, logger);
                    download(downloadContext, logger);
                    cacheByDownloadedResource(downloadContext, logger);
                } catch (Exception e) {
                    success = false;
                    logger.error("do cache resource failed", e);
                }

                // set status & signal all waiter
                lock.lock();
                cacheStatus = success ? ResourceCacheStatus.SUCCESS : ResourceCacheStatus.FAILED;
                afterStatus = cacheStatus;
                condition.signalAll();
                lock.unlock();

                // clean expired after successfully download
                if (success) {
                    try {
                        cleanExpired(downloadContext, logger);
                    } catch (Exception e) {
                        logger.error("resource[{}-{}-{}] clean up cache failed",
                                downloadContext.getResourceType(), downloadContext.getId(), downloadContext.getName());
                    }
                }
                break;
            default:
                logger.error("unknown cache status[{}]", cacheStatus);
                cacheStatus = ResourceCacheStatus.FAILED;
                afterStatus = cacheStatus;
                lock.unlock();
                break;
        }

        if (!afterStatus.equals(ResourceCacheStatus.SUCCESS)) {
            throw new RuntimeException(String.format("cache resource failed, because afterStatus[%s]", afterStatus));
        }
    }

    /**
     * get cache base directory
     * @param downloadContext download context
     * @return directory path string
     */
    public abstract String getCacheBaseDir(TaskResourceDownloadContext downloadContext);

    /**
     * get resource download directory
     * @param downloadContext download context
     * @return directory path string
     */
    public abstract String getResourceDownloadDir(TaskResourceDownloadContext downloadContext);

    /**
     * get cache directory
     * @param downloadContext download context
     * @return directory path string
     */
    protected String getCacheDir(TaskResourceDownloadContext downloadContext) {
        return String.format("%s/%s", getCacheBaseDir(downloadContext), getCacheDirName(downloadContext));
    }

    /**
     * get resource download path
     * @param downloadContext download context
     * @return resource download path string
     */
    public String getResourceDownloadPath(TaskResourceDownloadContext downloadContext) {
        return String.format("%s%s", getResourceDownloadDir(downloadContext), downloadContext.getName());
    }

    /**
     * get cache directory name
     * @param downloadContext
     * @return cache directory name
     */
    protected String getCacheDirName(TaskResourceDownloadContext downloadContext) {
        return DateUtils.format(downloadContext.getUpdateTime(), versionFormat);
    }

    /**
     * check name is cache directory name
     * @param name name
     * @return true or false
     */
    protected boolean isCacheDirName(String name) {
        return DateUtils.parse(name, versionFormat, false) != null;
    }

    /**
     * cache by download resource
     * @param downloadContext download context
     * @param logger logger
     */
    public abstract void cacheByDownloadedResource(TaskResourceDownloadContext downloadContext, Logger logger);

    /**
     * get directories should not exist before download
     * @param downloadContext download context
     * @return directory path string array
     */
    public abstract String[] getDirectoriesShouldNotExist(TaskResourceDownloadContext downloadContext);

    /**
     * before download
     * @param downloadContext download context
     * @param logger logger
     */
    public void beforeDownload(TaskResourceDownloadContext downloadContext, Logger logger) {
        for (String dir : getDirectoriesShouldNotExist(downloadContext)) {
            try {
                if (new File(dir).exists()) {
                    logger.info("before download, delete dir[{}]", dir);
                    FileUtils.deleteFile(dir);
                }
            } catch (IOException e) {
                throw new RuntimeException(String.format("before download, delete dir[%s] failed", dir), e);
            }
        }
    }

    /**
     * download resource to local file system
     * @param downloadContext download context
     * @param logger logger
     */
    private void download(TaskResourceDownloadContext downloadContext, Logger logger) {
        String tenantCode = downloadContext.getTenantCode();
        String fullName = downloadContext.getFullName();
        String localPath = getResourceDownloadPath(downloadContext);
        // query the tenant code of the resource according to the name of the resource
        String resHdfsPath = HadoopUtils.getHdfsResourceFileName(tenantCode, fullName);

        boolean success;
        try {
            logger.info("download [{}] from hdfs path [{}] to local path [{}]", fullName, resHdfsPath, localPath);
            success = HadoopUtils.getInstance().copyHdfsToLocal(resHdfsPath, localPath, false, true);
        } catch (Exception e){
            throw new RuntimeException(String.format("download [%s] from hdfs path [{%s] to local path [%s] exception",
                    fullName, resHdfsPath, localPath), e);
        }

        if (!success) {
            throw new RuntimeException(String.format("download [%s] from hdfs path [%s] to local path [%s] failed",
                    fullName, resHdfsPath, localPath));
        }
    }

    /**
     * clean expired
     * @param downloadContext download context
     * @param logger logger
     */
    public void cleanExpired(TaskResourceDownloadContext downloadContext, Logger logger) {
        try {
            List<String> expiredCacheNameList = getExpiredCacheList(downloadContext);
            for (String name : expiredCacheNameList) {
                String cachePath = String.format("%s%s%s", getCacheBaseDir(downloadContext), File.separator, name);
                logger.info("clear expired cache[{}] successfully", cachePath);
            }
        } catch (Exception e) {
            logger.error("clear expired cache[{}] failed", getCacheBaseDir(downloadContext), e);
        }
    }

    /**
     * get expired cache list
     * @param downloadContext download context
     * @return expired cache directory name list
     */
    private List<String> getExpiredCacheList(TaskResourceDownloadContext downloadContext) {
        long current = new Date().getTime();
        File cacheBaseFile = new File(getCacheBaseDir(downloadContext));
        File[] files = cacheBaseFile.listFiles();
        List<String> fileList = new ArrayList<>();
        if (files != null) {
            int notExpireCount = 0;
            for (File file : files) {
                if (file.isDirectory() && isCacheDirName(file.getName())) {
                    if (file.lastModified() + cacheExpiredMs < current) {
                        fileList.add(file.getName());
                    } else {
                        notExpireCount++;
                    }
                }
            }
            if (notExpireCount < cacheMinKeepCount) {
                // move (cacheMinKeepCount - notExpireCount) from fileList
                Collections.sort(fileList);
                return cacheMinKeepCount - notExpireCount < fileList.size()
                        ? fileList.subList(0, fileList.size() - (cacheMinKeepCount - notExpireCount)) : new ArrayList<>();
            }
        }
        return fileList;
    }
}