/*
 * Copyright 2014 OpenMarket Ltd
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
package org.matrix.androidsdk.rest.model.group;

import java.io.Serializable;

/**
 * Group sync profile
 */
public class GroupSyncProfile implements Serializable {
    /**
     * The name of the group, if any. May be nil.
     */
    public String name;

    /**
     * The URL for the group's avatar. May be nil.
     */
    public String avatarUrl;
}
