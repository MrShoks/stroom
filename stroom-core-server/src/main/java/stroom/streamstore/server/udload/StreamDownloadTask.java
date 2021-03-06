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

package stroom.streamstore.server.udload;

import java.io.File;

import stroom.streamstore.shared.FindStreamCriteria;
import stroom.util.task.ServerTask;

public class StreamDownloadTask extends ServerTask<StreamDownloadResult> {
    private FindStreamCriteria criteria;
    private File file;
    private StreamDownloadSettings settings;

    public StreamDownloadTask() {
    }

    public StreamDownloadTask(final String sessionId, final String userName, final FindStreamCriteria criteria,
            final File file, final StreamDownloadSettings settings) {
        super(null, sessionId, userName);
        this.criteria = criteria;
        this.file = file;
        this.settings = settings;
    }

    public FindStreamCriteria getCriteria() {
        return criteria;
    }

    public File getFile() {
        return file;
    }

    public StreamDownloadSettings getSettings() {
        return settings;
    }
}
