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

import stroom.query.Payload;
import stroom.util.shared.SharedObject;

public class NodeResult implements SharedObject {
    private static final long serialVersionUID = -6092749103483061802L;

    private Map<Integer, Payload> payloadMap;
    private List<String> errors;
    private boolean complete;

    public NodeResult() {
    }

    public NodeResult(final Map<Integer, Payload> payloadMap, final List<String> errors, final boolean complete) {
        this.payloadMap = payloadMap;
        this.errors = errors;
        this.complete = complete;
    }

    public Map<Integer, Payload> getPayloadMap() {
        return payloadMap;
    }

    public List<String> getErrors() {
        return errors;
    }

    public boolean isComplete() {
        return complete;
    }

    @Override
    public String toString() {
        return "search result";
    }
}
