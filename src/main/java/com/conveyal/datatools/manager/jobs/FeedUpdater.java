package com.conveyal.datatools.manager.jobs;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.conveyal.datatools.common.utils.aws.CheckedAWSException;
import com.conveyal.datatools.common.utils.aws.S3Utils;
import com.conveyal.datatools.manager.models.ExternalFeedSourceProperty;
import com.conveyal.datatools.manager.models.FeedSource;
import com.conveyal.datatools.manager.models.FeedVersion;
import com.conveyal.datatools.manager.persistence.FeedStore;
import com.conveyal.datatools.manager.persistence.Persistence;
import com.conveyal.datatools.manager.utils.HashUtils;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.conveyal.datatools.manager.extensions.mtc.MtcFeedResource.AGENCY_ID_FIELDNAME;
import static com.conveyal.datatools.common.utils.Scheduler.schedulerService;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.or;

/**
 * This class is used to schedule an {@link UpdateFeedsTask}, which will check the specified S3 bucket (and prefix) for
 * new files. If a new feed is found, the feed will be downloaded and the {@link FeedVersion#namespace} for the feed
 * version (for that feed source) with the most recent {@link FeedVersion#sentToExternalPublisher} timestamp will be set
 * as the newly published version in {@link FeedSource#publishedVersionId}.
 *
 * This is all done to ensure that the "published" version in MTC's RTD database matches the published version in Data
 * Tools, which is primarily used to ensure that any alerts are built using GTFS stop or route IDs from the active GTFS
 * feed.
 *
 * FIXME it is currently not possible with the MTC RTD feed processing workflow because GTFS files are modified during
 *   RTD's processing, but workflow should be replaced with a check for the processed file's MD5 checksum. Also, one RTD
 *   state that is not captured by Data Tools is when a feed version fails to process in RTD, the RTD application places
 *   it in a “failed” folder, yet there is no check by Data Tools to see if the feed landed there.
 */
public class FeedUpdater {
    private static final String TEST_BUCKET = "test-bucket";
    private static final String TEST_COMPLETED_FOLDER = "test-completed";
    private static final Logger LOG = LoggerFactory.getLogger(FeedUpdater.class);
    public static final String SENT_TO_EXTERNAL_PUBLISHER_FIELD = "sentToExternalPublisher";
    public static final String PROCESSED_BY_EXTERNAL_PUBLISHER_FIELD = "processedByExternalPublisher";

    private Map<String, String> eTagForFeed;
    private final String feedBucket;
    private final String bucketFolder;
    private final CompletedFeedRetriever completedFeedRetriever;
    private List<String> versionsToMarkAsProcessed;


    private FeedUpdater(int updateFrequencySeconds, String feedBucket, String bucketFolder) {
        LOG.info("Setting feed update to check every {} seconds", updateFrequencySeconds);
        schedulerService.scheduleAtFixedRate(new UpdateFeedsTask(), 0, updateFrequencySeconds, TimeUnit.SECONDS);
        this.feedBucket = feedBucket;
        this.bucketFolder = bucketFolder;
        this.completedFeedRetriever = new DefaultCompletedFeedRetriever();
    }

    /**
     * Constructor used for tests.
     */
    private FeedUpdater(CompletedFeedRetriever completedFeedRetriever) {
        this.feedBucket = TEST_BUCKET;
        this.bucketFolder = TEST_COMPLETED_FOLDER;
        this.completedFeedRetriever = completedFeedRetriever;
    }

    /**
     * Create a {@link FeedUpdater} to poll the provided S3 bucket/prefix at the specified interval (in seconds) for
     * updated files. The updater's task is run using the {@link com.conveyal.datatools.common.utils.Scheduler#schedulerService}.
     */
    public static FeedUpdater schedule(int updateFrequencySeconds, String s3Bucket, String s3Prefix) {
        return new FeedUpdater(updateFrequencySeconds, s3Bucket, s3Prefix);
    }

    /**
     * Helper method used in tests to create a {@link FeedUpdater}.
     */
    public static FeedUpdater createForTest(CompletedFeedRetriever completedFeedRetriever) {
        return new FeedUpdater(completedFeedRetriever);
    }

    private static String getFeedId(S3ObjectSummary objSummary) {
        String[] parts = objSummary.getKey().split("/");
        return parts.length > 1 ? parts[1].replace(".zip", "") : "";
    }

    public static List<String> getFeedIds(List<S3ObjectSummary> s3objects) {
        return s3objects.stream()
            .map(FeedUpdater::getFeedId)
            .filter(id -> id.length() != 0)
            .collect(Collectors.toList());
    }

    public static Map<String, String> mapFeedIdsToFeedSourceIds(List<ExternalFeedSourceProperty> properties) {
        Map<String, String> feedToFeedSourceId = new HashMap<>();
        Map<String, Boolean> multipleSourceWarning = new HashMap<>();
        for (ExternalFeedSourceProperty prop : properties) {
            String feedId = prop.value;
            if (feedToFeedSourceId.get(feedId) != null && !multipleSourceWarning.getOrDefault(feedId, false)) {
                multipleSourceWarning.put(feedId, true);
                LOG.warn("Found multiple feed sources for {}: {}. The published status on some feed versions will be incorrect.",
                    feedId,
                    properties
                        .stream().filter(p -> feedId.equals(p.value))
                        .map(p -> p.feedSourceId)
                        .collect(Collectors.toList())
                );
            }
            feedToFeedSourceId.put(feedId, prop.feedSourceId);
        }
        return feedToFeedSourceId;
    }

    public static Map<String, FeedSource> mapFeedIdsToFeedSources(
        Map<String, String> feedIdsToFeedSourceIds,
        List<FeedSource> feedSources
    ) {
        Map<String, FeedSource> feedSourceIdToFeedSource = feedSources.stream()
            .collect(Collectors.toMap(src -> src.id, Function.identity()));

        return feedIdsToFeedSourceIds.entrySet().stream()
            .collect(Collectors.toMap(e -> e.getKey(), e -> feedSourceIdToFeedSource.get(e.getValue())));
    }

    private class UpdateFeedsTask implements Runnable {
        public void run() {
            LOG.info("Starting UpdateFeedsTask");
            long startMillis = System.currentTimeMillis();
            Map<String, String> updatedTags;
            try {
                LOG.debug("Checking MTC feeds for newly processed versions");
                updatedTags = checkForUpdatedFeeds();
                eTagForFeed.putAll(updatedTags);
                if (!updatedTags.isEmpty()) LOG.info("New eTag list: {}", eTagForFeed);
                else LOG.debug("No feeds updated (eTags on S3 match current list).");
            } catch (Exception e) {
                LOG.error("Error updating feeds {}", e);
            }

            LOG.info("Completed UpdateFeedsTask in {} ms", System.currentTimeMillis() - startMillis);
        }
    }

    /**
     * Check for any updated feeds that have been published to the S3 bucket. This tracks eTagForFeed (AWS file hash) of s3
     * objects in order to keep data-tools application in sync with external processes (for example, MTC RTD).
     * @return          map of feedIDs to eTag values
     */
    public Map<String, String> checkForUpdatedFeeds() {
        if (eTagForFeed == null) {
            // If running the check for the first time, instantiate the eTag map.
            LOG.info("Running initial check for feeds on S3.");
            eTagForFeed = new HashMap<>();
        }

        // The feed versions corresponding to entries in objectSummaries
        // that need to be marked as processed should meet all conditions below:
        // - sentToExternalPublisher is not null,
        // - processedByExternalPublisher is null or before sentToExternalPublisher.
        Bson query = and(
            ne(SENT_TO_EXTERNAL_PUBLISHER_FIELD, null),
            or(
                eq(PROCESSED_BY_EXTERNAL_PUBLISHER_FIELD, null),
                lt(PROCESSED_BY_EXTERNAL_PUBLISHER_FIELD, SENT_TO_EXTERNAL_PUBLISHER_FIELD)
            )
        );
        versionsToMarkAsProcessed = Persistence.feedVersionSummaries.getFiltered(query)
            .stream()
            .map(v -> v.id)
            .collect(Collectors.toList());

        LOG.debug("Checking for feeds on S3.");
        Map<String, String> newTags = new HashMap<>();
        // iterate over feeds in download_prefix folder and register to (MTC project)
        List<S3ObjectSummary> objectSummaries = completedFeedRetriever.retrieveCompletedFeeds();
        if (objectSummaries == null) {
            return newTags;
        }

        List<String> allFeedIds = getFeedIds(objectSummaries);

        // Single Mongo query to get external properties for all feeds reported in S3.
        List<ExternalFeedSourceProperty> allProperties = Persistence.externalFeedSourceProperties.getFiltered(
            and(eq("name", AGENCY_ID_FIELDNAME), in("value", allFeedIds))
        );

        Map<String, String> feedIdToFeedSourceId = mapFeedIdsToFeedSourceIds(allProperties);

        // Single Mongo query to get the various feed source objects.
        List<FeedSource> feedSources = Persistence.feedSources
            .getByIds(Lists.newArrayList(feedIdToFeedSourceId.values()));

        Map<String, FeedSource> allFeedSourcesById = mapFeedIdsToFeedSources(feedIdToFeedSourceId, feedSources);

        LOG.debug(eTagForFeed.toString());
        for (S3ObjectSummary objSummary : objectSummaries) {
            String eTag = objSummary.getETag();
            String keyName = objSummary.getKey();

            LOG.debug("{} etag = {}", keyName, eTag);

            // Don't add object if it is a dir
            if (keyName.equals(bucketFolder)) continue;

            String feedId = getFeedId(objSummary);

            // Skip object if the filename is null
            if ("null".equals(feedId)) continue;

            FeedSource feedSource = allFeedSourcesById.get(feedId);
            if (feedSource == null) {
                LOG.error("No feed source found for feed ID {}", feedId);
                continue;
            }

            FeedVersion latestVersionSentForPublishing = getLatestVersionSentForPublishing(feedId, feedSource);
            if (shouldMarkFeedAsProcessed(eTag, latestVersionSentForPublishing)) {
                try {
                    // Don't mark a feed version as published if previous published version is before sentToExternalPublisher.
                    if (!objSummary.getLastModified().before(latestVersionSentForPublishing.sentToExternalPublisher)) {
                        LOG.info("New version found for {} at s3://{}/{}. ETag = {}.", feedId, feedBucket, keyName, eTag);
                        updatePublishedFeedVersion(feedId, latestVersionSentForPublishing);
                        // TODO: Explore if MD5 checksum can be used to find matching feed version.
                        // findMatchingFeedVersion(md5, feedId, feedSource);
                    }

                } catch (Exception e) {
                    LOG.warn("Could not load feed " + keyName, e);
                } finally {
                    // Add new tag to map used for tracking updates. NOTE: this is in a finally block because we still
                    // need to track the eTags even for feed sources that were not found. Otherwise, the feeds will be
                    // re-downloaded each time the update task is run, which could cause many unnecessary S3 operations.
                    newTags.put(feedId, eTag);
                }
            } else {
                LOG.debug("Etag {} already exists in map", eTag);
            }
        }
        return newTags;
    }

    /**
     * @return true if the feed with the corresponding etag should be mark as processed, false otherwise.
     */
    private boolean shouldMarkFeedAsProcessed(String eTag, FeedVersion publishedVersion) {
        if (eTagForFeed.containsValue(eTag)) return false;
        if (publishedVersion == null) return false;

        return versionsToMarkAsProcessed.contains(publishedVersion.id);
    }

    /**
     * Update the published feed version for the feed source.
     * @param feedId the unique ID used by MTC to identify a feed source
     * @param publishedVersion the feed version to be registered
     */
    private void updatePublishedFeedVersion(String feedId, FeedVersion publishedVersion) {
        try {
            if (publishedVersion != null) {
                if (publishedVersion.sentToExternalPublisher == null) {
                    LOG.warn("Not updating published version for {} (version was never sent to external publisher)", feedId);
                    return;
                }
                // Set published namespace to the feed version and set the processedByExternalPublisher timestamp.
                LOG.info("Latest published version (sent at {}) for {} is {}", publishedVersion.sentToExternalPublisher, feedId, publishedVersion.id);
                Persistence.feedVersions.updateField(publishedVersion.id, PROCESSED_BY_EXTERNAL_PUBLISHER_FIELD, new Date());
                Persistence.feedSources.updateField(publishedVersion.feedSourceId, "publishedVersionId", publishedVersion.namespace);
            } else {
                LOG.error(
                    "No published versions found for {} ({} id={})",
                    feedId,
                    publishedVersion.parentFeedSource().name,
                    publishedVersion.feedSourceId
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
            LOG.error("Error encountered while updating the latest published version for {}", feedId);
        }
    }

    /**
     * Get the latest published version (if there is one). NOTE: This is somewhat flawed because it presumes
     * that the latest published version is guaranteed to be the one found in the "completed" folder, but it
     * could be that more than one versions were recently "published" and the latest published version was a bad
     * feed that failed processing by RTD.
     */
    private static FeedVersion getLatestVersionSentForPublishing(String feedId, FeedSource feedSource) {
        LOG.info("Calling getLatestVersionSentForPublishing");
        try {
            // Collect the feed versions for the feed source.
            Collection<FeedVersion> versions = feedSource.retrieveFeedVersions();
            Optional<FeedVersion> lastPublishedVersionCandidate = versions
                .stream()
                .min(Comparator.comparing(v -> v.sentToExternalPublisher, Comparator.nullsLast(Comparator.reverseOrder())));
            return lastPublishedVersionCandidate.orElse(null);
        } catch (Exception e) {
            e.printStackTrace();
            LOG.error("Error encountered while checking for latest published version for {}", feedId);
            return null;
        }
    }

    /**
     * NOTE: This method is not in use, but should be strongly considered as an alternative approach if/when RTD is able
     * to maintain md5 checksums when copying a file from "waiting" folder to "completed".
     * Find matching feed version for a feed source based on md5. NOTE: This is no longer in use because MTC's RTD system
     * does NOT preserve MD5 checksums when moving a file from the "waiting" to "completed" folders on S3.
     */
    private FeedVersion findMatchingFeedVersion(
        String keyName,
        FeedSource feedSource
    ) throws AmazonServiceException, IOException, CheckedAWSException {
        String filename = keyName.split("/")[1];
        String feedId = filename.replace(".zip", "");
        S3Object object = S3Utils.getDefaultS3Client().getObject(feedBucket, keyName);
        InputStream in = object.getObjectContent();
        File file = new File(FeedStore.basePath, filename);
        OutputStream out = new FileOutputStream(file);
        ByteStreams.copy(in, out);
        String md5 = HashUtils.hashFile(file);
        Collection<FeedVersion> versions = feedSource.retrieveFeedVersions();
        LOG.info("Searching for md5 {} across {} versions for {} ({})", md5, versions.size(), feedSource.name, feedSource.id);
        FeedVersion matchingVersion = null;
        int count = 0;
        for (FeedVersion feedVersion : versions) {
            LOG.info("version {} md5: {}", count++, feedVersion.hash);
            if (feedVersion.hash.equals(md5)) {
                matchingVersion = feedVersion;
                LOG.info("Found local version that matches latest file on S3  (SQL namespace={})", feedVersion.namespace);
                if (!feedVersion.namespace.equals(feedSource.publishedVersionId)) {
                    LOG.info("Updating published version for feed {} to latest s3 published feed.", feedId);
                    Persistence.feedSources.updateField(feedSource.id, "publishedVersionId", feedVersion.namespace);
                } else {
                    LOG.info("No need to update published version (published s3 feed already matches feed source's published namespace).");
                }
            }
        }
        if (matchingVersion == null) {
            LOG.error("Did not find version for feed {} that matched eTag found in s3!!!", feedId);
        }
        return matchingVersion;
    }

    /**
     * Helper interface for fetching a list of feeds deemed production-complete.
     */
    public interface CompletedFeedRetriever {
        List<S3ObjectSummary> retrieveCompletedFeeds();
    }

    /**
     * Implements the default behavior for above interface.
     */
    public class DefaultCompletedFeedRetriever implements CompletedFeedRetriever {
        @Override
        public List<S3ObjectSummary> retrieveCompletedFeeds() {
            try {
                ObjectListing gtfsList = S3Utils.getDefaultS3Client().listObjects(feedBucket, bucketFolder);
                return gtfsList.getObjectSummaries();
            } catch (CheckedAWSException e) {
                LOG.error("Failed to list S3 Objects", e);
                return null;
            }
        }
    }
}
