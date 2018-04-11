/* 
 * Copyright 2014 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
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
package org.matrix.androidsdk.util;

import com.google.gson.FieldNamingStrategy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.rest.json.ConditionDeserializer;
import org.matrix.androidsdk.rest.json.MessageSerializer;
import org.matrix.androidsdk.rest.model.ContentResponse;
import org.matrix.androidsdk.rest.model.crypto.EncryptedEventContent;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.EventContent;
import org.matrix.androidsdk.rest.model.message.AudioMessage;
import org.matrix.androidsdk.rest.model.message.FileMessage;
import org.matrix.androidsdk.rest.model.crypto.ForwardedRoomKeyContent;
import org.matrix.androidsdk.rest.model.message.ImageMessage;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.message.LocationMessage;
import org.matrix.androidsdk.rest.model.message.Message;
import org.matrix.androidsdk.rest.model.crypto.NewDeviceContent;
import org.matrix.androidsdk.rest.model.crypto.OlmEventContent;
import org.matrix.androidsdk.rest.model.crypto.OlmPayloadContent;
import org.matrix.androidsdk.rest.model.PowerLevels;
import org.matrix.androidsdk.rest.model.crypto.RoomKeyContent;
import org.matrix.androidsdk.rest.model.crypto.RoomKeyRequest;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.RoomTags;
import org.matrix.androidsdk.rest.model.pid.RoomThirdPartyInvite;
import org.matrix.androidsdk.rest.model.User;
import org.matrix.androidsdk.rest.model.message.VideoMessage;
import org.matrix.androidsdk.rest.model.bingrules.Condition;
import org.matrix.androidsdk.rest.model.login.RegistrationFlowResponse;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Static methods for converting json into objects.
 */
public class JsonUtils {
    private static final String LOG_TAG = JsonUtils.class.getSimpleName();

    /**
     * Based on FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES.
     * toLowerCase() is replaced by toLowerCase(Locale.ENGLISH).
     * In some languages like turkish, toLowerCase does not provide the expected string.
     * e.g _I is not converted to _i.
     */
    public static class MatrixFieldNamingStrategy implements FieldNamingStrategy {

        /**
         * Converts the field name that uses camel-case define word separation into
         * separate words that are separated by the provided {@code separatorString}.
         */
        private static String separateCamelCase(String name, String separator) {
            StringBuilder translation = new StringBuilder();
            for (int i = 0; i < name.length(); i++) {
                char character = name.charAt(i);
                if (Character.isUpperCase(character) && translation.length() != 0) {
                    translation.append(separator);
                }
                translation.append(character);
            }
            return translation.toString();
        }

        /**
         * Translates the field name into its JSON field name representation.
         *
         * @param f the field object that we are translating
         * @return the translated field name.
         * @since 1.3
         */
        public String translateName(Field f) {
            return separateCamelCase(f.getName(), "_").toLowerCase(Locale.ENGLISH);
        }
    }

    private static final Gson gson = new GsonBuilder()
            .setFieldNamingStrategy(new MatrixFieldNamingStrategy())
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .registerTypeAdapter(Condition.class, new ConditionDeserializer())
            .registerTypeAdapter(Message.class, new MessageSerializer())
            .registerTypeAdapter(ImageMessage.class, new MessageSerializer())
            .registerTypeAdapter(VideoMessage.class, new MessageSerializer())
            .registerTypeAdapter(FileMessage.class, new MessageSerializer())
            .create();

    // add a call to serializeNulls().
    // by default the null parameters are not sent in the requests.
    // serializeNulls forces to add them.
    private static final Gson gsonWithNullSerialization = new GsonBuilder()
            .setFieldNamingStrategy(new MatrixFieldNamingStrategy())
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .serializeNulls()
            .registerTypeAdapter(Condition.class, new ConditionDeserializer())
            .registerTypeAdapter(Message.class, new MessageSerializer())
            .registerTypeAdapter(ImageMessage.class, new MessageSerializer())
            .registerTypeAdapter(VideoMessage.class, new MessageSerializer())
            .registerTypeAdapter(FileMessage.class, new MessageSerializer())
            .create();

    // for crypto (canonicalize)
    // avoid converting "=" to \u003d
    private static final Gson gsonWithoutHtmlEscaping = new GsonBuilder()
            .setFieldNamingStrategy(new MatrixFieldNamingStrategy())
            .disableHtmlEscaping()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .registerTypeAdapter(Condition.class, new ConditionDeserializer())
            .registerTypeAdapter(Message.class, new MessageSerializer())
            .registerTypeAdapter(ImageMessage.class, new MessageSerializer())
            .registerTypeAdapter(VideoMessage.class, new MessageSerializer())
            .registerTypeAdapter(FileMessage.class, new MessageSerializer())
            .create();

    /**
     * Provides the JSON parser.
     *
     * @param withNullSerialization true to serialise the null parameters
     * @return the JSON parser
     */
    public static Gson getGson(boolean withNullSerialization) {
        return withNullSerialization ? gsonWithNullSerialization : gson;
    }

    /**
     * Convert a JSON object to a room state.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return a room state
     */
    public static RoomState toRoomState(JsonElement jsonObject) {
        return (RoomState) toClass(jsonObject, RoomState.class);
    }

    /**
     * Convert a JSON object to an User.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an user
     */
    public static User toUser(JsonElement jsonObject) {
        return (User) toClass(jsonObject, User.class);
    }

    /**
     * Convert a JSON object to a RoomMember.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return a RoomMember
     */
    public static RoomMember toRoomMember(JsonElement jsonObject) {
        return (RoomMember) toClass(jsonObject, RoomMember.class);
    }

    /**
     * Convert a JSON object to a RoomTags.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return a RoomTags
     */
    public static RoomTags toRoomTags(JsonElement jsonObject) {
        return (RoomTags) toClass(jsonObject, RoomTags.class);
    }

    /**
     * Convert a JSON object to a MatrixError.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return a MatrixError
     */
    public static MatrixError toMatrixError(JsonElement jsonObject) {
        return (MatrixError) toClass(jsonObject, MatrixError.class);
    }

    /**
     * Retrieves the message type from a Json object.
     *
     * @param jsonObject the json object
     * @return the message type
     */
    public static String getMessageMsgType(JsonElement jsonObject) {
        try {
            Message message = gson.fromJson(jsonObject, Message.class);
            return message.msgtype;
        } catch (Exception e) {
            Log.e(LOG_TAG, "## getMessageMsgType failed " + e.getMessage());
        }

        return null;
    }

    /**
     * Convert a JSON object to a Message.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return a Message
     */
    public static Message toMessage(JsonElement jsonObject) {
        try {
            Message message = gson.fromJson(jsonObject, Message.class);

            // Try to return the right subclass
            if (Message.MSGTYPE_IMAGE.equals(message.msgtype)) {
                message = toImageMessage(jsonObject);
            }

            else if (Message.MSGTYPE_VIDEO.equals(message.msgtype)) {
                message = toVideoMessage(jsonObject);
            }

            else if (Message.MSGTYPE_LOCATION.equals(message.msgtype)) {
                message = toLocationMessage(jsonObject);
            }

            // Try to return the right subclass
            else if (Message.MSGTYPE_FILE.equals(message.msgtype)) {
                message = toFileMessage(jsonObject);
            }

            else if (Message.MSGTYPE_AUDIO.equals(message.msgtype)) {
                message = toAudioMessage(jsonObject);
            }

            if (jsonObject instanceof JsonObject) {
                for (Map.Entry<String, JsonElement> pair: ((JsonObject) jsonObject).entrySet()) {
                    String key = pair.getKey();
                    if (key.startsWith("m.")) {
                        message.add(key,pair.getValue());
                    }
                }
            }

            return message;
        } catch (Exception e) {
            Log.e(LOG_TAG, "## toMessage failed " + e.getMessage());
        }

        return new Message();
    }

    /**
     * Convert a JSON object to an Event.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an Event
     */
    public static Event toEvent(JsonElement jsonObject) {
        return (Event) toClass(jsonObject, Event.class);
    }

    /**
     * Convert a JSON object to an EncryptedEventContent.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an EncryptedEventContent
     */
    public static EncryptedEventContent toEncryptedEventContent(JsonElement jsonObject) {
        return (EncryptedEventContent) toClass(jsonObject, EncryptedEventContent.class);
    }

    /**
     * Convert a JSON object to an OlmEventContent.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an OlmEventContent
     */
    public static OlmEventContent toOlmEventContent(JsonElement jsonObject) {
        return (OlmEventContent) toClass(jsonObject, OlmEventContent.class);
    }

    /**
     * Convert a JSON object to an OlmPayloadContent.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an OlmPayloadContent
     */
    public static OlmPayloadContent toOlmPayloadContent(JsonElement jsonObject) {
        return (OlmPayloadContent) toClass(jsonObject, OlmPayloadContent.class);
    }

    /**
     * Convert a JSON object to an EventContent.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an EventContent
     */
    public static EventContent toEventContent(JsonElement jsonObject) {
        return (EventContent) toClass(jsonObject, EventContent.class);
    }

    /**
     * Convert a JSON object to an RoomKeyContent.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an RoomKeyContent
     */
    public static RoomKeyContent toRoomKeyContent(JsonElement jsonObject) {
        return (RoomKeyContent) toClass(jsonObject, RoomKeyContent.class);
    }

    /**
     * Convert a JSON object to an RoomKeyRequest.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an RoomKeyRequest
     */
    public static RoomKeyRequest toRoomKeyRequest(JsonElement jsonObject) {
        return (RoomKeyRequest) toClass(jsonObject, RoomKeyRequest.class);
    }

    /**
     * Convert a JSON object to an ForwardedRoomKeyContent.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an ForwardedRoomKeyContent
     */
    public static ForwardedRoomKeyContent toForwardedRoomKeyContent(JsonElement jsonObject) {
        return (ForwardedRoomKeyContent) toClass(jsonObject, ForwardedRoomKeyContent.class);
    }

    /**
     * Convert a JSON object to an ImageMessage.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an ImageMessage
     */
    public static ImageMessage toImageMessage(JsonElement jsonObject) {
        return (ImageMessage) toClass(jsonObject, ImageMessage.class);
    }

    /**
     * Convert a JSON object to an FileMessage.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an FileMessage
     */
    public static FileMessage toFileMessage(JsonElement jsonObject) {
        return (FileMessage) toClass(jsonObject, FileMessage.class);
    }

    /**
     * Convert a JSON object to an AudioMessage.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return an AudioMessage
     */
    public static AudioMessage toAudioMessage(JsonElement jsonObject) {
        return (AudioMessage) toClass(jsonObject, AudioMessage.class);
    }

    /**
     * Convert a JSON object to a VideoMessage.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return a VideoMessage
     */
    public static VideoMessage toVideoMessage(JsonElement jsonObject) {
        return (VideoMessage) toClass(jsonObject, VideoMessage.class);
    }

    /**
     * Convert a JSON object to a LocationMessage.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return a LocationMessage
     */
    public static LocationMessage toLocationMessage(JsonElement jsonObject) {
        return (LocationMessage) toClass(jsonObject, LocationMessage.class);
    }

    /**
     * Convert a JSON object to a ContentResponse.
     * The result is never null.
     *
     * @param jsonString the json as string to convert
     * @return a ContentResponse
     */
    public static ContentResponse toContentResponse(String jsonString) {
        return (ContentResponse) toClass(jsonString, ContentResponse.class);
    }

    /**
     * Convert a JSON object to a PowerLevels.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return a PowerLevels
     */
    public static PowerLevels toPowerLevels(JsonElement jsonObject) {
        return (PowerLevels) toClass(jsonObject, PowerLevels.class);
    }

    /**
     * Convert a JSON object to a RoomThirdPartyInvite.
     * The result is never null.
     *
     * @param jsonObject the json to convert
     * @return a RoomThirdPartyInvite
     */
    public static RoomThirdPartyInvite toRoomThirdPartyInvite(JsonElement jsonObject) {
        return (RoomThirdPartyInvite) toClass(jsonObject, RoomThirdPartyInvite.class);
    }

    /**
     * Convert a stringified JSON object to a RegistrationFlowResponse.
     * The result is never null.
     *
     * @param jsonString the json as string to convert
     * @return a RegistrationFlowResponse
     */
    public static RegistrationFlowResponse toRegistrationFlowResponse(String jsonString) {
        return (RegistrationFlowResponse) toClass(jsonString, RegistrationFlowResponse.class);
    }

    /**
     * Convert a JSON object into a class instance.
     * The returned value cannot be null.
     *
     * @param jsonObject the json object to convert
     * @param aClass     the class
     * @return the converted object
     */
    public static Object toClass(JsonElement jsonObject, Class aClass) {
        Object object = null;

        try {
            object = gson.fromJson(jsonObject, aClass);
        } catch (Exception e) {
            Log.e(LOG_TAG, "## toClass failed " + e.getMessage());
        }

        if (null == object) {
            Constructor<?>[] constructors = aClass.getConstructors();

            try {
                object = constructors[0].newInstance();
            } catch (Throwable t) {
                Log.e(LOG_TAG, "## toClass failed " + t.getMessage());
            }
        }

        return object;
    }

    /**
     * Convert a stringified JSON into a class instance.
     * The returned value cannot be null.
     *
     * @param jsonObjectAsString the json object as string to convert
     * @param aClass             the class
     * @return the converted object
     */
    public static Object toClass(String jsonObjectAsString, Class aClass) {
        Object object = null;

        try {
            object = gson.fromJson(jsonObjectAsString, aClass);
        } catch (Exception e) {
            Log.e(LOG_TAG, "## toClass failed " + e.getMessage());
        }

        if (null == object) {
            Constructor<?>[] constructors = aClass.getConstructors();

            try {
                object = constructors[0].newInstance();
            } catch (Throwable t) {
                Log.e(LOG_TAG, "## toClass failed " + t.getMessage());
            }
        }

        return object;
    }

    /**
     * Convert an Event instance to a Json object.
     *
     * @param event the event instance.
     * @return the json object
     */
    public static JsonObject toJson(Event event) {
        try {
            return (JsonObject) gson.toJsonTree(event);
        } catch (Exception e) {
            Log.e(LOG_TAG, "## toJson failed " + e.getMessage());
        }

        return new JsonObject();
    }

    /**
     * Convert an Message instance into a Json object.
     *
     * @param message the Message instance.
     * @return the json object
     */
    public static JsonObject toJson(Message message) {
        try {
            return (JsonObject) gson.toJsonTree(message);
        } catch (Exception e) {
            Log.e(LOG_TAG, "## toJson failed " + e.getMessage());
        }

        return null;
    }

    /**
     * Create a canonicalized json string for an object
     *
     * @param object the object to convert
     * @return the canonicalized string
     */
    public static String getCanonicalizedJsonString(Object object) {
        String canonicalizedJsonString = null;

        if (null != object) {
            if (object instanceof JsonElement) {
                canonicalizedJsonString = gsonWithoutHtmlEscaping.toJson(canonicalize((JsonElement) object));
            } else {
                canonicalizedJsonString = gsonWithoutHtmlEscaping.toJson(canonicalize(gsonWithoutHtmlEscaping.toJsonTree(object)));
            }

            if (null != canonicalizedJsonString) {
                canonicalizedJsonString = canonicalizedJsonString.replace("\\/", "/");
            }
        }

        return canonicalizedJsonString;
    }

    /**
     * Canonicalize a JsonElement element
     *
     * @param src the src
     * @return the canonicalize element
     */
    public static JsonElement canonicalize(JsonElement src) {
        // sanity check
        if (null == src) {
            return null;
        }

        if (src instanceof JsonArray) {
            // Canonicalize each element of the array
            JsonArray srcArray = (JsonArray) src;
            JsonArray result = new JsonArray();
            for (int i = 0; i < srcArray.size(); i++) {
                result.add(canonicalize(srcArray.get(i)));
            }
            return result;
        } else if (src instanceof JsonObject) {
            // Sort the attributes by name, and the canonicalize each element of the object
            JsonObject srcObject = (JsonObject) src;
            JsonObject result = new JsonObject();
            TreeSet<String> attributes = new TreeSet<>();

            for (Map.Entry<String, JsonElement> entry : srcObject.entrySet()) {
                attributes.add(entry.getKey());
            }
            for (String attribute : attributes) {
                result.add(attribute, canonicalize(srcObject.get(attribute)));
            }
            return result;
        } else {
            return src;
        }
    }

    /**
     * Convert a string from an UTF8 String
     *
     * @param s the string to convert
     * @return the utf-16 string
     */
    public static String convertFromUTF8(String s) {
        String out = s;

        if (null != out) {
            try {
                byte[] bytes = out.getBytes();
                out = new String(bytes, "UTF-8");
            } catch (Exception e) {
                Log.e(LOG_TAG, "## convertFromUTF8()  failed " + e.getMessage());
            }
        }

        return out;
    }

    /**
     * Convert a string to an UTF8 String
     *
     * @param s the string to convert
     * @return the utf-8 string
     */
    public static String convertToUTF8(String s) {
        String out = s;

        if (null != out) {
            try {
                byte[] bytes = out.getBytes("UTF-8");
                out = new String(bytes);
            } catch (Exception e) {
                Log.e(LOG_TAG, "## convertToUTF8()  failed " + e.getMessage());
            }
        }

        return out;
    }
}
