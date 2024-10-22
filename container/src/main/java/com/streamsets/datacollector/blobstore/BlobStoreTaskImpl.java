/*
 * Copyright 2018 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.datacollector.blobstore;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.streamsets.datacollector.blobstore.meta.BlobStoreMetadata;
import com.streamsets.datacollector.blobstore.meta.NamespaceMetadata;
import com.streamsets.datacollector.blobstore.meta.ObjectMetadata;
import com.streamsets.datacollector.main.RuntimeInfo;
import com.streamsets.datacollector.task.AbstractTask;
import com.streamsets.pipeline.api.BlobStore;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.impl.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

public class BlobStoreTaskImpl extends AbstractTask implements BlobStoreTask {

  private static final Logger LOG = LoggerFactory.getLogger(BlobStoreTaskImpl.class);
  // Subdirectory that will be created under data directory for storing the objects
  private static final String BASE_DIR = "blobstore";
  // File name of stored version of our metadata database
  private static final String METADATA_FILE = "metadata.json";

  // JSON ObjectMapper for storing and reading metadata database to/from disk
  private static final ObjectMapper jsonMapper = new ObjectMapper();
  static {
    jsonMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
  }

  private final RuntimeInfo runtimeInfo;

  private Path baseDir;
  private Path metadataFile;
  private BlobStoreMetadata metadata;

  @Inject
  public BlobStoreTaskImpl(
    RuntimeInfo runtimeInfo
  ) {
    super("Blob Store Task");
    this.runtimeInfo = runtimeInfo;
  }

  @Override
  public void initTask() {
    this.baseDir = Paths.get(runtimeInfo.getDataDir(), BASE_DIR);
    this.metadataFile = this.baseDir.resolve(METADATA_FILE);

    if (!Files.exists(baseDir)) {
      initializeFreshInstall();
    } else {
      initializeFromDisk();
    }
  }

  private void initializeFreshInstall() {
    try {
      Files.createDirectories(baseDir);
    } catch (IOException e) {
      throw new RuntimeException(Utils.format("Could not create directory '{}'", baseDir), e);
    }

    // Create new fresh metadata and persist them on disk
    this.metadata = new BlobStoreMetadata();
    try {
      saveMetadata();
    } catch (StageException e) {
      throw new RuntimeException(Utils.format("Can't initialize blob store: {}", e.toString()), e);
    }
  }

  private void initializeFromDisk() {
    try {
      this.metadata = loadMetadata();
      LOG.debug(
        "Loaded blob store metadata of version {} with {} namespaces",
        metadata.getVersion(),
        metadata.getNamespaces().size()
      );
    } catch (StageException e) {
      throw new RuntimeException(Utils.format("Can't initialize blob store: {}", e.toString()), e);
    }
  }

  @Override
  public synchronized void store(String namespace, String id, long version, String content) throws StageException {
    Preconditions.checkArgument(BlobStore.VALID_NAMESPACE_PATTERN.matcher(namespace).matches());
    Preconditions.checkArgument(BlobStore.VALID_ID_PATTERN.matcher(id).matches());

    ObjectMetadata objectMetadata = metadata.getOrCreateNamespace(namespace).getOrCreateObject(id);

    if(objectMetadata.containsVersion(version)) {
      throw new StageException(BlobStoreError.BLOB_STORE_0003, namespace, id, version);
    }

    // Store the content inside independent file with randomized location
    String contentFile = namespace + UUID.randomUUID().toString() + ".content";
    try {
      Files.write(baseDir.resolve(contentFile), content.getBytes());
    } catch (IOException e) {
      throw new StageException(BlobStoreError.BLOB_STORE_0004, e.toString(), e);
    }

    // Update and save internal structure
    objectMetadata.createContent(version, contentFile);
    saveMetadata();
  }

  @Override
  public synchronized long latestVersion(String namespace, String id) throws StageException {
    return getObjectDieIfNotExists(namespace, id).latestVersion();
  }

  @Override
  public boolean exists(String namespace, String id) {
    ObjectMetadata objectMetadata = getObject(namespace, id);
    return objectMetadata != null;
  }

  @Override
  public synchronized Set<Long> allVersions(String namespace, String id) {
    ObjectMetadata objectMetadata = getObject(namespace, id);
    return objectMetadata == null ? Collections.emptySet() : objectMetadata.allVersions();
  }

  @Override
  public synchronized String retrieve(String namespace, String id, long version) throws StageException {
    ObjectMetadata objectMetadata = getObjectDieIfNotExists(namespace, id);

    if(!objectMetadata.containsVersion(version)) {
      throw new StageException(BlobStoreError.BLOB_STORE_0007, namespace, id, version);
    }

    try {
      return new String(Files.readAllBytes(baseDir.resolve(objectMetadata.uuidForVersion(version))));
    } catch (IOException e) {
      throw new StageException(BlobStoreError.BLOB_STORE_0008, e.toString(), e);
    }
  }

  @Override
  public synchronized void delete(String namespace, String id, long version) throws StageException {
    ObjectMetadata objectMetadata = getObjectDieIfNotExists(namespace, id);

    if(!objectMetadata.containsVersion(version)) {
      throw new StageException(BlobStoreError.BLOB_STORE_0007, namespace, id, version);
    }

    String uuid = objectMetadata.uuidForVersion(version);
    objectMetadata.removeVersion(version);
    try {
      Files.delete(baseDir.resolve(uuid));
    } catch (IOException e) {
      throw new StageException(BlobStoreError.BLOB_STORE_0008, e.toString(), e);
    }

  }

  private void saveMetadata() throws StageException {
    // TODO: This is unsafe, we could truncate the metadata file and then die in middle of writing. We need to make a
    // three phase commit here. Write the metadata temp file, drop primary file, move the temporary. To keep this patch
    // simple this will be done in subsequent patch.
    try (
      OutputStream os = Files.newOutputStream(metadataFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    ) {
      jsonMapper.writeValue(os, metadata);
    } catch (IOException e) {
      throw new StageException(BlobStoreError.BLOB_STORE_0001, e.toString(), e);
    }
  }

  private BlobStoreMetadata loadMetadata() throws StageException {
    try(
      InputStream is = Files.newInputStream(metadataFile);
    ) {
      return jsonMapper.readValue(is, BlobStoreMetadata.class);
    } catch (IOException e) {
      throw new StageException(BlobStoreError.BLOB_STORE_0001, e.toString(), e);
    }
  }

  private ObjectMetadata getObject(String namespace, String id) {
    NamespaceMetadata namespaceMetadata = metadata.getNamespace(namespace);
    if(namespaceMetadata == null) {
      return null;
    }

    return namespaceMetadata.getObject(id);
  }

  private ObjectMetadata getObjectDieIfNotExists(String namespace, String id) throws StageException {
    NamespaceMetadata namespaceMetadata = metadata.getNamespace(namespace);
    if(namespaceMetadata == null) {
      throw new StageException(BlobStoreError.BLOB_STORE_0005, namespace);
    }

    ObjectMetadata objectMetadata = namespaceMetadata.getObject(id);
    if(objectMetadata == null) {
      throw new StageException(BlobStoreError.BLOB_STORE_0006, namespace, id);
    }

    return objectMetadata;
  }
}
