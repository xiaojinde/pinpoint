/*
 * Copyright 2019 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.grpc.server.lifecycle;

import com.navercorp.pinpoint.common.util.Assert;
import com.navercorp.pinpoint.grpc.Header;

/**
 * @author Woonduk Kang(emeroad)
 */
public class PingSession {
    private final Long id;
    private final Header header;

    public PingSession(Long id, Header header) {
        this.id = Assert.requireNonNull(id, "transportMetadata must not be null");
        this.header = Assert.requireNonNull(header, "header must not be null");

    }

    public Header getHeader() {
        return header;
    }

    public Long getId() {
        return id;
    }

    @Override
    public String toString() {
        return "PingSession{" +
                "id=" + id +
                ", header=" + header +
                '}';
    }
}
