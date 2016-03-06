/**
 *  Copyright 2014-2016 CyberVision, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.kaaproject.kaa.server.appenders.mongo.appender;

import java.io.Serializable;

import org.kaaproject.kaa.common.dto.logs.LogEventDto;
import org.kaaproject.kaa.server.common.log.shared.appender.data.ProfileInfo;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.mongodb.DBObject;
import com.mongodb.util.JSON;

@Document
public final class LogEvent implements Serializable {

    private static final long serialVersionUID = -2738374699172219071L;

    @Id
    private String id;
    private DBObject header;
    private DBObject event;
    private DBObject clientProfile;
    private DBObject serverProfile;

    public LogEvent() {

    }

    public LogEvent(LogEventDto dto, ProfileInfo clientProfile, ProfileInfo serverProfile) {
        this.id = dto.getId();
        this.header = (DBObject) JSON.parse(dto.getHeader());
        this.event = (DBObject) JSON.parse(dto.getEvent());
        this.clientProfile = (clientProfile != null) ? (DBObject) JSON.parse(clientProfile.getBody()) : null;
        this.serverProfile = (serverProfile != null) ? (DBObject) JSON.parse(serverProfile.getBody()) : null;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public DBObject getEvent() {
        return event;
    }

    public void setEvent(DBObject event) {
        this.event = event;
    }

    public DBObject getHeader() {
        return header;
    }

    public void setHeader(DBObject header) {
        this.header = header;
    }

    public DBObject getClientProfile() {
        return clientProfile;
    }

    public void setClientProfile(DBObject clientProfile) {
        this.clientProfile = clientProfile;
    }

    public DBObject getServerProfile() {
        return serverProfile;
    }

    public void setServerProfile(DBObject serverProfile) {
        this.serverProfile = serverProfile;
    }

    @Override
    public String toString() {
        return "LogEvent [id=" + id + ", header=" + header + ", event=" + event + ", clientProfile=" + clientProfile + ", serverProfile=" + serverProfile + "]";
    }

}
