/*
 * Copyright 2016 Crown Copyright
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

package stroom.volume.server;

import stroom.entity.server.CriteriaLoggingUtil;
import stroom.entity.server.QueryAppender;
import stroom.entity.server.SystemEntityServiceImpl;
import stroom.entity.server.event.EntityEvent;
import stroom.entity.server.event.EntityEventHandler;
import stroom.entity.server.util.StroomEntityManager;
import stroom.entity.server.util.SQLBuilder;
import stroom.entity.server.util.SQLUtil;
import stroom.entity.shared.Clearable;
import stroom.entity.shared.EntityAction;
import stroom.jobsystem.server.JobTrackedSchedule;
import stroom.node.server.StroomPropertyService;
import stroom.node.server.NodeCache;
import stroom.node.shared.FindVolumeCriteria;
import stroom.node.shared.Node;
import stroom.node.shared.Volume;
import stroom.node.shared.Volume.VolumeType;
import stroom.node.shared.Volume.VolumeUseStatus;
import stroom.node.shared.VolumeService;
import stroom.node.shared.VolumeState;
import stroom.security.Secured;
import stroom.statistics.common.StatisticEvent;
import stroom.statistics.common.StatisticTag;
import stroom.statistics.common.Statistics;
import stroom.statistics.common.StatisticsFactory;
import stroom.streamstore.server.fs.FileSystemUtil;
import stroom.util.logging.StroomLogger;
import stroom.util.spring.StroomBeanStore;
import stroom.util.spring.StroomFrequencySchedule;
import stroom.util.spring.StroomStartup;
import event.logging.BaseAdvancedQueryItem;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.inject.Inject;
import javax.inject.Provider;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation for the volume API.
 */
@Transactional
@Component("volumeService")
@Secured(Volume.MANAGE_VOLUMES_PERMISSION)
@EntityEventHandler(type = Volume.ENTITY_TYPE, action = {EntityAction.ADD, EntityAction.DELETE})
public class VolumeServiceImpl extends SystemEntityServiceImpl<Volume, FindVolumeCriteria>
        implements VolumeService, EntityEvent.Handler, Clearable {
    /**
     * How many permanent copies should we keep?
     */
    public static final String PROP_RESILIENT_REPLICATION_COUNT = "stroom.streamstore.resilientReplicationCount";
    /**
     * Should we try to write to local volumes if possible?
     */
    public static final String PROP_PREFER_LOCAL_VOLUMES = "stroom.streamstore.preferLocalVolumes";
    /**
     * How should we select volumes to use?
     */

    public static final String PROP_VOLUME_SELECTOR = "stroom.streamstore.volumeSelector";

    private static final StroomLogger LOGGER = StroomLogger.getLogger(VolumeServiceImpl.class);

    private static final Map<String, VolumeSelector> volumeSelectorMap;
    private static final int DEFAULT_RESILIENT_REPLICATION_COUNT = 1;
    private static final boolean DEFAULT_PREFER_LOCAL_VOLUMES = false;
    private static final VolumeSelector DEFAULT_VOLUME_SELECTOR;

    static {
        volumeSelectorMap = new HashMap<>();
        registerVolumeSelector(new MostFreePercentVolumeSelector());
        registerVolumeSelector(new MostFreeVolumeSelector());
        registerVolumeSelector(new RandomVolumeSelector());
        registerVolumeSelector(new RoundRobinIgnoreLeastFreePercentVolumeSelector());
        registerVolumeSelector(new RoundRobinIgnoreLeastFreeVolumeSelector());
        registerVolumeSelector(new RoundRobinVolumeSelector());
        registerVolumeSelector(new WeightedFreePercentRandomVolumeSelector());
        registerVolumeSelector(new WeightedFreeRandomVolumeSelector());
        DEFAULT_VOLUME_SELECTOR = volumeSelectorMap.get(RoundRobinVolumeSelector.NAME);
    }

    private final StroomEntityManager stroomEntityManager;
    private final NodeCache nodeCache;
    private final StroomPropertyService stroomPropertyService;
    private final StroomBeanStore stroomBeanStore;
    private final Provider<StatisticsFactory> factoryProvider;
    private final AtomicReference<List<Volume>> currentVolumeState = new AtomicReference<>();

    private volatile Statistics statistics;

    @Inject
    public VolumeServiceImpl(final StroomEntityManager stroomEntityManager, final NodeCache nodeCache,
                             final StroomPropertyService stroomPropertyService, final StroomBeanStore stroomBeanStore,
                             final Provider<StatisticsFactory> factoryProvider) {
        super(stroomEntityManager);
        this.stroomEntityManager = stroomEntityManager;
        this.nodeCache = nodeCache;
        this.stroomPropertyService = stroomPropertyService;
        this.stroomBeanStore = stroomBeanStore;
        this.factoryProvider = factoryProvider;
    }

    private static void registerVolumeSelector(final VolumeSelector volumeSelector) {
        volumeSelectorMap.put(volumeSelector.getName(), volumeSelector);
    }

    @StroomStartup
    public void afterPropertiesSet() throws Exception {
        if (stroomBeanStore != null) {
            final StatisticsFactory factory = factoryProvider.get();
            if (factory != null) {
                statistics = factory.instance();
            }
        }
    }

    @Transactional(readOnly = true)
    @Override
    public Set<Volume> getStreamVolumeSet(final Node node) {
        LocalVolumeUse localVolumeUse = null;
        if (isPreferLocalVolumes()) {
            localVolumeUse = LocalVolumeUse.PREFERRED;
        }

		return getVolumeSet(node, VolumeType.PUBLIC, VolumeUseStatus.ACTIVE, null, localVolumeUse, null,
				getResilientReplicationCount());
	}

	@Transactional(readOnly = true)
	@Override
	public Set<Volume> getIndexVolumeSet(final Node node, final Set<Volume> allowedVolumes) {
		return getVolumeSet(node, null, null, VolumeUseStatus.ACTIVE, LocalVolumeUse.REQUIRED, allowedVolumes, 1);
	}

	private Set<Volume> getVolumeSet(final Node node, final VolumeType volumeType, final VolumeUseStatus streamStatus,
			final VolumeUseStatus indexStatus, final LocalVolumeUse localVolumeUse, final Set<Volume> allowedVolumes,
			final int requiredNumber) {
		final VolumeSelector volumeSelector = getVolumeSelector();
		final List<Volume> allVolumeList = getCurrentState();
		final List<Volume> freeVolumes = VolumeListUtil.removeFullVolumes(allVolumeList);
		Set<Volume> set = Collections.emptySet();

		final List<Volume> filteredVolumeList = getFilteredVolumeList(freeVolumes, node, volumeType, streamStatus,
				indexStatus, null, allowedVolumes);
		if (filteredVolumeList.size() > 0) {
			// Create a list of local volumes if we are set to prefer or require
			// local.
			List<Volume> localVolumeList = null;
			if (localVolumeUse != null) {
				localVolumeList = getFilteredVolumeList(freeVolumes, node, volumeType, streamStatus, indexStatus,
						Boolean.TRUE, allowedVolumes);

                // If we require a local volume and there are none available
                // then return the empty set.
                if (LocalVolumeUse.REQUIRED.equals(localVolumeUse) && localVolumeList.size() == 0) {
                    return set;
                }
            }

            if (requiredNumber <= 1) {
                // With a replication count of 1 any volume will do.
                if (localVolumeList != null && localVolumeList.size() > 0) {
                    set = Collections.singleton(volumeSelector.select(localVolumeList));
                } else if (filteredVolumeList.size() > 0) {
                    set = Collections.singleton(volumeSelector.select(filteredVolumeList));
                }
            } else {
                set = new HashSet<>();

                final List<Volume> remaining = new ArrayList<>(filteredVolumeList);
                List<Volume> remainingInOtherRacks = new ArrayList<>(filteredVolumeList);

                for (int count = 0; count < requiredNumber && remaining.size() > 0; count++) {
                    if (set.size() == 0 && localVolumeList != null && localVolumeList.size() > 0) {
                        // If we are preferring local volumes and this is the
                        // first item then add a local volume here first.
                        final Volume volume = volumeSelector.select(localVolumeList);

                        remaining.remove(volume);
                        remainingInOtherRacks = VolumeListUtil.removeMatchingRack(remainingInOtherRacks,
                                volume.getNode().getRack());

                        set.add(volume);

                    } else if (remainingInOtherRacks.size() > 0) {
                        // Next try and get volumes in other racks.
                        final Volume volume = volumeSelector.select(remainingInOtherRacks);

                        remaining.remove(volume);
                        remainingInOtherRacks = VolumeListUtil.removeMatchingRack(remainingInOtherRacks,
                                volume.getNode().getRack());

                        set.add(volume);

                    } else if (remaining.size() > 0) {
                        // Finally add any other volumes to make up the required
                        // replication count.
                        final Volume volume = volumeSelector.select(remaining);

                        remaining.remove(volume);

                        set.add(volume);
                    }
                }
            }
        }

        if (requiredNumber > set.size()) {
            LOGGER.warn("getVolumeSet - Failed to obtain " + requiredNumber + " volumes as required on node "
                    + nodeCache.getDefaultNode() + " (set=" + set + ")");
        }

        return set;
    }

	private List<Volume> getFilteredVolumeList(final List<Volume> allVolumes, final Node node,
			final VolumeType volumeType, final VolumeUseStatus streamStatus, final VolumeUseStatus indexStatus,
			final Boolean local, final Set<Volume> allowedVolumes) {
		final List<Volume> list = new ArrayList<>();
		for (final Volume volume : allVolumes) {
            if (allowedVolumes == null || allowedVolumes.contains(volume)) {
                final Node nd = volume.getNode();

                // Check the volume type matches.
                boolean ok = true;
                if (volumeType != null) {
                    ok = volumeType.equals(volume.getVolumeType());
                }

                // Check the stream volume use status matches.
                if (ok) {
                    if (streamStatus != null) {
                        ok = streamStatus.equals(volume.getStreamStatus());
                    }
                }

                // Check the index volume use status matches.
                if (ok) {
                    if (indexStatus != null) {
                        ok = indexStatus.equals(volume.getIndexStatus());
                    }
                }

                // Check the node matches.
                if (ok) {
                    ok = false;
                    if (local == null) {
                        ok = true;
                    } else {
                        if ((Boolean.TRUE.equals(local) && node.equals(nd))
                                || (Boolean.FALSE.equals(local) && !node.equals(nd))) {
                            ok = true;
                        }
                    }
                }

                if (ok) {
                    list.add(volume);
                }
            }
        }
        return list;
    }

	@Override
	public void onChange(final EntityEvent event) {
		currentVolumeState.set(null);
	}

    @Override
    public void clear() {
        currentVolumeState.set(null);
    }

    private List<Volume> getCurrentState() {
        List<Volume> state = currentVolumeState.get();
        if (state == null) {
            synchronized (this) {
                state = currentVolumeState.get();
                if (state == null) {
                    state = refresh();
                    currentVolumeState.set(state);
                }
            }
        }
        return state;
    }

    @StroomFrequencySchedule("5m")
    @JobTrackedSchedule(jobName = "Volume Status", advanced = false, description = "Update the usage status of volumes owned by the node")
    @Override
    public void flush() {
        refresh();
    }

    public List<Volume> refresh() {
        final Node node = nodeCache.getDefaultNode();
        final List<Volume> newState = new ArrayList<>();

        final FindVolumeCriteria findVolumeCriteria = new FindVolumeCriteria();
        findVolumeCriteria.addOrderBy(FindVolumeCriteria.ORDER_BY_ID);
        final List<Volume> volumeList = find(findVolumeCriteria);
        for (final Volume volume : volumeList) {
            if (volume.getNode().equals(node)) {
                VolumeState volumeState = updateVolumeState(volume);
                volumeState = saveVolumeState(volumeState);
                volume.setVolumeState(volumeState);

                // Record some statistics for the use of this volume.
                recordStats(volume);
            }
            newState.add(volume);
        }

        return newState;
    }

    private void recordStats(final Volume volume) {
        try {
            final VolumeState volumeState = volume.getVolumeState();

            if (statistics != null) {
                final List<StatisticTag> tags = new ArrayList<>();
                tags.add(new StatisticTag("Id", String.valueOf(volume.getId())));
                tags.add(new StatisticTag("Node", volume.getNode().getName()));
                tags.add(new StatisticTag("Path", volume.getPath()));

                if (volume.getBytesLimit() != null) {
                    statistics.putEvent(new StatisticEvent(System.currentTimeMillis(), "Volume Limit", tags,
                            (double) volume.getBytesLimit()));
                }
                statistics.putEvent(new StatisticEvent(System.currentTimeMillis(), "Volume Used", tags,
                        (double) volumeState.getBytesUsed()));
                statistics.putEvent(new StatisticEvent(System.currentTimeMillis(), "Volume Free", tags,
                        (double) volumeState.getBytesFree()));
                statistics.putEvent(new StatisticEvent(System.currentTimeMillis(), "Volume Total", tags,
                        (double) volumeState.getBytesTotal()));
                statistics.putEvent(new StatisticEvent(System.currentTimeMillis(), "Volume Use%", tags,
                        (double) volumeState.getPercentUsed()));
            }
        } catch (final Throwable t) {
            LOGGER.warn(t.getMessage());
            LOGGER.debug(t.getMessage(), t);
        }
    }

    private VolumeState updateVolumeState(final Volume volume) {
        final VolumeState volumeState = volume.getVolumeState();
        volumeState.setStatusMs(System.currentTimeMillis());
        final File path = new File(volume.getPath());
        // Ensure the path exists
        if (path.mkdirs()) {
            LOGGER.debug("updateVolumeState() path created: " + path);
        } else {
            LOGGER.debug("updateVolumeState() path exists: " + path);
        }

        final long usableSpace = path.getUsableSpace();
        final long freeSpace = path.getFreeSpace();
        final long totalSpace = path.getTotalSpace();

        volumeState.setBytesTotal(totalSpace);
        volumeState.setBytesFree(usableSpace);
        volumeState.setBytesUsed(totalSpace - freeSpace);

        LOGGER.debug("updateVolumeState() exit" + volume);
        return volumeState;
    }

    /**
     * On creating a new volume create the directory Never create afterwards
     */
    @Override
    public Volume save(final Volume entity) throws RuntimeException {
        if (!entity.isPersistent()) {
            FileSystemUtil.mkdirs(null, new File(entity.getPath()));

            VolumeState volumeState = entity.getVolumeState();
            if (volumeState == null) {
                volumeState = new VolumeState();
            }
            // Save initial state
            volumeState = stroomEntityManager.saveEntity(volumeState);
            stroomEntityManager.flush();

            entity.setVolumeState(volumeState);
        }
        return super.save(entity);
    }

    @Override
    public Boolean delete(final Volume entity) throws RuntimeException {
        if (Boolean.TRUE.equals(super.delete(entity))) {
            return stroomEntityManager.deleteEntity(entity.getVolumeState());
        }
        return Boolean.FALSE;
    }

    VolumeState saveVolumeState(final VolumeState volumeState) {
        return stroomEntityManager.saveEntity(volumeState);
    }

    @Override
    public Class<Volume> getEntityClass() {
        return Volume.class;
    }

    @Override
    public FindVolumeCriteria createCriteria() {
        return new FindVolumeCriteria();
    }

    private int getResilientReplicationCount() {
        int resilientReplicationCount = stroomPropertyService.getIntProperty(PROP_RESILIENT_REPLICATION_COUNT,
                DEFAULT_RESILIENT_REPLICATION_COUNT);
        if (resilientReplicationCount < 1) {
            resilientReplicationCount = 1;
        }
        return resilientReplicationCount;
    }

    private boolean isPreferLocalVolumes() {
        return stroomPropertyService.getBooleanProperty(PROP_PREFER_LOCAL_VOLUMES, DEFAULT_PREFER_LOCAL_VOLUMES);
    }

    @Override
    public void appendCriteria(final List<BaseAdvancedQueryItem> items, final FindVolumeCriteria criteria) {
        CriteriaLoggingUtil.appendEntityIdSet(items, "nodeIdSet", criteria.getNodeIdSet());
        CriteriaLoggingUtil.appendCriteriaSet(items, "volumeTypeSet", criteria.getVolumeTypeSet());
        CriteriaLoggingUtil.appendCriteriaSet(items, "streamStatusSet", criteria.getStreamStatusSet());
        CriteriaLoggingUtil.appendCriteriaSet(items, "indexStatusSet", criteria.getIndexStatusSet());
    }

    private VolumeSelector getVolumeSelector() {
        VolumeSelector volumeSelector = null;

        try {
            final String value = stroomPropertyService.getProperty(PROP_VOLUME_SELECTOR);
            if (value != null) {
                volumeSelector = volumeSelectorMap.get(value);
            }
        } catch (final Exception e) {
            LOGGER.debug(e.getMessage());
        }

        if (volumeSelector == null) {
            volumeSelector = DEFAULT_VOLUME_SELECTOR;
        }

        return volumeSelector;
    }

    @Override
    protected QueryAppender<Volume, FindVolumeCriteria> createQueryAppender(StroomEntityManager entityManager) {
        return new VolumeQueryAppender(entityManager);
    }

    private enum LocalVolumeUse {
        REQUIRED, PREFERRED
    }

    private static class VolumeQueryAppender extends QueryAppender<Volume, FindVolumeCriteria> {
        public VolumeQueryAppender(final StroomEntityManager entityManager) {
            super(entityManager);
        }

        @Override
        public void appendBasicCriteria(SQLBuilder sql, String alias, FindVolumeCriteria criteria) {
            super.appendBasicCriteria(sql, alias, criteria);
            SQLUtil.appendSetQuery(sql, true, alias + ".node", criteria.getNodeIdSet());
            SQLUtil.appendSetQuery(sql, true, alias + ".pindexStatus", criteria.getIndexStatusSet(), false);
            SQLUtil.appendSetQuery(sql, true, alias + ".pstreamStatus", criteria.getStreamStatusSet(), false);
            SQLUtil.appendSetQuery(sql, true, alias + ".pvolumeType", criteria.getVolumeTypeSet(), false);
        }
    }
}
