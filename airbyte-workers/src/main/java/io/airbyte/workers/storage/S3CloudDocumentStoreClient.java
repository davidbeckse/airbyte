/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Document store on top of the S3Client.
 */
public class S3CloudDocumentStoreClient implements CloudDocumentStoreClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(S3CloudDocumentStoreClient.class);

  private final String bucketName;
  private final Path root;
  private final S3Client s3Client;

  public S3CloudDocumentStoreClient(final S3Client s3Client, final String bucketName, final Path root) {
    this.s3Client = s3Client;
    this.bucketName = bucketName;
    this.root = root;
  }

  String getKey(final String id) {
    return root + "/" + id;
  }

  @Override
  public void write(final String id, final String document) {
    final PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(getKey(id))
        .build();

    s3Client.putObject(request, RequestBody.fromString(document));
  }

  @Override
  public Optional<String> read(final String id) {
    try {
      final ResponseBytes<GetObjectResponse> objectAsBytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
          .bucket(bucketName)
          .key(getKey(id))
          .build());
      return Optional.of(objectAsBytes.asString(StandardCharsets.UTF_8));
    } catch (final NoSuchKeyException e) {
      LOGGER.debug("Could not find record with id {}", id);
      return Optional.empty();
    }
  }

  @Override
  public boolean delete(final String id) {
    boolean keyExists = true;
    try {
      s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(getKey(id)).build());
    } catch (final NoSuchKeyException e) {
      keyExists = false;
    }

    s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(getKey(id)).build());
    return keyExists;
  }

}
