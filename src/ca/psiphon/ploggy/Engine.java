/*
 * Copyright (c) 2013, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package ca.psiphon.ploggy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.preference.PreferenceManager;

import com.squareup.otto.Produce;
import com.squareup.otto.Subscribe;

public class Engine implements OnSharedPreferenceChangeListener {
    
    private static final String LOG_TAG = "Engine";

    private Context mContext;
    private SharedPreferences mSharedPreferences;
    private long mFriendPollPeriod;
    private Timer mTimer;
    private ExecutorService mTaskThreadPool;
    private LocationMonitor mLocationMonitor;
    private WebServer mWebServer;
    private TorWrapper mTorWrapper;

    public Engine(Context context) {
        Utils.initSecureRandom();
        mContext = context;

        // TODO: distinct instance of preferences for each persona
        // e.g., getSharedPreferencesName("persona1");
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    public synchronized void start() throws Utils.ApplicationError {
        Events.register(this);
        mTaskThreadPool = Executors.newCachedThreadPool();
        mTimer = new Timer();
        mLocationMonitor = new LocationMonitor(this);
        mLocationMonitor.start();
        // TODO: check Data.getInstance().hasSelf()...
        startSharingService();
        initFriendPollPeriod();
        schedulePollFriends();
        // TODO: Events.bus.post(new Events.EngineRunning()); ?
        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    public synchronized void stop() {
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        Events.unregister(this);
        stopSharingService();
        if (mLocationMonitor != null) {
        	mLocationMonitor.stop();
        	mLocationMonitor = null;
        }
        if (mTaskThreadPool != null) {
            Utils.shutdownExecutorService(mTaskThreadPool);
	        mTaskThreadPool = null;
        }
        if (mTimer != null) {
            mTimer.cancel();
            mTimer = null;
        }
        // TODO: Events.bus.post(new Events.EngineStopped()); ?
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        try {
            stop();
            start();
        } catch (Utils.ApplicationError e) {
            // TODO: ...?
        }
    }

    public synchronized void submitTask(Runnable task) {
        mTaskThreadPool.submit(task);
    }
    
    private class ScheduledTask extends TimerTask {
        Runnable mTask;
        public ScheduledTask(Runnable task) {
            mTask = task;
        }
        @Override
        public void run() {
            mTaskThreadPool.submit(mTask);
        }
    }
    
    public synchronized void scheduleTask(Runnable task, long delayMilliseconds) {
        mTimer.schedule(new ScheduledTask(task), delayMilliseconds);
    }
    
    private void startSharingService() throws Utils.ApplicationError {
        try {
            Data.Self self = Data.getInstance().getSelf();
            ArrayList<String> friendCertificates = new ArrayList<String>();
            for (Data.Friend friend : Data.getInstance().getFriends()) {
                friendCertificates.add(friend.mPublicIdentity.mX509Certificate);
            }
            stopSharingService();
            mWebServer = new WebServer(
                    mTaskThreadPool,
                    new X509.KeyMaterial(self.mPublicIdentity.mX509Certificate, self.mPrivateIdentity.mX509PrivateKey),
                    friendCertificates);
            mWebServer.start();
            mTorWrapper = new TorWrapper(
                    TorWrapper.Mode.MODE_RUN_SERVICES,
                    new HiddenService.KeyMaterial(self.mPublicIdentity.mHiddenServiceHostname, self.mPrivateIdentity.mHiddenServicePrivateKey),
                    mWebServer.getListeningPort());
            mTorWrapper.start();
        } catch (IOException e) {
            throw new Utils.ApplicationError(LOG_TAG, e);
        }
    }
    
    private void stopSharingService() {
    	if (mTorWrapper != null) {
    		mTorWrapper.stop();
    	}
    	if (mWebServer != null) {
    		mWebServer.stop();
    	}
    }
    
    public synchronized int getTorSocksProxyPort() throws Utils.ApplicationError {
        if (mTorWrapper != null) {
            return mTorWrapper.getSocksProxyPort();
        }
        throw new Utils.ApplicationError(LOG_TAG, "no Tor socks proxy");
    }
    
    @Subscribe
    private synchronized void handleRequestGenerateSelf(
            Events.RequestGenerateSelf requestGenerateSelf) {
        
        // TODO: check if already in progress?

        final Events.RequestGenerateSelf taskRequestGenerateSelf = requestGenerateSelf;
        Runnable task = new Runnable() {
                public void run() {
                    try {
                        // TODO: validate nickname?
                        // TODO: cancellable generation?
                        stopSharingService();
                        /*
                        Data.Self self = new Data.Self(
                                taskRequestGenerateSelf.mNickname,
                                TransportSecurity.KeyMaterial.generate(),
                                HiddenService.KeyMaterial.generate());
                        Data.getInstance().updateSelf(self);
                        Events.post(new Events.GeneratedSelf(self));
                        */
                    } catch (/*TEMP*/Exception e) {
                        Events.post(new Events.RequestFailed(taskRequestGenerateSelf.mRequestId, e.getMessage()));
                    } finally {
                        // Apply new transport and hidden service credentials, or restart with old settings on error
                        try {
                            startSharingService();
                        } catch (Utils.ApplicationError e) {
                            // TODO: ...
                        }                        
                    }
                }
            };
        mTaskThreadPool.submit(task);
    }
    
    @Produce
    private synchronized Events.GeneratedSelf produceGeneratedSelf() {
        // TODO: ...
        return null;
    }

    @Subscribe
    private synchronized void handleRequestDecodeFriend(
            Events.RequestDecodeFriend requestDecodeFriend)  {
        // TODO: ...
    }

    @Subscribe
    private synchronized void handleRequestAddFriend(
            Events.RequestAddFriend requestAddFriend)  {
        // ...[re-]validate
        // ...insert or update data
        // ... update trust manager back end?
        // ...schedule polling (if new) with schedulePollFriend()
        // TODO: ...
    }

    @Subscribe
    private synchronized void handleRequestDeleteFriend(
            Events.RequestDeleteFriend requestDeleteFriend) {
        // ...doesn't cancel polling
        // TODO: ...
    }

    @Produce
    private synchronized Events.NewSelfStatus produceNewSelfStatus() {
        // TODO: ...
        return null;
    }
    
    private void initFriendPollPeriod() {
        // TODO: adjust for foreground, battery, sleep, network type 
        mFriendPollPeriod = 60*1000;
    }

    private void schedulePollFriends() throws Utils.ApplicationError {
        for (Data.Friend friend : Data.getInstance().getFriends()) {
            schedulePollFriend(friend.mId, true);
        }
    }
    
    private void schedulePollFriend(String friendId, boolean initialRequest) {
        final String taskFriendId = friendId;
        Runnable task = new Runnable() {
            public void run() {
                try {
                    Data.Self self = Data.getInstance().getSelf();
                    Data.Friend friend = Data.getInstance().getFriendById(taskFriendId);
                    String response = WebClient.makeGetRequest(
                            new X509.KeyMaterial(self.mPublicIdentity.mX509Certificate, self.mPrivateIdentity.mX509PrivateKey),
                            friend.mPublicIdentity.mX509Certificate,
                            getTorSocksProxyPort(),
                            friend.mPublicIdentity.mHiddenServiceHostname,
                            Protocol.GET_STATUS_REQUEST_PATH);
                    Data.Status friendStatus = Json.fromJson(response, Data.Status.class);
                    Events.post(new Events.NewFriendStatus(friendStatus));
                    // Schedule next poll
                    schedulePollFriend(taskFriendId, false);
                } catch (Data.DataNotFoundException e) {
                    // TODO: ...Deleted; Next poll won't be scheduled
                } catch (Utils.ApplicationError e) {
                    // TODO: ...?
                }
            }
        };
        long delay = initialRequest ? 0 : mFriendPollPeriod;
        scheduleTask(task, delay);
    }

    public Context getContext() {
        return mContext;
    }
    
    public boolean getBooleanPreference(int keyResID) throws Utils.ApplicationError {
        String key = mContext.getString(keyResID);
        if (!mSharedPreferences.contains(key)) {
            throw new Utils.ApplicationError(LOG_TAG, "missing preference default");
        }
        return mSharedPreferences.getBoolean(key, false);        
    }
    
    public int getIntPreference(int keyResID) throws Utils.ApplicationError {
        String key = mContext.getString(keyResID);
        if (!mSharedPreferences.contains(key)) {
            throw new Utils.ApplicationError(LOG_TAG, "missing preference default");
        }
        return mSharedPreferences.getInt(key, 0);        
    }
}
