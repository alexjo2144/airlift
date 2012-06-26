/*
 * Copyright 2010 Proofpoint, Inc.
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
package io.airlift.event.client;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import org.codehaus.jackson.JsonEncoding;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.JsonSerializer;

import javax.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;

import static io.airlift.event.client.EventJsonSerializer.createEventJsonSerializer;

public class JsonEventWriter
{
    private final JsonFactory jsonFactory;
    private final Map<Class<?>, JsonSerializer<?>> serializers;

    @Inject
    public JsonEventWriter(Set<EventTypeMetadata<?>> eventTypes, HttpEventClientConfig config)
    {
        Preconditions.checkNotNull(eventTypes, "eventTypes is null");
        Preconditions.checkNotNull(config, "config is null");

        this.jsonFactory = new JsonFactory();

        ImmutableMap.Builder<Class<?>, JsonSerializer<?>> serializerBuilder = ImmutableMap.builder();

        for (EventTypeMetadata<?> eventType : eventTypes) {
            serializerBuilder.put(eventType.getEventClass(), createEventJsonSerializer(eventType, config.getJsonVersion()));
        }
        this.serializers = serializerBuilder.build();
    }

    public <T> void writeEvents(EventClient.EventGenerator<T> events, OutputStream out)
            throws IOException
    {
        Preconditions.checkNotNull(events, "events is null");
        Preconditions.checkNotNull(out, "out is null");

        final JsonGenerator jsonGenerator = jsonFactory.createJsonGenerator(out, JsonEncoding.UTF8);

        jsonGenerator.writeStartArray();

        events.generate(new EventClient.EventPoster<T>()
        {
            @Override
            public void post(T event)
                    throws IOException
            {
                JsonSerializer<T> serializer = getSerializer(event);
                if (serializer == null) {
                    throw new InvalidEventException("Event class [%s] has not been registered as an event", event.getClass().getName());
                }

                serializer.serialize(event, jsonGenerator, null);
            }
        });

        jsonGenerator.writeEndArray();
        jsonGenerator.flush();
    }

    @SuppressWarnings("unchecked")
    private <T> JsonSerializer<T> getSerializer(T event)
    {
        return (JsonSerializer<T>) serializers.get(event.getClass());
    }
}
