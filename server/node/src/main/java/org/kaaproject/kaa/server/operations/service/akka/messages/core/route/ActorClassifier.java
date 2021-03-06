/*
 * Copyright 2014-2016 CyberVision, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kaaproject.kaa.server.operations.service.akka.messages.core.route;

public class ActorClassifier {
    private final boolean globalActor;

    public ActorClassifier(boolean globalActor) {
        super();
        this.globalActor = globalActor;
    }

    public boolean isGlobalActor() {
        return globalActor;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (globalActor ? 1231 : 1237);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ActorClassifier other = (ActorClassifier) obj;
        if (globalActor != other.globalActor)
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "ActorClassifier [globalActor=" + globalActor + "]";
    }

}
