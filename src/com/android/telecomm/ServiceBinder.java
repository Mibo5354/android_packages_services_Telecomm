/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.telecomm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.IInterface;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.util.Set;

/**
 * Abstract class to perform the work of binding and unbinding to the specified service interface.
 * Subclasses supply the service intent and component name and this class will invoke protected
 * methods when the class is bound, unbound, or upon failure.
 */
abstract class ServiceBinder<ServiceInterface extends IInterface> {

    /**
     * Callback to notify after a binding succeeds or fails.
     */
    interface BindCallback {
        void onSuccess();
        void onFailure();
    }

    /**
     * Listener for bind events on ServiceBinder.
     */
    interface Listener {
        @SuppressWarnings("rawtypes")
        void onUnbind(ServiceBinder serviceBinder);
    }

    /**
     * Helper class to perform on-demand binding.
     */
    final class Binder {
        /**
         * Performs an asynchronous bind to the service (only if not already bound) and executes the
         * specified callback.
         *
         * @param callback The callback to notify of the binding's success or failure.
         */
        void bind(BindCallback callback) {
            ThreadUtil.checkOnMainThread();
            Log.d(ServiceBinder.this, "bind()");

            // Reset any abort request if we're asked to bind again.
            clearAbort();

            if (!mCallbacks.isEmpty()) {
                // Binding already in progress, append to the list of callbacks and bail out.
                mCallbacks.add(callback);
                return;
            }

            mCallbacks.add(callback);
            if (mServiceConnection == null) {
                Intent serviceIntent = new Intent(mServiceAction).setComponent(mComponentName);
                ServiceConnection connection = new ServiceBinderConnection();

                Log.d(ServiceBinder.this, "Binding to call service with intent: %s", serviceIntent);
                if (!mContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
                    handleFailedConnection();
                    return;
                }
            } else {
                Log.d(ServiceBinder.this, "Service is already bound.");
                Preconditions.checkNotNull(mBinder);
                handleSuccessfulConnection();
            }
        }
    }

    private final class ServiceBinderConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder binder) {
            ThreadUtil.checkOnMainThread();
            Log.i(this, "Service bound %s", componentName);

            // Unbind request was queued so unbind immediately.
            if (mIsBindingAborted) {
                clearAbort();
                logServiceDisconnected("onServiceConnected");
                mContext.unbindService(this);
                handleFailedConnection();
                return;
            }

            mServiceConnection = this;
            setBinder(binder);
            handleSuccessfulConnection();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            logServiceDisconnected("onServiceDisconnected");

            mServiceConnection = null;
            clearAbort();

            handleServiceDisconnected();
        }
    }

    /** The application context. */
    private final Context mContext;

    /** The intent action to use when binding through {@link Context#bindService}. */
    private final String mServiceAction;

    /** The component name of the service to bind to. */
    private final ComponentName mComponentName;

    /** The set of callbacks waiting for notification of the binding's success or failure. */
    private final Set<BindCallback> mCallbacks = Sets.newHashSet();

    /** Used to bind and unbind from the service. */
    private ServiceConnection mServiceConnection;

    /** The binder provided by {@link ServiceConnection#onServiceConnected} */
    private IBinder mBinder;

    private int mAssociatedCallCount = 0;

    /**
     * Indicates that an unbind request was made when the service was not yet bound. If the service
     * successfully connects when this is true, it should be unbound immediately.
     */
    private boolean mIsBindingAborted;

    /**
     * Set of currently registered listeners.
     */
    private Set<Listener> mListeners = Sets.newHashSet();

    /**
     * Persists the specified parameters and initializes the new instance.
     *
     * @param serviceAction The intent-action used with {@link Context#bindService}.
     * @param componentName The component name of the service with which to bind.
     */
    protected ServiceBinder(String serviceAction, ComponentName componentName) {
        Preconditions.checkState(!Strings.isNullOrEmpty(serviceAction));
        Preconditions.checkNotNull(componentName);

        mContext = TelecommApp.getInstance();
        mServiceAction = serviceAction;
        mComponentName = componentName;
    }

    final void incrementAssociatedCallCount() {
        mAssociatedCallCount++;
        Log.v(this, "Call count increment %d, %s", mAssociatedCallCount,
                mComponentName.flattenToShortString());
    }

    final void decrementAssociatedCallCount() {
        if (mAssociatedCallCount > 0) {
            mAssociatedCallCount--;
            Log.v(this, "Call count decrement %d, %s", mAssociatedCallCount,
                    mComponentName.flattenToShortString());

            if (mAssociatedCallCount == 0) {
                unbind();
            }
        } else {
            Log.wtf(this, "%s: ignoring a request to decrement mAssociatedCallCount below zero",
                    mComponentName.getClassName());
        }
    }

    final int getAssociatedCallCount() {
        return mAssociatedCallCount;
    }

    /**
     * Unbinds from the service if already bound, no-op otherwise.
     */
    final void unbind() {
        ThreadUtil.checkOnMainThread();

        if (mServiceConnection == null) {
            // We're not yet bound, so queue up an abort request.
            mIsBindingAborted = true;
        } else {
            logServiceDisconnected("unbind");
            mContext.unbindService(mServiceConnection);
            mServiceConnection = null;
            setBinder(null);
        }
    }

    final ComponentName getComponentName() {
        return mComponentName;
    }

    final boolean isServiceValid(String actionName) {
        if (mBinder == null) {
            Log.wtf(this, "%s invoked while service is unbound", actionName);
            return false;
        }

        return true;
    }

    final void addListener(Listener listener) {
        mListeners.add(listener);
    }

    final void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /**
     * Logs a standard message upon service disconnection. This method exists because there is no
     * single method called whenever the service unbinds and we want to log the same string in all
     * instances where that occurs.  (Context.unbindService() does not cause onServiceDisconnected
     * to execute).
     *
     * @param sourceTag Tag to disambiguate
     */
    private void logServiceDisconnected(String sourceTag) {
        Log.i(this, "Service unbound %s, from %s.", mComponentName, sourceTag);
    }

    /**
     * Notifies all the outstanding callbacks that the service is successfully bound. The list of
     * outstanding callbacks is cleared afterwards.
     */
    private void handleSuccessfulConnection() {
        for (BindCallback callback : mCallbacks) {
            callback.onSuccess();
        }
        mCallbacks.clear();
    }

    /**
     * Notifies all the outstanding callbacks that the service failed to bind. The list of
     * outstanding callbacks is cleared afterwards.
     */
    private void handleFailedConnection() {
        for (BindCallback callback : mCallbacks) {
            callback.onFailure();
        }
        mCallbacks.clear();
    }

    /**
     * Handles a service disconnection.
     */
    private void handleServiceDisconnected() {
        setBinder(null);
    }

    private void clearAbort() {
        mIsBindingAborted = false;
    }

    /**
     * Sets the (private) binder and updates the child class.
     *
     * @param binder The new binder value.
     */
    private void setBinder(IBinder binder) {
        if (mBinder != binder) {
            mBinder = binder;

            setServiceInterface(binder);

            if (binder == null) {
                // Use a copy of the listener list to allow the listeners to unregister themselves
                // as part of the unbind without causing issues.
                for (Listener l : ImmutableSet.copyOf(mListeners)) {
                    l.onUnbind(this);
                }
            }
        }
    }

    /**
     * Sets the service interface after the service is bound or unbound.
     *
     * @param binder The actual bound service implementation.
     */
    protected abstract void setServiceInterface(IBinder binder);
}
