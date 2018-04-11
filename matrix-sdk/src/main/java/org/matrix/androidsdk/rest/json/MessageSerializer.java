/*
 * Copyright 2018 EITA Cooperative (eita.org.br)
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

package org.matrix.androidsdk.rest.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import org.matrix.androidsdk.rest.model.message.Message;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;


public class MessageSerializer implements JsonSerializer<Message> {

    @Override
    public JsonElement serialize(Message message, Type type, JsonSerializationContext jsonSerializationContext) {
        Gson gson = new GsonBuilder().
                        excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC).
                        create();

        JsonElement serializedMessage = gson.toJsonTree(message);
        JsonObject contentExtras = message.getContentExtras();

        if (serializedMessage instanceof JsonObject) {

            Set<Map.Entry<String, JsonElement>> entrySet = contentExtras.entrySet();
            for(Map.Entry<String,JsonElement> entry : entrySet){
                ((JsonObject) serializedMessage).add(entry.getKey(),entry.getValue());
            }
        }
        return serializedMessage;
    }
}
