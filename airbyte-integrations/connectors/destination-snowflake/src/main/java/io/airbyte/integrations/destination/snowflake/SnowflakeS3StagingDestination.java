/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.snowflake;

import com.amazonaws.services.s3.AmazonS3;
import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.jdbc.JdbcDatabase;
import io.airbyte.integrations.base.AirbyteMessageConsumer;
import io.airbyte.integrations.base.Destination;
import io.airbyte.integrations.base.sentry.AirbyteSentry;
import io.airbyte.integrations.destination.jdbc.AbstractJdbcDestination;
import io.airbyte.integrations.destination.jdbc.copy.StagingConsumerFactory;
import io.airbyte.integrations.destination.jdbc.copy.s3.S3CopyConfig;
import io.airbyte.integrations.destination.s3.S3DestinationConfig;
import io.airbyte.integrations.destination.s3.csv.S3CsvFormatConfig;
import io.airbyte.integrations.destination.s3.csv.S3CsvFormatConfig.Flattening;
import io.airbyte.protocol.models.AirbyteConnectionStatus;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnowflakeS3StagingDestination extends AbstractJdbcDestination implements Destination {

  private static final Logger LOGGER = LoggerFactory.getLogger(SnowflakeS3StagingDestination.class);

  public SnowflakeS3StagingDestination() {
    super("", new SnowflakeSQLNameTransformer(), new SnowflakeSqlOperations());
  }

  @Override
  public AirbyteConnectionStatus check(final JsonNode config) {
    final S3DestinationConfig s3Config = getS3DestinationConfig(config);
    final SnowflakeSQLNameTransformer nameTransformer = new SnowflakeSQLNameTransformer();
    final SnowflakeS3StagingSqlOperations SnowflakeS3StagingSqlOperations = new SnowflakeS3StagingSqlOperations(s3Config.getS3Client(), s3Config);
    try (final JdbcDatabase database = getDatabase(config)) {
      final String outputSchema = super.getNamingResolver().getIdentifier(config.get("schema").asText());
      AirbyteSentry.executeWithTracing("CreateAndDropTable",
          () -> attemptSQLCreateAndDropTableOperations(outputSchema, database, nameTransformer, SnowflakeS3StagingSqlOperations));
      AirbyteSentry.executeWithTracing("CreateAndDropStage",
          () -> attemptSQLCreateAndDropStages(outputSchema, database, nameTransformer, SnowflakeS3StagingSqlOperations));
      return new AirbyteConnectionStatus().withStatus(AirbyteConnectionStatus.Status.SUCCEEDED);
    } catch (final Exception e) {
      LOGGER.error("Exception while checking connection: ", e);
      return new AirbyteConnectionStatus()
          .withStatus(AirbyteConnectionStatus.Status.FAILED)
          .withMessage("Could not connect with provided configuration. \n" + e.getMessage());
    }
  }

  private static void attemptSQLCreateAndDropStages(final String outputSchema,
                                                    final JdbcDatabase database,
                                                    final SnowflakeSQLNameTransformer namingResolver,
                                                    final SnowflakeS3StagingSqlOperations sqlOperations)
      throws Exception {

    // verify we have permissions to create/drop stage
    final String outputTableName = namingResolver.getIdentifier("_airbyte_connection_test_" + UUID.randomUUID().toString().replaceAll("-", ""));
    final String stageName = namingResolver.getStageName(outputSchema, outputTableName);
    sqlOperations.createStageIfNotExists(database, stageName);
    sqlOperations.dropStageIfExists(database, stageName);
  }

  @Override
  protected JdbcDatabase getDatabase(final JsonNode config) {
    return SnowflakeDatabase.getDatabase(config);
  }

  @Override
  protected Map<String, String> getDefaultConnectionProperties(final JsonNode config) {
    return Collections.emptyMap();
  }

  // this is a no op since we override getDatabase.
  @Override
  public JsonNode toJdbcConfig(final JsonNode config) {
    return Jsons.emptyObject();
  }

  @Override
  public AirbyteMessageConsumer getConsumer(final JsonNode config,
                                            final ConfiguredAirbyteCatalog catalog,
                                            final Consumer<AirbyteMessage> outputRecordCollector) {
    final S3DestinationConfig s3Config = getS3DestinationConfig(config);
    return new StagingConsumerFactory().create(
        outputRecordCollector,
        getDatabase(config),
        new SnowflakeS3StagingSqlOperations(s3Config.getS3Client(), s3Config),
        new SnowflakeSQLNameTransformer(), config, catalog);
  }

  private S3DestinationConfig getS3DestinationConfig(final JsonNode config) {
    final JsonNode loadingMethod = config.get("loading_method");
    return S3DestinationConfig.getS3DestinationConfig(loadingMethod);
  }

}
