/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.persistence.job;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.commons.version.Version;
import io.airbyte.config.ActorDefinitionVersion;
import io.airbyte.config.DestinationConnection;
import io.airbyte.config.JobConfig;
import io.airbyte.config.JobConfig.ConfigType;
import io.airbyte.config.JobResetConnectionConfig;
import io.airbyte.config.JobSyncConfig;
import io.airbyte.config.JobTypeResourceLimit.JobType;
import io.airbyte.config.ResetSourceConfiguration;
import io.airbyte.config.ResourceRequirements;
import io.airbyte.config.ResourceRequirementsType;
import io.airbyte.config.SourceConnection;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.StandardSourceDefinition.SourceType;
import io.airbyte.config.StandardSync;
import io.airbyte.config.StandardSyncOperation;
import io.airbyte.config.provider.ResourceRequirementsProvider;
import io.airbyte.protocol.models.CatalogHelpers;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.DestinationSyncMode;
import io.airbyte.protocol.models.StreamDescriptor;
import io.airbyte.protocol.models.SyncMode;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of enqueueing a job. Hides the details of building the Job object and
 * storing it in the jobs db.
 */
@Slf4j
public class DefaultJobCreator implements JobCreator {

  private final JobPersistence jobPersistence;
  private final ResourceRequirementsProvider resourceRequirementsProvider;

  public DefaultJobCreator(final JobPersistence jobPersistence,
                           final ResourceRequirementsProvider resourceRequirementsProvider) {
    this.jobPersistence = jobPersistence;
    this.resourceRequirementsProvider = resourceRequirementsProvider;
  }

  @Override
  public Optional<Long> createSyncJob(final SourceConnection source,
                                      final DestinationConnection destination,
                                      final StandardSync standardSync,
                                      final String sourceDockerImageName,
                                      final Version sourceProtocolVersion,
                                      final String destinationDockerImageName,
                                      final Version destinationProtocolVersion,
                                      final List<StandardSyncOperation> standardSyncOperations,
                                      @Nullable final JsonNode webhookOperationConfigs,
                                      final StandardSourceDefinition sourceDefinition,
                                      final StandardDestinationDefinition destinationDefinition,
                                      final ActorDefinitionVersion sourceDefinitionVersion,
                                      final ActorDefinitionVersion destinationDefinitionVersion,
                                      final UUID workspaceId)
      throws IOException {
    // reusing this isn't going to quite work.

    final ResourceRequirements mergedOrchestratorResourceReq = getOrchestratorResourceRequirements(standardSync);
    final ResourceRequirements mergedSrcResourceReq = getSourceResourceRequirements(standardSync, sourceDefinition);
    final ResourceRequirements mergedDstResourceReq = getDestinationResourceRequirements(standardSync, destinationDefinition);

    final JobSyncConfig jobSyncConfig = new JobSyncConfig()
        .withNamespaceDefinition(standardSync.getNamespaceDefinition())
        .withNamespaceFormat(standardSync.getNamespaceFormat())
        .withPrefix(standardSync.getPrefix())
        .withSourceDockerImage(sourceDockerImageName)
        .withSourceProtocolVersion(sourceProtocolVersion)
        .withDestinationDockerImage(destinationDockerImageName)
        .withDestinationProtocolVersion(destinationProtocolVersion)
        .withOperationSequence(standardSyncOperations)
        .withWebhookOperationConfigs(webhookOperationConfigs)
        .withConfiguredAirbyteCatalog(standardSync.getCatalog())
        .withResourceRequirements(mergedOrchestratorResourceReq)
        .withSourceResourceRequirements(mergedSrcResourceReq)
        .withDestinationResourceRequirements(mergedDstResourceReq)
        .withIsSourceCustomConnector(sourceDefinition.getCustom())
        .withIsDestinationCustomConnector(destinationDefinition.getCustom())
        .withWorkspaceId(workspaceId)
        .withSourceDefinitionVersionId(sourceDefinitionVersion.getVersionId())
        .withDestinationDefinitionVersionId(destinationDefinitionVersion.getVersionId());

    final JobConfig jobConfig = new JobConfig()
        .withConfigType(ConfigType.SYNC)
        .withSync(jobSyncConfig);
    return jobPersistence.enqueueJob(standardSync.getConnectionId().toString(), jobConfig);
  }

  @Override
  public Optional<Long> createResetConnectionJob(final DestinationConnection destination,
                                                 final StandardSync standardSync,
                                                 final ActorDefinitionVersion destinationDefinitionVersion,
                                                 final String destinationDockerImage,
                                                 final Version destinationProtocolVersion,
                                                 final boolean isDestinationCustomConnector,
                                                 final List<StandardSyncOperation> standardSyncOperations,
                                                 final List<StreamDescriptor> streamsToReset)
      throws IOException {
    final ConfiguredAirbyteCatalog configuredAirbyteCatalog = standardSync.getCatalog();
    configuredAirbyteCatalog.getStreams().forEach(configuredAirbyteStream -> {
      final StreamDescriptor streamDescriptor = CatalogHelpers.extractDescriptor(configuredAirbyteStream);
      if (streamsToReset.contains(streamDescriptor)) {
        // The Reset Source will emit no record messages for any streams, so setting the destination sync
        // mode to OVERWRITE will empty out this stream in the destination.
        // Note: streams in streamsToReset that are NOT in this configured catalog (i.e. deleted streams)
        // will still have their state reset by the Reset Source, but will not be modified in the
        // destination since they are not present in the catalog that is sent to the destination.
        configuredAirbyteStream.setSyncMode(SyncMode.FULL_REFRESH);
        configuredAirbyteStream.setDestinationSyncMode(DestinationSyncMode.OVERWRITE);
      } else {
        // Set streams that are not being reset to APPEND so that they are not modified in the destination
        if (configuredAirbyteStream.getDestinationSyncMode() == DestinationSyncMode.OVERWRITE) {
          configuredAirbyteStream.setDestinationSyncMode(DestinationSyncMode.APPEND);
        }
      }
    });

    final JobResetConnectionConfig resetConnectionConfig = new JobResetConnectionConfig()
        .withNamespaceDefinition(standardSync.getNamespaceDefinition())
        .withNamespaceFormat(standardSync.getNamespaceFormat())
        .withPrefix(standardSync.getPrefix())
        .withDestinationDockerImage(destinationDockerImage)
        .withDestinationProtocolVersion(destinationProtocolVersion)
        .withOperationSequence(standardSyncOperations)
        .withConfiguredAirbyteCatalog(configuredAirbyteCatalog)
        .withResourceRequirements(getOrchestratorResourceRequirements(standardSync))
        .withResetSourceConfiguration(new ResetSourceConfiguration().withStreamsToReset(streamsToReset))
        .withIsSourceCustomConnector(false)
        .withIsDestinationCustomConnector(isDestinationCustomConnector)
        .withWorkspaceId(destination.getWorkspaceId())
        .withDestinationDefinitionVersionId(destinationDefinitionVersion.getVersionId());

    final JobConfig jobConfig = new JobConfig()
        .withConfigType(ConfigType.RESET_CONNECTION)
        .withResetConnection(resetConnectionConfig);
    return jobPersistence.enqueueJob(standardSync.getConnectionId().toString(), jobConfig);
  }

  private ResourceRequirements getOrchestratorResourceRequirements(final StandardSync standardSync) {
    final ResourceRequirements defaultOrchestratorRssReqs =
        resourceRequirementsProvider.getResourceRequirements(ResourceRequirementsType.ORCHESTRATOR, Optional.empty());
    return ResourceRequirementsUtils.getResourceRequirements(
        standardSync.getResourceRequirements(),
        defaultOrchestratorRssReqs);
  }

  private ResourceRequirements getSourceResourceRequirements(final StandardSync standardSync, final StandardSourceDefinition sourceDefinition) {
    final ResourceRequirements defaultSrcRssReqs =
        resourceRequirementsProvider.getResourceRequirements(ResourceRequirementsType.SOURCE,
            Optional.ofNullable(sourceDefinition.getSourceType()).map(SourceType::toString));
    return ResourceRequirementsUtils.getResourceRequirements(
        standardSync.getResourceRequirements(),
        sourceDefinition.getResourceRequirements(),
        defaultSrcRssReqs,
        JobType.SYNC);
  }

  private ResourceRequirements getDestinationResourceRequirements(final StandardSync standardSync,
                                                                  final StandardDestinationDefinition destinationDefinition) {
    final ResourceRequirements defaultDstRssReqs =
        resourceRequirementsProvider.getResourceRequirements(ResourceRequirementsType.DESTINATION, Optional.empty());
    return ResourceRequirementsUtils.getResourceRequirements(
        standardSync.getResourceRequirements(),
        destinationDefinition.getResourceRequirements(),
        defaultDstRssReqs,
        JobType.SYNC);
  }

}
