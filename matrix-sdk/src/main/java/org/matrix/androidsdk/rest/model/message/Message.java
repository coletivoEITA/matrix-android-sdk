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
package org.matrix.androidsdk.rest.model.message;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.lang.reflect.Modifier;
import java.util.HashMap;

public class Message {
    public static final String MSGTYPE_TEXT = "m.text";
    public static final String MSGTYPE_EMOTE = "m.emote";
    public static final String MSGTYPE_NOTICE = "m.notice";
    public static final String MSGTYPE_IMAGE = "m.image";
    public static final String MSGTYPE_AUDIO = "m.audio";
    public static final String MSGTYPE_VIDEO = "m.video";
    public static final String MSGTYPE_LOCATION = "m.location";
    public static final String MSGTYPE_FILE = "m.file";
    public static final String FORMAT_MATRIX_HTML = "org.matrix.custom.html";

    public String msgtype;
    public String body;

    public String format;
    public String formatted_body;


    /*************************************************************
     * EXTRA CONTENTS                                            *
     *************************************************************/

    private JsonObject contentExtras = new JsonObject();

    public void add(String property, JsonElement value) {
        contentExtras.add(property,value);
    }

    public void add(String property, Boolean value) {
        contentExtras.addProperty(property,value);
    }

    public void add(String property, Character value) {
        contentExtras.addProperty(property,value);
    }

    public void add(String property, Number value) {
        contentExtras.addProperty(property,value);
    }

    public void add(String property, String value) {
        contentExtras.addProperty(property,value);
    }

    public JsonElement get(String property) {
        return contentExtras.get(property);
    }

    public JsonElement remove(String property) {
        return contentExtras.remove(property);
    }

    public JsonObject getContentExtras() {
        return contentExtras;
    }

}