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
package org.matrix.androidsdk;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import org.matrix.androidsdk.call.MXCallsManager;
import org.matrix.androidsdk.crypto.MXCrypto;
import org.matrix.androidsdk.crypto.MXCryptoError;
import org.matrix.androidsdk.crypto.MXDecryptionException;
import org.matrix.androidsdk.crypto.MXEventDecryptionResult;
import org.matrix.androidsdk.data.DataRetriever;
import org.matrix.androidsdk.data.MyUser;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.data.RoomSummary;
import org.matrix.androidsdk.data.store.IMXStore;
import org.matrix.androidsdk.data.store.MXMemoryStore;
import org.matrix.androidsdk.db.MXMediasCache;
import org.matrix.androidsdk.groups.GroupsManager;
import org.matrix.androidsdk.listeners.IMXEventListener;
import org.matrix.androidsdk.network.NetworkConnectivityReceiver;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.client.AccountDataRestClient;
import org.matrix.androidsdk.rest.client.EventsRestClient;
import org.matrix.androidsdk.rest.client.PresenceRestClient;
import org.matrix.androidsdk.rest.client.ProfileRestClient;
import org.matrix.androidsdk.rest.client.RoomsRestClient;
import org.matrix.androidsdk.rest.client.ThirdPidRestClient;
import org.matrix.androidsdk.rest.json.ConditionDeserializer;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.ReceiptData;
import org.matrix.androidsdk.rest.model.RoomAliasDescription;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.group.InvitedGroupSync;
import org.matrix.androidsdk.rest.model.sync.InvitedRoomSync;
import org.matrix.androidsdk.rest.model.sync.SyncResponse;
import org.matrix.androidsdk.rest.model.User;
import org.matrix.androidsdk.rest.model.bingrules.BingRule;
import org.matrix.androidsdk.rest.model.bingrules.BingRuleSet;
import org.matrix.androidsdk.rest.model.bingrules.BingRulesResponse;
import org.matrix.androidsdk.rest.model.bingrules.Condition;
import org.matrix.androidsdk.rest.model.login.Credentials;
import org.matrix.androidsdk.ssl.UnrecognizedCertificateException;
import org.matrix.androidsdk.util.BingRulesManager;
import org.matrix.androidsdk.util.JsonUtils;
import org.matrix.androidsdk.util.Log;
import org.matrix.androidsdk.util.MXOsHandler;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The data handler provides a layer to help manage matrix input and output.
 * <ul>
 * <li>Handles events</li>
 * <li>Stores the data in its storage layer</li>
 * <li>Provides the means for an app to get callbacks for data changes</li>
 * </ul>
 */
public class MXDataHandler implements IMXEventListener {
    private static final String LOG_TAG = MXDataHandler.class.getSimpleName();

    private static final String LEFT_ROOMS_FILTER = "{\"room\":{\"timeline\":{\"limit\":1},\"include_leave\":true}}";

    public interface RequestNetworkErrorListener {
        /**
         * Call there is a configuration error.
         *
         * @param matrixErrorCode the matrix error code
         */
        void onConfigurationError(String matrixErrorCode);

        /**
         * Call when the requests are rejected after a SSL update
         *
         * @param exception the exception
         */
        void onSSLCertificateError(UnrecognizedCertificateException exception);
    }

    private IMXEventListener mCryptoEventsListener = null;
    private final Set<IMXEventListener> mEventListeners = new HashSet<>();

    private final IMXStore mStore;
    private final Credentials mCredentials;
    private volatile String mInitialSyncToToken = null;
    private DataRetriever mDataRetriever;
    private BingRulesManager mBingRulesManager;
    private MXCallsManager mCallsManager;
    private MXMediasCache mMediasCache;

    private ProfileRestClient mProfileRestClient;
    private PresenceRestClient mPresenceRestClient;
    private ThirdPidRestClient mThirdPidRestClient;
    private RoomsRestClient mRoomsRestClient;
    private EventsRestClient mEventsRestClient;
    private AccountDataRestClient mAccountDataRestClient;

    private NetworkConnectivityReceiver mNetworkConnectivityReceiver;

    private MyUser mMyUser;

    private HandlerThread mSyncHandlerThread;
    private final MXOsHandler mSyncHandler;
    private final MXOsHandler mUiHandler;

    // list of ignored users
    // null -> not initialized
    // should be retrieved from the store
    private List<String> mIgnoredUserIdsList;

    // list all the roomIds of the current direct chat rooms
    private List<String> mLocalDirectChatRoomIdsList = null;

    private boolean mIsAlive = true;

    private RequestNetworkErrorListener mRequestNetworkErrorListener;

    // the left rooms are managed
    // by default, they are not supported
    private boolean mAreLeftRoomsSynced;

    //
    private final ArrayList<ApiCallback<Void>> mLeftRoomsRefreshCallbacks = new ArrayList<>();
    private boolean mIsRetrievingLeftRooms;

    // the left rooms are saved in a dedicated store.
    private final MXMemoryStore mLeftRoomsStore;

    // e2e decoder
    private MXCrypto mCrypto;

    // the crypto is only started when the sync did not retrieve new device
    private boolean mIsStartingCryptoWithInitialSync = false;

    // groups manager
    private GroupsManager mGroupsManager;

    /**
     * Default constructor.
     *
     * @param store       the data storage implementation.
     * @param credentials the credentials
     */
    public MXDataHandler(IMXStore store, Credentials credentials) {
        mStore = store;
        mCredentials = credentials;

        mUiHandler = new MXOsHandler(Looper.getMainLooper());

        mSyncHandlerThread = new HandlerThread("MXDataHandler" + mCredentials.userId, Thread.MIN_PRIORITY);
        mSyncHandlerThread.start();
        mSyncHandler = new MXOsHandler(mSyncHandlerThread.getLooper());

        mLeftRoomsStore = new MXMemoryStore(credentials, store.getContext());
    }

    /**
     * Set the network error listener.
     *
     * @param requestNetworkErrorListener the network error listener
     */
    public void setRequestNetworkErrorListener(RequestNetworkErrorListener requestNetworkErrorListener) {
        mRequestNetworkErrorListener = requestNetworkErrorListener;
    }

    /**
     * @return the credentials
     */
    public Credentials getCredentials() {
        return mCredentials;
    }

    /**
     * Update the profile Rest client.
     *
     * @param profileRestClient the REST client
     */
    public void setProfileRestClient(ProfileRestClient profileRestClient) {
        mProfileRestClient = profileRestClient;
    }

    /**
     * @return the profile REST client
     */
    public ProfileRestClient getProfileRestClient() {
        return mProfileRestClient;
    }

    /**
     * Update the presence Rest client.
     *
     * @param presenceRestClient the REST client
     */
    public void setPresenceRestClient(PresenceRestClient presenceRestClient) {
        mPresenceRestClient = presenceRestClient;
    }

    /**
     * @return the presence REST client
     */
    public PresenceRestClient getPresenceRestClient() {
        return mPresenceRestClient;
    }

    /**
     * Update the thirdPid Rest client.
     *
     * @param thirdPidRestClient the REST client
     */
    public void setThirdPidRestClient(ThirdPidRestClient thirdPidRestClient) {
        mThirdPidRestClient = thirdPidRestClient;
    }

    /**
     * @return the ThirdPid REST client
     */
    public ThirdPidRestClient getThirdPidRestClient() {
        return mThirdPidRestClient;
    }

    /**
     * Update the rooms Rest client.
     *
     * @param roomsRestClient the rooms client
     */
    public void setRoomsRestClient(RoomsRestClient roomsRestClient) {
        mRoomsRestClient = roomsRestClient;
    }

    /**
     * Update the events Rest client.
     *
     * @param eventsRestClient the events client
     */
    public void setEventsRestClient(EventsRestClient eventsRestClient) {
        mEventsRestClient = eventsRestClient;
    }

    /**
     * Update the account data Rest client.
     *
     * @param accountDataRestClient the account data client
     */
    public void setAccountDataRestClient(AccountDataRestClient accountDataRestClient) {
        mAccountDataRestClient = accountDataRestClient;
    }

    /**
     * Update the network connectivity receiver.
     *
     * @param networkConnectivityReceiver the network connectivity receiver
     */
    public void setNetworkConnectivityReceiver(NetworkConnectivityReceiver networkConnectivityReceiver) {
        mNetworkConnectivityReceiver = networkConnectivityReceiver;

        if (null != getCrypto()) {
            getCrypto().setNetworkConnectivityReceiver(mNetworkConnectivityReceiver);
        }
    }

    /**
     * Set the groups manager.
     *
     * @param groupsManager the groups manager
     */
    public void setGroupsManager(GroupsManager groupsManager) {
        mGroupsManager = groupsManager;
    }

    /**
     * @return the crypto engine
     */
    public MXCrypto getCrypto() {
        return mCrypto;
    }

    /**
     * Update the crypto engine.
     *
     * @param crypto the crypto engine
     */
    public void setCrypto(MXCrypto crypto) {
        mCrypto = crypto;
    }

    /**
     * @return true if the crypto is enabled
     */
    public boolean isCryptoEnabled() {
        return null != mCrypto;
    }

    /**
     * Provide the list of user Ids to ignore.
     * The result cannot be null.
     *
     * @return the user Ids list
     */
    public List<String> getIgnoredUserIds() {
        if (null == mIgnoredUserIdsList) {
            mIgnoredUserIdsList = mStore.getIgnoredUserIdsList();
        }

        // avoid the null case
        if (null == mIgnoredUserIdsList) {
            mIgnoredUserIdsList = new ArrayList<>();
        }

        return mIgnoredUserIdsList;
    }

    /**
     * Test if the current instance is still active.
     * When the session is closed, many objects keep a reference to this class
     * to dispatch events : isAlive() should be called before calling a method of this class.
     */
    private void checkIfAlive() {
        synchronized (this) {
            if (!mIsAlive) {
                Log.e(LOG_TAG, "use of a released dataHandler");
                //throw new AssertionError("Should not used a MXDataHandler");
            }
        }
    }

    /**
     * Tell if the current instance is still active.
     * When the session is closed, many objects keep a reference to this class
     * to dispatch events : isAlive() should be called before calling a method of this class.
     *
     * @return true if it is active.
     */
    public boolean isAlive() {
        synchronized (this) {
            return mIsAlive;
        }
    }

    /**
     * Dispatch the configuration error.
     *
     * @param matrixErrorCode the matrix error code.
     */
    public void onConfigurationError(String matrixErrorCode) {
        if (null != mRequestNetworkErrorListener) {
            mRequestNetworkErrorListener.onConfigurationError(matrixErrorCode);
        }
    }

    /**
     * Call when the requests are rejected after a SSL update.
     *
     * @param exception the SSL certificate exception
     */
    public void onSSLCertificateError(UnrecognizedCertificateException exception) {
        if (null != mRequestNetworkErrorListener) {
            mRequestNetworkErrorListener.onSSLCertificateError(exception);
        }
    }

    /**
     * Get the session's current user. The MyUser object provides methods for updating user properties which are not possible for other users.
     *
     * @return the session's MyUser object
     */
    public MyUser getMyUser() {
        checkIfAlive();

        IMXStore store = getStore();

        // MyUser is initialized as late as possible to have a better chance at having the info in storage,
        // which should be the case if this is called after the initial sync
        if (mMyUser == null) {
            mMyUser = new MyUser(store.getUser(mCredentials.userId));
            mMyUser.setDataHandler(this);

            // assume the profile is not yet initialized
            if (null == store.displayName()) {
                store.setAvatarURL(mMyUser.getAvatarUrl(), System.currentTimeMillis());
                store.setDisplayName(mMyUser.displayname, System.currentTimeMillis());
            } else {
                // use the latest user information
                // The user could have updated his profile in offline mode and kill the application.
                mMyUser.displayname = store.displayName();
                mMyUser.setAvatarUrl(store.avatarURL());
            }

            // Handle the case where the user is null by loading the user information from the server
            mMyUser.user_id = mCredentials.userId;
        } else if (null != store) {
            // assume the profile is not yet initialized
            if ((null == store.displayName()) && (null != mMyUser.displayname)) {
                // setAvatarURL && setDisplayName perform a commit if it is required.
                store.setAvatarURL(mMyUser.getAvatarUrl(), System.currentTimeMillis());
                store.setDisplayName(mMyUser.displayname, System.currentTimeMillis());
            } else if (!TextUtils.equals(mMyUser.displayname, store.displayName())) {
                mMyUser.displayname = store.displayName();
                mMyUser.setAvatarUrl(store.avatarURL());
            }
        }

        // check if there is anything to refresh
        mMyUser.refreshUserInfos(null);

        return mMyUser;
    }

    /**
     * @return true if the initial sync is completed.
     */
    public boolean isInitialSyncComplete() {
        checkIfAlive();
        return (null != mInitialSyncToToken);
    }

    /**
     * @return the DataRetriever.
     */
    public DataRetriever getDataRetriever() {
        checkIfAlive();
        return mDataRetriever;
    }

    /**
     * Update the dataRetriever.
     *
     * @param dataRetriever the dataRetriever.
     */
    public void setDataRetriever(DataRetriever dataRetriever) {
        checkIfAlive();
        mDataRetriever = dataRetriever;
    }

    /**
     * Update the push rules manager.
     *
     * @param bingRulesManager the new push rules manager.
     */
    public void setPushRulesManager(BingRulesManager bingRulesManager) {
        if (isAlive()) {
            mBingRulesManager = bingRulesManager;

            mBingRulesManager.loadRules(new SimpleApiCallback<Void>() {
                @Override
                public void onSuccess(Void info) {
                    MXDataHandler.this.onBingRulesUpdate();
                }
            });
        }
    }

    /**
     * Update the calls manager.
     *
     * @param callsManager the new calls manager.
     */
    public void setCallsManager(MXCallsManager callsManager) {
        checkIfAlive();
        mCallsManager = callsManager;
    }

    /**
     * @return the user calls manager.
     */
    public MXCallsManager getCallsManager() {
        checkIfAlive();
        return mCallsManager;
    }

    /**
     * Update the medias cache.
     *
     * @param mediasCache the new medias cache.
     */
    public void setMediasCache(MXMediasCache mediasCache) {
        checkIfAlive();
        mMediasCache = mediasCache;
    }

    /**
     * Retrieve the medias cache.
     *
     * @return the used mediasCache
     */
    public MXMediasCache getMediasCache() {
        checkIfAlive();
        return mMediasCache;
    }

    /**
     * @return the used push rules set.
     */
    public BingRuleSet pushRules() {
        if (isAlive() && (null != mBingRulesManager)) {
            return mBingRulesManager.pushRules();
        }

        return null;
    }

    /**
     * Trigger a push rules refresh.
     */
    public void refreshPushRules() {
        if (isAlive() && (null != mBingRulesManager)) {
            mBingRulesManager.loadRules(new SimpleApiCallback<Void>() {
                @Override
                public void onSuccess(Void info) {
                    MXDataHandler.this.onBingRulesUpdate();
                }
            });
        }
    }

    /**
     * @return the used BingRulesManager.
     */
    public BingRulesManager getBingRulesManager() {
        checkIfAlive();
        return mBingRulesManager;
    }

    /**
     * Set the crypto events listener
     *
     * @param listener the listener
     */
    public void setCryptoEventsListener(IMXEventListener listener) {
        mCryptoEventsListener = listener;
    }

    /**
     * Add a listener to the listeners list.
     *
     * @param listener the listener to add.
     */
    public void addListener(IMXEventListener listener) {
        if (isAlive() && (null != listener)) {
            synchronized (this) {
                mEventListeners.add(listener);
            }

            if (null != mInitialSyncToToken) {
                listener.onInitialSyncComplete(mInitialSyncToToken);
            }
        }
    }

    /**
     * Remove a listener from the listeners list.
     *
     * @param listener to remove.
     */
    public void removeListener(IMXEventListener listener) {
        if (isAlive() && (null != listener)) {
            synchronized (this) {
                mEventListeners.remove(listener);
            }
        }
    }

    /**
     * Clear the instance data.
     */
    public void clear() {
        synchronized (this) {
            mIsAlive = false;
            // remove any listener
            mEventListeners.clear();
        }

        // clear the store
        mStore.close();
        mStore.clear();

        if (null != mSyncHandlerThread) {
            mSyncHandlerThread.quit();
            mSyncHandlerThread = null;
        }
    }

    /**
     * @return the current user id.
     */
    public String getUserId() {
        if (isAlive()) {
            return mCredentials.userId;
        } else {
            return "dummy";
        }
    }

    /**
     * Update the missing data fields loaded from a permanent storage.
     */
    void checkPermanentStorageData() {
        if (!isAlive()) {
            Log.e(LOG_TAG, "checkPermanentStorageData : the session is not anymore active");
            return;
        }

        // When the data are extracted from a persistent storage,
        // some fields are not retrieved :
        // They are used to retrieve some data
        // so add the missing links.

        Collection<Room> rooms = mStore.getRooms();

        for (Room room : rooms) {
            room.init(mStore, room.getRoomId(), this);
        }

        Collection<RoomSummary> summaries = mStore.getSummaries();
        for (RoomSummary summary : summaries) {
            if (null != summary.getLatestRoomState()) {
                summary.getLatestRoomState().setDataHandler(this);
            }
        }
    }


    /**
     * @return the used store.
     */
    public IMXStore getStore() {
        if (isAlive()) {
            return mStore;
        } else {
            Log.e(LOG_TAG, "getStore : the session is not anymore active");
            return null;
        }
    }

    /**
     * Provides the store in which the room is stored.
     *
     * @param roomId the room id
     * @return the used store
     */
    public IMXStore getStore(String roomId) {
        if (isAlive()) {
            if (null == roomId) {
                return mStore;
            } else {
                if (null != mLeftRoomsStore.getRoom(roomId)) {
                    return mLeftRoomsStore;
                } else {
                    return mStore;
                }
            }
        } else {
            Log.e(LOG_TAG, "getStore : the session is not anymore active");
            return null;
        }
    }

    /**
     * Returns the member with userID;
     *
     * @param members the members List
     * @param userID  the user ID
     * @return the roomMember if it exists.
     */
    public RoomMember getMember(Collection<RoomMember> members, String userID) {
        if (isAlive()) {
            for (RoomMember member : members) {
                if (TextUtils.equals(userID, member.getUserId())) {
                    return member;
                }
            }
        } else {
            Log.e(LOG_TAG, "getMember : the session is not anymore active");
        }
        return null;
    }

    /**
     * Check a room exists with the dedicated roomId
     *
     * @param roomId the room ID
     * @return true it exists.
     */
    public boolean doesRoomExist(String roomId) {
        return (null != roomId) && (null != mStore.getRoom(roomId));
    }

    /**
     * @return the left rooms
     */
    public Collection<Room> getLeftRooms() {
        return new ArrayList<>(mLeftRoomsStore.getRooms());
    }

    /**
     * Get the room object for the corresponding room id. Creates and initializes the object if there is none.
     *
     * @param roomId the room id
     * @return the corresponding room
     */
    public Room getRoom(String roomId) {
        return getRoom(roomId, true);
    }

    /**
     * Get the room object for the corresponding room id.
     * The left rooms are not included.
     *
     * @param roomId the room id
     * @param create create the room it does not exist.
     * @return the corresponding room
     */
    public Room getRoom(String roomId, boolean create) {
        return getRoom(mStore, roomId, create);
    }

    /**
     * Get the room object for the corresponding room id.
     * By default, the left rooms are not included.
     *
     * @param roomId        the room id
     * @param testLeftRooms true to test if the room is a left room
     * @param create        create the room it does not exist.
     * @return the corresponding room
     */
    public Room getRoom(String roomId, boolean testLeftRooms, boolean create) {
        Room room = null;

        if (null != roomId) {
            room = mStore.getRoom(roomId);

            if ((null == room) && testLeftRooms) {
                room = mLeftRoomsStore.getRoom(roomId);
            }

            if ((null == room) && create) {
                room = getRoom(mStore, roomId, create);
            }
        }

        return room;
    }

    /**
     * Get the room object from the corresponding room id.
     *
     * @param store  the dedicated store
     * @param roomId the room id
     * @param create create the room it does not exist.
     * @return the corresponding room
     */
    public Room getRoom(IMXStore store, String roomId, boolean create) {
        if (!isAlive()) {
            Log.e(LOG_TAG, "getRoom : the session is not anymore active");
            return null;
        }

        // sanity check
        if (TextUtils.isEmpty(roomId)) {
            return null;
        }

        Room room;

        synchronized (this) {
            room = store.getRoom(roomId);
            if ((room == null) && create) {
                Log.d(LOG_TAG, "## getRoom() : create the room " + roomId);
                room = new Room();
                room.init(store, roomId, this);
                store.storeRoom(room);
            } else if ((null != room) && (null == room.getDataHandler())) {
                // GA reports that some rooms have no data handler
                // so ensure that it is not properly set
                Log.e(LOG_TAG, "getRoom " + roomId + " was not initialized");
                room.init(store, roomId, this);
                store.storeRoom(room);
            }
        }

        return room;
    }

    /**
     * Checks if the room is properly initialized.
     * GA reported us that some room fields are not initialized.
     * But, i really don't know how it is possible.
     *
     * @param room the room check
     */
    public void checkRoom(Room room) {
        // sanity check
        if (null != room) {
            if (null == room.getDataHandler()) {
                Log.e(LOG_TAG, "checkRoom : the room was not initialized");
                room.init(mStore, room.getRoomId(), this);
            } else if ((null != room.getLiveTimeLine()) && (null == room.getLiveTimeLine().mDataHandler)) {
                Log.e(LOG_TAG, "checkRoom : the timeline was not initialized");
                room.init(mStore, room.getRoomId(), this);
            }
        }
    }

    /**
     * Provides the room summaries list.
     *
     * @param withLeftOnes set to true to include the left rooms
     * @return the room summaries
     */
    public Collection<RoomSummary> getSummaries(boolean withLeftOnes) {
        ArrayList<RoomSummary> summaries = new ArrayList<>();

        summaries.addAll(getStore().getSummaries());

        if (withLeftOnes) {
            summaries.addAll(mLeftRoomsStore.getSummaries());
        }

        return summaries;
    }

    /**
     * Retrieve a room Id by its alias.
     *
     * @param roomAlias the room alias
     * @param callback  the asynchronous callback
     */
    public void roomIdByAlias(final String roomAlias, final ApiCallback<String> callback) {
        String roomId = null;

        Collection<Room> rooms = getStore().getRooms();

        for (Room room : rooms) {
            if (TextUtils.equals(room.getState().alias, roomAlias)) {
                roomId = room.getRoomId();
                break;
            } else {
                // getAliases cannot be null
                List<String> aliases = room.getState().getAliases();

                for (String alias : aliases) {
                    if (TextUtils.equals(alias, roomAlias)) {
                        roomId = room.getRoomId();
                        break;
                    }
                }

                // find one matched room id.
                if (null != roomId) {
                    break;
                }
            }
        }

        if (null != roomId) {
            final String fRoomId = roomId;

            Handler handler = new Handler(Looper.getMainLooper());

            handler.post(new Runnable() {
                @Override
                public void run() {
                    callback.onSuccess(fRoomId);
                }
            });
        } else {
            mRoomsRestClient.getRoomIdByAlias(roomAlias, new ApiCallback<RoomAliasDescription>() {
                @Override
                public void onSuccess(RoomAliasDescription info) {
                    callback.onSuccess(info.room_id);
                }

                @Override
                public void onNetworkError(Exception e) {
                    callback.onNetworkError(e);
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    callback.onMatrixError(e);
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    callback.onUnexpectedError(e);
                }
            });
        }

    }

    /**
     * Delete an event.
     *
     * @param event The event to be stored.
     */
    public void deleteRoomEvent(Event event) {
        if (isAlive()) {
            Room room = getRoom(event.roomId);

            if (null != room) {
                mStore.deleteEvent(event);
                Event lastEvent = mStore.getLatestEvent(event.roomId);
                RoomState beforeLiveRoomState = room.getState().deepCopy();

                RoomSummary summary = mStore.getSummary(event.roomId);
                if (null == summary) {
                    summary = new RoomSummary(null, lastEvent, beforeLiveRoomState, mCredentials.userId);
                } else {
                    summary.setLatestReceivedEvent(lastEvent, beforeLiveRoomState);
                }

                if (TextUtils.equals(summary.getReadReceiptEventId(), event.eventId)) {
                    summary.setReadReceiptEventId(lastEvent.eventId);
                }

                if (TextUtils.equals(summary.getReadMarkerEventId(), event.eventId)) {
                    summary.setReadMarkerEventId(lastEvent.eventId);
                }

                mStore.storeSummary(summary);
            }
        } else {
            Log.e(LOG_TAG, "deleteRoomEvent : the session is not anymore active");
        }
    }

    /**
     * Return an user from his id.
     *
     * @param userId the user id;.
     * @return the user.
     */
    public User getUser(String userId) {
        if (!isAlive()) {
            Log.e(LOG_TAG, "getUser : the session is not anymore active");
            return null;
        } else {
            User user = mStore.getUser(userId);

            if (null == user) {
                user = mLeftRoomsStore.getUser(userId);
            }

            return user;
        }
    }

    //================================================================================
    // Account Data management
    //================================================================================

    /**
     * Manage the sync accountData field
     *
     * @param accountData   the account data
     * @param isInitialSync true if it is an initial sync response
     */
    private void manageAccountData(Map<String, Object> accountData, boolean isInitialSync) {
        try {

            if (accountData.containsKey("events")) {
                List<Map<String, Object>> events = (List<Map<String, Object>>) accountData.get("events");

                if (!events.isEmpty()) {
                    // ignored users list
                    manageIgnoredUsers(events, isInitialSync);
                    // push rules
                    managePushRulesUpdate(events);
                    // direct messages rooms
                    manageDirectChatRooms(events, isInitialSync);
                    // URL preview
                    manageUrlPreview(events);
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "manageAccountData failed " + e.getMessage());
        }
    }

    /**
     * Refresh the push rules from the account data events list
     *
     * @param events the account data events.
     */
    private void managePushRulesUpdate(List<Map<String, Object>> events) {
        for (Map<String, Object> event : events) {
            String type = (String) event.get("type");

            if (TextUtils.equals(type, "m.push_rules")) {
                if (event.containsKey("content")) {
                    Gson gson = new GsonBuilder()
                            .setFieldNamingStrategy(new JsonUtils.MatrixFieldNamingStrategy())
                            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC)
                            .registerTypeAdapter(Condition.class, new ConditionDeserializer())
                            .create();

                    // convert the data to BingRulesResponse
                    // because BingRulesManager supports only BingRulesResponse
                    JsonElement element = gson.toJsonTree(event.get("content"));
                    getBingRulesManager().buildRules(gson.fromJson(element, BingRulesResponse.class));

                    // warn the client that the push rules have been updated
                    onBingRulesUpdate();
                }

                return;
            }
        }
    }

    /**
     * Check if the ignored users list is updated
     *
     * @param events the account data events list
     */
    private void manageIgnoredUsers(List<Map<String, Object>> events, boolean isInitialSync) {
        List<String> newIgnoredUsers = ignoredUsers(events);

        if (null != newIgnoredUsers) {
            List<String> curIgnoredUsers = getIgnoredUserIds();

            // the both lists are not empty
            if ((0 != newIgnoredUsers.size()) || (0 != curIgnoredUsers.size())) {
                // check if the ignored users list has been updated
                if ((newIgnoredUsers.size() != curIgnoredUsers.size()) || !newIgnoredUsers.containsAll(curIgnoredUsers)) {
                    // update the store
                    mStore.setIgnoredUserIdsList(newIgnoredUsers);
                    mIgnoredUserIdsList = newIgnoredUsers;

                    if (!isInitialSync) {
                        // warn there is an update
                        onIgnoredUsersListUpdate();
                    }
                }
            }
        }
    }

    /**
     * Extract the ignored users list from the account data events list..
     *
     * @param events the account data events list.
     * @return the ignored users list. null means that there is no defined user ids list.
     */
    private List<String> ignoredUsers(List<Map<String, Object>> events) {
        List<String> ignoredUsers = null;

        if (0 != events.size()) {
            for (Map<String, Object> event : events) {
                String type = (String) event.get("type");

                if (TextUtils.equals(type, AccountDataRestClient.ACCOUNT_DATA_TYPE_IGNORED_USER_LIST)) {
                    if (event.containsKey("content")) {
                        Map<String, Object> contentDict = (Map<String, Object>) event.get("content");

                        if (contentDict.containsKey(AccountDataRestClient.ACCOUNT_DATA_KEY_IGNORED_USERS)) {
                            Map<String, Object> ignored_users = (Map<String, Object>) contentDict.get(AccountDataRestClient.ACCOUNT_DATA_KEY_IGNORED_USERS);

                            if (null != ignored_users) {
                                ignoredUsers = new ArrayList<>(ignored_users.keySet());
                            }
                        }
                    }
                }
            }

        }

        return ignoredUsers;
    }


    /**
     * Extract the direct chat rooms list from the dedicated events.
     *
     * @param events the account data events list.
     */
    private void manageDirectChatRooms(List<Map<String, Object>> events, boolean isInitialSync) {
        if (0 != events.size()) {
            for (Map<String, Object> event : events) {
                String type = (String) event.get("type");

                if (TextUtils.equals(type, AccountDataRestClient.ACCOUNT_DATA_TYPE_DIRECT_MESSAGES)) {
                    if (event.containsKey("content")) {
                        Map<String, List<String>> contentDict = (Map<String, List<String>>) event.get("content");

                        Log.d(LOG_TAG, "## manageDirectChatRooms() : update direct chats map" + contentDict);

                        mStore.setDirectChatRoomsDict(contentDict);

                        // reset the current list of the direct chat roomIDs
                        // to update it
                        mLocalDirectChatRoomIdsList = null;

                        if (!isInitialSync) {
                            // warn there is an update
                            onDirectMessageChatRoomsListUpdate();
                        }
                    }
                }
            }
        }
    }

    /**
     * Manage the URL preview flag
     *
     * @param events the events list
     */
    private void manageUrlPreview(List<Map<String, Object>> events) {
        if (0 != events.size()) {
            for (Map<String, Object> event : events) {
                String type = (String) event.get("type");

                if (TextUtils.equals(type, AccountDataRestClient.ACCOUNT_DATA_TYPE_PREVIEW_URLS)) {
                    if (event.containsKey("content")) {
                        Map<String, Object> contentDict = (Map<String, Object>) event.get("content");

                        Log.d(LOG_TAG, "## manageUrlPreview() : " + contentDict);
                        boolean enable = true;
                        if (contentDict.containsKey(AccountDataRestClient.ACCOUNT_DATA_KEY_URL_PREVIEW_DISABLE)) {
                            enable = !((boolean) contentDict.get(AccountDataRestClient.ACCOUNT_DATA_KEY_URL_PREVIEW_DISABLE));
                        }

                        mStore.setURLPreviewEnabled(enable);
                    }
                }
            }
        }
    }


    //================================================================================
    // Sync V2
    //================================================================================

    /**
     * Handle a presence event.
     *
     * @param presenceEvent teh presence event.
     */
    private void handlePresenceEvent(Event presenceEvent) {
        // Presence event
        if (Event.EVENT_TYPE_PRESENCE.equals(presenceEvent.getType())) {
            User userPresence = JsonUtils.toUser(presenceEvent.getContent());

            // use the sender by default
            if (!TextUtils.isEmpty(presenceEvent.getSender())) {
                userPresence.user_id = presenceEvent.getSender();
            }

            User user = mStore.getUser(userPresence.user_id);

            if (user == null) {
                user = userPresence;
                user.setDataHandler(this);
            } else {
                user.currently_active = userPresence.currently_active;
                user.presence = userPresence.presence;
                user.lastActiveAgo = userPresence.lastActiveAgo;
            }

            user.setLatestPresenceTs(System.currentTimeMillis());

            // check if the current user has been updated
            if (mCredentials.userId.equals(user.user_id)) {
                // always use the up-to-date information
                getMyUser().displayname = user.displayname;
                getMyUser().avatar_url = user.getAvatarUrl();

                mStore.setAvatarURL(user.getAvatarUrl(), presenceEvent.getOriginServerTs());
                mStore.setDisplayName(user.displayname, presenceEvent.getOriginServerTs());
            }

            mStore.storeUser(user);
            this.onPresenceUpdate(presenceEvent, user);
        }
    }

    /**
     * Manage a syncResponse.
     *
     * @param syncResponse the syncResponse to manage.
     * @param fromToken    the start sync token
     * @param isCatchingUp true when there is a pending catch-up
     */
    public void onSyncResponse(final SyncResponse syncResponse, final String fromToken, final boolean isCatchingUp) {
        // perform the sync in background
        // to avoid UI thread lags.
        mSyncHandler.post(new Runnable() {
            @Override
            public void run() {
                manageResponse(syncResponse, fromToken, isCatchingUp);
            }
        });
    }

    /**
     * Delete a room from its room id.
     * The room data is copied into the left rooms store.
     *
     * @param roomId the room id
     */
    public void deleteRoom(String roomId) {
        // copy the room from a store to another one
        Room r = this.getStore().getRoom(roomId);

        if (null != r) {
            if (mAreLeftRoomsSynced) {
                Room leftRoom = getRoom(mLeftRoomsStore, roomId, true);
                leftRoom.setIsLeft(true);

                // copy the summary
                RoomSummary summary = getStore().getSummary(roomId);
                if (null != summary) {
                    mLeftRoomsStore.storeSummary(new RoomSummary(summary, summary.getLatestReceivedEvent(), summary.getLatestRoomState(), getUserId()));
                }

                // copy events and receiptData
                // it is not required but it is better, it could be useful later
                // the room summary should be enough to be displayed in the recent pages
                ArrayList<ReceiptData> receipts = new ArrayList<>();
                Collection<Event> events = getStore().getRoomMessages(roomId);

                if (null != events) {
                    for (Event e : events) {
                        receipts.addAll(getStore().getEventReceipts(roomId, e.eventId, false, false));
                        mLeftRoomsStore.storeLiveRoomEvent(e);
                    }

                    for (ReceiptData receipt : receipts) {
                        mLeftRoomsStore.storeReceipt(receipt, roomId);
                    }
                }

                // copy the state
                leftRoom.getLiveTimeLine().setState(r.getLiveTimeLine().getState());
            }

            // remove the previous definition
            getStore().deleteRoom(roomId);
        }
    }

    /**
     * Manage the sync response in the UI thread.
     *
     * @param syncResponse the syncResponse to manage.
     * @param fromToken    the start sync token
     * @param isCatchingUp true when there is a pending catch-up
     */
    private void manageResponse(final SyncResponse syncResponse, final String fromToken, final boolean isCatchingUp) {
        if (!isAlive()) {
            Log.e(LOG_TAG, "manageResponse : ignored because the session has been closed");
            return;
        }

        boolean isInitialSync = (null == fromToken);
        boolean isEmptyResponse = true;

        // sanity check
        if (null != syncResponse) {
            Log.d(LOG_TAG, "onSyncComplete");

            // Handle the to device events before the room ones
            // to ensure to decrypt them properly
            if ((null != syncResponse.toDevice) &&
                    (null != syncResponse.toDevice.events) &&
                    (syncResponse.toDevice.events.size() > 0)) {
                Log.d(LOG_TAG, "manageResponse : receives " + syncResponse.toDevice.events.size() + " toDevice events");

                for (Event toDeviceEvent : syncResponse.toDevice.events) {
                    handleToDeviceEvent(toDeviceEvent);
                }
            }

            // Handle account data before the room events
            // to be able to update direct chats dictionary during invites handling.
            if (null != syncResponse.accountData) {
                Log.d(LOG_TAG, "Received " + syncResponse.accountData.size() + " accountData events");
                manageAccountData(syncResponse.accountData, isInitialSync);
            }

            // sanity check
            if (null != syncResponse.rooms) {
                // joined rooms events
                if ((null != syncResponse.rooms.join) && (syncResponse.rooms.join.size() > 0)) {
                    Log.d(LOG_TAG, "Received " + syncResponse.rooms.join.size() + " joined rooms");

                    Set<String> roomIds = syncResponse.rooms.join.keySet();

                    // Handle first joined rooms
                    for (String roomId : roomIds) {
                        try {
                            if (null != mLeftRoomsStore.getRoom(roomId)) {
                                Log.d(LOG_TAG, "the room " + roomId + " moves from left to the joined ones");
                                mLeftRoomsStore.deleteRoom(roomId);
                            }

                            getRoom(roomId).handleJoinedRoomSync(syncResponse.rooms.join.get(roomId), isInitialSync);
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "## manageResponse() : handleJoinedRoomSync failed " + e.getMessage() + " for room " + roomId);
                        }
                    }

                    isEmptyResponse = false;
                }

                // invited room management
                if ((null != syncResponse.rooms.invite) && (syncResponse.rooms.invite.size() > 0)) {
                    Log.d(LOG_TAG, "Received " + syncResponse.rooms.invite.size() + " invited rooms");

                    Set<String> roomIds = syncResponse.rooms.invite.keySet();

                    HashMap<String, List<String>> updatedDirectChatRoomsDict = null;
                    boolean hasChanged = false;

                    for (String roomId : roomIds) {
                        try {
                            Log.d(LOG_TAG, "## manageResponse() : the user has been invited to " + roomId);

                            if (null != mLeftRoomsStore.getRoom(roomId)) {
                                Log.d(LOG_TAG, "the room " + roomId + " moves from left to the invited ones");
                                mLeftRoomsStore.deleteRoom(roomId);
                            }

                            Room room = getRoom(roomId);
                            InvitedRoomSync invitedRoomSync = syncResponse.rooms.invite.get(roomId);

                            room.handleInvitedRoomSync(invitedRoomSync);

                            // Handle here the invites to a direct chat.
                            if (room.isDirectChatInvitation()) {
                                // Retrieve the inviter user id.
                                String participantUserId = null;
                                for (Event event : invitedRoomSync.inviteState.events) {
                                    if (null != event.sender) {
                                        participantUserId = event.sender;
                                        break;
                                    }
                                }

                                if (null != participantUserId) {
                                    // Prepare the updated dictionary.
                                    if (null == updatedDirectChatRoomsDict) {
                                        if (null != this.getStore().getDirectChatRoomsDict()) {
                                            // Consider the current dictionary.
                                            updatedDirectChatRoomsDict = new HashMap<>(this.getStore().getDirectChatRoomsDict());
                                        } else {
                                            updatedDirectChatRoomsDict = new HashMap<>();
                                        }
                                    }

                                    ArrayList<String> roomIdsList;
                                    if (updatedDirectChatRoomsDict.containsKey(participantUserId)) {
                                        roomIdsList = new ArrayList<>(updatedDirectChatRoomsDict.get(participantUserId));
                                    } else {
                                        roomIdsList = new ArrayList<>();
                                    }

                                    // Check whether the room was not yet seen as direct chat
                                    if (roomIdsList.indexOf(roomId) < 0) {
                                        Log.d(LOG_TAG, "## manageResponse() : add this new invite in direct chats");

                                        roomIdsList.add(roomId); // update room list with the new room
                                        updatedDirectChatRoomsDict.put(participantUserId, roomIdsList);
                                        hasChanged = true;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "## manageResponse() : handleInvitedRoomSync failed " + e.getMessage() + " for room " + roomId);
                        }
                    }

                    isEmptyResponse = false;

                    if (hasChanged) {
                        mAccountDataRestClient.setAccountData(mCredentials.userId, AccountDataRestClient.ACCOUNT_DATA_TYPE_DIRECT_MESSAGES, updatedDirectChatRoomsDict, new ApiCallback<Void>() {
                            @Override
                            public void onSuccess(Void info) {
                                Log.d(LOG_TAG, "## manageResponse() : succeeds");
                            }

                            @Override
                            public void onNetworkError(Exception e) {
                                Log.e(LOG_TAG, "## manageResponse() : update account data failed " + e.getMessage());
                                // TODO: we should try again.
                            }

                            @Override
                            public void onMatrixError(MatrixError e) {
                                Log.e(LOG_TAG, "## manageResponse() : update account data failed " + e.getMessage());
                            }

                            @Override
                            public void onUnexpectedError(Exception e) {
                                Log.e(LOG_TAG, "## manageResponse() : update account data failed " + e.getMessage());
                            }
                        });
                    }
                }

                // left room management
                // it should be done at the end but it seems there is a server issue
                // when inviting after leaving a room, the room is defined in the both leave & invite rooms list.
                if ((null != syncResponse.rooms.leave) && (syncResponse.rooms.leave.size() > 0)) {
                    Log.d(LOG_TAG, "Received " + syncResponse.rooms.leave.size() + " left rooms");

                    Set<String> roomIds = syncResponse.rooms.leave.keySet();

                    for (String roomId : roomIds) {
                        // RoomSync leftRoomSync = syncResponse.rooms.leave.get(roomId);

                        // Presently we remove the existing room from the rooms list.
                        // FIXME SYNC V2 Archive/Display the left rooms!
                        // For that create 'handleArchivedRoomSync' method

                        String membership = RoomMember.MEMBERSHIP_LEAVE;
                        Room room = getRoom(roomId);

                        // Retrieve existing room
                        // check if the room still exists.
                        if (null != room) {
                            // use 'handleJoinedRoomSync' to pass the last events to the room before leaving it.
                            // The room will then able to notify its listeners.
                            room.handleJoinedRoomSync(syncResponse.rooms.leave.get(roomId), isInitialSync);

                            RoomMember member = room.getMember(getUserId());
                            if (null != member) {
                                membership = member.membership;
                            }

                            Log.d(LOG_TAG, "## manageResponse() : leave the room " + roomId);
                        }

                        if (!TextUtils.equals(membership, RoomMember.MEMBERSHIP_KICK) && !TextUtils.equals(membership, RoomMember.MEMBERSHIP_BAN)) {
                            // ensure that the room data are properly deleted
                            this.getStore().deleteRoom(roomId);
                            onLeaveRoom(roomId);
                        } else {
                            onRoomKick(roomId);
                        }

                        // don't add to the left rooms if the user has been kicked / banned
                        if ((mAreLeftRoomsSynced) && TextUtils.equals(membership, RoomMember.MEMBERSHIP_LEAVE)) {
                            Room leftRoom = getRoom(mLeftRoomsStore, roomId, true);
                            leftRoom.handleJoinedRoomSync(syncResponse.rooms.leave.get(roomId), isInitialSync);
                        }
                    }

                    isEmptyResponse = false;
                }
            }

            // groups
            if (null != syncResponse.groups) {
                // Handle invited groups
                if ((null != syncResponse.groups.invite) && !syncResponse.groups.invite.isEmpty()) {
                    // Handle invited groups
                    for (String groupId : syncResponse.groups.invite.keySet()) {
                        InvitedGroupSync invitedGroupSync = syncResponse.groups.invite.get(groupId);
                        mGroupsManager.onNewGroupInvitation(groupId, invitedGroupSync.profile, invitedGroupSync.inviter, !isInitialSync);
                    }
                }

                // Handle joined groups
                if ((null != syncResponse.groups.join) && !syncResponse.groups.join.isEmpty()) {
                    for (String groupId : syncResponse.groups.join.keySet()) {
                        mGroupsManager.onJoinGroup(groupId, !isInitialSync);
                    }
                }
                // Handle left groups
                if ((null != syncResponse.groups.leave) && !syncResponse.groups.leave.isEmpty()) {
                    // Handle joined groups
                    for (String groupId : syncResponse.groups.leave.keySet()) {
                        mGroupsManager.onLeaveGroup(groupId, !isInitialSync);
                    }
                }
            }

            // Handle presence of other users
            if ((null != syncResponse.presence) && (null != syncResponse.presence.events)) {
                Log.d(LOG_TAG, "Received " + syncResponse.presence.events.size() + " presence events");
                for (Event presenceEvent : syncResponse.presence.events) {
                    handlePresenceEvent(presenceEvent);
                }
            }

            if (null != mCrypto) {
                mCrypto.onSyncCompleted(syncResponse, fromToken, isCatchingUp);
            }

            IMXStore store = getStore();

            if (!isEmptyResponse && (null != store)) {
                store.setEventStreamToken(syncResponse.nextBatch);
                store.commit();
            }
        }

        if (isInitialSync) {
            if (!isCatchingUp) {
                startCrypto(true);
            } else {
                // the events thread sends a dummy initial sync event
                // when the application is restarted.
                mIsStartingCryptoWithInitialSync = !isEmptyResponse;
            }

            onInitialSyncComplete((null != syncResponse) ? syncResponse.nextBatch : null);
        } else {

            if (!isCatchingUp) {
                startCrypto(mIsStartingCryptoWithInitialSync);
            }

            try {
                onLiveEventsChunkProcessed(fromToken, (null != syncResponse) ? syncResponse.nextBatch : fromToken);
            } catch (Exception e) {
                Log.e(LOG_TAG, "onLiveEventsChunkProcessed failed " + e.getMessage());
            }

            try {
                // check if an incoming call has been received
                mCallsManager.checkPendingIncomingCalls();
            } catch (Exception e) {
                Log.e(LOG_TAG, "checkPendingIncomingCalls failed " + e + " " + e.getMessage());
            }
        }
    }

    /**
     * Refresh the unread summary counters of the updated rooms.
     */
    private void refreshUnreadCounters() {
        Set<String> roomIdsList;

        synchronized (mUpdatedRoomIdList) {
            roomIdsList = new HashSet<>(mUpdatedRoomIdList);
            mUpdatedRoomIdList.clear();
        }
        // refresh the unread counter
        for (String roomId : roomIdsList) {
            Room room = mStore.getRoom(roomId);

            if (null != room) {
                room.refreshUnreadCounter();
            }
        }
    }

    /**
     * @return true if the historical rooms loaded
     */
    public boolean areLeftRoomsSynced() {
        return mAreLeftRoomsSynced;
    }

    /**
     * @return true if the left rooms are retrieving
     */
    public boolean isRetrievingLeftRooms() {
        return mIsRetrievingLeftRooms;
    }

    /**
     * Release the left rooms store
     */
    public void releaseLeftRooms() {
        if (mAreLeftRoomsSynced) {
            mLeftRoomsStore.clear();
            mAreLeftRoomsSynced = false;
        }
    }

    /**
     * Retrieve the historical rooms
     *
     * @param callback the asynchronous callback.
     */
    public void retrieveLeftRooms(ApiCallback<Void> callback) {
        // already loaded
        if (mAreLeftRoomsSynced) {
            if (null != callback) {
                callback.onSuccess(null);
            }
        } else {
            int count;

            synchronized (mLeftRoomsRefreshCallbacks) {
                if (null != callback) {
                    mLeftRoomsRefreshCallbacks.add(callback);
                }
                count = mLeftRoomsRefreshCallbacks.size();
            }

            // start the request only for the first listener
            if (1 == count) {
                mIsRetrievingLeftRooms = true;

                Log.d(LOG_TAG, "## refreshHistoricalRoomsList() : requesting");

                mEventsRestClient.syncFromToken(null, 0, 30000, null, LEFT_ROOMS_FILTER, new ApiCallback<SyncResponse>() {
                    @Override
                    public void onSuccess(final SyncResponse syncResponse) {

                        Runnable r = new Runnable() {
                            @Override
                            public void run() {
                                if (null != syncResponse.rooms.leave) {
                                    Set<String> roomIds = syncResponse.rooms.leave.keySet();

                                    // Handle first joined rooms
                                    for (String roomId : roomIds) {
                                        Room room = getRoom(mLeftRoomsStore, roomId, true);

                                        // sanity check
                                        if (null != room) {
                                            room.setIsLeft(true);
                                            room.handleJoinedRoomSync(syncResponse.rooms.leave.get(roomId), true);

                                            RoomMember selfMember = room.getState().getMember(getUserId());

                                            // keep only the left rooms (i.e not the banned / kicked ones)
                                            if ((null == selfMember) || !TextUtils.equals(selfMember.membership, RoomMember.MEMBERSHIP_LEAVE)) {
                                                mLeftRoomsStore.deleteRoom(roomId);
                                            }
                                        }
                                    }

                                    Log.d(LOG_TAG, "## refreshHistoricalRoomsList() : " + mLeftRoomsStore.getRooms().size() + " left rooms");
                                }

                                mIsRetrievingLeftRooms = false;
                                mAreLeftRoomsSynced = true;

                                synchronized (mLeftRoomsRefreshCallbacks) {
                                    for (ApiCallback<Void> c : mLeftRoomsRefreshCallbacks) {
                                        c.onSuccess(null);
                                    }
                                    mLeftRoomsRefreshCallbacks.clear();
                                }
                            }
                        };

                        Thread t = new Thread(r);
                        t.setPriority(Thread.MIN_PRIORITY);
                        t.start();
                    }

                    @Override
                    public void onNetworkError(Exception e) {
                        synchronized (mLeftRoomsRefreshCallbacks) {
                            Log.d(LOG_TAG, "## refreshHistoricalRoomsList() : failed " + e.getMessage());

                            for (ApiCallback<Void> c : mLeftRoomsRefreshCallbacks) {
                                c.onNetworkError(e);
                            }
                            mLeftRoomsRefreshCallbacks.clear();
                        }
                    }

                    @Override
                    public void onMatrixError(MatrixError e) {
                        synchronized (mLeftRoomsRefreshCallbacks) {
                            Log.d(LOG_TAG, "## refreshHistoricalRoomsList() : failed " + e.getMessage());

                            for (ApiCallback<Void> c : mLeftRoomsRefreshCallbacks) {
                                c.onMatrixError(e);
                            }
                            mLeftRoomsRefreshCallbacks.clear();
                        }
                    }

                    @Override
                    public void onUnexpectedError(Exception e) {
                        synchronized (mLeftRoomsRefreshCallbacks) {
                            Log.d(LOG_TAG, "## refreshHistoricalRoomsList() : failed " + e.getMessage());

                            for (ApiCallback<Void> c : mLeftRoomsRefreshCallbacks) {
                                c.onUnexpectedError(e);
                            }
                            mLeftRoomsRefreshCallbacks.clear();
                        }
                    }
                });
            }
        }
    }

    /*         
     * Handle a 'toDevice' event
     * @param event the event
     */
    private void handleToDeviceEvent(Event event) {
        // Decrypt event if necessary
        decryptEvent(event, null);

        if (TextUtils.equals(event.getType(), Event.EVENT_TYPE_MESSAGE) && (null != event.getContent()) && TextUtils.equals(JsonUtils.getMessageMsgType(event.getContent()), "m.bad.encrypted")) {
            Log.e(LOG_TAG, "## handleToDeviceEvent() : Warning: Unable to decrypt to-device event : " + event.getContent());
        } else {
            onToDeviceEvent(event);
        }
    }

    /**
     * Decrypt an encrypted event
     *
     * @param event      the event to decrypt
     * @param timelineId the timeline identifier
     * @return true if the event has been decrypted
     */
    public boolean decryptEvent(Event event, String timelineId) {
        if ((null != event) && TextUtils.equals(event.getType(), Event.EVENT_TYPE_MESSAGE_ENCRYPTED)) {
            if (null != getCrypto()) {
                MXEventDecryptionResult result = null;
                try {
                    result = getCrypto().decryptEvent(event, timelineId);
                } catch (MXDecryptionException exception) {
                    event.setCryptoError(exception.getCryptoError());
                }

                if (null != result) {
                    event.setClearData(result);
                    return true;
                }
            } else {
                event.setCryptoError(new MXCryptoError(MXCryptoError.ENCRYPTING_NOT_ENABLED_ERROR_CODE, MXCryptoError.ENCRYPTING_NOT_ENABLED_REASON, null));
            }
        }

        return false;
    }

    /**
     * Reset replay attack data for the given timeline.
     *
     * @param timelineId the timeline id
     */
    public void resetReplayAttackCheckInTimeline(String timelineId) {
        if ((null != timelineId) && (null != mCrypto) && (null != mCrypto.getOlmDevice())) {
            mCrypto.resetReplayAttackCheckInTimeline(timelineId);
        }
    }

    //================================================================================
    // Listeners management
    //================================================================================

    /**
     * @return the current MXEvents listeners .
     */
    private List<IMXEventListener> getListenersSnapshot() {
        List<IMXEventListener> eventListeners;

        synchronized (this) {
            eventListeners = new ArrayList<>(mEventListeners);
        }

        return eventListeners;
    }

    /**
     * Dispatch that the store is ready.
     */
    public void onStoreReady() {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onStoreReady();
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onStoreReady();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onStoreReady " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onAccountInfoUpdate(final MyUser myUser) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onAccountInfoUpdate(myUser);
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onAccountInfoUpdate(myUser);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onAccountInfoUpdate " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onPresenceUpdate(final Event event, final User user) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onPresenceUpdate(event, user);
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onPresenceUpdate(event, user);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onPresenceUpdate " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Stored the room id of the rooms which have received some events
     * which imply an unread messages counter refresh.
     */
    private final Set<String> mUpdatedRoomIdList = new HashSet<>();

    /**
     * Tell if a room Id event should be ignored
     *
     * @param roomId the room id
     * @return true to do not dispatch the event.
     */
    private boolean ignoreEvent(String roomId) {
        if (mIsRetrievingLeftRooms && !TextUtils.isEmpty(roomId)) {
            return null != mLeftRoomsStore.getRoom(roomId);
        } else {
            return false;
        }
    }

    @Override
    public void onLiveEvent(final Event event, final RoomState roomState) {
        if (ignoreEvent(event.roomId)) {
            return;
        }

        String type = event.getType();

        if (!TextUtils.equals(Event.EVENT_TYPE_TYPING, type) && !TextUtils.equals(Event.EVENT_TYPE_RECEIPT, type) && !TextUtils.equals(Event.EVENT_TYPE_TYPING, type)) {
            synchronized (mUpdatedRoomIdList) {
                mUpdatedRoomIdList.add(roomState.roomId);
            }
        }

        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onLiveEvent(event, roomState);
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onLiveEvent(event, roomState);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onLiveEvent " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onLiveEventsChunkProcessed(final String startToken, final String toToken) {
        refreshUnreadCounters();

        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onLiveEventsChunkProcessed(startToken, toToken);
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onLiveEventsChunkProcessed(startToken, toToken);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onLiveEventsChunkProcessed " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onBingEvent(final Event event, final RoomState roomState, final BingRule bingRule) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onBingEvent(event, roomState, bingRule);
        }

        if (ignoreEvent(event.roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onBingEvent(event, roomState, bingRule);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onBingEvent " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Update the event state and warn the listener if there is an update.
     *
     * @param event    the event
     * @param newState the new state
     */
    public void updateEventState(Event event, Event.SentState newState) {
        if ((null != event) && (event.mSentState != newState)) {
            event.mSentState = newState;
            getStore().flushRoomEvents(event.roomId);
            onEventSentStateUpdated(event);
        }
    }

    @Override
    public void onEventSentStateUpdated(final Event event) {
        if (ignoreEvent(event.roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onEventSentStateUpdated(event);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onEventSentStateUpdated " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onEventSent(final Event event, final String prevEventId) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onEventSent(event, prevEventId);
        }

        if (ignoreEvent(event.roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onEventSent(event, prevEventId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onEventSent " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onBingRulesUpdate() {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onBingRulesUpdate();
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onBingRulesUpdate();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onBingRulesUpdate " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Dispatch the onInitialSyncComplete event.
     */
    private void dispatchOnInitialSyncComplete(final String toToken) {
        mInitialSyncToToken = toToken;

        refreshUnreadCounters();

        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onInitialSyncComplete(toToken);
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onInitialSyncComplete(mInitialSyncToToken);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onInitialSyncComplete " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Dispatch the OnCryptoSyncComplete event.
     */
    private void dispatchOnCryptoSyncComplete() {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onCryptoSyncComplete();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "OnCryptoSyncComplete " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Start the crypto
     */
    private void startCrypto(final boolean isInitialSync) {
        if ((null != getCrypto()) && !getCrypto().isStarted() && !getCrypto().isStarting()) {
            getCrypto().setNetworkConnectivityReceiver(mNetworkConnectivityReceiver);
            getCrypto().start(isInitialSync, new ApiCallback<Void>() {
                @Override
                public void onSuccess(Void info) {
                    dispatchOnCryptoSyncComplete();
                }

                private void onError(String errorMessage) {
                    Log.e(LOG_TAG, "## onInitialSyncComplete() : getCrypto().start fails " + errorMessage);
                }

                @Override
                public void onNetworkError(Exception e) {
                    onError(e.getMessage());
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    onError(e.getMessage());
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    onError(e.getMessage());
                }
            });
        }
    }


    @Override
    public void onInitialSyncComplete(String toToken) {
        dispatchOnInitialSyncComplete(toToken);
    }

    @Override
    public void onCryptoSyncComplete() {
    }

    @Override
    public void onNewRoom(final String roomId) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onNewRoom(roomId);
        }

        if (ignoreEvent(roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onNewRoom(roomId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onNewRoom " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onJoinRoom(final String roomId) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onJoinRoom(roomId);
        }

        if (ignoreEvent(roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onJoinRoom(roomId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onJoinRoom " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onRoomInitialSyncComplete(final String roomId) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onRoomInitialSyncComplete(roomId);
        }

        if (ignoreEvent(roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onRoomInitialSyncComplete(roomId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onRoomInitialSyncComplete " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onRoomInternalUpdate(final String roomId) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onRoomInternalUpdate(roomId);
        }

        if (ignoreEvent(roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onRoomInternalUpdate(roomId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onRoomInternalUpdate " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onNotificationCountUpdate(final String roomId) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onNotificationCountUpdate(roomId);
        }

        if (ignoreEvent(roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onNotificationCountUpdate(roomId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onNotificationCountUpdate " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onLeaveRoom(final String roomId) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onLeaveRoom(roomId);
        }

        if (ignoreEvent(roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onLeaveRoom(roomId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onLeaveRoom " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onRoomKick(final String roomId) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onRoomKick(roomId);
        }

        if (ignoreEvent(roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onRoomKick(roomId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onRoomKick " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onReceiptEvent(final String roomId, final List<String> senderIds) {
        synchronized (mUpdatedRoomIdList) {
            // refresh the unread countries at the end of the process chunk
            mUpdatedRoomIdList.add(roomId);
        }

        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onReceiptEvent(roomId, senderIds);
        }

        if (ignoreEvent(roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onReceiptEvent(roomId, senderIds);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onReceiptEvent " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onRoomTagEvent(final String roomId) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onRoomTagEvent(roomId);
        }

        if (ignoreEvent(roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onRoomTagEvent(roomId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onRoomTagEvent " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onReadMarkerEvent(final String roomId) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onReadMarkerEvent(roomId);
        }

        if (ignoreEvent(roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onReadMarkerEvent(roomId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onReadMarkerEvent " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onRoomFlush(final String roomId) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onRoomFlush(roomId);
        }

        if (ignoreEvent(roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onRoomFlush(roomId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onRoomFlush " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onIgnoredUsersListUpdate() {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onIgnoredUsersListUpdate();
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onIgnoredUsersListUpdate();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onIgnoredUsersListUpdate " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onToDeviceEvent(final Event event) {
        if (null != mCryptoEventsListener) {
            mCryptoEventsListener.onToDeviceEvent(event);
        }

        if (ignoreEvent(event.roomId)) {
            return;
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onToDeviceEvent(event);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "OnToDeviceEvent " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onDirectMessageChatRoomsListUpdate() {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onDirectMessageChatRoomsListUpdate();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onDirectMessageChatRoomsListUpdate " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onEventDecrypted(final Event event) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onEventDecrypted(event);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onDecryptedEvent " + e.getMessage());
                    }
                }
            }
        });
    }


    @Override
    public void onNewGroupInvitation(final String groupId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onNewGroupInvitation(groupId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onNewGroupInvitation " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onJoinGroup(final String groupId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onJoinGroup(groupId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onJoinGroup " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onLeaveGroup(final String groupId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onLeaveGroup(groupId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onLeaveGroup " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onGroupProfileUpdate(final String groupId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onGroupProfileUpdate(groupId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onGroupProfileUpdate " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onGroupRoomsListUpdate(final String groupId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onGroupRoomsListUpdate(groupId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onGroupRoomsListUpdate " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onGroupUsersListUpdate(final String groupId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onGroupUsersListUpdate(groupId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onGroupUsersListUpdate " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onGroupInvitedUsersListUpdate(final String groupId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onGroupInvitedUsersListUpdate(groupId);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "onGroupInvitedUsersListUpdate " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * @return the direct chat room ids list
     */
    public List<String> getDirectChatRoomIdsList() {
        if (null != mLocalDirectChatRoomIdsList) return  mLocalDirectChatRoomIdsList;

        IMXStore store = getStore();
        List<String> directChatRoomIdsList = new ArrayList<>();

        if (null == store) {
            Log.e(LOG_TAG, "## getDirectChatRoomIdsList() : null store");
            return directChatRoomIdsList;
        }

        Collection<List<String>> listOfList = null;

        if (null != store.getDirectChatRoomsDict()) {
            listOfList = store.getDirectChatRoomsDict().values();
        }

        // if the direct messages entry has been defined
        if (null != listOfList) {
            for (List<String> list : listOfList) {
                for (String roomId : list) {
                    // test if the room is defined once and exists
                    if ((directChatRoomIdsList.indexOf(roomId) < 0) && (null != store.getRoom(roomId))) {
                        directChatRoomIdsList.add(roomId);
                    }
                }
            }
        } else {
            // background compatibility heuristic (named looksLikeDirectMessageRoom in the JS)
            ArrayList<RoomIdsListRetroCompat> directChatRoomIdsListRetValue = new ArrayList<>();
            getDirectChatRoomIdsListRetroCompat(store, directChatRoomIdsListRetValue);

            // force direct chat room list to be updated with retro compatibility rooms values
            if (0 != directChatRoomIdsListRetValue.size()) {
                forceDirectChatRoomValue(directChatRoomIdsListRetValue, new ApiCallback<Void>() {
                    @Override
                    public void onSuccess(Void info) {
                        Log.d(LOG_TAG, "## getDirectChatRoomIdsList(): background compatibility heuristic => account_data update succeed");
                    }

                    @Override
                    public void onMatrixError(MatrixError e) {
                        if (MatrixError.FORBIDDEN.equals(e.errcode)) {
                            Log.e(LOG_TAG, "## getDirectChatRoomIdsList(): onMatrixError Msg=" + e.error);
                        }
                    }

                    @Override
                    public void onNetworkError(Exception e) {
                        Log.e(LOG_TAG, "## getDirectChatRoomIdsList(): onNetworkError Msg=" + e.getMessage());
                    }

                    @Override
                    public void onUnexpectedError(Exception e) {
                        Log.e(LOG_TAG, "## getDirectChatRoomIdsList(): onUnexpectedError Msg=" + e.getMessage());
                    }
                });
            }
        }

        return mLocalDirectChatRoomIdsList = directChatRoomIdsList;
    }

    /**
     * For the value account_data with the rooms list passed in aRoomIdsListToAdd for a given user ID (aParticipantUserId)<br>
     * WARNING: this method must be used with care because it erases the account_data object.
     *
     * @param aRoomParticipantUserIdList the couple direct chat rooms ID / user IDs
     * @param callback                   the asynchronous response callback
     */
    private void forceDirectChatRoomValue(List<RoomIdsListRetroCompat> aRoomParticipantUserIdList, ApiCallback<Void> callback) {
        Map<String, List<String>> params = new HashMap<>();
        List<String> roomIdsList;

        if (null != aRoomParticipantUserIdList) {
            for (RoomIdsListRetroCompat item : aRoomParticipantUserIdList) {
                if (params.containsKey(item.mParticipantUserId)) {
                    roomIdsList = new ArrayList<>(params.get(item.mParticipantUserId));
                    roomIdsList.add(item.mRoomId);
                } else {
                    roomIdsList = new ArrayList<>();
                    roomIdsList.add(item.mRoomId);
                }
                params.put(item.mParticipantUserId, roomIdsList);
            }

            mAccountDataRestClient.setAccountData(getMyUser().user_id, AccountDataRestClient.ACCOUNT_DATA_TYPE_DIRECT_MESSAGES, params, callback);
        }
    }

    /**
     * This class defines a direct chat backward compliancyc structure
     */
    private class RoomIdsListRetroCompat {
        final String mRoomId;
        final String mParticipantUserId;

        public RoomIdsListRetroCompat(String aParticipantUserId, String aRoomId) {
            this.mParticipantUserId = aParticipantUserId;
            this.mRoomId = aRoomId;
        }
    }

    /**
     * Return the direct chat room list for retro compatibility with 1:1 rooms.
     *
     * @param aStore                         store instance
     * @param aDirectChatRoomIdsListRetValue the other participants in the 1:1 room
     */
    private void getDirectChatRoomIdsListRetroCompat(IMXStore aStore, ArrayList<RoomIdsListRetroCompat> aDirectChatRoomIdsListRetValue) {
        RoomIdsListRetroCompat item;

        if ((null != aStore) && (null != aDirectChatRoomIdsListRetValue)) {
            ArrayList<Room> rooms = new ArrayList<>(aStore.getRooms());
            ArrayList<RoomMember> members;
            int otherParticipantIndex;

            for (Room r : rooms) {
                // Show 1:1 chats in separate "Direct Messages" section as long as they haven't
                // been moved to a different tag section
                if ((r.getActiveMembers().size() == 2) && (null != r.getAccountData()) && (!r.getAccountData().hasTags())) {
                    RoomMember roomMember = r.getMember(getMyUser().user_id);
                    members = new ArrayList<>(r.getActiveMembers());

                    if (null != roomMember) {
                        String membership = roomMember.membership;

                        if (TextUtils.equals(membership, RoomMember.MEMBERSHIP_JOIN) ||
                                TextUtils.equals(membership, RoomMember.MEMBERSHIP_BAN) ||
                                TextUtils.equals(membership, RoomMember.MEMBERSHIP_LEAVE)) {

                            if (TextUtils.equals(members.get(0).getUserId(), getMyUser().user_id)) {
                                otherParticipantIndex = 1;
                            } else {
                                otherParticipantIndex = 0;
                            }

                            item = new RoomIdsListRetroCompat(members.get(otherParticipantIndex).getUserId(), r.getRoomId());
                            aDirectChatRoomIdsListRetValue.add(item);
                        }
                    }
                }
            }
        }
    }

    /**
     * Return the list of the direct chat room IDs for the user given in parameter.<br>
     * Based on the account_data map content, the entry associated with aSearchedUserId is returned.
     *
     * @param aSearchedUserId user ID
     * @return the list of the direct chat room Id
     */
    public List<String> getDirectChatRoomIdsList(String aSearchedUserId) {
        ArrayList<String> directChatRoomIdsList = new ArrayList<>();
        IMXStore store = getStore();
        Room room;

        HashMap<String, List<String>> params;

        if (null != store.getDirectChatRoomsDict()) {
            params = new HashMap<>(store.getDirectChatRoomsDict());
            if (params.containsKey(aSearchedUserId)) {
                directChatRoomIdsList = new ArrayList<>();

                for (String roomId : params.get(aSearchedUserId)) {
                    room = store.getRoom(roomId);
                    if (null != room) { // skipp empty rooms
                        directChatRoomIdsList.add(roomId);
                    }
                }
            } else {
                Log.w(LOG_TAG, "## getDirectChatRoomIdsList(): UserId " + aSearchedUserId + " has no entry in account_data");
            }
        } else {
            Log.w(LOG_TAG, "## getDirectChatRoomIdsList(): failure - getDirectChatRoomsDict()=null");
        }

        return directChatRoomIdsList;
    }
}
