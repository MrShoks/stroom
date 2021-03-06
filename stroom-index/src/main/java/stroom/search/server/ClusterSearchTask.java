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

package stroom.search.server;

import java.util.List;
import java.util.Map;

import stroom.query.shared.CoprocessorSettings;
import stroom.query.shared.IndexField;
import stroom.query.shared.Search;
import stroom.node.shared.Node;
import stroom.task.cluster.ClusterTask;
import stroom.util.shared.Task;

public class ClusterSearchTask extends ClusterTask<NodeResult> {
    private static final long serialVersionUID = -1305243739417365803L;

    private final Search search;
    private final List<Long> shards;
    private final Node targetNode;
    private final IndexField[] storedFields;
    private final int resultSendFrequency;
    private final Map<Integer, CoprocessorSettings> coprocessorMap;

    public ClusterSearchTask(final String sessionId, final String userName, final String taskName, final Search search,
                             final List<Long> shards, final Node targetNode, final IndexField[] storedFields,
                             final int resultSendFrequency, final Map<Integer, CoprocessorSettings> coprocessorMap) {
        super(sessionId, userName, taskName);
        this.search = search;
        this.shards = shards;
        this.targetNode = targetNode;
        this.storedFields = storedFields;
        this.resultSendFrequency = resultSendFrequency;
        this.coprocessorMap = coprocessorMap;
    }

    public Node getTargetNode() {
        return targetNode;
    }

    public Search getSearch() {
        return search;
    }

    public List<Long> getShards() {
        return shards;
    }

    public IndexField[] getStoredFields() {
        return storedFields;
    }

    public int getResultSendFrequency() {
        return resultSendFrequency;
    }

    public Map<Integer, CoprocessorSettings> getCoprocessorMap() {
        return coprocessorMap;
    }
}
